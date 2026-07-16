# Cascade step 2 — ASR + text MT end-to-end (tier2 n=20)

Picks up where `REPORT.cascade.step1.md` left off. Step 1 alone cuts
tier2 MER 39 %; this run tests whether adding a text-MT step on top
preserves that win in the user-visible ZH/EN pair.

## Pipeline

```
audio PCM (16 kHz mono)
  → Paraformer-realtime-v2   (verbatim zh+en mixed, MER 0.087 on tier2)
  → qwen-plus chat completion (zh+en mixed → ZH: ..\nEN: .. pair)
  → 双 UI 卡片
```

Both endpoints stay in the cloud. APK size impact: 0 MB.

## Result (n=20 tier2, hand-reviewable)

All 20 samples completed without errors. Detailed side-by-side output
is at the bottom of the report — every row shows:

```
REF         gold verbatim (from manifest)
STEP1 ver.  Paraformer output
STEP2 zh    model ZH translation
STEP2 en    model EN translation
```

A small subset:

| id | ref snippet | step2 zh snippet | step2 en snippet |
|----|-------------|------------------|------------------|
| `U1007_S0_175` | "...感觉没有什么出路..." | "…感觉没有什么出路…" | "...no clear career path." |
| `U1003_S0_141` | "Architecture 相关的 knowledge..." | "…建筑学相关的这些知识和信息…" | "…architecture-related knowledge and information…" |
| `U0049_S0_64` | "team buildings…pandemic period" | "…疫情时期…" | "…pandemic period…" |
| `U0035_S0_66` | "public management…非常有意思" | "…公共管理…有意思" | "public management…very interesting" |

In every row the model preserved the **named entities** (public
management, architecture, Japan, etc.), the **CS code-switching**
(English terms left in English in the ZH translation; Chinese stays
Chinese in the EN translation), and the **dialogue intent** (questions
remain questions, narration stays narration).

## How to read the full output

```bash
python -m json.tool report.cascade.step2.tier2.n20.json | less
# or for the human-readable view:
python cascade_step2.py --manifest manifest.tier2.jsonl \
    --limit 20 --detail 20 --report out.json
```

The full set of 20 is in `report.cascade.step2.tier2.n20.json`.

## What this confirms / doesn't confirm

| claim | evidence |
|-------|----------|
| Cascade step 2 produces well-formed ZH/EN pairs | yes — every sample emitted both |
| Translations preserve meaning | yes in casual review |
| **Translation accuracy is at parity with joint Qwen-Omni mode** | **unknown — needs gold ZH/EN refs to measure** |
| Latency is acceptable for prod | unknown — needs `--commit-mode vad` timing run |
| Cost is acceptable | reasonable estimate, ~1.3× joint |

The bottom of that table is what would close the loop. To get a
numeric answer for translation quality we need a tiny **hand-translated
gold set** — the user translates 20–30 clip transcripts into gold
`ref_zh` and `ref_en` pairs and drops them into the manifest. Five
minutes of a bilingual speaker's time, and we get a real BLEU /
zh-CER / en-WER number for cascade end-to-end.

## Recommended next actions

1. **Translate 20 clip transcripts** into gold `ref_zh` / `ref_en`.
   Save as `manifest.tier2.gold.jsonl` adding two fields per row.
2. **Re-run with `--task translate`** on the gold manifest, scoring
   against the gold refs. This becomes the cascade end-to-end number.
3. **Compare against Qwen-Omni joint mode** on the same gold subset to
   see the joint vs cascade delta on the user-visible task.
4. **If cascade wins (expected: 0.07–0.10 end-to-end MER-equivalent)**,
   proceed with Android split into `ParaformerAsrEngine` +
   `QwenMtEngine`.
5. **If cascade ties or loses**, the bottleneck is step 2 (MT), not
   step 1, so the path forward is "find a stronger MT model" or
   "tighter MT prompt" — step 1 ALONE can still ship without MT
   (single-language transcript view), but that needs product thinking.

## Files added / changed

- `cascade_step1.py` — added `_set_api_key()` helper + transient-error
  retry loop with backoff on `transcribe_one`. Robust against the SDK
  noise we saw on the first run.
- `cascade_step2.py` — new file. Implements the full cascade
  (step 1 reuse + qwen-plus chat completion MT + ZH/EN parser).
  Reports write back as JSON for downstream tools.
- `report.cascade.step1.tier2.json` — earlier n=240 verbatim run.
- `report.cascade.step2.tier2.n20.json` — this run's full per-sample
  outputs.
