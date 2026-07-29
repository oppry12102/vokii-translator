# MT history-context A/B — CS-Dialogue (8 sessions × 30 turns)

**Date**: 2026-07-29
**Feature under test**: experimental "History context" toggle (commit
`4aba848`) — last 6 committed turns (bilingual) injected as
`CONVERSATION HISTORY` into the MT system prompt.
**Driver**: `mt_context_ab.py`. Both arms qwen-turbo, temp 0.3,
production `MtPromptBuilder` prompt (byte-port). Source text = existing
fun-asr-realtime hyps (production fidelity; no new ASR calls). Arm B
threads **its own** outputs as history, exactly like the app's
`recordUtterance` path. 240 turns, 0 generation errors.

CS-Dialogue has no gold translations, so quality is scored by a blind
LLM judge (qwen-plus, temp 0, md5-seeded position randomization, judge
sees gold refs + previous gold turns) plus two objective metrics.

## Headline

| Metric | Arm A (baseline) | Arm B (history) |
|--------|------------------|-----------------|
| Verbatim-line fidelity (line 1 = source) | **0.9846** | 0.9807 |
| Format OK (ZH:/EN: both present) | **240/240** | 239/240 |
| Judge verdicts (n=224, hist≥2 turns) | **92 wins / 51 ties / 81 losses** for B | |

Raw judge split (92:81) is within coin-flip range — **but the judge is
forced to prefer even when outputs are identical**, so the meaningful cut
stratifies by how much the two arms' target lines actually differ:

| Target-line diff (normalized edit dist) | n | B wins | ties | A wins |
|------------------------------------------|---|--------|------|--------|
| identical (0) | 64 | 9 | **51** | 4 |
| small (<0.15, mostly temp jitter) | 60 | 25 | 0 | 35 |
| **material (≥0.15)** | **100** | **58** | 0 | **42** |

## Interpretation

1. **Where history changed the output, it helped**: 58:42 (+16 net) on
   the 100 materially-different turns (one-sided p≈0.05–0.1 — suggestive,
   not ironclad at this n). Judge noise floor calibrated at ~20% forced
   preferences on identical texts (9:4, roughly symmetric), so the
   material-bucket edge stands above noise.
2. **29% of turns are unaffected** (identical outputs — short/simple
   utterances where context has nothing to add); 27% differ only by
   temperature-0.3 sampling jitter (25:35 ≈ coin flip, as expected).
3. **Cost side is small but real**: verbatim-line fidelity −0.4 pt
   (0.9807 vs 0.9846 — history occasionally nudges line 1 off the
   verbatim source, the exact risk this metric was built to catch),
   1/240 format miss, +~400 prompt tokens per MT call (~10–20 ms prefill).

## Verdict

**Modest positive, no meaningful harm — keep the feature experimental
with default OFF (as shipped).** The benefit concentrates in turns where
context actually changes the translation (coreference/terminology-rich
dialogue); simple utterances are untouched and the verbatim-fidelity
cost is 0.4 pt. Promotion to default-ON would want a bigger-n judge run
(500+ material-diff turns) plus a verbatim-fidelity guard in the prompt
(e.g. repeating "line 1 = EXACTLY the new input" after the history
section).

---

# Full-scale follow-up (same day): all 30 sessions, 6,186 turns

The 240-turn window understated the verbatim-line risk. Full-scale runs
(generation ×2 + judge ×2, ~25k API calls, 0 generation errors):

| | A (baseline) | B (history) | B+guard |
|---|---|---|---|
| **line-1 vs GOLD MER** (contamination) | 0.144 | **0.226** | **0.150** |
| verbatim-line fidelity (vs ASR input) | 0.960 | 0.875 | 0.948 |
| format misses (of 6,186) | 4 | 28 | 60 |
| judge material-bucket net (B−A) | — | **+99 / 2,820** (p≈0.06) | +43 / 2,730 (p≈0.4) |
| judge identical-bucket forced prefs | — | +57 B-skew | +53 B-skew |

Notes:
- **Contamination is real and big at scale**: with history, the model
  drags line 1 off the ASR input toward its own prior phrasing — and NOT
  toward the gold transcript (line-1 vs gold worsens 0.144 → 0.226;
  closer-to-gold only 127 turns vs 179 farther). History does not fix
  ASR errors; it corrupts the source echo.
- Baseline surprise: even WITHOUT history, MT line 1 is worse than the
  raw ASR hyp (0.144 vs 0.112 gold-MER) — the model "cleans up" despite
  CORE PRINCIPLE. The app locks the verbatim column to MT line 1 at
  commit (MainActivity.java:667-677), so this drift already ships.
- **The guard prompt fixes contamination** (0.226 → 0.150 ≈ baseline)
  **but spends the translation-side benefit** (+99 → +43, back inside
  noise). The two are coupled: part of what made context "win" was the
  harmonization freedom that also corrupts line 1.
- Judge artifact: identical-text pairs get ~17% forced preferences with
  a consistent +53..+57 B-skew (flip-mapping artifact), visible in both
  runs — raw verdict margins must be read net of this.

## Full-scale verdict

**Translation-side benefit is weak (~52–54% decisive-verdict rate) and
verbatim-side cost is decisive without mitigation.** Three options:

1. **Keep as-is (toggle OFF)** — justified; the feature is net-negative
   for a transcript-first product in its current form.
2. **Guard prompt** — removes the cost but also the benefit; pointless.
3. **App-side fix (recommended next step)**: never lock the verbatim
   column to MT line 1 — always commit the ASR verbatim (empty-parse
   guard already exists at MainActivity.java:677). Baseline line 1 is
   already worse than raw ASR (0.144 vs 0.112), so MT echo adds nothing
   on the transcript side anyway. With contamination impossible by
   construction, UNGUARDED history keeps its modest translation win.
   This changes commit behavior for everyone, not just toggle users —
   product decision, needs owner sign-off.

Reports: `report.mt_context_ab.full.json` (B), `report.mt_context_ab.guard.json` (B+guard).
Reproduce: add `--sessions 30 --turns 0`; guard variant adds `--guard`
(`--no-judge` for generation-only, `--judge-only` to re-judge).

## Reproduce

```bash
cd tools/eval
export DASHSCOPE_API_KEY=sk-...
python3 mt_context_ab.py --sessions 8 --turns 30 --workers 8 \
    --report report.mt_context_ab.json        # generation + judge
python3 mt_context_ab.py --judge-only --report report.mt_context_ab.json
```
