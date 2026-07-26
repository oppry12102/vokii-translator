"""tools/eval/audio_diff.py

Quantify how much production audio quality degrades MER relative to the
clean CS-Dialogue reference. Answers the question "is the eval harness's
0.100 MER representative of what users actually see on-device?"

Two modes per row (auto-detected from the manifest):

  Paired     — both ``clean_audio`` and ``prod_audio`` paths are present.
               ``prod_audio`` is what you recorded by replaying the clean
               clip through your phone speaker into the mic. This is the
               ground truth.

  Synthetic  — only ``clean_audio`` is present, plus a ``degradation``
               field naming one of the cheap first-order models below.
               Bounds the prod-induced delta without needing recorded
               prod audio.

Per row we run the full Vokii pipeline **twice** (once on clean, once
on prod) and score both against ``ref``. Output is ΔMER = MER_prod −
MER_clean plus the worst-loss clips so you know which audio slices
dominate the regression.

Typical workflow:

    # 1) Synthetic baseline (no recording needed)
    python audio_diff.py --manifest manifest.tier2.jsonl \\
        --model qwen3.5-omni-plus-realtime-2026-03-15 \\
        --report report.diff.synth.json --limit 30

    # 2) Build a paired manifest by re-recording a small subset on the
    #    target phone and pointing prod_audio at the captured wav.
    # 3) Re-run on the paired manifest.
    python audio_diff.py --manifest paired.tier1.jsonl \\
        --report report.diff.paired.json
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

import numpy as np

from metrics import mixed_error_rate
from qwen_client import QwenRealtimeClient, load_wav_as_pcm16_mono


SAMPLE_RATE = 16000


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
            for k in ("id", "clean_audio"):
                if k not in obj:
                    print(f"  ! manifest line {ln}: missing '{k}'", file=sys.stderr)
                    break
            else:
                items.append(obj)
    return items


# ---------------------------------------------------------------------
# Synthetic degradations
# ---------------------------------------------------------------------
#
# Cheap first-order models of the Android audio capture path
# (mic → OpenSLES / AAudio → 16 kHz mono PCM16). Tuned to roughly match
# what we observe when comparing prod captures to the dataset. These are
# not a substitute for an actual prod recording; they bracket the impact
# so we know whether to expect a 1-point or 10-point MER delta.
#
# All operate on a 16 kHz mono float32 PCM in [-1, 1] and return back to
# 16-bit little-endian bytes in the same layout ``load_wav_as_pcm16_mono``
# produces.

def _to_float(pcm: bytes) -> np.ndarray:
    return np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0


def _to_int16(arr: np.ndarray) -> bytes:
    return (np.clip(arr, -1.0, 1.0) * 32767).astype("<i2").tobytes()


def _resolve_instructions(arg: str) -> str:
    """Map the user-facing instructions flag to a literal string. 'v1' /
    'v2' / 'v3' / 'default' are sentinels; anything else is taken verbatim.

    The default is v1 — that's the version that won the tier1 A/B. Pass
    'v2' or 'v3' to A/B a different prompt. Pass a literal string to test
    a free-form edit.
    """
    from qwen_client import (
        TRANSCRIBE_INSTRUCTIONS_V1,
        TRANSCRIBE_INSTRUCTIONS_V2,
        TRANSCRIBE_INSTRUCTIONS_V3,
    )
    if arg in (None, "", "default", "v1"):
        return TRANSCRIBE_INSTRUCTIONS_V1
    if arg == "v2":
        return TRANSCRIBE_INSTRUCTIONS_V2
    if arg == "v3":
        return TRANSCRIBE_INSTRUCTIONS_V3
    return arg


def _add_noise(pcm: np.ndarray, snr_db: float, rng: np.random.Generator) -> np.ndarray:
    sig_pow = float((pcm ** 2).mean())
    if sig_pow <= 0:
        return pcm
    noise_pow = sig_pow / (10 ** (snr_db / 10))
    return pcm + rng.standard_normal(len(pcm)) * np.sqrt(noise_pow)


def _bandpass(pcm: np.ndarray, lo: float, hi: float, order: int = 4) -> np.ndarray:
    from scipy.signal import butter, sosfiltfilt  # type: ignore
    sos = butter(order, [lo, hi], btype="bandpass", fs=SAMPLE_RATE, output="sos")
    out = sosfiltfilt(sos, pcm).astype(np.float32)
    return out


def _resample_round_trip(pcm: np.ndarray, target_sr: int) -> np.ndarray:
    from scipy.signal import resample_poly  # type: ignore
    up = resample_poly(pcm, target_sr, SAMPLE_RATE)
    return resample_poly(up, SAMPLE_RATE, target_sr).astype(np.float32)


# Available degradation kinds (also see --help).
DEGRADATIONS = (
    "none",
    "add_noise_25db", "add_noise_20db", "add_noise_15db", "add_noise_10db",
    "mic_eq_strict",        # telephony 300-3400 Hz
    "mic_eq_loose",         # smartphone ~100-7000 Hz
    "resample_44k",         # 16 -> 44.1 -> 16 round-trip
    "mic_eq+noise_20db",    # bandpass 300-3400 + 20 dB SNR noise
    "mic_loose+noise_15db+resample",  # bandpass 100-7000 + 15 dB SNR + resample
)


def _parse_kind(kind: str) -> tuple[str, dict]:
    """Split a degradation name into (base, args). E.g.
    ``add_noise_20db`` -> ('add_noise', {'snr_db': 20.0}).
    """
    if kind in DEGRADATIONS:
        if kind == "none":
            return "none", {}
        if kind == "mic_eq_strict":
            return "bandpass", {"lo": 300.0, "hi": 3400.0}
        if kind == "mic_eq_loose":
            return "bandpass", {"lo": 100.0, "hi": 7000.0}
        if kind == "resample_44k":
            return "resample", {"target_sr": 44100}
        if kind == "mic_eq+noise_20db":
            return "eq_noise", {"lo": 300.0, "hi": 3400.0, "snr_db": 20.0}
        if kind == "mic_loose+noise_15db+resample":
            return "eq_noise_resample", {"lo": 100.0, "hi": 7000.0,
                                          "snr_db": 15.0, "target_sr": 44100}
        if kind.startswith("add_noise_") and kind.endswith("db"):
            snr = float(kind[len("add_noise_"):-2])
            return "noise", {"snr_db": snr}
    raise ValueError(f"unknown degradation kind: {kind!r} "
                     f"(see DEGRADATIONS)")


def apply_degradation(pcm: bytes, kind: str, seed: int = 0) -> bytes:
    """Apply a named degradation to a 16 kHz mono int16 PCM buffer."""
    rng = np.random.default_rng(seed)
    base, args = _parse_kind(kind)
    x = _to_float(pcm)

    if base == "none":
        out = x
    elif base == "noise":
        out = _add_noise(x, args["snr_db"], rng)
    elif base == "bandpass":
        out = _bandpass(x, args["lo"], args["hi"])
    elif base == "resample":
        out = _resample_round_trip(x, args["target_sr"])
    elif base == "eq_noise":
        out = _bandpass(x, args["lo"], args["hi"])
        out = _add_noise(out, args["snr_db"], rng)
    elif base == "eq_noise_resample":
        out = _bandpass(x, args["lo"], args["hi"])
        out = _add_noise(out, args["snr_db"], rng)
        out = _resample_round_trip(out, args["target_sr"])
    else:
        raise ValueError(f"unknown degradation base: {base!r}")
    return _to_int16(out)


# ---------------------------------------------------------------------
# Per-sample scoring
# ---------------------------------------------------------------------

async def _transcribe_one(client: QwenRealtimeClient, pcm: bytes, sid: str):
    return await client.transcribe(pcm, sid)


def _score(ref: str, hyp: str) -> tuple[Optional[float], int]:
    """Return (mer, n_ref_tokens). None MER means no reference to score
    against — the row still contributes to latency / non-scoring stats."""
    if not ref:
        return None, 0
    s = mixed_error_rate(ref, hyp)
    return s.mer, s.n_ref_tokens


# ---------------------------------------------------------------------
# Runner
# -----------------------------------------------------------------

async def run(args: argparse.Namespace) -> int:
    api_key = args.api_key or os.environ.get("DASHSCOPE_API_KEY") or os.environ.get("QWEN_API_KEY")
    if not api_key:
        print("ERROR: --api-key or DASHSCOPE_API_KEY env required", file=sys.stderr)
        return 2

    items = load_manifest(Path(args.manifest))
    if not items:
        print(f"ERROR: empty manifest at {args.manifest}", file=sys.stderr)
        return 2
    print(f"Loaded {len(items)} item(s) from {args.manifest}")

    client = QwenRealtimeClient(
        endpoint=args.endpoint, model=args.model, api_key=api_key,
        task="transcribe", commit_mode=args.commit_mode,
        realtime=not args.no_realtime,
        repetition_penalty=args.repetition_penalty,
        instructions=_resolve_instructions(args.instructions),
    )

    per_sample: List[dict] = []
    for i, item in enumerate(items, 1):
        sid = item["id"]
        ref = (item.get("ref") or item.get("ref_transcript") or "").strip()
        clean_path = Path(item["clean_audio"])
        prod_path = Path(item["prod_audio"]) if item.get("prod_audio") else None
        degradation = item.get("degradation")
        mode = "paired" if prod_path else "synthetic"

        print(f"[{i}/{len(items)}] {sid} mode={mode} ", end="", flush=True)
        if mode == "paired":
            print(f"({clean_path.name} → {prod_path.name}) ", end="", flush=True)
        else:
            print(f"({clean_path.name} → '{degradation}') ", end="", flush=True)

        try:
            clean_pcm = load_wav_as_pcm16_mono(str(clean_path))
        except Exception as e:
            print(f"clean load failed: {e}")
            continue

        if mode == "paired":
            try:
                prod_pcm = load_wav_as_pcm16_mono(str(prod_path))
            except Exception as e:
                print(f"prod load failed: {e}")
                continue
        else:
            try:
                prod_pcm = apply_degradation(clean_pcm, degradation or "none",
                                             seed=args.seed)
            except Exception as e:
                print(f"degradation {degradation!r} failed: {e}")
                continue

        # Two API round-trips per row: one on clean, one on prod.
        # Sequential to keep rate-limit handling boring.
        try:
            clean_res = await _transcribe_one(client, clean_pcm, f"{sid}.clean")
            prod_res = await _transcribe_one(client, prod_pcm, f"{sid}.prod")
        except Exception as e:
            print(f"transcribe failed: {e}")
            continue

        mer_c, n_ref = _score(ref, clean_res.transcript)
        mer_p, _ = _score(ref, prod_res.transcript)
        delta = None
        if mer_c is not None and mer_p is not None:
            delta = mer_p - mer_c

        per_sample.append({
            "id": sid,
            "mode": mode,
            "degradation": degradation if mode == "synthetic" else None,
            "n_ref_tokens": n_ref,
            "clean_mer": mer_c,
            "prod_mer": mer_p,
            "delta_mer": delta,
            "ref": ref,
            "clean_hyp": clean_res.transcript,
            "prod_hyp": prod_res.transcript,
            "clean_error": clean_res.error,
            "prod_error": prod_res.error,
        })

        if delta is None:
            print(f"  clean={mer_c}  prod={mer_p}  (no ref to diff)")
        else:
            print(f"  clean={mer_c:.3f}  prod={mer_p:.3f}  Δ={delta:+.3f}")

    summary = summarize(per_sample)
    print_summary(summary, per_sample, args)

    if args.report:
        write_json_report(Path(args.report), per_sample, summary, args)
    return 0


def summarize(per_sample: List[dict]) -> dict:
    """Aggregate per-sample dicts into a summary. Rows without a ref (no
    delta) are counted but excluded from the MER statistics."""
    valid = [s for s in per_sample if s.get("delta_mer") is not None]
    if not valid:
        return {"n": 0, "n_total": len(per_sample)}
    return {
        "n": len(valid),
        "n_total": len(per_sample),
        "avg_clean_mer": sum(s["clean_mer"] for s in valid) / len(valid),
        "avg_prod_mer": sum(s["prod_mer"] for s in valid) / len(valid),
        "avg_delta_mer": sum(s["delta_mer"] for s in valid) / len(valid),
        "max_delta_mer": max(s["delta_mer"] for s in valid),
        "min_delta_mer": min(s["delta_mer"] for s in valid),
        "pct_prod_better": sum(1 for s in valid if s["delta_mer"] < 0) / len(valid),
        "pct_prod_worse": sum(1 for s in valid if s["delta_mer"] > 0) / len(valid),
        "pct_prod_tied": sum(1 for s in valid if s["delta_mer"] == 0) / len(valid),
    }


def print_summary(summary: dict, per_sample: List[dict], args) -> None:
    print("\n=== summary ===")
    if summary.get("n", 0) == 0:
        print("  (no scored rows)")
        return
    print(f"  pairs scored        {summary['n']}  (of {summary['n_total']} total)")
    print(f"  avg clean MER       {summary['avg_clean_mer']:.4f}")
    print(f"  avg prod  MER       {summary['avg_prod_mer']:.4f}")
    print(f"  ΔMER (prod - clean) {summary['avg_delta_mer']:+.4f}")
    print(f"  range               [{summary['min_delta_mer']:+.4f}, "
          f"{summary['max_delta_mer']:+.4f}]")
    print(f"  prod better         {summary['pct_prod_better']*100:5.1f}%")
    print(f"  prod tied           {summary['pct_prod_tied']*100:5.1f}%")
    print(f"  prod worse          {summary['pct_prod_worse']*100:5.1f}%")

    by_worst = sorted(
        [s for s in per_sample if s.get("delta_mer") is not None],
        key=lambda s: -s["delta_mer"],
    )[:args.worst_n]
    if by_worst:
        print(f"\n=== top {len(by_worst)} prod-worst clips ===")
        print(f"  {'id':<22} {'clean':>8} {'prod':>8} {'Δ':>7}  excerpt")
        for s in by_worst:
            hyp = (s.get("prod_hyp") or "")[:60].replace("\n", " ")
            print(f"  {s['id']:<22} {s['clean_mer']:8.3f} {s['prod_mer']:8.3f} "
                  f"{s['delta_mer']:+7.3f}  '{hyp}'")


def write_json_report(path: Path, per_sample: List[dict], summary: dict, args) -> None:
    out = {
        "config": {
            "model": args.model, "endpoint": args.endpoint,
            "commit_mode": args.commit_mode,
            "repetition_penalty": args.repetition_penalty,
            "instructions": args.instructions,
            "n_items": len(per_sample),
        },
        "samples": per_sample,
        "summary": summary,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
    }
    path.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nReport written: {path}")


def main() -> int:
    # Print ref/hyp (Chinese) safely on a Windows cp1252/gbk console; no-op on
    # Linux/macOS where stdout is already utf-8.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    p = argparse.ArgumentParser(description=(
        "Vokii audio diff — quantify prod-induced MER drift. "
        "Each manifest row is scored on clean AND prod audio; ΔMER = "
        "MER_prod - MER_clean."
    ))
    p.add_argument("--manifest", required=True,
                   help="JSONL: {id, ref, clean_audio, prod_audio?, degradation?}")
    p.add_argument("--api-key", help="DashScope API key (or DASHSCOPE_API_KEY env)")
    p.add_argument("--model", default="qwen3.5-omni-plus-realtime-2026-03-15",
                   help="default = Pro model — accuracy baseline")
    p.add_argument("--endpoint", default="wss://dashscope.aliyuncs.com/api-ws/v1/realtime")
    p.add_argument("--commit-mode", default="manual", choices=["manual", "vad"])
    p.add_argument("--no-realtime", action="store_true")
    p.add_argument("--repetition-penalty", type=float, default=None)
    p.add_argument("--instructions", default="v1",
                   help="session instructions. Default 'v1' (best on tier1). "
                        "Sentinels: 'v1', 'v2' (5 strict rules; +0.025 on tier1), "
                        "'v3' (v1 + 3 safe rules). Anything else is taken verbatim. "
                        "A/B fairness: same instructions on clean and prod sides.")
    p.add_argument("--limit", type=int, default=0,
                   help="only run the first N rows (0 = all)")
    p.add_argument("--worst-n", type=int, default=10,
                   help="print the N prod-worst clips in the summary")
    p.add_argument("--seed", type=int, default=0,
                   help="RNG seed for synthetic degradations (reproducible)")
    p.add_argument("--report", default="report.diff.json")
    args = p.parse_args()
    if args.limit > 0:
        orig = load_manifest
        def patched(p): return orig(p)[:args.limit]   # noqa: F811
        globals()["load_manifest"] = patched
    return asyncio.run(run(args))


if __name__ == "__main__":
    sys.exit(main())
