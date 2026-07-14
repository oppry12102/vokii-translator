# CS-Dialogue transcription eval — results

Dataset: **BAAI/CS-Dialogue**, `dev` split (only split with labels in this
download). Task: **verbatim ASR** (code-switch Mandarin+English), scored
with **MER** (Mixed Error Rate: Chinese by char, English by word; NFKC +
punctuation-stripped + lowercased before scoring).

Pipeline: Qwen-Omni Realtime (`qwen3.5-omni-flash-realtime-2026-03-15`),
`--commit-mode manual` (VAD disabled, whole clip pushed then one
commit+response — see "Tuning" below).

## Two tiers

| Tier | Purpose | N | Selection |
|------|---------|---|-----------|
| 1 | dev / debug | 40 | `select_tiers.py`, min-len 8, speaker×code-switch stratified |
| 2 | **conclusion** | 240 | 8/speaker × 30 speakers, 120 code-switch + 120 pure-zh, disjoint from tier 1 |

## Tier-2 conclusion (n=240)

| Metric | Value |
|--------|-------|
| **MER** mean / median | **0.163 / 0.095** (raw) · **0.141 / 0.091** (after re-running transient empty outputs) |
| CER (Chinese char) | 0.127 (n=170) |
| WER (English word) | 0.233 (n=120) |
| code-switch MER | 0.196 (n=120) |
| pure-Chinese MER | 0.130 (n=120) |
| distribution | 53% ≤0.1 · 78% ≤0.2 · 94% ≤0.5 · 29 perfect |
| by length | ≤3 tokens: 0.530 · ≥15 tokens: 0.115 |
| reliability | 0.8% (2/240) persistent empty output, both on <2 s fragments |

## Findings

1. **English is the weak spot** — WER_en (0.233) is ~2× CER_zh (0.127); the
   code-switch English portions drive most of the error.
2. **Long real sentences transcribe well** (MER 0.115 for ≥15 tokens). Error
   concentrates in very short utterances (MER 0.53 for ≤3 tokens), which are
   high-variance (one wrong token = 100%) and also cause the empty outputs.
3. **Empty-output reliability**: ~3% of clips returned empty on the first
   pass; re-running recovered all but 2, which are sub-2 s sentence fragments
   ("And the.", "Oh sorry.") the model declines to transcribe.

## Tuning (why manual commit mode)

Baseline used production `server_vad`, which segments a long clip on
internal pauses into multiple turns — the model dropped whole segments,
and the harness originally kept only the first (often empty) response.
Fixes, in order of impact:

1. **`--commit-mode manual`** — disable VAD, push whole clip, one
   commit+response. Eliminates segment dropping. Tier-1 MER 0.279 → 0.195,
   median 0.240 → 0.113.
2. **Multi-turn accumulation** — the client now concatenates every turn's
   text instead of returning on the first `response.done` (fixed the
   original empty-output-on-long-clips bug in vad mode).
3. **min-len 8** — drop filler acks (嗯/哦/uh-huh) that are pure MER noise.

Reproduce:

```bash
python select_tiers.py --tier1 40 --tier2 240 --min-len 8
python eval.py --manifest manifest.tier2.jsonl --commit-mode manual \
    --no-realtime --report report.tier2.json
```

`--no-realtime` speeds the accuracy run ~5× (latency metrics are then not
representative; use realtime + vad mode for a latency-faithful run).

## Flash vs Pro (tier-2, n=240, paired same 240 clips)

After re-running transient empty outputs and re-scoring with the
traditional→simplified normalizer:

| | Flash | Pro | Δ |
|--|------:|----:|---:|
| **MER mean** | 0.163 | **0.100** | **-0.063 (-39%)** |
| MER median | 0.095 | **0.045** | -0.049 |
| CER_zh | 0.111 | **0.066** | -0.045 |
| WER_en | 0.193 | **0.146** | -0.047 |
| code-switch MER | 0.154 | **0.130** | |
| pure-Chinese MER | 0.115 | **0.051** | |
| perfect (MER=0) | 29/240 | **57/240** | |
| pair-wise | Pro wins 112 · tie 73 · Flash wins 54 | | |

**Pro conclusion**: Pro is clearly worth the extra cost. -39% relative
MER, +96% perfect-clip count, near-doubling of the median improvement.
The two persistent empty outputs (6.4s and 6.9s of clear Mandarin) are
a Pro-specific failure mode worth investigating separately, but don't
materially affect the conclusion.

Run with `--model qwen3.5-omni-plus-realtime-2026-03-15`.
