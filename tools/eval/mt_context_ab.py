"""tools/eval/mt_context_ab.py — does CONVERSATION HISTORY in the MT prompt
actually improve translation? A/B on CS-Dialogue.

The experimental app feature (v2.5.2+, Settings toggle, default OFF)
injects the last 6 committed turns (corrected source + committed
translation, oldest first) into the MT system prompt. CS-Dialogue has no
gold translations, so this driver scores with:

  1. **Verbatim-line fidelity** (objective, catches the main RISK): per
     CORE PRINCIPLE, line 1 of the output must be the verbatim source.
     History could tempt the model into "correcting" line 1 with context.
     We edit-distance line 1 against the actual input source per arm.
  2. **Format validity** (objective): both ZH:/EN: labels present.
  3. **Blind LLM-judge** (primary quality signal): qwen-plus compares A/B
     outputs per utterance with gold ref + preceding gold turns as judge
     context; randomized positions (md5-seeded), win/tie/loss + reason.

Fidelity notes:
  - Source text = fun-asr-realtime hyps from the n=6186 full report
    (report.cs_dialogue.full.funasr.json) — production fidelity, MT sees
    what ASR actually heard. No new ASR calls.
  - Arm B threads ITS OWN outputs as history (source = funasr hyp,
    translation = arm B's own committed target line), exactly like the
    app's recordUtterance → SessionContext path. Sessions run
    sequentially internally, in parallel across sessions.
  - Prompt = PROD_SYSTEM_PROMPT from cascade_latency.py (byte-port of
    MtPromptBuilder) +, for arm B, the history section text ported
    byte-exact from SessionContext.java.

Usage:
    export DASHSCOPE_API_KEY=sk-...
    python mt_context_ab.py --sessions 8 --turns 30 --workers 8 \
        --report report.mt_context_ab.json
    python mt_context_ab.py --judge-only --report report.mt_context_ab.json
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import List, Optional

from cascade_latency import PROD_CHAT_URL, PROD_SYSTEM_PROMPT
from cascade_step1 import _set_api_key
from metrics import edit_distance, normalize_asr, tokenize_mixed
from qwen_client import parse_bilingual, _han

FULL_MANIFEST = "manifest.cs_dialogue.full.jsonl"
FUNASR_REPORT = "report.cs_dialogue.full.funasr.json"
MT_MODEL = "qwen-turbo"
MT_TEMPERATURE = 0.3
JUDGE_MODEL = "qwen-plus"
MAX_HISTORY_TURNS = 6          # SessionContext.MAX_HISTORY_TURNS


# ---------------------------------------------------------------------
# History section — byte-exact port of SessionContext.buildPromptSection's
# mtHistoryContext branch (SessionContext.java, added 2026-07-29).
# ---------------------------------------------------------------------

def history_section(pairs: List[tuple]) -> str:
    sb = []
    sb.append("\n\nCONVERSATION HISTORY (reference only)\n")
    sb.append("=====================================\n")
    sb.append("Recent committed turns, oldest first. Use for terminology, "
              "names and register consistency ONLY — translate ONLY the new "
              "input that follows; never re-translate or answer the history.\n")
    for i, (src, tgt) in enumerate(pairs, 1):
        sb.append(f'  {i}. "{src}" → "{tgt}"\n')
    return "".join(sb)


# ---------------------------------------------------------------------
# MT call (non-streaming — quality eval, latency irrelevant)
# ---------------------------------------------------------------------

def chat(system: str, user: str, model: str, api_key: str,
         temperature: float = MT_TEMPERATURE, timeout: float = 60.0) -> str:
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "stream": False,
        "temperature": temperature,
    }
    req = urllib.request.Request(
        PROD_CHAT_URL, data=json.dumps(body).encode(),
        headers={"Authorization": f"Bearer {api_key}",
                 "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        o = json.loads(r.read().decode())
    return (o["choices"][0]["message"]["content"] or "").strip()


def target_of(src: str, zh: str, en: str) -> str:
    """The OTHER-language line, mirroring how the app commits Turn.target:
    source mostly-Han → target is the EN line, otherwise the ZH line."""
    letters = len(re.findall(r"[A-Za-z]", src))
    return en if _han(src) >= letters else zh


# ---------------------------------------------------------------------
# Data loading
# ---------------------------------------------------------------------

def load_sessions(sessions_n: int, turns_n: int) -> List[dict]:
    """Pick evenly-spaced sessions; within each, the first turns_n turns
    (manifest order = dataset order = chronological). Source text comes
    from the fun-asr full-set report (skip turns it errored/emptied)."""
    refs, order = {}, {}
    with open(FULL_MANIFEST, encoding="utf-8") as f:
        for idx, line in enumerate(f):
            d = json.loads(line)
            refs[d["id"]] = d.get("ref") or ""
            order[d["id"]] = idx
    hyps = {}
    for s in json.load(open(FUNASR_REPORT))["samples"]:
        if not s.get("error") and (s.get("hyp") or "").strip():
            hyps[s["id"]] = s["hyp"]

    by_session = {}
    for sid in sorted(hyps, key=lambda i: order.get(i, 1 << 30)):
        key = sid.rsplit("_", 1)[0]
        by_session.setdefault(key, []).append(sid)
    keys = sorted(by_session)
    if sessions_n < len(keys):
        step = len(keys) / sessions_n
        keys = [keys[int(i * step)] for i in range(sessions_n)]
    out = []
    for k in keys:
        turns = [{"id": sid, "ref": refs.get(sid, ""), "src": hyps[sid]}
                 for sid in by_session[k][:turns_n]]
        out.append({"session": k, "turns": turns})
    return out


# ---------------------------------------------------------------------
# Generation
# ---------------------------------------------------------------------

def run_session(sess: dict, api_key: str, retries: int = 1) -> List[dict]:
    """Translate every turn in both arms, threading arm B's own outputs
    as history (source = funasr hyp, target = arm B's target line)."""
    results = []
    hist_b: List[tuple] = []
    for t in sess["turns"]:
        row = {"id": t["id"], "session": sess["session"],
               "ref": t["ref"], "src": t["src"], "hist_len": len(hist_b)}
        for attempt in range(retries + 1):
            try:
                row["mt_a"] = chat(PROD_SYSTEM_PROMPT, t["src"], MT_MODEL, api_key)
                sys_b = (PROD_SYSTEM_PROMPT + history_section(hist_b)
                         if hist_b else PROD_SYSTEM_PROMPT)
                row["mt_b"] = chat(sys_b, t["src"], MT_MODEL, api_key)
                row["error"] = None
                break
            except Exception as e:
                row["error"] = f"{type(e).__name__}: {e}"
                time.sleep(1.0 + attempt)
        # Thread arm B's committed turn into its own history (even on a
        # parse-miss — app commits whatever the final MT produced).
        if not row["error"]:
            zh_b, en_b = parse_bilingual(row["mt_b"])
            tgt_b = target_of(t["src"], zh_b, en_b)
            hist_b.append((t["src"], tgt_b))
            if len(hist_b) > MAX_HISTORY_TURNS:
                hist_b.pop(0)
        results.append(row)
    return results


def objective_metrics(rows: List[dict]) -> dict:
    """Verbatim-line fidelity + format validity per arm."""
    out = {}
    for arm in ("a", "b"):
        n = ok_fmt = 0
        fid_sum = 0.0
        for r in rows:
            mt = r.get("mt_" + arm)
            if r.get("error") or not mt:
                continue
            zh, en = parse_bilingual(mt)
            ok_fmt += 1 if (zh and en) else 0
            # line 1 = the label matching the SOURCE language
            line1 = zh if _han(r["src"]) >= len(re.findall(r"[A-Za-z]", r["src"])) else en
            rt = tokenize_mixed(normalize_asr(r["src"]))
            ht = tokenize_mixed(normalize_asr(line1))
            fid_sum += 1.0 - (edit_distance(rt, ht) / len(rt) if rt else (0.0 if not ht else 1.0))
            n += 1
        out[arm] = {"n": n,
                    "format_ok": ok_fmt,
                    "verbatim_fidelity": round(fid_sum / n, 4) if n else None}
    return out


# ---------------------------------------------------------------------
# Blind LLM judge
# ---------------------------------------------------------------------

JUDGE_PROMPT = """You are grading two translations of the SAME utterance from a Mandarin-English code-switch conversation. The utterance was transcribed by ASR (may contain recognition errors, which BOTH translations share — ignore those).

Context — the previous turns (gold transcripts):
{context}

Current utterance:
- ASR transcript (what both translations heard): {hyp}
- Gold transcript (what was actually said): {ref}

Translation 1:
{ta}

Translation 2:
{tb}

Each translation has two lines: a verbatim-source line and a free-translation line. Judge ONLY the free-translation line (the one in the OTHER language than the source). Score on: (1) meaning accuracy vs the ASR transcript, (2) use of the conversation context — coreference, terminology and register consistency with the previous turns, (3) fluency. The verbatim-source line is NOT being judged.

Answer with EXACTLY three lines:
WINNER: 1 | 2 | TIE
DIM: comma-separated subset of [accuracy, context, fluency] where the winner was better (empty if TIE)
REASON: one short sentence"""


def judge_one(row: dict, prev_golds: List[str], api_key: str) -> dict:
    """Blind pairwise: randomize 1/2 position per sample (md5 of id)."""
    flip = int(hashlib.md5(row["id"].encode()).hexdigest(), 16) % 2 == 1
    ta, tb = (row["mt_b"], row["mt_a"]) if flip else (row["mt_a"], row["mt_b"])
    prompt = (JUDGE_PROMPT
              .replace("{context}", "\n".join(f"  - {g}" for g in prev_golds) or "  (none)")
              .replace("{hyp}", row["src"])
              .replace("{ref}", row["ref"])
              .replace("{ta}", ta).replace("{tb}", tb))
    for attempt in range(2):
        try:
            out = chat("You are a meticulous translation grader.",
                       prompt, JUDGE_MODEL, api_key, temperature=0.0)
            m = re.search(r"WINNER:[ \t]*(\d|TIE)", out)
            d = re.search(r"DIM:[ \t]*(.*)", out)
            rs = re.search(r"REASON:[ \t]*(.*)", out)
            if not m:
                raise ValueError("unparseable judge output: " + out[:80])
            w = m.group(1)
            if w == "TIE":
                winner = "tie"
            else:
                winner_num = int(w)
                # map back through the flip
                winner = ("b" if winner_num == 1 else "a") if flip else ("a" if winner_num == 1 else "b")
            return {"id": row["id"], "winner": winner,
                    "dim": (d.group(1).strip() if d else ""),
                    "reason": (rs.group(1).strip() if rs else "")}
        except Exception as e:
            if attempt == 1:
                return {"id": row["id"], "winner": "judge_error", "dim": "",
                        "reason": f"{type(e).__name__}: {e}"[:120]}
            time.sleep(1.0)


def run_judge(report: dict, api_key: str, workers: int) -> dict:
    rows = [r for r in report["samples"] if not r.get("error")]
    # only turns with >=2 history turns — early turns can't benefit
    rows = [r for r in rows if r["hist_len"] >= 2]
    # judge needs preceding gold refs as context: rebuild per session
    by_sess = {}
    for r in rows:
        by_sess.setdefault(r["session"], []).append(r)
    prev_map = {}
    for sess_rows in by_sess.values():
        golds: List[str] = []
        for r in sess_rows:
            prev_map[r["id"]] = golds[-3:]
            golds.append(r["ref"])
    verdicts = []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futs = {pool.submit(judge_one, r, prev_map[r["id"]], api_key): r["id"]
                for r in rows}
        for f in as_completed(futs):
            verdicts.append(f.result())
    wins = sum(1 for v in verdicts if v["winner"] == "b")
    ties = sum(1 for v in verdicts if v["winner"] == "tie")
    losses = sum(1 for v in verdicts if v["winner"] == "a")
    errs = sum(1 for v in verdicts if v["winner"] == "judge_error")
    return {"n": len(verdicts), "context_wins": wins, "ties": ties,
            "context_losses": losses, "judge_errors": errs,
            "verdicts": sorted(verdicts, key=lambda v: v["id"])}


# ---------------------------------------------------------------------

def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--sessions", type=int, default=8)
    p.add_argument("--turns", type=int, default=30)
    p.add_argument("--workers", type=int, default=8)
    p.add_argument("--api-key")
    p.add_argument("--report", default="report.mt_context_ab.json")
    p.add_argument("--judge-only", action="store_true",
                   help="skip generation; re-run only the judge on --report")
    args = p.parse_args()
    api_key = _set_api_key(args.api_key)

    if args.judge_only:
        report = json.load(open(args.report))
    else:
        sessions = load_sessions(args.sessions, args.turns)
        total = sum(len(s["turns"]) for s in sessions)
        print(f"{len(sessions)} sessions, {total} turns "
              f"(hist cap {MAX_HISTORY_TURNS})", file=sys.stderr)
        samples: List[dict] = []
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            futs = {pool.submit(run_session, s, api_key): s["session"]
                    for s in sessions}
            for f in as_completed(futs):
                rows = f.result()
                samples.extend(rows)
                errs = sum(1 for r in rows if r.get("error"))
                print(f"  session {futs[f]}: {len(rows)} turns, {errs} errors",
                      file=sys.stderr, flush=True)
        report = {"config": {"sessions": args.sessions, "turns": args.turns,
                             "mt_model": MT_MODEL, "judge_model": JUDGE_MODEL,
                             "hist_cap": MAX_HISTORY_TURNS,
                             "source": "fun-asr-realtime n=6186 hyps"},
                  "samples": sorted(samples, key=lambda r: r["id"])}
        report["objective"] = objective_metrics(report["samples"])
        print("objective:", json.dumps(report["objective"], ensure_ascii=False),
              file=sys.stderr)

    report["judge"] = run_judge(report, api_key, args.workers)
    j = report["judge"]
    print(f"\nJUDGE (n={j['n']}): context wins {j['context_wins']} | "
          f"ties {j['ties']} | losses {j['context_losses']} | "
          f"judge_errors {j['judge_errors']}")
    Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2),
                                 encoding="utf-8")
    print(f"Report written: {args.report}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
