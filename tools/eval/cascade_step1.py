"""tools/eval/cascade_step1.py — run Paraformer-V2 ASR on the eval manifest,
score with MER, write a report. Tests whether a dedicated ASR model beats
Qwen-Omni realtime's joint ASR+translate pipeline on verbatim code-switch
transcription.

The hypothesis: a model trained for *only* ASR (and trained natively on
code-switch zh+en) will produce a lower MER than a model whose attention
is split between ASR and translation. If this driver shows a meaningful
drop relative to `report.tier2.v1.json` (avg MER 0.1436 on n=240), the
cascade architecture (ASR + MT separately) is worth shipping.

Usage:
    python cascade_step1.py --manifest manifest.tier2.jsonl \\
        --model paraformer-realtime-v2 \\
        --report report.cascade.step1.tier2.json
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Optional

# Local imports — keep this script runnable from the eval dir.
from metrics import aggregate, filler_equiv_mer, mixed_error_rate
from qwen_client import load_wav_as_pcm16_mono

import dashscope
from dashscope.audio.asr import Recognition, RecognitionCallback


# Defaults — DashScope paraformer v2 streaming CS endpoint.
DEFAULT_MODEL = "paraformer-realtime-v2"
SAMPLE_RATE = 16000


def _set_api_key(explicit: Optional[str] = None) -> str:
    """Set ``dashscope.api_key`` from an explicit value or any of the
    common env var names. Returns the key used."""
    key = (explicit
           or os.environ.get("DASHSCOPE_API_KEY")
           or os.environ.get("QWEN_API_KEY")
           or os.environ.get("DASHSCOPE_TOKEN"))
    if not key:
        raise RuntimeError(
            "No DashScope API key. Pass --api-key or set DASHSCOPE_API_KEY "
            "(or QWEN_API_KEY) in the environment."
        )
    dashscope.api_key = key
    return key


@dataclass
class Step1Result:
    sample_id: str
    transcript: str = ""
    error: Optional[str] = None
    audio_seconds: float = 0.0
    pcm_bytes: int = 0
    elapsed_s: float = 0.0


# ---------------------------------------------------------------------
# Callback bridge: DashScope SDK runs the WS on a worker thread; we
# collect the final sentence into a thread-safe container and signal
# when done.
# ---------------------------------------------------------------------

class _CB(RecognitionCallback):
    """Bridge DashScope's streaming events into a final transcript.

    Each ``on_event`` carries ``result.output.sentence`` as a dict with:
      - ``text``: the running hypothesis (gets longer as audio streams in)
      - ``end_time``: ``None`` while the sentence is in flight, a number
        once it has finalized
      - ``sentence_end``: ``True`` when the model committed this sentence
      - ``words``: per-word timings / tokens (we don't need them)

    Strategy: keep the latest ``text`` per ``sentence_id``. On complete,
    concatenate all finalized sentences in order. If the model left an
    in-flight sentence with non-empty text at complete time, include it
    too — that's the final partial the model couldn't finalize in time.
    """

    def __init__(self) -> None:
        self._latest_per_id: dict[int, str] = {}
        self._order: List[int] = []        # sentence_ids in arrival order
        self._finalized: dict[int, bool] = {}
        self._lock = threading.Lock()
        self._done = threading.Event()
        self._err: Optional[str] = None

    def on_open(self) -> None:
        pass

    def on_event(self, result) -> None:
        out = getattr(result, "output", None)
        if out is None:
            return
        sent = getattr(out, "sentence", None) if not isinstance(out, dict) \
               else out.get("sentence")
        if not isinstance(sent, dict):
            return
        sid = sent.get("sentence_id")
        text = sent.get("text", "") or ""
        end_t = sent.get("end_time")
        if sid is None:
            return
        with self._lock:
            if sid not in self._order:
                self._order.append(sid)
            # Always overwrite with the latest text — the hypothesis grows.
            self._latest_per_id[sid] = text
            if end_t is not None:
                self._finalized[sid] = True

    def on_complete(self) -> None:
        self._done.set()

    def on_error(self, result) -> None:
        # Avoid str(result): the SDK's __str__ tries to read .headers off an
        # internal response and crashes for transient errors. Pull what's there.
        with self._lock:
            err = (getattr(result, "message", None)
                   or getattr(result, "code", None))
            try:
                out = getattr(result, "output", None)
                if out is not None and not isinstance(out, dict):
                    err = err or getattr(out, "message", None) or getattr(out, "code", None)
            except Exception:
                pass
            self._err = str(err) if err else "RecognitionError (no detail)"
        self._done.set()

    def on_close(self) -> None:
        self._done.set()

    def transcript(self) -> str:
        with self._lock:
            parts: List[str] = []
            for sid in self._order:
                txt = self._latest_per_id.get(sid, "")
                if txt:
                    parts.append(txt)
            return "".join(parts).strip()

    @property
    def error(self) -> Optional[str]:
        with self._lock:
            return self._err


def transcribe_one(pcm: bytes, model: str, sample_id: str,
                   timeout_s: float = 30.0,
                   retries: int = 1) -> Step1Result:
    """Send pcm to Paraformer-V2 realtime WS, return assembled transcript.

    On transient failure (empty transcript, network error, internal SDK
    invalid-state error), retries up to ``retries`` additional times with
    a short backoff. Many of the failures we see are SDK state issues
    after a long-running process — a fresh ``Recognition`` instance
    usually recovers them.
    """
    last = Step1Result(sample_id=sample_id,
                       audio_seconds=len(pcm) / (SAMPLE_RATE * 2))
    for attempt in range(retries + 1):
        res = Step1Result(sample_id=sample_id,
                           audio_seconds=len(pcm) / (SAMPLE_RATE * 2))
        cb = _CB()
        rec = Recognition(model=model, callback=cb, format="pcm", sample_rate=SAMPLE_RATE)
        t0 = time.monotonic()
        try:
            rec.start()
            FRAME = 640
            offset = 0
            while offset < len(pcm):
                rec.send_audio_frame(pcm[offset:offset + FRAME])
                offset += FRAME
                # Pace gently — SDK buffers; pushing too fast can drop frames.
                time.sleep(0.005)
            res.pcm_bytes = offset
            rec.stop()
            if not cb._done.wait(timeout=timeout_s):
                res.error = f"timed out after {timeout_s}s"
            elif cb.error:
                res.error = cb.error
            else:
                res.transcript = cb.transcript()
        except Exception as e:
            res.error = f"{type(e).__name__}: {e}"
        res.elapsed_s = time.monotonic() - t0
        last = res
        # Treat empty transcript as a soft failure (model returned nothing).
        if not res.error and res.transcript.strip():
            return res
        # On error, no point retrying — but if transcript is empty + no error,
        # that often means "speech recognition has stopped" internal SDK bug.
        if attempt < retries:
            time.sleep(1.0 + attempt * 0.5)
            continue
        # Final attempt: surface whatever we got. Annotate if transcript empty.
        if not res.error and not res.transcript.strip():
            res.error = "empty transcript (model produced no text)"
        return last
    return last


# ---------------------------------------------------------------------
# Main runner
# ---------------------------------------------------------------------

def score(result: Step1Result, ref: str) -> dict:
    out: dict = {
        "id": result.sample_id,
        "hyp": result.transcript,
        "audio_seconds": round(result.audio_seconds, 3),
        "elapsed_s": round(result.elapsed_s, 3),
        "pcm_bytes": result.pcm_bytes,
        "error": result.error,
    }
    if ref and not result.error:
        m = mixed_error_rate(ref, result.transcript)
        out["mer"] = m.mer
        out["mer_fe"] = filler_equiv_mer(ref, result.transcript)
        out["cer_zh"] = m.cer_zh if m.n_ref_zh else None
        out["wer_en"] = m.wer_en if m.n_ref_en else None
        out["n_ref_tokens"] = m.n_ref_tokens
    return out


def run(args: argparse.Namespace) -> int:
    try:
        _set_api_key(args.api_key)
    except RuntimeError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 2

    manifest = Path(args.manifest)
    if not manifest.is_file():
        print(f"ERROR: manifest not found: {manifest}", file=sys.stderr)
        return 2

    items: List[dict] = []
    with manifest.open("r", encoding="utf-8") as f:
        for ln, line in enumerate(f, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            try:
                items.append(json.loads(line))
            except json.JSONDecodeError as e:
                print(f"  ! line {ln}: {e}", file=sys.stderr)
    if args.limit and args.limit > 0:
        items = items[: args.limit]
    print(f"Loaded {len(items)} item(s) from {manifest}", file=sys.stderr)

    per_sample: List[dict] = []

    def _process_one(item: dict) -> dict:
        sid = item["id"]
        ref = (item.get("ref") or item.get("ref_transcript") or "").strip()
        audio_path = Path(item["audio"])
        try:
            pcm = load_wav_as_pcm16_mono(str(audio_path))
        except Exception as e:
            return {"id": sid, "error": f"audio load: {e}"}
        result = transcribe_one(pcm, args.model, sid, timeout_s=args.timeout)
        s = score(result, ref)
        return s

    n_workers = max(1, args.workers)
    if n_workers == 1:
        for i, item in enumerate(items, 1):
            sid = item["id"]
            print(f"[{i}/{len(items)}] {sid} ... ", end="", flush=True)
            s = _process_one(item)
            per_sample.append(s)
            if s.get("error"):
                print(f"err={s['error'][:50]}")
            else:
                print(f"MER={s.get('mer'):.3f} ({len(s.get('hyp', ''))} chars)  "
                      f"[{s.get('elapsed_s', 0):.1f}s]")
    else:
        print(f"Running with {n_workers} parallel workers on {len(items)} samples...", flush=True)
        # Submit all, collect in original order via index mapping.
        with ThreadPoolExecutor(max_workers=n_workers) as pool:
            futures = {pool.submit(_process_one, item): i
                      for i, item in enumerate(items)}
            results: List[Optional[dict]] = [None] * len(items)
            done_count = 0
            for future in as_completed(futures):
                idx = futures[future]
                try:
                    s = future.result()
                except Exception as e:
                    s = {"id": items[idx]["id"], "error": f"worker: {e}"}
                results[idx] = s
                done_count += 1
                sid = s.get("id", "?")
                if done_count % 50 == 0 or done_count == len(items):
                    mer_str = f"MER={s.get('mer'):.3f}" if s.get('mer') is not None else "MER=—"
                    err_str = f"  err={s.get('error', '')[:40]}" if s.get('error') else ""
                    print(f"  [{done_count}/{len(items)}] {sid}  {mer_str}{err_str}", flush=True)
            per_sample = [r for r in results if r is not None]

    sample_metrics = [
        {k: v for k, v in s.items()
         if k not in ("id", "error", "hyp") and isinstance(v, (int, float))}
        for s in per_sample
    ]
    summary = aggregate(sample_metrics)
    summary["n_samples"] = len(items)
    summary["n_errors"] = sum(1 for s in per_sample if s.get("error"))

    print("\nSummary:")
    for k, v in sorted(summary.items()):
        if isinstance(v, float):
            print(f"  {k:<22} {v:.4f}")
        else:
            print(f"  {k:<22} {v}")

    if args.report:
        out = {
            "config": {
                "model": args.model, "manifest": str(manifest),
                "n_items": len(items),
            },
            "samples": per_sample,
            "summary": summary,
            "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        }
        Path(args.report).write_text(json.dumps(out, ensure_ascii=False, indent=2),
                                     encoding="utf-8")
        print(f"\nReport written: {args.report}")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description=(
        "Cascade step 1 — Paraformer-V2 streaming ASR scored with MER."
    ))
    p.add_argument("--manifest", required=True)
    p.add_argument("--api-key", help="DashScope API key (or DASHSCOPE_API_KEY env)")
    p.add_argument("--model", default=DEFAULT_MODEL,
                   help="default: paraformer-realtime-v2 (CS zh+en)")
    p.add_argument("--limit", type=int, default=0)
    p.add_argument("--timeout", type=float, default=30.0,
                   help="per-sample receive timeout (sec)")
    p.add_argument("--workers", type=int, default=1,
                   help="number of parallel workers (1 = sequential; "
                        "8-16 recommended for large manifests)")
    p.add_argument("--report", default="report.cascade.step1.json")
    args = p.parse_args()
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
