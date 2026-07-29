"""tools/eval/rescore_fillereq.py — backfill the filler-equivalence
secondary metric into an existing report, in place.

Adds ``mer_fe`` to every scored sample (raw ``mer`` untouched) and
avg/min/max_mer_fe to the summary, so historical reports become
dual-track without re-running the ASR (offline, no API calls).

Usage:
    python rescore_fillereq.py --manifest manifest.tier3.1000.jsonl \
        report.cascade.step1.funasr.tier3.1000.json [more reports...]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from metrics import filler_equiv_mer


def rescore(report_path: Path, refs: dict[str, str]) -> None:
    report = json.loads(report_path.read_text(encoding="utf-8"))
    samples = report.get("samples", [])
    n_scored = n_skipped = 0
    vals = []
    for s in samples:
        if s.get("error") or s.get("mer") is None:
            n_skipped += 1
            continue
        ref = (refs.get(s["id"]) or "").strip()
        if not ref:
            n_skipped += 1
            continue
        s["mer_fe"] = filler_equiv_mer(ref, s.get("hyp") or "")
        vals.append(s["mer_fe"])
        n_scored += 1
    if vals:
        summary = report.setdefault("summary", {})
        summary["avg_mer_fe"] = sum(vals) / len(vals)
        summary["min_mer_fe"] = min(vals)
        summary["max_mer_fe"] = max(vals)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2),
                           encoding="utf-8")
    avg = f"{sum(vals)/len(vals):.4f}" if vals else "—"
    print(f"{report_path.name}: {n_scored} re-scored, {n_skipped} skipped, "
          f"avg_mer_fe={avg}")


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--manifest", required=True,
                   help="manifest jsonl with the ground-truth refs")
    p.add_argument("reports", nargs="+", help="report JSON(s) to update in place")
    args = p.parse_args()

    refs: dict[str, str] = {}
    with open(args.manifest, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            d = json.loads(line)
            refs[d["id"]] = d.get("ref") or d.get("ref_transcript") or d.get("text") or ""
    for path in args.reports:
        rp = Path(path)
        if not rp.is_file():
            print(f"ERROR: not found: {rp}", file=sys.stderr)
            return 2
        rescore(rp, refs)
    return 0


if __name__ == "__main__":
    sys.exit(main())
