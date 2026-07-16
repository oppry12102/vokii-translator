"""tools/eval/cascade_step2.py

End-to-end cascade:
  audio PCM
    → Paraformer-V2 (ASR, verbatim zh/en mixed)
    → qwen-plus chat completion (text-in, ZH:/EN: pair out)

Step 1 alone cuts tier2 MER 39% relative (see REPORT.cascade.step1.md).
This driver adds step 2 so we can see the user-visible translation
quality end-to-end.

Eval challenge: tier1/tier2 manifests ship only ``ref`` (the verbatim
transcript), not gold ZH/EN translations. Without those we can't BLEU-
score the pair. So this driver **prints side-by-side transcripts** for
human review instead, and writes a JSON report with the raw pieces for
downstream analysis.

Output columns per row:
    ref              gold verbatim (from manifest)
    step1_verbatim   what Paraformer heard
    step2_zh         the ZH translation
    step2_en         the EN translation
    (judgement is the user's — does step2 preserve step1's meaning?

Usage:
    python cascade_step2.py --manifest manifest.tier2.jsonl \\
        --limit 20 --report report.cascade.step2.n20.json
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import List, Optional, Tuple

from cascade_step1 import _set_api_key, transcribe_one
from qwen_client import load_wav_as_pcm16_mono


# ---------------------------------------------------------------------
# Step 2: text-in translation via qwen-plus chat completion.
# ---------------------------------------------------------------------

# OpenAI-compatible endpoint exposed by DashScope.
CHAT_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

# System prompt tuned for code-switch translation (mirrors the prod
# interpreter prompt but tightened a touch for the no-audio case).
MT_SYSTEM = (
    "You are a real-time interpreter. The user gives you a mixed "
    "Mandarin-English utterance — keep every word in its original "
    "language and translate the rest. Output ONLY this exact two-line "
    "format and nothing else:\n"
    "ZH: <the Mandarin Chinese translation>\n"
    "EN: <the English translation>\n"
    "ZH line first, then EN line. No extra commentary, no markdown, "
    "no preamble, no apology. Output Simplified Chinese (简体) and "
    "ASCII English with Arabic digits."
)


def translate_text(text: str, api_key: str, model: str = "qwen-plus",
                   timeout_s: float = 30.0) -> Tuple[Optional[str], Optional[str], Optional[str]]:
    """Send verbatim text to qwen-plus; return (zh, en, raw).

    On failure, returns (None, None, error_message).
    """
    if not text or not text.strip():
        return None, None, "empty verbatim input"
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": MT_SYSTEM},
            {"role": "user",   "content": text.strip()},
        ],
        "max_tokens": 800,
        "temperature": 0.0,
    }
    req = urllib.request.Request(
        CHAT_URL, data=json.dumps(body).encode(),
        headers={"Authorization": f"Bearer {api_key}",
                 "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout_s) as r:
            d = json.loads(r.read())
            raw = d["choices"][0]["message"]["content"].strip()
    except urllib.error.HTTPError as e:
        return None, None, f"HTTP {e.code}: {e.read().decode()[:200]}"
    except Exception as e:
        return None, None, f"{type(e).__name__}: {e}"

    zh, en = parse_zh_en(raw)
    return zh, en, raw


# ---------------------------------------------------------------------
# Parser: split ``ZH: <zh>\nEN: <en>`` into a pair. Robust to minor
# label variants (full-width colon, swapped order).
# ---------------------------------------------------------------------

_LBL_RE = re.compile(r"\b(ZH|EN|中文|英文|英语)\s*[:：]\s*", re.IGNORECASE)


def parse_zh_en(raw: str) -> Tuple[Optional[str], Optional[str]]:
    """Extract the ZH and EN halves from a chat completion.

    Handles:
      - ``ZH: <text>\nEN: <text>``              (canonical, expected)
      - swapped order (EN first, ZH second)
      - full-width colons (ZH：...)
      - missing one half (returns empty string for that side)
    """
    if not raw:
        return None, None
    matches = list(_LBL_RE.finditer(raw))
    if len(matches) < 2:
        # Try splitting by line if ZH/EN lines are bare without labels.
        lines = [ln.strip() for ln in raw.split("\n") if ln.strip()]
        if len(lines) >= 2:
            return lines[0], lines[1]
        if len(lines) == 1:
            # heuristic: no labels, single line — peek Han density
            text = lines[0]
            han = sum(1 for c in text if 0x4E00 <= ord(c) <= 0x9FFF)
            if han > len(text) * 0.3:
                return text, ""
            return "", text
        return None, None

    zh: Optional[str] = None
    en: Optional[str] = None
    for i, m in enumerate(matches):
        label = m.group(1).upper()
        start = m.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(raw)
        chunk = raw[start:end].strip()
        if label in ("ZH", "中文", "CHINESE"):
            zh = chunk
        elif label in ("EN", "英文", "英语", "ENGLISH"):
            en = chunk
    return zh, en


# ---------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------

def run(args: argparse.Namespace) -> int:
    try:
        api_key = _set_api_key(args.api_key)
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
    console_rows: List[dict] = []      # for the human-readable output
    for i, item in enumerate(items, 1):
        sid = item["id"]
        ref = (item.get("ref") or item.get("ref_transcript") or "").strip()
        audio_path = Path(item["audio"])
        print(f"[{i}/{len(items)}] {sid} ... ", end="", flush=True)

        # Step 1.
        try:
            pcm = load_wav_as_pcm16_mono(str(audio_path))
        except Exception as e:
            print(f"audio load failed: {e}")
            per_sample.append({"id": sid, "error": f"audio load: {e}"})
            continue
        s1 = transcribe_one(pcm, args.asr_model, sid, timeout_s=args.asr_timeout)
        if s1.error:
            print(f"step1 err={s1.error[:50]}")
            per_sample.append({"id": sid, "step1_error": s1.error})
            continue

        # Step 2.
        zh, en, raw = translate_text(s1.transcript, api_key,
                                     model=args.mt_model,
                                     timeout_s=args.mt_timeout)
        if zh is None and en is None:
            print(f"step2 err={raw[:80] if raw else 'no output'}")
        else:
            print(f"step1 {len(s1.transcript)}c → step2 zh={len(zh or '')}c en={len(en or '')}c")

        per_sample.append({
            "id": sid,
            "ref": ref,
            "step1_verbatim": s1.transcript,
            "step1_elapsed_s": round(s1.elapsed_s, 3),
            "step2_zh": zh,
            "step2_en": en,
            "step2_raw": raw,
        })
        console_rows.append({
            "id": sid,
            "ref": ref,
            "step1": s1.transcript,
            "zh": zh or "",
            "en": en or "",
        })

    # Console: per-row brief + last 3 detailed samples at the end.
    print("\n=== summary table (first 8 rows; -v for all) ===")
    print(f'  {"id":<22}  {"step1(len)":>10}  {"zh(len)":>8}  {"en(len)":>8}  err')
    for s in per_sample[:8]:
        sid = s.get("id", "?")
        e = s.get("step1_error") or s.get("error") or ""
        zh = s.get("step2_zh") or ""
        en = s.get("step2_en") or ""
        v1 = s.get("step1_verbatim") or ""
        flag = "ERR" if e or not v1.strip() else "ok"
        print(f'  {sid:<22}  {len(v1):>10}  {len(zh):>8}  {len(en):>8}  {flag}')

    # Detailed view of N samples (default 3) for human review.
    print(f"\n=== DETAIL: {args.detail} samples for human review ===")
    for r in console_rows[:args.detail]:
        print(f"\n----- {r['id']} -----")
        print(f"  REF        : {r['ref']}")
        print(f"  STEP1 ver. : {r['step1']}")
        print(f"  STEP2 zh   : {r['zh']}")
        print(f"  STEP2 en   : {r['en']}")

    if args.report:
        out = {
            "config": {
                "asr_model": args.asr_model, "mt_model": args.mt_model,
                "manifest": str(manifest), "n_items": len(items),
            },
            "samples": per_sample,
            "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        }
        Path(args.report).write_text(json.dumps(out, ensure_ascii=False, indent=2),
                                     encoding="utf-8")
        print(f"\nReport written: {args.report}")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description=(
        "Cascade step 2 — Paraformer-V2 (ASR) + qwen-plus (text-MT) end-to-end."
    ))
    p.add_argument("--manifest", required=True)
    p.add_argument("--api-key", help="DashScope API key (or DASHSCOPE_API_KEY env)")
    p.add_argument("--asr-model", default="paraformer-realtime-v2",
                   help="step 1: streaming ASR model")
    p.add_argument("--mt-model", default="qwen-plus",
                   help="step 2: text translation model")
    p.add_argument("--limit", type=int, default=0)
    p.add_argument("--detail", type=int, default=3,
                   help="print this many detailed side-by-side samples")
    p.add_argument("--asr-timeout", type=float, default=30.0)
    p.add_argument("--mt-timeout", type=float, default=30.0)
    p.add_argument("--report", default="report.cascade.step2.json")
    args = p.parse_args()
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
