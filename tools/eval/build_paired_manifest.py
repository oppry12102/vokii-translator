"""tools/eval/build_paired_manifest.py

Build a paired manifest for ``audio_diff.py`` by matching user-recorded
prod audio against the existing eval manifest.

Typical workflow:

  1. Pick a quiet room.
  2. On Phone A, play the recommended clips in order (see --print-playlist).
     Use ~3 seconds of silence between clips so the recorder sees a clear
     gap — Vokii's downstream resegmentation will tolerate up to ~5 s gaps
     between samples.
  3. On Phone B, record at 44.1 kHz or 48 kHz, mono if possible (the
     eval pipeline downmixes anyway). Use the same filenames as the clip
     ids: ``ZH-CN_U0023_S0_91.wav``, ``ZH-CN_U0024_S0_142.wav``, etc.
  4. Run::

         python build_paired_manifest.py \\
             --source-manifest manifest.tier1.jsonl \\
             --prod-dir ./recordings \\
             --out manifest.tier1.paired.jsonl

  5. Run::

         python audio_diff.py --manifest manifest.tier1.paired.jsonl \\
             --instructions v1 --report report.diff.paired.json

The resulting ``ΔMER`` is the production-induced drift you actually care
about — independent of any synthetic noise model.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import List, Optional


# Audio file extensions to try when matching recordings. WAV is the
# preferred target; if the recorder only saves compressed, we accept the
# others and rely on soundfile to decode them.
_AUDIO_EXTS = (".wav", ".m4a", ".ogg", ".flac", ".mp3", ".WAV", ".M4A")


def find_prod(prod_dir: Path, stem: str) -> Optional[Path]:
    """Return the recorded file matching ``stem`` in ``prod_dir``, or None."""
    for ext in _AUDIO_EXTS:
        p = prod_dir / f"{stem}{ext}"
        if p.is_file():
            return p
    return None


def print_playlist(rows: List[dict]) -> None:
    """Print a clipboard-friendly playback list. Spacing is implicit — just
    play each clip, wait ~3s, play the next."""
    print(f"# {len(rows)} clips to record. Play each, then wait ~3s.\n")
    for i, r in enumerate(rows, 1):
        print(f"{i:3d}.  {r['id']:<22}  ref='{r['ref'][:60]}'")
        print(f"     file: {Path(r['audio']).name}")


def main() -> int:
    # Print ref (Chinese) safely on a Windows cp1252/gbk console; no-op on
    # Linux/macOS where stdout is already utf-8.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    p = argparse.ArgumentParser(description=(
        "Build a paired-manifest for audio_diff from your recorded prod audio."
    ))
    p.add_argument("--source-manifest", required=True,
                   help="the clean-side manifest (e.g. manifest.tier1.jsonl)")
    p.add_argument("--prod-dir", type=Path, required=True,
                   help="directory containing user-recorded prod wavs")
    p.add_argument("--limit", type=int, default=0,
                   help="only take the first N rows of the source manifest (0 = all)")
    p.add_argument("--out", type=Path, default=None,
                   help="output paired manifest path (JSONL); "
                        "not required with --print-playlist")
    p.add_argument("--print-playlist", action="store_true",
                   help="print a playlist of the clips to record and exit")
    p.add_argument("--strict", action="store_true",
                   help="fail if any row has no matching prod recording")
    args = p.parse_args()

    src = Path(args.source_manifest)
    if not src.is_file():
        print(f"ERROR: source manifest not found: {src}", file=sys.stderr)
        return 2

    rows: List[dict] = []
    with src.open("r", encoding="utf-8") as f:
        for ln in f:
            ln = ln.strip()
            if not ln or ln.startswith("#"):
                continue
            rows.append(json.loads(ln))

    if args.limit and args.limit > 0:
        rows = rows[: args.limit]

    if args.print_playlist:
        print_playlist(rows)
        return 0

    if args.out is None:
        print("ERROR: --out is required (only optional with --print-playlist)",
              file=sys.stderr)
        return 2

    if not args.prod_dir.is_dir():
        print(f"ERROR: prod-dir not found: {args.prod_dir}", file=sys.stderr)
        return 2

    matched, missing = 0, []
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8") as out:
        for r in rows:
            sid = r["id"]
            clean = r["audio"]
            ref = r.get("ref") or r.get("ref_transcript") or ""
            prod = find_prod(args.prod_dir, sid)
            if prod is None:
                missing.append(sid)
                if args.strict:
                    print(f"ERROR: no recording for {sid} in {args.prod_dir}",
                          file=sys.stderr)
                    return 2
                continue
            obj = {
                "id": sid,
                "ref": ref,
                "clean_audio": str(Path(clean).resolve()),
                "prod_audio": str(prod.resolve()),
            }
            out.write(json.dumps(obj, ensure_ascii=False) + "\n")
            matched += 1

    print(f"\nWrote {matched} paired rows to {args.out}", file=sys.stderr)
    if missing:
        print(f"Missing {len(missing)} recording(s) in {args.prod_dir}:",
              file=sys.stderr)
        for sid in missing[:10]:
            print(f"  {sid}", file=sys.stderr)
        if len(missing) > 10:
            print(f"  ... and {len(missing) - 10} more", file=sys.stderr)
    return 0 if matched > 0 else 2


if __name__ == "__main__":
    sys.exit(main())
