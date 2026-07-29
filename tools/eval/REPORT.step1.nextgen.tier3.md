# Cascade step 1 — next-generation ASR A/B (tier3, n=1000)

**Date**: 2026-07-29
**Dataset**: `manifest.tier3.1000.jsonl` (CS-Dialogue dev, 500 code-switch +
500 pure-zh, 30 speakers, min-len 8, deterministic md5 selection)
**Baseline**: `fun-asr-realtime` (undated alias, = 2025-08-22 snapshot),
the n=1000 robust baseline from 2026-07-27.
**Challengers**: `fun-asr-realtime-2026-02-28` (newest dated snapshot),
`qwen3-asr-flash-realtime` (newest DashScope realtime ASR family,
OpenAI-Realtime protocol, snapshots 2025-10-27 / 2026-02-10).

## Headline

| Metric | fun-asr (base) | fun-asr@2026-02-28 | qwen3-asr |
|--------|---------------|--------------------|-----------|
| **avg MER** | **0.0761** | 0.0783 (+2.9%) | 0.1019 (**+34%**) |
| median MER | **0.0336** | 0.0353 | 0.0503 |
| avg CER_zh | 0.0539 | **0.0497** (−7.8%) | 0.0756 (+40%) |
| avg WER_en | **0.1642** | 0.1650 (flat) | 0.2165 (+32%) |
| perfect | **323** | 318 | 261 |
| ≤0.10 | 779 | **781** | 705 |
| errors | 0 | 0 | 0 |

Pairwise vs baseline:
- **fun-asr@2026-02-28**: 148 W / 684 T / 168 L, avg ΔMER +0.0069 — **not an
  improvement**. CER_zh gain is real but small; overall MER moves the wrong
  way (one new hallucination outlier: ZH-CN_U0081_S0_102, 0.000 → 2.333).
- **qwen3-asr**: 158 W / 479 T / 363 L, avg ΔMER +0.0496 — **clearly worse**
  in every length bucket and both languages.

## Is qwen3-asr's deficit just written-form conventions?

No. Re-scored all three reports offline with the filler-equivalence
normalizer (嗯哼=uh-huh, 哦=oh, 啊=ah mapped to shared canonical tokens,
both ref and hyp):

| model | MER baseline | MER filler-equiv |
|-------|-------------|------------------|
| fun-asr | 0.0761 | 0.0741 |
| fun-asr@2026-02-28 | 0.0783 | 0.0756 |
| qwen3-asr | 0.1019 | 0.0990 |

Form noise is only ~0.002–0.003 MER on tier3 (the min-len 8 filter already
removes most filler-only short utterances — on the unfiltered full set it is
worth ~0.017). qwen3-asr's +0.026 gap is **genuine recognition error**, not
orthography. Verdict: qwen3-asr-flash-realtime, despite being the newer
model family (52-language claims), is worse on conversational zh+en
code-switch than fun-asr's CS-specialized training. **Dropped.**

## Conclusions

1. **No engine change.** `fun-asr-realtime` (undated) stays the cascade
   step-1 default. The 2026-02-28 snapshot offers a small CER_zh gain at a
   small overall-MER cost — not worth switching (and the undated alias may
   itself roll forward over time; re-run this A/B if DashScope announces a
   new default).
2. **DashScope realtime ASR model space is now exhausted**: paraformer-v2
   (0.087 tier2) < **fun-asr (champion)** > qwen3-asr (+34%). Further MER
   gains will not come from swapping the ASR model on DashScope.
3. qwen3-asr-flash-realtime speaks the OpenAI-Realtime protocol
   (`/api-ws/v1/realtime`, session.update + input_audio_buffer.append +
   server_vad), NOT the fun-asr run-task protocol. The new driver
   `cascade_step1_qwen3asr.py` (built on the SDK's `OmniRealtimeConversation`
   + `TranscriptionParams`) is kept for future re-tests — event flow verified:
   `speech_started → transcription.text (partials) → speech_stopped →
   committed → transcription.completed → session.finished`.

## Error-mass anatomy (from the n=6186 full-set report, offline analysis)

Where the remaining MER lives (informs what could still move the number):

- MER>1 silence-hallucination tail: only 26/6186 samples, **8.3%** of error
  mass → a client-side silence gate is **not** worth building for MER.
- MER∈(0.1, 1.0]: 1450 samples holding **78.5%** of error mass; ~half of that
  in ≤5-token utterances, dominated by backchannel written-form mismatches
  (嗯哼→"uh-huh.", 哦→"Oh.", 啊→"ah.") — a **scoring-convention** question
  (filler-equivalence normalization would take full-set mean MER
  0.1121 → 0.0951, −15%), pending owner decision.
- Contraction expansion (you're vs you are): 22 samples, negligible.
- ITN digit normalization: 141 hyps with Arabic digits vs always-hanzi refs,
  but only −0.0008 mean MER — negligible.
- Wrong-script hallucinations (Hangul/Kana in hyp): 17 samples, 2.3% of mass.
- English remains the genuine weak spot: WER_en 0.169; the 15.8% of
  en-utterances with WER>0.3 hold 68.4% of English error mass.

## Reproduce

```bash
cd tools/eval
export DASHSCOPE_API_KEY=sk-...

python3 cascade_step1_alt.py --manifest manifest.tier3.1000.jsonl \
    --model fun-asr-realtime-2026-02-28 --workers 8 \
    --report report.cascade.step1.funasr-20260228.tier3.1000.json

python3 cascade_step1_qwen3asr.py --manifest manifest.tier3.1000.jsonl \
    --model qwen3-asr-flash-realtime --workers 6 \
    --report report.cascade.step1.qwen3asr.tier3.1000.json

python3 cascade_compare.py \
    report.cascade.step1.funasr.tier3.1000.json \
    report.cascade.step1.funasr-20260228.tier3.1000.json \
    report.cascade.step1.qwen3asr.tier3.1000.json
```
