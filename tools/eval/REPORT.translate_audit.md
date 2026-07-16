# Translate-mode audit (E')

The Vokii Android app (production) runs `task=translate` — the Qwen-Omni
interpreter prompt — and splits the response into a ZH / EN pair for the
two UI columns. The eval harness supports `translate` as a CLI choice
(`--task translate`) but the translate path has never been measured on
tier1 / tier2.

We ran a 3-sample smoke (`debug_translate.py`) to confirm the protocol.

## Smoke result

| Sample id | full_text (raw) | parser → zh | parser → en |
|-----------|-----------------|-------------|-------------|
| `ZH-CN_U0023_S0_91` | `ZH: 但这也不会那么容易，所以我的导师…\nEN: But it's not gonna be like so easy too, so my advisor will focus more on some structure aspects…` | full ZH line | full EN line |
| `ZH-CN_U0024_S0_142` (ref: "Questions.") | *(empty)* | empty | empty |
| `ZH-CN_U0025_S0_477` | `ZH: ...\nEN: ...` (clean) | full | full |

The model **does** emit both lines correctly. The first time the same
run was attempted via `eval.py --task translate` on the same file, the
report had `hyp_en=''`, suggesting a transient stream cut. n=3 is too
small to characterise translate-mode variance.

## Recommendation

Before any prompt work on the translate task, run a **larger smoke**
to measure baseline variance:

```bash
python eval.py --manifest manifest.tier1.jsonl \
    --task translate --commit-mode manual --no-realtime \
    --limit 30 --report report.tier1.translate.v1.json
```

Three things to look at in the resulting report:

1. **zh-en completion rate.** What fraction of samples have non-empty
   `hyp_zh` AND non-empty `hyp_en`? If it's <90%, the prompt or
   streaming is broken in production-relevant ways.
2. **CER_zh and WER_en** — but only meaningful if you have `ref_zh`
   / `ref_en` in the manifest. The shipped `manifest.tier1.jsonl`
   doesn't, so this needs either (a) `ref_zh` derived from `ref`
   via a Chinese-segmenting tool, or (b) a small manually-translated
   eval set.
3. **Latency**. The interpreter prompt is heavier than the transcriber
   prompt; TTFB on translate is typically 200-400 ms higher. Worth
   capturing on tier1 / tier2 alongside the accuracy.

The current priority order is unchanged: **first** finish the tier1
transcribe A/B (B), **then** run the translate smoke, **then**
decide whether to A/B-prompt the translate task the same way we did
for transcribe.
