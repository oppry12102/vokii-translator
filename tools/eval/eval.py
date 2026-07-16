"""Vokii evaluation runner.

Reads a manifest of (audio, ref_zh, ref_en) tuples, runs each through
the Qwen-Omni Realtime pipeline, and writes a per-sample + aggregate
report (JSON + console table).

Usage:
    python eval.py \\
        --manifest manifest.jsonl \\
        --api-key "$DASHSCOPE_API_KEY" \\
        --model qwen3.5-omni-flash-realtime-2026-03-15 \\
        --endpoint wss://dashscope.aliyuncs.com/api-ws/v1/realtime \\
        --report report.json
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import time
from pathlib import Path
from typing import List, Optional

from metrics import (
    LatencyStats, aggregate, bleu_score, compute_latency,
    error_rate, missing_char_rate, mixed_error_rate, repetition_rate,
)
from qwen_client import EvalResult, QwenRealtimeClient, load_wav_as_pcm16_mono


# ---------------------------------------------------------------------
# Manifest
# ---------------------------------------------------------------------

def load_manifest(path: Path) -> List[dict]:
    items: List[dict] = []
    with path.open("r", encoding="utf-8") as f:
        for ln, line in enumerate(f, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError as e:
                print(f"  ! manifest line {ln}: {e}", file=sys.stderr)
                continue
            for k in ("id", "audio"):
                if k not in obj:
                    print(f"  ! manifest line {ln}: missing '{k}'", file=sys.stderr)
                    break
            else:
                items.append(obj)
    return items


# ---------------------------------------------------------------------
# Per-sample scoring
# ---------------------------------------------------------------------

def score_one(result: EvalResult, ref_zh: str, ref_en: str) -> dict:
    """Translation-task scoring: zh/en are scored independently against
    separate references. Latency + stream-health always present."""
    metrics: dict = {}

    _add_latency(metrics, result)

    # Health on each language.
    metrics["cer_zh"] = error_rate(ref_zh, result.text_zh, "char") if ref_zh else None
    metrics["wer_en"] = error_rate(ref_en, result.text_en, "word") if ref_en else None
    metrics["missing_rate_zh"] = missing_char_rate(ref_zh, result.text_zh) if ref_zh else None
    metrics["rep_rate"] = repetition_rate(result.text_zh + result.text_en)

    metrics["error"] = result.error
    metrics["hyp_zh"] = result.text_zh
    metrics["hyp_en"] = result.text_en
    return metrics


def score_transcribe(result: EvalResult, ref: str) -> dict:
    """Transcription-task scoring: a single verbatim hypothesis is scored
    against a single mixed reference with Mixed Error Rate (Chinese by
    char, English by word), plus per-language breakdown.

    Empty hypothesis (no text emitted at all) is treated as a hard failure:
    mer = 1.0 so an empty row shows up red in the summary table instead
    of being silently folded into the average. The ``is_empty`` flag is
    also set so the report carries the diagnostic forward.
    """
    metrics: dict = {}
    _add_latency(metrics, result)

    hyp = result.transcript or ""
    is_empty = (not hyp.strip()) and not result.error
    metrics["is_empty"] = is_empty
    if ref:
        if is_empty:
            # Empty hypothesis against a non-empty reference: every ref token
            # is "missing" → MER = 1.0. Without this, the sample drops out
            # of the average and masks regressions where the model started
            # declining to commit on short utterances (v2/v3 history).
            metrics["mer"] = 1.0
            metrics["cer_zh"] = 1.0 if any(_is_han(c) for c in ref) else None
            metrics["wer_en"] = 1.0 if any(_is_en_word(c) for c in ref.split()) else None
            metrics["n_ref_tokens"] = len(ref.split())
        else:
            mstats = mixed_error_rate(ref, hyp)
            metrics["mer"] = mstats.mer
            metrics["cer_zh"] = mstats.cer_zh if mstats.n_ref_zh else None
            metrics["wer_en"] = mstats.wer_en if mstats.n_ref_en else None
            metrics["n_ref_tokens"] = mstats.n_ref_tokens
    else:
        metrics["mer"] = None
        metrics["cer_zh"] = None
        metrics["wer_en"] = None
    metrics["rep_rate"] = repetition_rate(hyp)
    metrics["error"] = result.error
    metrics["hyp"] = hyp
    return metrics


def _is_han(c: str) -> bool:
    return "一" <= c <= "鿿"


def _is_en_word(tok: str) -> bool:
    return bool(tok) and tok[0].isalpha() and ord(tok[0]) < 128


def _add_latency(metrics: dict, result: EvalResult) -> None:
    lat = compute_latency(result.delta_times)
    metrics["ttfb"] = lat.ttfb
    metrics["total_latency"] = lat.total
    metrics["delta_count"] = lat.delta_count
    if lat.turn_gaps:
        gaps = lat.turn_gaps
        metrics["avg_delta_gap"] = sum(gaps) / len(gaps)
        metrics["max_delta_gap"] = max(gaps)
    else:
        metrics["avg_delta_gap"] = 0.0
        metrics["max_delta_gap"] = 0.0
    metrics["audio_seconds"] = result.audio_seconds
    metrics["pcm_bytes"] = result.pcm_bytes_sent


# ---------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------

def print_console_report(samples: List[dict], summary: dict) -> None:
    cols = ["id", "mer", "cer_zh", "wer_en", "rep_rate", "ttfb", "total_latency", "max_delta_gap", "err"]
    headers = {
        "id": "id", "mer": "MER", "cer_zh": "CER(zh)", "wer_en": "WER(en)", "rep_rate": "rep%",
        "ttfb": "TTFB", "total_latency": "Total", "max_delta_gap": "maxGap", "err": "err",
    }
    print()
    print("  ".join(f"{headers[c]:<14}" for c in cols))
    print("-" * (16 * len(cols)))
    for s in samples:
        row = []
        for c in cols:
            v = s.get(c)
            if v is None:
                row.append("—")
            elif c == "id":
                row.append(str(v)[:14])
            elif c == "err":
                row.append("Y" if v else "")
            elif isinstance(v, float):
                row.append(f"{v:.3f}")
            else:
                row.append(str(v))
        print("  ".join(f"{x:<14}" for x in row))

    print()
    print("Summary:")
    for k, v in sorted(summary.items()):
        if k.startswith("avg_") or k in ("avg_delta_gap",):
            label = k.replace("avg_", "")
            print(f"  {label:<22} {v:.3f}")
        elif k in ("n_samples", "n_errors", "n_empty"):
            print(f"  {k:<22} {v}")


def write_json_report(path: Path, samples: List[dict], summary: dict,
                      config: dict) -> None:
    out = {
        "config": config,
        "samples": samples,
        "summary": summary,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
    }
    path.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nReport written: {path}")


def _instructions_label(arg: Optional[str]) -> str:
    """Stamp a short label in the report so an A/B is attributable. Avoid
    dumping the full prompt into JSONL every time."""
    if arg is None:
        return "default(v1)"
    from qwen_client import (
        TRANSCRIBE_INSTRUCTIONS_V1,
        TRANSCRIBE_INSTRUCTIONS_V2,
        TRANSCRIBE_INSTRUCTIONS_V3,
    )
    if arg == TRANSCRIBE_INSTRUCTIONS_V1:
        return "v1"
    if arg == TRANSCRIBE_INSTRUCTIONS_V2:
        return "v2"
    if arg == TRANSCRIBE_INSTRUCTIONS_V3:
        return "v3"
    head = " ".join(arg.split()[:6])
    return f"custom:{head}…"


# ---------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------

async def run(args: argparse.Namespace) -> int:
    api_key = args.api_key or os.environ.get("DASHSCOPE_API_KEY") or os.environ.get("QWEN_API_KEY")
    if not api_key:
        print("ERROR: --api-key or DASHSCOPE_API_KEY env required", file=sys.stderr)
        return 2

    manifest = load_manifest(Path(args.manifest))
    if not manifest:
        print(f"ERROR: empty manifest at {args.manifest}", file=sys.stderr)
        return 2
    print(f"Loaded {len(manifest)} sample(s) from {args.manifest}")

    client = QwenRealtimeClient(
        endpoint=args.endpoint,
        model=args.model,
        api_key=api_key,
        silence_ms=args.vad_silence,
        threshold=args.vad_threshold,
        task=args.task,
        trailing_silence_ms=args.trailing_silence,
        commit_mode=args.commit_mode,
        realtime=not args.no_realtime,
        instructions=args.instructions,
        repetition_penalty=args.repetition_penalty,
    )
    print(f"Task: {args.task}  commit_mode={args.commit_mode}  "
          f"realtime={not args.no_realtime}  "
          f"repetition_penalty={args.repetition_penalty}")

    per_sample: List[dict] = []
    refs_zh: List[str] = []
    hyps_zh: List[str] = []
    refs_en: List[str] = []
    hyps_en: List[str] = []

    for i, item in enumerate(manifest, 1):
        sid = item["id"]
        audio_path = Path(item["audio"])
        ref_zh = (item.get("ref_zh") or "").strip()
        ref_en = (item.get("ref_en") or "").strip()
        # transcribe task: single mixed reference (fall back to ref_transcript).
        ref = (item.get("ref") or item.get("ref_transcript") or "").strip()
        print(f"[{i}/{len(manifest)}] {sid} ... ", end="", flush=True)
        t0 = time.monotonic()
        try:
            pcm = load_wav_as_pcm16_mono(str(audio_path))
        except Exception as e:
            print(f"audio load failed: {e}")
            per_sample.append({"id": sid, "error": f"audio load: {e}"})
            continue
        try:
            result = await client.transcribe(pcm, sid)
        except Exception as e:
            print(f"transcribe failed: {e}")
            per_sample.append({"id": sid, "error": f"transcribe: {e}"})
            continue
        if args.task == "transcribe":
            metrics = score_transcribe(result, ref)
        else:
            metrics = score_one(result, ref_zh, ref_en)
            if ref_zh and result.text_zh:
                refs_zh.append(ref_zh); hyps_zh.append(result.text_zh)
            if ref_en and result.text_en:
                refs_en.append(ref_en); hyps_en.append(result.text_en)
        per_sample.append({"id": sid, **metrics})
        dt = time.monotonic() - t0
        if args.task == "transcribe":
            print(f"done in {dt:.1f}s  MER={metrics['mer']}")
        else:
            print(f"done in {dt:.1f}s  CER={metrics['cer_zh']}  WER={metrics['wer_en']}")

    # Aggregate.
    sample_metrics = [
        {k: v for k, v in s.items() if k not in ("id", "error", "hyp", "hyp_zh", "hyp_en", "is_empty")}
        for s in per_sample
    ]
    summary = aggregate(sample_metrics)
    if args.task == "translate":
        summary["bleu_zh"] = bleu_score(refs_zh, hyps_zh)
        summary["bleu_en"] = bleu_score(refs_en, hyps_en)
    summary["n_samples"] = len(per_sample)
    summary["n_errors"] = sum(1 for s in per_sample if s.get("error"))
    summary["n_empty"] = sum(1 for s in per_sample if s.get("is_empty"))

    print_console_report(per_sample, summary)

    if args.report:
        write_json_report(
            Path(args.report), per_sample, summary,
            config={"model": args.model, "endpoint": args.endpoint,
                    "vad_silence_ms": args.vad_silence,
                    "vad_threshold": args.vad_threshold,
                    "commit_mode": args.commit_mode,
                    "task": args.task,
                    "repetition_penalty": args.repetition_penalty,
                    "instructions": _instructions_label(args.instructions),
                    "n_samples": len(manifest)},
        )
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description="Vokii eval runner")
    p.add_argument("--manifest", required=True, help="path to JSONL manifest")
    p.add_argument("--task", default="transcribe", choices=["transcribe", "translate"],
                   help="transcribe = verbatim ASR scored with MER; "
                        "translate = interpreter pipeline scored with CER/WER/BLEU")
    p.add_argument("--api-key", help="DashScope API key (or DASHSCOPE_API_KEY env)")
    p.add_argument("--model", default="qwen3.5-omni-flash-realtime-2026-03-15")
    p.add_argument("--endpoint", default="wss://dashscope.aliyuncs.com/api-ws/v1/realtime")
    p.add_argument("--vad-silence", type=int, default=300, help="server_vad silence_duration_ms")
    p.add_argument("--vad-threshold", type=float, default=0.3)
    p.add_argument("--trailing-silence", type=int, default=800,
                   help="ms of trailing silence appended so server_vad "
                        "commits the final turn (dataset clips have none)")
    p.add_argument("--commit-mode", default="manual", choices=["manual", "vad"],
                   help="manual: disable VAD, push whole clip + one commit "
                        "(clean ASR accuracy, no segment drops). "
                        "vad: mirror the production server_vad path.")
    p.add_argument("--no-realtime", action="store_true",
                   help="don't pace upload at real time — much faster for "
                        "big accuracy runs, but makes latency metrics moot")
    p.add_argument("--instructions", default=None,
                   help="session instructions override. Sentinels: "
                        "'v1' (default — best on tier1), 'v2' (5 strict rules, "
                        "tier1 +0.025 regression), 'v3' (v1 + rules 1/2/3). "
                        "Anything else is passed verbatim to the model.")
    p.add_argument("--repetition-penalty", type=float, default=None,
                   help="optional sampling knob forwarded to session.update. "
                        "1.0 = off; try 1.05-1.1 to curb stuck repeats. "
                        "Server may ignore if unsupported.")
    p.add_argument("--report", default="report.json", help="output JSON path (or empty to skip)")
    p.add_argument("--limit", type=int, default=0, help="only run first N samples (0 = all)")
    args = p.parse_args()
    # Resolve prompt-version sentinels to literal prompt strings.
    from qwen_client import (
        TRANSCRIBE_INSTRUCTIONS_V1,
        TRANSCRIBE_INSTRUCTIONS_V2,
        TRANSCRIBE_INSTRUCTIONS_V3,
    )
    if args.instructions is None or args.instructions == "v1":
        args.instructions = TRANSCRIBE_INSTRUCTIONS_V1
    elif args.instructions == "v2":
        args.instructions = TRANSCRIBE_INSTRUCTIONS_V2
    elif args.instructions == "v3":
        args.instructions = TRANSCRIBE_INSTRUCTIONS_V3
    if args.limit > 0:
        # Pre-truncate the loaded manifest by intercepting load_manifest.
        orig = load_manifest
        def patched(p):
            return orig(p)[:args.limit]
        globals()["load_manifest"] = patched
    return asyncio.run(run(args))


if __name__ == "__main__":
    sys.exit(main())
