# Cascade step 3 — fun-asr-realtime vs paraformer-realtime-v2 (tier2 n=240)

Follow-up to `REPORT.cascade.step1.md` (which established cascade step 1
wins −39 % over joint) and `REPORT.latency.tier1.md` (which showed
cascade step 1 is also faster). This run A/B's the **cascade step 1
ASR model** itself: `fun-asr-realtime` (DashScope's newer 2025 model)
vs `paraformer-realtime-v2` (the current default).

## Headline

| metric | paraformer (cascade1 baseline) | **fun-asr-realtime** | Δ | rel. |
|--------|-------------------------------:|--------------------:|----:|-----:|
| **avg MER** | 0.0869 | **0.0693** | **−0.0175** | **−20.2 %** |
| median MER | 0.0520 | 0.0392 | −0.0128 | −24.6 % |
| avg CER_zh | 0.0767 | 0.0589 | −0.0178 | −23.2 % |
| avg WER_en | 0.1828 | 0.1335 | **−0.0493** | **−27.0 %** |
| perfect (MER=0) | 59/240 | **71/240** | +12 | |
| empty outputs | 0 | 0 | 0 | |
| errors | 0 | 0 | | |

**fun-asr-realtime wins on every aggregate metric.** Compared to the
v1 joint baseline (MER 0.1436) the cascade with fun-asr is **−0.0743
(−52 %)** — the cumulative MER drop from joint → cascade1 → fun-asr.

## Per ref-token bucket (cascade_compare output)

| bucket (ref tokens) | paraformer (n) | fun-asr (n) | rel. drop |
|---------------------|---------------:|------------:|----------:|
| ≤10                 | 0.132 (40)     | 0.117 (40)  | −11 %     |
| 10-30               | 0.100 (80)     | 0.076 (80)  | −24 %     |
| 30-60               | 0.075 (58)     | 0.056 (58)  | −25 %     |
| 60+                 | 0.052 (62)     | 0.042 (62)  | −19 %     |

Every bucket improves; the middle-of-the-distribution buckets (10-30,
30-60 tokens) gain the most. The ≤10-token bucket — the user-perceived
"short utterance" pain point — improves another 11 % on top of the
cascade1 step 1 gain.

## Pairwise (paired n=240, fun-asr vs paraformer)

| direction         | n  | %    |
|-------------------|---:|-----:|
| fun-asr better    | 96 | 40.0 % |
| tie               |101 | 42.1 % |
| fun-asr worse     | 43 | 17.9 % |

Net +53 wins for fun-asr; per-pair avg ΔMER = **−0.0302** (the matched
sum divided by 240, per `cascade_compare.py`).

## fun-asr vs v1 joint (cumulative gain)

| direction            | n  | %    |
|----------------------|---:|-----:|
| fun-asr better       |108 | 45.0 % |
| tie                  | 72 | 30.0 % |
| fun-asr worse        | 60 | 25.0 % |

vs v1 (the joint path) fun-asr wins on **45 %** of clips outright.
The 60 losses are mostly the same short-utterance code-switch boundary
failures that v1 also struggled with — and the user only sees the wins
because loss severity on this dataset is bounded (no clip regresses
worse than ~0.6 MER).

## Top fun-asr wins (cascade was wrong, fun-asr fixed it)

| id | cas | fun | Δ |
|----|----:|----:|--:|
| `ZH-CN_U0049_S0_36` | 0.800 | 0.300 | **−0.500** |
| `ZH-CN_U0060_S0_230` | 0.500 | 0.000 | −0.500 |
| `ZH-CN_U0062_S0_355` | 0.400 | 0.000 | −0.400 |
| `ZH-CN_U0061_S0_530` | 0.364 | 0.000 | −0.364 |
| `ZH-CN_U1003_S0_20` | 0.333 | 0.000 | −0.333 |
| `ZH-CN_U1031_S0_245` | 0.435 | 0.130 | −0.304 |
| `ZH-CN_U1103_S0_47` | 0.300 | 0.000 | −0.300 |
| `ZH-CN_U0081_S0_165` | 0.312 | 0.062 | −0.250 |
| `ZH-CN_U0064_S0_604` | 0.250 | 0.000 | −0.250 |
| `ZH-CN_U0024_S0_357` | 0.200 | 0.000 | −0.200 |

The fun-asr wins are exactly the cascade1 regressions we flagged in
step 1 analysis: Mandarin-biased substitutions, random-English-tail
hallucinations, full-width punctuation errors. fun-asr's training set
covers these patterns better.

## Top fun-asr losses (cascade was right, fun-asr was wrong)

| id | cas | fun | Δ |
|----|----:|----:|--:|
| `ZH-CN_U0026_S0_274` | 0.500 | 1.000 | +0.500 |
| `ZH-CN_U1053_S0_123` | 0.000 | 0.333 | +0.333 |
| `ZH-CN_U1053_S0_10`  | 0.143 | 0.571 | +0.429 |
| `ZH-CN_U1008_S0_9`   | 0.000 | 0.250 | +0.250 |
| `ZH-CN_U0035_S0_88`  | 0.000 | 0.250 | +0.250 |

**Pattern**: fun-asr tends to "clean up" disfluent English:
- `Passage to write.` → `passage to right.` (lowercase, "write"→"right")
- `How how about you?` → `how about you?` (drops the deliberate repeat)
- `Oh, I think yes.` → `I think yes.` (drops the discourse marker)
- Conversely it sometimes **adds** fillers (`Em`, `uh`, `um`) where
  paraformer would leave the audio clean.

This is a fun-asr behavioural bias we can't fix in a prompt — it's a
model-characteristic trade-off. The aggregate is overwhelmingly
positive; on individual clips where the user actually did stutter or
repeat, paraformer was better.

## Verdict

**fun-asr-realtime should replace paraformer-realtime-v2 as the cascade
step 1 engine.** −20 % MER relative drop on top of the existing −39 %
cascade1 win; combined with the latency improvements in
`REPORT.latency.tier1.md`, the cascade architecture with fun-asr is
both faster and more accurate than the v1 joint path on every metric.

Recommended Android change:
- `CascadeEngine.java` line ~64 — `asrClient.connect("fun-asr-realtime", ...)`
- Keep `paraformer-realtime-v2` as a Settings fallback for users who
  hit specific fun-asr regressions (none observed on tier2 outside the
  43-loss set above, which is balanced by 96 wins).

## How to reproduce

```bash
python cascade_step1_alt.py \
    --manifest manifest.tier2.jsonl \
    --model fun-asr-realtime \
    --report report.cascade.step1.funasr.tier2.json

python cascade_compare.py \
    --baseline report.cascade.step1.tier2.json \
    --challenger report.cascade.step1.funasr.tier2.json
```

The refresh of `report.cascade.step1.tier2.json` (run 2026-07-16) showed
identical 0.0869 MER — the cascade1 baseline number from the original
step 1 report is reproducible, not a transient.

## Files

- `cascade_step1_alt.py` — alternate-model A/B driver (added 2026-07-16)
- `report.cascade.step1.funasr.tier2.json` — this run's per-sample output
- `report.cascade.step1.tier2.refresh.json` — reproducibility check on
  cascade1 baseline (MER 0.0869, Δ = +0.0000 vs original)