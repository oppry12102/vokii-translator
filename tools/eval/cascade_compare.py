"""tools/eval/cascade_compare.py

Side-by-side comparison of two (or more) eval reports, optimised for
the transcription-quality workstream.

Every A/B, prompt swap, model swap, audio-diff run produces a JSON
report with the same per-sample schema (``id``, ``mer``, ``cer_zh``,
``wer_en``, ``error``). This script ingests those reports and prints:

  - Per-report summary (avg MER / CER_zh / WER_en, perfect/empty counts)
  - Distribution buckets (perfect, ≤0.05, ≤0.10, ≤0.20, >0.50)
  - Length bucket breakdown (≤10 / 10-30 / 30-60 / 60+ ref tokens)
  - Pairwise directional split (which report is better on each sample)
  - Worst samples in each direction (top 5 regressions + top 5 wins)
  - Per-bucket row of CSV-formatted output to stdout for paste-into-Excel

Usage:
    python cascade_compare.py --baseline report.tier2.v1.json \\
        --challenger report.cascade.step1.tier2.json

    # More than two:
    python cascade_compare.py \\
        report.tier2.v1.json report.cascade.step1.tier2.json report.tier2.v3.json

The first arg (or --baseline) is "A", each subsequent (or --challenger
list) is compared against A. If a challenger is strictly *better* than A
on a row, it's a "win"; equal = tie; worse = "loss". Direction labels
relative to A.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from statistics import mean, median
from typing import List, Optional


# ---------------------------------------------------------------------
# Per-report helpers
# ---------------------------------------------------------------------

def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def per_sample_rows(report: dict) -> List[dict]:
    return [s for s in report.get("samples", []) if s.get("mer") is not None]


def summary(report: dict) -> dict:
    rows = per_sample_rows(report)
    if not rows:
        return {"n": 0}
    s = {}
    s["n"] = len(rows)
    s["avg_mer"] = mean(r["mer"] for r in rows)
    s["median_mer"] = median(r["mer"] for r in rows)
    if any("cer_zh" in r and r["cer_zh"] is not None for r in rows):
        s["avg_cer_zh"] = mean(r["cer_zh"] for r in rows if r["cer_zh"] is not None)
    if any("wer_en" in r and r["wer_en"] is not None for r in rows):
        s["avg_wer_en"] = mean(r["wer_en"] for r in rows if r["wer_en"] is not None)
    s["perfect"] = sum(1 for r in rows if r["mer"] == 0.0)
    s["le_005"]  = sum(1 for r in rows if r["mer"] <= 0.05)
    s["le_010"]  = sum(1 for r in rows if r["mer"] <= 0.10)
    s["le_020"]  = sum(1 for r in rows if r["mer"] <= 0.20)
    s["le_050"]  = sum(1 for r in rows if r["mer"] <= 0.50)
    s["gt_050"]  = sum(1 for r in rows if r["mer"] > 0.50)
    s["empty"] = sum(1 for s in report.get("samples", [])
                     if not s.get("hyp", "").strip())
    return s


_BUCKETS = [
    ("≤10",   lambda n: n <= 10),
    ("10-30", lambda n: 10 < n <= 30),
    ("30-60", lambda n: 30 < n <= 60),
    ("60+",   lambda n: n > 60),
]


def length_buckets(report: dict) -> dict:
    """Return avg MER per token-count bucket. ``token count`` ≈
    the reference's character count (good enough — see metrics.py
    for exact detail)."""
    rows = per_sample_rows(report)
    out = {}
    for label, pred in _BUCKETS:
        sub = []
        for r in rows:
            # n_ref_tokens when present is the **post-tokenization** count
            # (from mixed_error_rate); for short rows that count may be 1.
            tag = r.get("n_ref_tokens")
            tok = tag if (tag and isinstance(tag, int)) else None
            if tok is None:
                # Fallback: count chars in the hyp/ref.
                text = r.get("ref") or r.get("hyp") or ""
                # Stripping approximates a token count for mixed text.
                tok = len(text) if len(text) < 200 else len(text) // 2
            if pred(tok):
                sub.append(r["mer"])
        out[label] = (mean(sub) if sub else None, len(sub))
    return out


def name_of(report: dict, path: Path) -> str:
    cfg = report.get("config") or {}
    bits = []
    if cfg.get("model"):
        m = cfg["model"]
        # Order matters: test the most specific ids first. "qwen3-asr-flash"
        # contains "flash" but is NOT an omni model; the omni-plus/omni-flash
        # ids both contain "omni" and the flash id contains "flash", so
        # "plus" precedes "flash". Dated fun-asr snapshots keep their date so
        # an undated-vs-dated A/B doesn't show two identical columns.
        date = re.search(r"20\d{2}-\d{2}-\d{2}", m)
        suffix = ("@" + date.group(0)) if date else ""
        bits.append(("qwen3-asr" + suffix if "qwen3-asr" in m else
                     "qwen-omni-plus" if "plus" in m else
                     "qwen-omni-flash" if "flash" in m else
                     "paraformer" if "paraformer" in m else
                     "fun-asr" + suffix if "fun" in m else
                     m))
    if cfg.get("instructions"):
        bits.append("instr=" + cfg["instructions"])
    label = "+".join(bits) if bits else path.stem
    return label[:32]


# ---------------------------------------------------------------------
# Pairwise comparison
# ---------------------------------------------------------------------

def pairwise(a_rows: List[dict], b_rows: List[dict]) -> dict:
    """For samples present in both lists (matched by ``id``), decide
    win / tie / loss for B relative to A. ``mer`` strictly lower = win."""
    a_by_id = {r["id"]: r for r in a_rows}
    matched: List[tuple] = []
    for br in b_rows:
        ar = a_by_id.get(br["id"])
        if ar is None:
            continue
        matched.append((ar, br))
    wins  = [(a, b) for a, b in matched if b["mer"] < a["mer"]]
    ties  = [(a, b) for a, b in matched if b["mer"] == a["mer"]]
    losses = [(a, b) for a, b in matched if b["mer"] > a["mer"]]
    return {
        "matched": len(matched),
        "wins":  wins,
        "ties":  ties,
        "losses": losses,
    }


# ---------------------------------------------------------------------
# Printing
# ---------------------------------------------------------------------

def fmt(x, fmtstr: str = ".4f") -> str:
    if x is None:
        return "—"
    return f"{x:{fmtstr}}"


def print_summary_table(reports_full: List[tuple], summaries: List[tuple]) -> None:
    """Print summary stats. ``summaries`` is the precomputed summary list
    (name, summary_dict); ``reports_full`` is the raw reports (used by
    the length-bucket and pairwise helpers)."""
    print("\n=== headline summary ===\n")
    col_w = max(28, max(len(n) for n, _ in summaries) + 2)
    print(f"  {'metric':<28}" + "".join(f"{n:>{col_w}}" for n, _ in summaries))
    print("  " + "-" * (28 + col_w * len(summaries)))
    rows = [
        ("n",            lambda s: s["n"]),
        ("avg MER",      lambda s: s.get("avg_mer")),
        ("median MER",   lambda s: s.get("median_mer")),
        ("avg CER_zh",   lambda s: s.get("avg_cer_zh")),
        ("avg WER_en",   lambda s: s.get("avg_wer_en")),
        ("perfect",      lambda s: s.get("perfect")),
        ("≤0.05",        lambda s: s.get("le_005")),
        ("≤0.10",        lambda s: s.get("le_010")),
        ("≤0.20",        lambda s: s.get("le_020")),
        (">0.50",        lambda s: s.get("gt_050")),
        ("empty",        lambda s: s.get("empty")),
    ]
    for label, fn in rows:
        vals = [fn(s) for _, s in summaries]
        formatted = [fmt(v) if isinstance(v, float) else str(v) for v in vals]
        print(f"  {label:<28}" + "".join(f"{f:>{col_w}}" for f in formatted))


def print_length_buckets(reports: List[tuple]) -> None:
    """reports is full reports (raw dicts with 'samples'), not summaries."""
    print("\n=== avg MER by ref length bucket ===\n")
    col_w = max(28, max(len(n) for n, _ in reports) + 2)
    print(f"  {'bucket (ref tokens)':<28}" +
          "".join(f"{n + ' (n)':>{col_w}}" for n, _ in reports))
    for label in ("≤10", "10-30", "30-60", "60+"):
        cells = []
        for _, r in reports:
            bucket = length_buckets(r)
            mv, n = bucket[label]
            cells.append(f"{fmt(mv)} ({n})")
        print(f"  {label:<28}" + "".join(f"{c:>{col_w}}" for c in cells))


def print_pairwise(reports: List[tuple]) -> None:
    """A vs each other. Operates on full reports."""
    if len(reports) < 2:
        return
    base_name, base = reports[0]
    base_rows = per_sample_rows(base)
    print(f"\n=== pairwise vs {base_name} ===\n")
    print(f"  {'challenger':<28}{'wins':>8}{'ties':>8}{'losses':>10}{'avg ΔMER':>12}")
    for name, rep in reports[1:]:
        cmp = pairwise(base_rows, per_sample_rows(rep))
        avg_delta = (mean(b["mer"] - a["mer"] for a, b in cmp["wins"] + cmp["losses"])
                     if cmp["wins"] or cmp["losses"] else 0.0)
        n_m = cmp["matched"]
        print(f"  {name:<28}{len(cmp['wins']):>8}{len(cmp['ties']):>8}"
              f"{len(cmp['losses']):>10}{avg_delta:>+12.4f} "
              f"  (matched: {n_m})")


def print_extremes(reports: List[tuple], k: int = 5) -> None:
    """Top-k wins and losses for the first challenger vs baseline."""
    if len(reports) < 2:
        return
    base_name, base = reports[0]
    chall_name, chall = reports[1]
    base_rows = per_sample_rows(base)
    chall_rows = per_sample_rows(chall)
    cmp = pairwise(base_rows, chall_rows)

    def show(label: str, items: List[tuple]) -> None:
        if not items:
            return
        print(f"\n  {label} (top {min(k, len(items))} of {len(items)}):")
        for a, b in sorted(items, key=lambda ab: -(ab[1]["mer"] - ab[0]["mer"]))[:k]:
            d = b["mer"] - a["mer"]
            print(f"    {b['id']:<22}  base={a['mer']:.3f}  "
                  f"chall={b['mer']:.3f}  Δ={d:+.3f}")

    print(f"\n=== extremes: {chall_name} vs {base_name} ===")
    show(f"{chall_name} wins (lower MER)",  cmp["wins"])
    show(f"{chall_name} losses (higher MER)", cmp["losses"])


def print_csv(reports: List[tuple], summaries: List[tuple]) -> None:
    """Print a CSV header + one row of values per metric so the output
    pastes into Excel. Uses summaries for head-row stats; reports for the
    length-bucket breakdown."""
    print("\n=== CSV (paste into a spreadsheet) ===\n")
    keys = ["n", "avg_mer", "median_mer", "avg_cer_zh", "avg_wer_en",
            "perfect", "le_005", "le_010", "le_020", "gt_050", "empty"]
    names = [n for n, _ in summaries]
    print(",".join(["metric"] + names))
    for k in keys:
        row = [k]
        for _, s in summaries:
            v = s.get(k)
            row.append("" if v is None else f"{v}")
        print(",".join(row))
    print("\n# length-bucket breakdown (avg MER per ref-token bucket)")
    print("bucket," + ",".join(names))
    for label in ("≤10", "10-30", "30-60", "60+"):
        cells = []
        for _, r in reports:
            mv, n = length_buckets(r)[label]
            cells.append("" if mv is None else f"{mv:.4f} (n={n})")
        print(f"{label}," + ",".join(cells))


def main() -> int:
    p = argparse.ArgumentParser(description=(
        "Side-by-side transcription comparison of two or more eval reports. "
        "Compares on MER / CER_zh / WER_en only — translation is out of scope "
        "per the project's optimisation target."
    ))
    p.add_argument("reports", nargs="*", type=Path,
                   help="report.json files (positional, first = baseline)")
    p.add_argument("--baseline", type=Path, default=None,
                   help="explicit baseline (overrides positional [0])")
    p.add_argument("--challenger", action="append", type=Path, default=None,
                   help="challenger(s); repeatable. With no positional reports, "
                        "use --baseline + --challenger(s).")
    p.add_argument("--no-pairwise", action="store_true")
    p.add_argument("--no-extremes", action="store_true")
    p.add_argument("--no-csv", action="store_true")
    p.add_argument("--extremes-k", type=int, default=5)
    args = p.parse_args()

    paths: List[Path] = []
    if args.reports:
        paths.append(args.reports[0] if args.baseline is None else args.baseline)
        if args.challenger:
            paths.extend(args.challenger)
        else:
            paths.extend(args.reports[1:])
    elif args.baseline and args.challenger:
        paths = [args.baseline] + args.challenger
    else:
        p.error("Provide reports as positional args (first = baseline), "
                "or use --baseline + --challenger [repeatable]")

    if not paths:
        p.error("No report paths given")
    if not all(p_.is_file() for p_ in paths):
        bad = [str(p_) for p_ in paths if not p_.is_file()]
        p.error(f"Missing report files: {bad}")

    reports = [(name_of(load(p), p), load(p)) for p in paths]
    print(f"\ncomparing {len(reports)} report(s):")
    for (n, r), p_ in zip(reports, paths):
        cfg = r.get("config") or {}
        print(f"  • {n:<32}  ({p_})  model={cfg.get('model','?')}")

    summaries = [(n, summary(r)) for n, r in reports]
    print_summary_table(reports, summaries)
    print_length_buckets(reports)
    if not args.no_pairwise:
        print_pairwise(reports)
    if not args.no_extremes:
        print_extremes(reports, k=args.extremes_k)
    if not args.no_csv:
        print_csv(reports, summaries)
    return 0


if __name__ == "__main__":
    sys.exit(main())
