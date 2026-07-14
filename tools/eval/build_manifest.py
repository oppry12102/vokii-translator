"""Convert a CS-Dialogue (BAAI) index into a Vokii eval manifest.

CS-Dialogue is an ASR dataset: its `text` file holds the *verbatim
code-switched transcription* of each utterance (mixed Chinese + English),
not a monolingual translation.

The Vokii pipeline (see qwen_client.py) is a real-time *interpreter*: for
each utterance it emits a full Mandarin translation (ZH:) and a full
English translation (EN:). eval.py scores `cer_zh = CER(ref_zh, ZH-output)`
and `wer_en = WER(ref_en, EN-output)`.

Those two things do not line up. CS-Dialogue ships no gold translations, so
there is nothing correct to put in ref_en at all, and a mixed transcript is
the wrong reference for a pure-Mandarin translation. Hence the --refs modes:

  none      (default) emit only id + audio. Scores latency / ttfb /
            rep_rate / max_delta_gap — the metrics that need no reference
            and are genuinely valid for this dataset.
  pure-zh   additionally set ref_zh = cleaned transcript ONLY for utterances
            that contain no English (a pure-Chinese utterance's "Mandarin
            translation" is ~= its transcript, so CER is meaningful there).
            Code-switched utterances stay reference-less.
  raw       dump the cleaned transcript into ref_zh for every line. NOT
            recommended — CER will be inflated on code-switched lines. Only
            for a crude "is it in the ballpark" glance.

Usage:
    python build_manifest.py \\
        --index-root ../../../cs_dialogue/cs_dialogue_data/datasets/BAAI--CS-Dialogue/snapshots/master/data/index \\
        --split dev --limit 30 --out manifest.cs_dialogue.jsonl

The audio (data/short_wav/*.tar.gz) must be extracted first; --audio-root
defaults to the `data/` dir that contains the index. Use --only-existing to
drop rows whose .wav isn't on disk yet.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Dict, List, Optional

TAG_RE = re.compile(r"<[^>]*>")          # <FIL/> <SPK/> <NON/> <NPS/> ...
WS_RE = re.compile(r"\s+")
LATIN_RE = re.compile(r"[A-Za-z]")


def clean_text(raw: str) -> str:
    """Strip CS-Dialogue markup tags and normalize whitespace."""
    s = TAG_RE.sub(" ", raw)
    s = WS_RE.sub(" ", s)
    return s.strip()


def has_english(s: str) -> bool:
    return bool(LATIN_RE.search(s))


def read_kv(path: Path) -> List[tuple[str, str]]:
    """Read a Kaldi-style '<id> <rest of line>' file, preserving order."""
    rows: List[tuple[str, str]] = []
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line.strip():
                continue
            parts = line.split(maxsplit=1)
            if len(parts) == 1:
                rows.append((parts[0], ""))
            else:
                rows.append((parts[0], parts[1]))
    return rows


def build(args: argparse.Namespace) -> int:
    index_root = Path(args.index_root).expanduser().resolve()
    split_dir = index_root / args.set / args.split
    text_path = split_dir / "text"
    scp_path = split_dir / "wav.scp"

    if args.set != "short_wav":
        print(f"ERROR: only 'short_wav' is supported (got {args.set!r}); "
              f"long_wav needs TextGrid segmentation, not handled here.",
              file=sys.stderr)
        return 2
    for p in (text_path, scp_path):
        if not p.is_file():
            print(f"ERROR: missing {p}", file=sys.stderr)
            return 2

    # audio_root defaults to the `data/` dir (parent of `index/`), which is
    # what wav.scp paths ('short_wav/WAVE/...') are relative to.
    audio_root = (Path(args.audio_root).expanduser().resolve()
                  if args.audio_root else index_root.parent)

    texts: Dict[str, str] = dict(read_kv(text_path))
    scp = read_kv(scp_path)  # ordered; drives output order

    emitted = 0
    n_missing_audio = 0
    n_no_text = 0
    n_ref_zh = 0
    out_lines: List[str] = []

    for utt_id, rel_wav in scp:
        if args.limit and emitted >= args.limit:
            break
        raw = texts.get(utt_id)
        if raw is None:
            n_no_text += 1
            continue
        cleaned = clean_text(raw)

        audio_abs = (audio_root / rel_wav).resolve()
        exists = audio_abs.is_file()
        if not exists:
            n_missing_audio += 1
            if args.only_existing:
                continue

        obj: Dict[str, object] = {"id": utt_id, "audio": str(audio_abs)}

        # Reference population per --task.
        #   transcribe: the verbatim mixed transcript IS the reference (ref).
        #   translate : CS-Dialogue has no gold translation. --refs controls
        #               a best-effort ref_zh (see module docstring); most rows
        #               stay reference-less.
        if args.task == "transcribe":
            obj["ref"] = cleaned
            if cleaned:
                n_ref_zh += 1
        else:
            if args.refs == "raw":
                obj["ref_zh"] = cleaned
                n_ref_zh += 1
            elif args.refs == "pure-zh" and cleaned and not has_english(cleaned):
                obj["ref_zh"] = cleaned
                n_ref_zh += 1
            # Carry the verbatim transcript for human inspection either way.
            obj["ref_transcript"] = cleaned

        out_lines.append(json.dumps(obj, ensure_ascii=False))
        emitted += 1

    out_path = Path(args.out).expanduser()
    out_path.write_text("\n".join(out_lines) + ("\n" if out_lines else ""),
                        encoding="utf-8")

    print(f"Wrote {emitted} sample(s) -> {out_path}")
    if args.task == "transcribe":
        print(f"  task             : transcribe  ({n_ref_zh} rows carry a ref)")
    else:
        print(f"  task             : translate  refs={args.refs}"
              + (f"  ({n_ref_zh} rows got ref_zh)" if args.refs != "none" else ""))
    print(f"  audio_root       : {audio_root}")
    if n_missing_audio:
        verb = "skipped" if args.only_existing else "kept (audio NOT on disk)"
        print(f"  ! {n_missing_audio} row(s) {verb} — extract the tar.gz "
              f"under {audio_root} first")
    if n_no_text:
        print(f"  ! {n_no_text} wav.scp id(s) had no matching transcript")
    if emitted == 0:
        print("  ! empty manifest — check --index-root / --audio-root", file=sys.stderr)
        return 1
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description="CS-Dialogue -> Vokii eval manifest")
    p.add_argument(
        "--index-root",
        default="../../../cs_dialogue/cs_dialogue_data/datasets/"
                "BAAI--CS-Dialogue/snapshots/master/data/index",
        help="path to the dataset's data/index dir")
    p.add_argument("--set", default="short_wav",
                   choices=["short_wav", "long_wav"],
                   help="only short_wav is supported")
    p.add_argument("--split", default="dev", choices=["dev", "test", "train"])
    p.add_argument("--task", default="transcribe",
                   choices=["transcribe", "translate"],
                   help="transcribe: write verbatim transcript as `ref` "
                        "(what the CS-Dialogue ASR eval scores). "
                        "translate: use --refs for a best-effort ref_zh.")
    p.add_argument("--audio-root", default=None,
                   help="dir the wav paths are relative to "
                        "(default: the data/ dir holding index/)")
    p.add_argument("--limit", type=int, default=30,
                   help="max samples to emit (0 = all)")
    p.add_argument("--refs", default="none",
                   choices=["none", "pure-zh", "raw"],
                   help="how to populate ref_zh (see module docstring)")
    p.add_argument("--only-existing", action="store_true",
                   help="drop rows whose .wav isn't on disk yet")
    p.add_argument("--out", default="manifest.cs_dialogue.jsonl")
    return build(p.parse_args())


if __name__ == "__main__":
    sys.exit(main())
