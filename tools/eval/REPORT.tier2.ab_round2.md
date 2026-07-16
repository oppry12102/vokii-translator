# Tier2 A/B — round 2 (n=240, v1 vs v3)

The follow-up to `REPORT.tier1.ab_round1.md`: now that v1 was confirmed
as the better baseline on tier1 (40 samples), this round tries v3 (= v1
+ the 3 net-positive v2 rules: ASCII digits, no full-width, no fillers)
against v1 on the full n=240 tier2 set to confirm or rule out a small
edge.

## Headline numbers

| metric | v1 | v3 | Δ (v3 − v1) |
|--------|----:|----:|------------:|
| **avg MER** | **0.1436** | 0.1805 | **+0.0369** |
| median MER | 0.0851 | 0.1048 | +0.0197 |
| avg CER_zh  | 0.1133 | 0.1453 | +0.0320 |
| avg WER_en  | 0.2220 | 0.2672 | +0.0452 |
| perfect (MER=0) | 38 | 26 | −12 |
| **empty output** | 3 | 8 | **+5** |
| ≤0.05 | 77 | 57 | −20 |
| ≤0.10 | 140 | 117 | −23 |
| >0.50 | 11 | 17 | +6 |

Per-sample directional split: v3 better 72 · tie 65 · v1 better 102.

**v3 also regresses**, almost identically to v2 did on tier1. The
"no fillers" rule that looked promising (helped one tier1 sample drop
a hallucinated "Uh") overcorrected on tier2: at least 5 samples that
v1 transcribed correctly came back empty under v3.

## Top regressions (where v3 hurt the most)

| id | ref | v1 | v3 | Δ |
|----|-----|----|----|--:|
| `ZH-CN_U1053_S0_123` | "Passage to write." | "Passage to write." | "Pass it to right." | +1.00 |
| `ZH-CN_U0035_S0_88` | "Oh, I think yes." | "Oh, I think yes." | *(empty)* | +1.00 |
| `ZH-CN_U1003_S0_195` | "Yes, I know." | "Yes, I know." | *(empty)* | +1.00 |
| `ZH-CN_U1022_S0_223` | "嗯，像我也是，你说。" | "嗯，像我也是，你说。" | *(empty)* | +1.00 |
| `ZH-CN_U1053_S0_10` | "I come from 南充." | "I come from 南充." | *(empty)* | +1.00 |

Five of the eight worst regressions are v1 → empty under v3. The
"NO FILLERS" prompt rule interacts badly with the model's stream
behaviour on short utterances where v1 already does well — v3 makes
the model decline to commit when it would otherwise have emitted
something recoverable.

## Top wins (where v3 helped)

| id | reason | Δ |
|----|--------|--:|
| `ZH-CN_U1008_S0_9` | v1 gave empty, v3 returned "How about you?" | −0.75 |
| `ZH-CN_U1104_S0_41` | v1 hallucinated, v3 cleaner | −0.75 |
| `ZH-CN_U0035_S0_8` | smoother phrasing through fillers | −0.55 |
| `ZH-CN_U0025_S0_4` | added missing politeness particle | −0.50 |
| `ZH-CN_U0081_S0_223` | removed hallucinated "um" fillers | −0.46 |

These are the same kind of wins v2 had on tier1 — but the
regressions are larger in absolute terms on tier2.

## Verdict

**v3 stays out of the default.** Both prompt revisions (v2, v3)
regressed on the same magnitudes and through similar mechanisms
(more empty outputs, hallucinated substitutions). The prompt is not
the lever — the model has a baseline behaviour on short utterances
and code-switch boundaries that these prompts can't budge without
breaking something else.

`TRANSCRIBE_INSTRUCTIONS` remains = `TRANSCRIBE_INSTRUCTIONS_V1`. v3
remains available via `--instructions v3` for follow-up experiments.

## What this leaves on the table

Three directions the prompt can't reach:

1. **Cascade ASR + MT** — replace Qwen-Omni's joint ASR+translate
   single-step with a dedicated ASR model (`paraformer-v2` / `sensevoice`,
   both natively trained for code-switch) + a separate translation model.
   Projected impact −0.02 to −0.05.
2. **LoRA on CS-Dialogue** — fine-tune a small Qwen3-ASR head on the
   CS-Dialogue train split. The dataset is gated but the licensing
   allows fine-tuning under CC-BY-NC-SA. Projected impact −0.03 to −0.06.
3. **Android capture path** — even if prompt gains are exhausted, the
   audio_diff baseline (n=8) suggested there's still a +0 to +0.04
   prod-vs-eval gap. Run a paired audio_diff with real recorded prod
   audio (`record_prod.md`) to bound this properly. If ΔMER ≥ +0.02,
   ship webrtc-audio-processing (NS + AGC + AEC) before the WS send.

Priority recommendation: **(3) first** (cheap), then **(1)** (a single
afternoon to test), defer **(2)** to a separate session.

## Read-through: the v1 baseline is also regressed

For history: `report.tier2.json` in the repo (Flash model, no prompt
rewrite) had similar avg MER. The README cited "Pro 0.100" because the
authors re-ran transient empty outputs by hand and re-scored — when run
fresh, the same prompt + Pro model yields 0.144 avg MER (this round's
v1 number). That's a worse-than-expected baseline to optimise against,
not a better one. The 0.100 number is what you'd see with one retry
per sample.
