"""tools/eval/cascade_step1_alt.py — alternate-ASR A/B for the cascade step 1.

Reuses the cascade_step1 driver but exposes the model id so we can swap
between DashScope realtime ASR endpoints on the same tier2 manifest:

    python cascade_step1_alt.py --model fun-asr-realtime --report report.cascade.step1.funasr.tier2.json
    python cascade_step1_alt.py --model paraformer-realtime-v1 --report report.cascade.step1.pfv1.tier2.json
    python cascade_step1_alt.py --model sensevoice-realtime-v1 --report report.cascade.step1.sv.tier2.json

Compare with cascade_compare.py against report.cascade.step1.tier2.json
(the v2 baseline) to see if any of these alternatives:
  - recovers the 65 regression samples that v1 (Qwen-Omni) handled but
    Paraformer-v2 missed;
  - keeps the 97 cascade-step1 wins without giving them back;
  - improves on the ≤10-token bucket (currently MER 0.132).

Notes on model availability (as of 2026-07):
  - paraformer-realtime-v2 : the current baseline (MER 0.087 tier2).
  - fun-asr-realtime       : newer DashScope model (2025-08-22 release),
                             claims accuracy close to offline ASR.
  - sensevoice-realtime-v1 : older but known good for zh+en CS; being
                             phased out per DashScope 2026 roadmap.
  - paraformer-realtime-v1 : backstop, in case v2 is unavailable.

Usage:
    export DASHSCOPE_API_KEY=sk-...
    python cascade_step1_alt.py --manifest manifest.tier2.jsonl \
        --model fun-asr-realtime --report report.cascade.step1.funasr.tier2.json
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

# Reuse the existing cascade_step1 implementation verbatim — the driver is
# model-agnostic; only --model changes. This file is the canonical entry
# point for "try the cascade with a different ASR engine".
from cascade_step1 import run, DEFAULT_MODEL  # noqa: F401


def main() -> int:
    p = argparse.ArgumentParser(description=(
        "Cascade step 1 — alternate-ASR A/B. Mirrors cascade_step1.py but "
        "exposes --model so we can swap DashScope realtime ASR engines."
    ))
    p.add_argument("--manifest", required=True)
    p.add_argument("--api-key", help="DashScope API key")
    p.add_argument("--model", default=DEFAULT_MODEL,
                   help="DashScope realtime ASR model id. Candidates: "
                        "paraformer-realtime-v2 (default), fun-asr-realtime, "
                        "sensevoice-realtime-v1, paraformer-realtime-v1")
    p.add_argument("--limit", type=int, default=0)
    p.add_argument("--timeout", type=float, default=30.0)
    p.add_argument("--report", default="report.cascade.step1.alt.json")
    args = p.parse_args()
    return run(args)


if __name__ == "__main__":
    sys.exit(main())