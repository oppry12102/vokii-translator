"""tools/eval/cascade_step1_qwen3asr.py — qwen3-asr-flash-realtime A/B driver.

qwen3-asr-flash-realtime (DashScope 2025-10+) speaks the OpenAI-Realtime
style protocol on ``wss://dashscope.aliyuncs.com/api-ws/v1/realtime`` —
NOT the run-task protocol used by fun-asr/paraformer (cascade_step1.py).
The installed dashscope SDK (>=1.26.3) already exposes the transport via
``OmniRealtimeConversation`` + ``TranscriptionParams`` (its docstring names
qwen3-asr-flash-realtime explicitly), so this driver builds on that.

Eval flow per sample (mirrors cascade_step1.py so reports are comparable):
  connect -> session.update (modalities=["text"], pcm 16k, server_vad)
  -> append 100ms pcm chunks (paced) -> append 1.2s digital silence so the
  server VAD commits the final turn -> session.finish -> assemble transcript
  from ``conversation.item.input_audio_transcription.completed`` events.

Usage:
    export DASHSCOPE_API_KEY=sk-...
    python cascade_step1_qwen3asr.py --manifest manifest.tier3.1000.jsonl \
        --model qwen3-asr-flash-realtime --workers 8 \
        --report report.cascade.step1.qwen3asr.tier3.1000.json
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional

from metrics import aggregate, mixed_error_rate
from qwen_client import load_wav_as_pcm16_mono

import dashscope
from dashscope.audio.qwen_omni.omni_realtime import (
    MultiModality,
    OmniRealtimeCallback,
    OmniRealtimeConversation,
    TranscriptionParams,
)

DEFAULT_MODEL = "qwen3-asr-flash-realtime"
SAMPLE_RATE = 16000
CHUNK_BYTES = 3200          # 100ms of 16kHz s16le mono
SILENCE_TAIL_S = 1.2        # appended so server_vad commits the last turn
PACE_S = 0.03               # ~3.3x realtime push


def _set_api_key(explicit: Optional[str] = None) -> str:
    key = (explicit
           or os.environ.get("DASHSCOPE_API_KEY")
           or os.environ.get("QWEN_API_KEY")
           or os.environ.get("DASHSCOPE_TOKEN"))
    if not key:
        raise RuntimeError(
            "No DashScope API key. Pass --api-key or set DASHSCOPE_API_KEY."
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


class _CB(OmniRealtimeCallback):
    """Collect finalized transcripts; keep partials as fallback.

    qwen3-asr-flash-realtime emits per-turn events:
      - ``conversation.item.input_audio_transcription.completed``
        with ``transcript`` — the committed turn text (what we want)
      - ``...text`` / ``...delta`` partial variants (fallback only)
      - ``input_audio_buffer.speech_started/stopped/committed`` (VAD)
      - ``session.created`` / ``session.finished`` / ``error``
    """

    def __init__(self) -> None:
        self._finals: List[str] = []
        self._last_partial: str = ""
        self._lock = threading.Lock()
        self._done = threading.Event()
        self._err: Optional[str] = None
        self.event_types: dict[str, int] = {}

    def on_open(self) -> None:
        pass

    def on_close(self, code, msg) -> None:
        self._done.set()

    def on_event(self, message: dict) -> None:
        mtype = message.get("type", "?")
        with self._lock:
            self.event_types[mtype] = self.event_types.get(mtype, 0) + 1
            if mtype == "conversation.item.input_audio_transcription.completed":
                text = (message.get("transcript") or "").strip()
                if text:
                    self._finals.append(text)
                self._last_partial = ""
            elif mtype in (
                "conversation.item.input_audio_transcription.text",
                "conversation.item.input_audio_transcription.delta",
            ):
                # partial: .text is cumulative-ish, .delta is incremental —
                # keep only as a fallback if no completed event ever arrives.
                t = message.get("text") or message.get("delta") or ""
                if mtype.endswith(".text"):
                    self._last_partial = t
                else:
                    self._last_partial += t
            elif mtype == "response.audio_transcript.done":
                # omni-style final, just in case the ASR model reuses it
                t = (message.get("transcript") or "").strip()
                if t:
                    self._finals.append(t)
            elif mtype == "session.finished":
                self._done.set()
            elif mtype == "error":
                err = message.get("error") or {}
                self._err = (err.get("message") or err.get("code")
                             or json.dumps(err, ensure_ascii=False))
                self._done.set()

    def transcript(self) -> str:
        with self._lock:
            parts = [t for t in self._finals if t]
            if not parts and self._last_partial.strip():
                parts.append(self._last_partial.strip())
            return "".join(parts).strip()

    @property
    def error(self) -> Optional[str]:
        with self._lock:
            return self._err


def transcribe_one(pcm: bytes, model: str, sample_id: str,
                   language: Optional[str] = None,
                   silence_ms: int = 800,
                   timeout_s: float = 30.0,
                   retries: int = 1) -> Step1Result:
    """Stream pcm to qwen3-asr-flash-realtime, return assembled transcript."""
    last = Step1Result(sample_id=sample_id,
                       audio_seconds=len(pcm) / (SAMPLE_RATE * 2))
    for attempt in range(retries + 1):
        res = Step1Result(sample_id=sample_id,
                          audio_seconds=len(pcm) / (SAMPLE_RATE * 2))
        cb = _CB()
        conv = OmniRealtimeConversation(model=model, callback=cb)
        t0 = time.monotonic()
        try:
            conv.connect()
            conv.update_session(
                output_modalities=[MultiModality.TEXT],
                enable_input_audio_transcription=False,  # base key unused; transcription_params replaces it
                enable_turn_detection=True,
                turn_detection_type="server_vad",
                turn_detection_silence_duration_ms=silence_ms,
                transcription_params=TranscriptionParams(
                    language=language,
                    sample_rate=SAMPLE_RATE,
                    input_audio_format="pcm",
                ),
            )
            offset = 0
            while offset < len(pcm):
                conv.append_audio(
                    base64.b64encode(pcm[offset:offset + CHUNK_BYTES]).decode())
                offset += CHUNK_BYTES
                time.sleep(PACE_S)
            res.pcm_bytes = offset
            # Trailing digital silence so server_vad sees the turn end.
            silence = b"\x00" * int(SILENCE_TAIL_S * SAMPLE_RATE * 2)
            for off in range(0, len(silence), CHUNK_BYTES):
                conv.append_audio(
                    base64.b64encode(silence[off:off + CHUNK_BYTES]).decode())
                time.sleep(PACE_S)
            conv.end_session(timeout=timeout_s)
            if cb.error:
                res.error = cb.error
            else:
                res.transcript = cb.transcript()
            if sample_id.endswith("_0") or os.environ.get("QWEN3ASR_DEBUG"):
                print(f"    [events {sample_id}] {cb.event_types}",
                      file=sys.stderr, flush=True)
        except Exception as e:
            res.error = f"{type(e).__name__}: {e}"
        finally:
            try:
                conv.close()
            except Exception:
                pass
        res.elapsed_s = time.monotonic() - t0
        last = res
        if not res.error and res.transcript.strip():
            return res
        if attempt < retries:
            time.sleep(1.0 + attempt * 0.5)
            continue
        if not res.error and not res.transcript.strip():
            res.error = "empty transcript (model produced no text)"
        return last
    return last


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
        result = transcribe_one(pcm, args.model, sid,
                                language=args.language,
                                silence_ms=args.silence_ms,
                                timeout_s=args.timeout)
        return score(result, ref)

    n_workers = max(1, args.workers)
    if n_workers == 1:
        for i, item in enumerate(items, 1):
            sid = item["id"]
            print(f"[{i}/{len(items)}] {sid} ... ", end="", flush=True)
            s = _process_one(item)
            per_sample.append(s)
            if s.get("error"):
                print(f"err={s['error'][:60]}")
            else:
                print(f"MER={s.get('mer'):.3f} ({len(s.get('hyp', ''))} chars)  "
                      f"[{s.get('elapsed_s', 0):.1f}s]")
    else:
        print(f"Running with {n_workers} parallel workers on {len(items)} samples...",
              flush=True)
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
                    mer_str = f"MER={s.get('mer'):.3f}" if s.get("mer") is not None else "MER=—"
                    err_str = f"  err={s.get('error', '')[:40]}" if s.get("error") else ""
                    print(f"  [{done_count}/{len(items)}] {sid}  {mer_str}{err_str}",
                          flush=True)
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
                "n_items": len(items), "protocol": "openai-realtime",
                "language": args.language, "silence_ms": args.silence_ms,
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
        "Cascade step 1 — qwen3-asr-flash-realtime (OpenAI-Realtime "
        "protocol) scored with MER. Report format matches cascade_step1.py."
    ))
    p.add_argument("--manifest", required=True)
    p.add_argument("--api-key", help="DashScope API key (or DASHSCOPE_API_KEY env)")
    p.add_argument("--model", default=DEFAULT_MODEL,
                   help="e.g. qwen3-asr-flash-realtime or a dated snapshot")
    p.add_argument("--language", default=None,
                   help="optional language hint (e.g. zh, en); default: auto")
    p.add_argument("--silence-ms", type=int, default=800,
                   help="server_vad silence duration to commit a turn")
    p.add_argument("--limit", type=int, default=0)
    p.add_argument("--timeout", type=float, default=30.0)
    p.add_argument("--workers", type=int, default=1)
    p.add_argument("--report", default="report.cascade.step1.qwen3asr.json")
    args = p.parse_args()
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
