"""tools/eval/cascade_latency.py — production-fidelity latency sweep for the
cascade pipeline.

The cascade architecture (Paraformer step 1 + qwen-mt-plus step 2) opens
two WS connections per utterance vs the joint Qwen-Omni path's one, so
TTFB and end-of-stream latency will be ~100-300 ms higher until the
connection pool warms. This script measures it under server_vad (the
prod commit mode) on a small tier1 subset, so we can spot the regression
before shipping the toggle.

Run modes:

  A) Joint baseline  — current Qwen-Omni realtime, default model.
  B) Cascade step 1  — Paraformer-V2 verbatim ASR (no MT step).
  C) Cascade step 1+2 — Paraformer-V2 → qwen-mt-plus, end-to-end.

Per mode the script records:
  - ttfb           (sec, time to first text delta from any stage)
  - first_zhen     (sec, time to first fully-formed ZH/EN pair)
  - total_latency  (sec, response.done)
  - max_delta_gap  (sec, largest intra-turn pause — catches stalls)
  - delta_count    (count)

Recommended output: a JSON with per-sample latencies + a summary that
compares modes side-by-side (mean/median/p95 per metric).

Usage:
    export DASHSCOPE_API_KEY=sk-...
    python cascade_latency.py --manifest manifest.tier1.jsonl \
        --modes joint,cascade1,cascade2 \
        --limit 10 --report report.latency.tier1.json
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import statistics
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional

from qwen_client import (
    EvalResult, QwenRealtimeClient, TRANSCRIBE_INSTRUCTIONS_V1, load_wav_as_pcm16_mono,
)

# Cascade step 1 uses the DashScope SDK (different driver).
try:
    import dashscope
    from dashscope.audio.asr import Recognition, RecognitionCallback
    import threading
    HAVE_DASHSCOPE = True
except Exception:
    HAVE_DASHSCOPE = False


def _set_api_key(explicit: Optional[str] = None) -> str:
    key = (explicit or os.environ.get("DASHSCOPE_API_KEY")
           or os.environ.get("QWEN_API_KEY") or os.environ.get("DASHSCOPE_TOKEN"))
    if not key:
        raise RuntimeError("Set DASHSCOPE_API_KEY")
    if HAVE_DASHSCOPE:
        dashscope.api_key = key
    return key


# ---------------------------------------------------------------------
# Mode A: joint Qwen-Omni
# ---------------------------------------------------------------------

async def run_joint(pcm: bytes, sid: str, args) -> dict:
    client = QwenRealtimeClient(
        endpoint=args.endpoint,
        model=args.model,
        api_key=_set_api_key(args.api_key),
        silence_ms=args.vad_silence,
        threshold=args.vad_threshold,
        task="transcribe",
        trailing_silence_ms=args.trailing_silence,
        commit_mode="vad",
        realtime=True,           # latency run: real-time pacing required
        instructions=TRANSCRIBE_INSTRUCTIONS_V1,
    )
    result = await client.transcribe(pcm, sid)
    lat = _latency(result.delta_times)
    return {
        "id": sid, "mode": "joint",
        "ttfb": lat["ttfb"], "total": lat["total"],
        "max_delta_gap": lat["max_delta_gap"], "delta_count": lat["delta_count"],
        "n_chars": len(result.transcript or ""),
    }


# ---------------------------------------------------------------------
# Mode B: cascade step 1 (Paraformer-V2) — latency only
# ---------------------------------------------------------------------

class _ParaLatCb(RecognitionCallback):
    def __init__(self):
        self._first_text_t: Optional[float] = None
        self._last_text_t: Optional[float] = None
        self._deltas: List[float] = []
        self._final_text = ""
        self._lock = threading.Lock()
        self._done = threading.Event()
        self._err: Optional[str] = None

    def on_event(self, result):
        out = getattr(result, "output", None)
        if out is None: return
        sent = out.get("sentence") if isinstance(out, dict) else getattr(out, "sentence", None)
        if not isinstance(sent, dict): return
        text = sent.get("text") or ""
        end_t = sent.get("end_time")
        now = time.monotonic()
        with self._lock:
            if text:
                if self._first_text_t is None: self._first_text_t = now
                self._last_text_t = now
                self._deltas.append(now)
                self._final_text = text
            if end_t is not None:
                # sentence finalised
                pass

    def on_complete(self): self._done.set()
    def on_error(self, result):
        with self._lock:
            self._err = (getattr(result, "message", None) or getattr(result, "code", None)
                         or "RecognitionError")
        self._done.set()
    def on_close(self): self._done.set()


def run_cascade1(pcm: bytes, sid: str, args) -> dict:
    if not HAVE_DASHSCOPE:
        return {"id": sid, "mode": "cascade1", "error": "dashscope SDK missing"}
    cb = _ParaLatCb()
    rec = Recognition(model=args.asr_model, callback=cb, format="pcm", sample_rate=16000)
    t0 = time.monotonic()
    rec.start()
    FRAME = 640
    offset = 0
    while offset < len(pcm):
        rec.send_audio_frame(pcm[offset:offset + FRAME]); offset += FRAME
        time.sleep(0.005)
    rec.stop()
    cb._done.wait(timeout=30)
    with cb._lock:
        deltas = cb._deltas[:]
        first = cb._first_text_t
        last = cb._last_text_t
        err = cb._err
        text = cb._final_text
    gaps = [b - a for a, b in zip(deltas, deltas[1:])] if len(deltas) > 1 else []
    return {
        "id": sid, "mode": "cascade1",
        "ttfb": (first - t0) if first else None,
        "total": (last - t0) if last else None,
        "max_delta_gap": max(gaps) if gaps else 0.0,
        "delta_count": len(deltas),
        "n_chars": len(text or ""),
        "error": err,
    }


# ---------------------------------------------------------------------
# Mode C: cascade step 1 + step 2 — full end-to-end pipeline
# ---------------------------------------------------------------------

# Reuse the same verbatim ASR prompt used in cascade_step1.py.
VERBATIM_INSTRUCTIONS = (
    "You are a speech-to-text transcription engine. Transcribe the user's "
    "speech EXACTLY as spoken, word for word, in the original language(s). "
    "The speech freely mixes Mandarin Chinese and English within a single "
    "utterance — keep every word in the language it was actually spoken; do "
    "NOT translate anything. Write all Chinese characters in SIMPLIFIED "
    "Chinese (简体字). Output ONLY the raw transcription text: no labels, "
    "no language tags, no quotation marks, no markdown, no commentary. If "
    "nothing intelligible was said, output nothing."
)

MT_INSTRUCTIONS = (
    "You are a real-time interpreter. The user speaks either Chinese or "
    "English. For each utterance, output the translation in BOTH languages "
    "using EXACTLY this two-line format and nothing else:\n"
    "ZH: <Mandarin translation>\n"
    "EN: <English translation>\n"
    "Always output the ZH line first, then the EN line. Use no labels other "
    "than 'ZH:' and 'EN:'. No extra commentary, no markdown, no apologies."
)


def run_cascade2(pcm: bytes, sid: str, args) -> dict:
    """Step 1 (Paraformer ASR) + step 2 (qwen-mt-plus MT) end-to-end.
    Records latency at both stages and the combined pipeline total.

    Returns ttfb = time from upload-start to first text delta from EITHER
    stage (in practice: ASR first, then MT). Total = ASR commit + MT
    complete. For finer breakdown see step1_ttfb / step1_total /
    mt_ttfb / mt_total in the returned dict.
    """
    if not HAVE_DASHSCOPE:
        return {"id": sid, "mode": "cascade2", "error": "dashscope SDK missing"}

    # ---- Step 1: Paraformer ASR ----
    cb = _ParaLatCb()
    rec = Recognition(model=args.asr_model, callback=cb, format="pcm", sample_rate=16000)
    pipeline_t0 = time.monotonic()
    step1_t0 = pipeline_t0
    try:
        rec.start()
        FRAME = 640
        offset = 0
        while offset < len(pcm):
            rec.send_audio_frame(pcm[offset:offset + FRAME]); offset += FRAME
            time.sleep(0.005)
        rec.stop()
        cb._done.wait(timeout=30)
    finally:
        pass  # SDK cleans up; cb._done.wait guarantees we exit before step 2

    with cb._lock:
        deltas1 = cb._deltas[:]
        first1 = cb._first_text_t
        last1 = cb._last_text_t
        err1 = cb._err
        asr_text = cb._final_text
    step1_ttfb = (first1 - step1_t0) if first1 else None
    step1_total = (last1 - step1_t0) if last1 else None
    gaps1 = [b - a for a, b in zip(deltas1, deltas1[1:])] if len(deltas1) > 1 else []

    if err1 or not (asr_text or "").strip():
        return {
            "id": sid, "mode": "cascade2",
            "ttfb": step1_ttfb, "total": step1_total,
            "step1_ttfb": step1_ttfb, "step1_total": step1_total,
            "mt_ttfb": None, "mt_total": None,
            "max_delta_gap": max(gaps1) if gaps1 else 0.0,
            "delta_count": len(deltas1),
            "n_chars_step1": len(asr_text or ""),
            "n_chars_step2": 0,
            "error": err1 or "step1 empty",
        }

    # ---- Step 2: qwen-mt-plus streaming chat completion ----
    from dashscope import Generation
    step2_t0 = time.monotonic()
    mt_first_t: Optional[float] = None
    mt_last_t: Optional[float] = None
    mt_deltas: List[float] = []
    mt_text = ""
    mt_err: Optional[str] = None
    try:
        messages = [
            {"role": "system", "content": MT_INSTRUCTIONS},
            {"role": "user", "content": asr_text},
        ]
        # incremental_output=True makes Generation emit only the new chunk
        # text per iteration — exactly what we need for TTFB timing.
        responses = Generation.call(
            model=args.mt_model,
            messages=messages,
            stream=True,
            incremental_output=True,
            result_format="message",
        )
        for chunk in responses:
            now = time.monotonic()
            if mt_first_t is None:
                mt_first_t = now
            mt_last_t = now
            mt_deltas.append(now)
            try:
                # chunk.output.choices[0].message.content is the new text
                content = chunk.output.choices[0].message.content
                if content:
                    mt_text += content
            except (AttributeError, IndexError, KeyError):
                # Some chunks may be metadata-only — skip.
                pass
    except Exception as e:
        mt_err = f"{type(e).__name__}: {e}"

    mt_ttfb = (mt_first_t - step2_t0) if mt_first_t else None
    mt_total = (mt_last_t - step2_t0) if mt_last_t else None
    mt_gaps = [b - a for a, b in zip(mt_deltas, mt_deltas[1:])] if len(mt_deltas) > 1 else []

    # Combined metrics: end-to-end time + first text from either stage.
    # ASR runs first, so combined ttfb is whichever stage delivered first.
    pipeline_first_t = first1 if first1 else mt_first_t
    pipeline_last_t = mt_last_t if mt_last_t else last1

    # Combined max_delta_gap: the worst stall across both stages, with
    # the MT start offset added so the value is comparable to single-mode.
    combined_gaps = list(gaps1)
    if mt_deltas:
        # MT runs serially after ASR; offsets within step 2 are relative.
        if mt_deltas and mt_deltas[0] is not None and last1 is not None:
            mt_gaps_abs = [(d - mt_deltas[0]) + (last1 - step1_t0)
                           for d in mt_deltas]
        else:
            mt_gaps_abs = []
        # Add inter-stage gap (ASR last → MT first).
        if mt_first_t and last1:
            inter = mt_first_t - last1
            combined_gaps.append(inter)
        combined_gaps.extend(mt_gaps_abs)

    return {
        "id": sid, "mode": "cascade2",
        # Combined end-to-end (what the user actually feels)
        "ttfb": (pipeline_first_t - pipeline_t0) if pipeline_first_t else None,
        "total": (pipeline_last_t - pipeline_t0) if pipeline_last_t else None,
        "max_delta_gap": max(combined_gaps) if combined_gaps else 0.0,
        # Per-stage breakdown
        "step1_ttfb": step1_ttfb,
        "step1_total": step1_total,
        "mt_ttfb": mt_ttfb,
        "mt_total": mt_total,
        "delta_count": len(deltas1) + len(mt_deltas),
        "n_chars_step1": len(asr_text or ""),
        "n_chars_step2": len(mt_text or ""),
        "error": mt_err,
    }


# ---------------------------------------------------------------------
# Aggregator
# ---------------------------------------------------------------------

def _latency(times: List[float]) -> dict:
    if not times:
        return {"ttfb": None, "total": None, "max_delta_gap": 0.0, "delta_count": 0}
    return {
        "ttfb": times[0],
        "total": times[-1],
        "max_delta_gap": max((b - a) for a, b in zip(times, times[1:])) if len(times) > 1 else 0.0,
        "delta_count": len(times),
    }


def summarise(samples: List[dict]) -> dict:
    """Median / p95 per metric per mode. p95 helps catch tail latencies
    that the average masks. For cascade2 we also surface per-stage
    breakdowns (step1_*, mt_*) so MT-step overhead is visible."""
    by_mode: Dict[str, List[dict]] = {}
    for s in samples:
        by_mode.setdefault(s["mode"], []).append(s)
    out: dict = {}
    for mode, rows in by_mode.items():
        if not rows: continue
        for metric in ("ttfb", "total", "max_delta_gap",
                       "step1_ttfb", "step1_total", "mt_ttfb", "mt_total"):
            vals = sorted([r[metric] for r in rows if r.get(metric) is not None])
            if not vals: continue
            out[f"{mode}.{metric}.median"] = statistics.median(vals)
            p95 = vals[max(0, int(0.95 * len(vals)) - 1)]
            out[f"{mode}.{metric}.p95"] = p95
            out[f"{mode}.{metric}.mean"] = statistics.mean(vals)
        out[f"{mode}.n"] = len(rows)
    return out


async def run(args) -> int:
    try:
        _set_api_key(args.api_key)
    except RuntimeError as e:
        print(f"ERROR: {e}", file=sys.stderr); return 2
    if "cascade1" in args.modes and not HAVE_DASHSCOPE:
        print("ERROR: cascade1 mode needs the `dashscope` Python SDK", file=sys.stderr)
        return 2

    manifest = [json.loads(ln) for ln in Path(args.manifest).read_text().splitlines()
                if ln.strip() and not ln.startswith("#")]
    if args.limit > 0:
        manifest = manifest[: args.limit]
    print(f"Loaded {len(manifest)} sample(s), modes={args.modes}")

    samples: List[dict] = []
    for i, item in enumerate(manifest, 1):
        sid = item["id"]
        audio = item["audio"]
        try:
            pcm = load_wav_as_pcm16_mono(audio)
        except Exception as e:
            print(f"[{i}/{len(manifest)}] {sid}: audio load failed: {e}")
            continue
        for mode in args.modes:
            print(f"[{i}/{len(manifest)}] {sid} mode={mode} ... ", end="", flush=True)
            t0 = time.monotonic()
            if mode == "joint":
                r = await run_joint(pcm, sid, args)
            elif mode == "cascade1":
                r = run_cascade1(pcm, sid, args)
            elif mode == "cascade2":
                r = run_cascade2(pcm, sid, args)
            else:
                r = {"id": sid, "mode": mode, "error": "unknown mode"}
            r["wall_s"] = round(time.monotonic() - t0, 3)
            samples.append(r)
            print(f"ttfb={r.get('ttfb')}  total={r.get('total')}  chars={r.get('n_chars')}")

    summary = summarise(samples)
    print("\nSummary:")
    for k, v in sorted(summary.items()):
        if isinstance(v, float):
            print(f"  {k:<32} {v:.3f}")
        else:
            print(f"  {k:<32} {v}")
    if args.report:
        out = {"config": vars(args), "samples": samples, "summary": summary,
               "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S")}
        Path(args.report).write_text(json.dumps(out, ensure_ascii=False, indent=2),
                                     encoding="utf-8")
        print(f"\nReport written: {args.report}")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description="Cascade latency sweep (server_vad mode)")
    p.add_argument("--manifest", required=True)
    p.add_argument("--api-key", default=None)
    p.add_argument("--endpoint", default="wss://dashscope.aliyuncs.com/api-ws/v1/realtime")
    p.add_argument("--model", default="qwen3.5-omni-flash-realtime-2026-03-15",
                   help="joint-mode model")
    p.add_argument("--asr-model", default="fun-asr-realtime",
                   help="cascade step 1 model (default fun-asr-realtime, "
                        "DashScope 2025 model that beat paraformer by -20% MER)")
    p.add_argument("--mt-model", default="qwen-mt-plus",
                   help="cascade step 2 MT model")
    p.add_argument("--vad-silence", type=int, default=300)
    p.add_argument("--vad-threshold", type=float, default=0.3)
    p.add_argument("--trailing-silence", type=int, default=800)
    p.add_argument("--modes", default="joint,cascade1",
                   help="comma-separated: joint, cascade1, cascade2")
    p.add_argument("--limit", type=int, default=10)
    p.add_argument("--report", default="report.latency.json")
    args = p.parse_args()
    args.modes = [m.strip() for m in args.modes.split(",") if m.strip()]
    return asyncio.run(run(args))


if __name__ == "__main__":
    sys.exit(main())