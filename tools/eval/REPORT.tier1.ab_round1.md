# tier1 A/B — round 1 (40 samples)

Three runs on `manifest.tier1.jsonl`, model `qwen3.5-omni-plus-realtime`,
`--commit-mode manual --no-realtime`. ASR scored with MER (Mixed Error Rate).

| Config | avg_mer | avg_cer_zh | avg_wer_en | perfect | empty | errors |
|--------|--------:|-----------:|-----------:|--------:|------:|-------:|
| **v1** (baseline, production) | **0.1341** | 0.1036 | 0.2055 | 6/40 | 0 | 0 |
| v2 (default, new rules) | 0.1590 | 0.1131 | 0.2371 | 6/40 | **1** | 1 |
| v2 + repetition_penalty 1.05 | 0.1487 | 0.1208 | 0.2145 | 6/40 | 0 | 0 |

`repetition_penalty=1.05` was accepted by the DashScope session (no
protocol error) — partial recovery from the v2 regression but still worse
than v1.

## v2 regression — per-sample inspection

Pairwise compare of v1 vs v2 on the 40 sample ids:

| | count |
|--|------:|
| v2 **better** | 14 |
| tie          | 15 |
| v2 **worse** | 10 |
| error/empty  | 1 |

### Worst regressions (v2 produced wrong / dropped text)

| id | ref | v1 hyp | v2 hyp | Δ |
|----|-----|--------|--------|--:|
| `ZH-CN_U1007_S0_79` | "我准备一下，三、二、一。" | "我准备一下，三、二、一。" | "我准，二一。" | **+0.50** |
| `ZH-CN_U0024_S0_24` | "你也是哈萨克族。" | "你也是哈萨克族。" | "你也是哈毒。" | **+0.43** |
| `ZH-CN_U0049_S0_96` | "Yes, I think." | "Yes, I think." | "Yes, I." | +0.33 |
| `ZH-CN_U0061_S0_400` | "...ice cream... Zhongxu" | "...ice cream... Zhongxu" | "...aspirin... 中学膏" | +0.29 |
| `ZH-CN_U0063_S0_32` | (non-empty) | (non-empty) | *(empty)* | n/a |

Pattern: rule #4 ("wait ≥0.5s of silence before writing") and rule #5
("output nothing if not intelligible") combined make the model **drop the
opening of sentences and hallucinate substitutions to fill the gap**
("哈萨克族" → "哈毒", "ice cream" → "aspirin"). One sample went fully
empty.

### Wins from v2

| id | reason | Δ |
|----|--------|--:|
| `ZH-CN_U0081_S0_125` | read "二" (digit 2) as "二百" (200) — rule #1 helped | −0.13 |
| `ZH-CN_U0062_S0_209` | added missing particle "对" | −0.12 |
| `ZH-CN_U0036_S0_361` | smoother phrasing through 3-gram | −0.11 |
| `ZH-CN_U0082_S0_229` | removed hallucinated "Uh" filler | −0.23 |

### Verdict on the v2 prompt

The 5-rule rewrite **regressed the average by +0.025 MER and triggered
1 hard empty output**. The wins (cleaner fillers, better numerals) are
real but smaller than the losses. **Roll back to v1 as the default
immediately**; anything novel must first ship behind `--instructions` for
A/B and require n=240 to confirm no regression at the tier2 confidence.

## v3 plan — keep only the rules that didn't backfire

| v2 rule | verdict | action |
|---------|---------|--------|
| 1. ASCII digits / simplified only | net slightly positive | keep |
| 2. NO LANGUAGE FLIP | neutral (no signal either way on n=40) | keep, more rephrasing |
| 3. NO FILLERS | mixed (helped 1, hurt 0) | keep, narrower scope |
| 4. WAIT FOR ≥0.5s SILENCE | **hard regression** | **drop** |
| 5. OUTPUT NOTHING IF UNCLEAR | **caused empty output** | **drop** the "nothing" branch |

v3 will be v1 + rules 1, 2, 3 only — no silence/empty rules.

## audio_diff synthetic (n=8, mic_loose + noise 15 dB + resample 44.1k)

Bracketed MER delta of "clean CS-Dialogue audio" vs "synthetic
phone-mic-degraded audio" (worst-case preset chain):

| metric | value |
|--------|------:|
| avg clean MER | 0.259 |
| avg prod MER  | 0.118 |
| ΔMER (prod−clean) | **−0.142** (suspicious) |

That negative number is misleading. The single outlier (sample
`ZH-CN_U0024_S0_142`, ref "Questions.") brought the clean side to MER
1.0 — model transcribed "question。" instead of "questions." On the
**degraded** audio the model returned "Questions." verbatim. Removing
that one clip:

| metric | value (n=7) |
|--------|------:|
| avg ΔMER | −0.019 |
| prod tied | 3/7 |
| prod worse | 2/7 |

**Take-away**: synthetic mic degradation alone doesn't materially inflate
MER. Either the model is more robust than the worst case implies, or my
synthetic chain (bandpass + 15 dB SNR + one resample round-trip) is too
mild. Real bound requires **paired prod recordings** (manifest with
`prod_audio`). Same n=40 tier1 paired run would give a real
number.

## Recommended order of operations next

1. **Roll back default to v1** in `qwen_client.py` (keep v2 available
   via `--instructions v2` opt-in).
2. **Ship v3** = v1 + rules 1, 2, 3 only. Verify against tier2 (n=240)
   before any default promotion.
3. **Add hard-empty failure mode** so the 1 empty sample under v2 stops
   being silently folded into the average — make empty output a row that
   shows up red in the summary table.
4. **Record prod audio** for ~30 of the tier1 clips (replay through phone
   speaker into mic, save as wav) → `manifest.tier1.paired.jsonl` →
   re-run audio_diff. This is the only way to know if prod is actually
   worse than eval, and by how much.
5. **If paired audio_diff shows ΔMER ≥ +0.02**, the next leverage is the
   Android capture pipeline (AEC + NS + AGC), not the prompt. Insert
   `webrtc-audio-processing` before the WS send and re-score.
6. If audio_diff shows ΔMER ≈ 0, **the eval track is the bottleneck** —
   swap the prompt-engineering track for **cascade ASR + MT**
   (Paraformer-V2 → qwen-mt-plus) or **LoRA-tune Qwen3-ASR on
   CS-Dialogue**. Both should target a much bigger tier2 swing (−0.03
   to −0.06 each, per the previous analysis).
