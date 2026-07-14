"""Select a two-tier evaluation sample from the CS-Dialogue dev split.

Tier 1 (dev/debug): small, fast, for iterating on prompt / VAD / parsing.
Tier 2 (conclusion): larger, stratified, for the reported number.

Both tiers are:
  * drawn only from utterances whose audio is on disk,
  * filtered to >= --min-len characters (drops '嗯/哦/对' filler acks that
    are low-signal for ASR),
  * stratified by speaker x code-switch (Chinese-only vs mixed) so no
    single voice or language mode dominates,
  * DISJOINT from each other — tier 2 never reuses a tier-1 utterance, so
    tuning on tier 1 doesn't inflate the tier-2 conclusion,
  * deterministic — ordering is by md5(utt_id), so re-running yields the
    same sample (no RNG seed to remember).

Only the dev split has labels in this download (no test/train index), so
the tier-2 "conclusion" is on dev. Note that in the report.

Usage:
    python select_tiers.py \\
        --index-root /path/to/.../data/index \\
        --tier1 40 --tier2 240 \\
        --out-dir .
Writes manifest.tier1.jsonl and manifest.tier2.jsonl (transcribe schema:
{id, audio, ref}).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, Tuple

from build_manifest import clean_text, has_english, read_kv


def _rank(utt_id: str) -> str:
    """Stable per-utterance sort key — deterministic, uniform-ish."""
    return hashlib.md5(utt_id.encode("utf-8")).hexdigest()


def speaker_of(utt_id: str) -> str:
    # ZH-CN_U0023_S0_2 -> U0023
    parts = utt_id.split("_")
    return parts[1] if len(parts) > 1 else utt_id


def round_robin_pick(buckets: Dict[str, List[dict]], quota: int,
                     used: set) -> List[dict]:
    """Pick up to `quota` items, cycling speakers so coverage is even.
    Each bucket is a per-speaker list already sorted by rank; we pop from
    the front. Skips ids already in `used` and adds picks to it."""
    picked: List[dict] = []
    speakers = sorted(buckets.keys())
    cursors = {s: 0 for s in speakers}
    progressed = True
    while len(picked) < quota and progressed:
        progressed = False
        for s in speakers:
            if len(picked) >= quota:
                break
            lst = buckets[s]
            i = cursors[s]
            while i < len(lst) and lst[i]["id"] in used:
                i += 1
            if i < len(lst):
                item = lst[i]
                picked.append(item)
                used.add(item["id"])
                cursors[s] = i + 1
                progressed = True
            else:
                cursors[s] = i
    return picked


def build_pool(args: argparse.Namespace) -> Tuple[List[dict], List[dict]]:
    index_root = Path(args.index_root).expanduser().resolve()
    split_dir = index_root / "short_wav" / args.split
    audio_root = (Path(args.audio_root).expanduser().resolve()
                  if args.audio_root else index_root.parent)

    texts = dict(read_kv(split_dir / "text"))
    scp = read_kv(split_dir / "wav.scp")

    cs: List[dict] = []   # code-switch (has English)
    zh: List[dict] = []   # pure Chinese
    n_missing = n_short = 0

    for utt_id, rel_wav in scp:
        raw = texts.get(utt_id)
        if raw is None:
            continue
        cleaned = clean_text(raw)
        if len(cleaned) < args.min_len:
            n_short += 1
            continue
        audio_abs = (audio_root / rel_wav).resolve()
        if not audio_abs.is_file():
            n_missing += 1
            continue
        item = {"id": utt_id, "audio": str(audio_abs), "ref": cleaned,
                "_spk": speaker_of(utt_id), "_rank": _rank(utt_id)}
        (cs if has_english(cleaned) else zh).append(item)

    if n_missing:
        print(f"  ! {n_missing} utt(s) skipped — audio not on disk", file=sys.stderr)
    if n_short:
        print(f"  (filtered {n_short} utt(s) shorter than {args.min_len} chars)")
    return cs, zh


def by_speaker(items: List[dict]) -> Dict[str, List[dict]]:
    d: Dict[str, List[dict]] = defaultdict(list)
    for it in sorted(items, key=lambda x: x["_rank"]):
        d[it["_spk"]].append(it)
    return d


def pick_tier(cs_buckets, zh_buckets, total: int, used: set) -> List[dict]:
    """Half code-switch, half pure-Chinese, speaker-spread, disjoint via
    `used`. If one side runs dry, the other backfills."""
    half = total // 2
    picks = round_robin_pick(cs_buckets, half, used)
    picks += round_robin_pick(zh_buckets, total - len(picks), used)
    # Backfill from code-switch if pure-Chinese ran short (or vice-versa).
    if len(picks) < total:
        picks += round_robin_pick(cs_buckets, total - len(picks), used)
    return picks


def write_manifest(path: Path, items: List[dict]) -> None:
    lines = [json.dumps({k: it[k] for k in ("id", "audio", "ref")},
                        ensure_ascii=False) for it in items]
    path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")


def summarize(name: str, items: List[dict]) -> None:
    n = len(items)
    if not n:
        print(f"{name}: EMPTY"); return
    cs = sum(1 for it in items if has_english(it["ref"]))
    spks = sorted({it["_spk"] for it in items})
    lens = sorted(len(it["ref"]) for it in items)
    print(f"{name}: {n} utt  |  {cs} code-switch / {n - cs} pure-zh  |  "
          f"{len(spks)} speakers  |  ref chars median={lens[n // 2]} "
          f"(min={lens[0]} max={lens[-1]})")


def main() -> int:
    p = argparse.ArgumentParser(description="CS-Dialogue two-tier eval selector")
    p.add_argument(
        "--index-root",
        default="/home/oppry/cs_dialogue/cs_dialogue_data/datasets/"
                "BAAI--CS-Dialogue/snapshots/master/data/index")
    p.add_argument("--split", default="dev", choices=["dev", "test", "train"])
    p.add_argument("--audio-root", default=None)
    p.add_argument("--tier1", type=int, default=40, help="tier-1 (debug) size")
    p.add_argument("--tier2", type=int, default=240, help="tier-2 (conclusion) size")
    p.add_argument("--min-len", type=int, default=5,
                   help="drop utterances shorter than this many chars")
    p.add_argument("--out-dir", default=".")
    args = p.parse_args()

    cs, zh = build_pool(args)
    print(f"Pool: {len(cs)} code-switch + {len(zh)} pure-zh "
          f"= {len(cs) + len(zh)} eligible utt(s)")

    cs_b, zh_b = by_speaker(cs), by_speaker(zh)
    used: set = set()
    tier1 = pick_tier(cs_b, zh_b, args.tier1, used)   # carved out first
    tier2 = pick_tier(cs_b, zh_b, args.tier2, used)   # disjoint from tier1

    out = Path(args.out_dir).expanduser()
    write_manifest(out / "manifest.tier1.jsonl", tier1)
    write_manifest(out / "manifest.tier2.jsonl", tier2)

    print()
    summarize("Tier 1 (debug)     ", tier1)
    summarize("Tier 2 (conclusion)", tier2)
    overlap = {it["id"] for it in tier1} & {it["id"] for it in tier2}
    print(f"\nOverlap tier1∩tier2: {len(overlap)} (must be 0)")
    print(f"Wrote {out / 'manifest.tier1.jsonl'} and {out / 'manifest.tier2.jsonl'}")
    return 0 if not overlap else 1


if __name__ == "__main__":
    sys.exit(main())
