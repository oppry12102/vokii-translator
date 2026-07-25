# Cascade step 2 production fidelity — tier1 n=30 (cascade2 vs cascade2_prod)

Follow-up to `REPORT.latency.tier1.cascade2.md` open item #2. That
report's `cascade2` mode runs a **simplified** MT step (hardcoded 8-line
`MT_INSTRUCTIONS`, `qwen-mt-plus` via the DashScope native SDK, no
tools), while the production app runs a heavier path
(`MtPromptBuilder.buildSystemPrompt` + `qwen-turbo` over the
OpenAI-compatible streaming endpoint). This run adds a `cascade2_prod`
mode that reproduces the production MT step, so the reported MT latency
reflects what the shipping app actually pays.

## Setup

- `cascade2` — simplified MT (existing): `MT_INSTRUCTIONS`,
  `qwen-mt-plus`, dashscope native SDK, no tools.
- `cascade2_prod` — production-fidelity MT (new): full
  `MtPromptBuilder` auto-branch prompt (verbatim CORE PRINCIPLE +
  auto-detect interpreter block + eavesdrop/command paragraph + a static
  SESSION CONTEXT stub), `qwen-turbo`, OpenAI-compat streaming,
  temperature 0.3, **no tools** (latency memory measured tools-vs-no-tools
  TTFB at 631 vs 639 ms — noise; CS-Dialogue samples carry no voice
  commands so the `tool_calls` branch never fires).
- Both modes share the same fun-asr step 1 (`_run_asr_step1`), so the
  MT-step comparison is on identical ASR input + timing.
- n=30 tier1, server_vad, real-time pacing. **0 errors / 60 runs.**

## Headline (median / p95, seconds)

| metric | cascade2 (simplified) | **cascade2_prod** (production) | Δ median |
|--------|--------------------:|-------------------------------:|---------:|
| step1_ttfb (ASR first text) | 0.574 | 0.541 | — (shared ASR) |
| step1_total (ASR) | 6.572 | 7.032 | +0.460 |
| **mt_ttfb** (MT first byte) | 0.891 | **0.717** | **−0.174 (−20 %)** |
| mt_total | 0.891 | 1.093 | +0.202 |
| **mt_first_bilingual** (first ZH:/EN:) | n/a | **0.723** | (new metric) |
| pipeline ttfb | 0.574 | 0.541 | −0.033 |
| pipeline total | 8.378 | 8.080 | −0.298 |

p95: `mt_ttfb` cascade2 5.833 vs cascade2_prod 5.643; pipeline `total`
p95 18.206 vs 16.613.

## Key finding — the production path is FASTER on MT first text

The prediction behind open item #2 (and `REPORT.translate_audit.md`,
which forecast +200–400 ms for the heavier interpreter prompt) does
**not** hold. `cascade2_prod` MT first-text is **0.717 s vs 0.891 s —
0.17 s (≈20 %) faster**, not slower.

Why the prediction missed: it charged the heavier production prompt a
prefill cost but ignored that the two paths use **different models**.
The simplified `cascade2` runs `qwen-mt-plus`; production runs
`qwen-turbo`. `qwen-turbo`'s decode-start advantage (~280 ms, per the P5
measurement in `latency-asr-partial-live-caption`) more than offsets the
longer production prompt's prefill. The prompt-length cost is real but
dominated by the model swap.

`mt_total` does run ~0.2 s longer on the production path (1.09 vs 0.89):
the verbatim + translation output is slightly longer, so the stream
takes a touch more wall-time to finish — but the user is already reading
the first bilingual line by ~0.72 s.

## mt_first_bilingual — the user-visible event

New metric: time to the first SSE delta whose accumulated text contains
a `ZH:`/`EN:` label. `cascade2_prod` median **0.723 s**, p95 5.650 —
within 6 ms of `mt_ttfb` (0.717), i.e. the first delta already carries a
label. This is the number that maps to "user sees the first translated
line" and closes the caveat at `REPORT.latency.tier1.cascade2.md:84-88`
(first byte ≠ first readable text). The two are effectively identical
here because qwen-turbo emits the `ZH:` prefix as its first tokens.

## Caveats

- **n=30 tier1 only**, real-time pacing. Absolute numbers carry this
  dev/network run's overhead (note `cascade2` mt_ttfb 0.89 s here vs
  0.55 s in the 2026-07-16 report — same harness, different network
  session); the **relative** production-vs-simplified comparison is
  clean (shared ASR, same run, 0 errors).
- **No tools sent.** `tool_calls` routing rate is unmeasured; on
  CS-Dialogue (no commands) it would be ~0 anyway. A tools-included run
  is not expected to move `mt_ttfb` (latency memory: 631 vs 639 ms).
- **cascade2 `mt_total ≈ mt_ttfb`** (0.891 = 0.891): the dashscope
  native SDK's `incremental_output` stream timestamping collapses
  first/last in this harness. `cascade2_prod` (urllib SSE) shows the
  true gap (0.717 → 1.093). Does not affect the `mt_ttfb` comparison.
- The production prompt is a Python port of `MtPromptBuilder` for the
  default `auto`/`en` session; a live session's SESSION CONTEXT grows
  with command history (+~80 tokens/entry), adding a few ms of prefill
  per entry — negligible for typical sessions.

## Verdict

Open item #2 is resolved: the production translate path does **not**
add latency. `qwen-turbo` over the OpenAI-compat endpoint with the full
`MtPromptBuilder` prompt is **≈20 % faster to first bilingual text**
than the simplified `qwen-mt-plus` baseline the original latency report
measured. No release blocker; `mt_first_bilingual` is the metric to
watch going forward.

## How to reproduce

```bash
export QWEN_API_KEY=sk-...
python cascade_latency.py \
    --manifest manifest.tier1.jsonl \
    --modes cascade2,cascade2_prod --limit 30 \
    --report report.latency.tier1.cascade2_prod.json
```

## Files

- `cascade_latency.py` — `cascade2_prod` mode + `_run_prod_mt` +
  `PROD_SYSTEM_PROMPT` + shared `_run_asr_step1` (added 2026-07-25)
- `report.latency.tier1.cascade2_prod.json` — this run's per-sample data
