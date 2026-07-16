# Vokii Eval — speech-to-text evaluation harness

Quantifies the quality and latency of the Qwen-Omni Realtime pipeline
the Vokii Android app uses, so improvements can be measured instead of
guessed.

## Two tasks: transcribe vs translate

`eval.py --task` picks what the pipeline is asked to do and how it's scored:

| `--task` | Session instructions | Scored with | Needs |
|----------|----------------------|-------------|-------|
| `transcribe` (default) | verbatim ASR, keep code-switch, no translation | **MER** (Mixed Error Rate) + zh/en breakdown | one `ref` (the verbatim transcript) |
| `translate` | interpreter, emit `ZH:`/`EN:` translations | `cer_zh` / `wer_en` / `bleu_*` | `ref_zh` and/or `ref_en` (gold translations) |

**MER** tokenizes mixed text into Chinese *characters* + English *words*,
edit-distances against the reference, and divides by reference length —
the standard code-switch ASR metric. Before scoring, both sides are
normalized: NFKC, punctuation/symbols dropped, English lowercased, spaces
collapsed. `cer_zh`/`wer_en` in transcribe mode are that same number
restricted to the Chinese / English tokens.

## What it scores

| Metric | What it tells you |
|--------|-------------------|
| `mer` | Mixed error rate (transcribe task) — 0 is perfect |
| `cer_zh` | Chinese character error rate |
| `wer_en` | English word error rate |
| `bleu_zh` / `bleu_en` | Translation BLEU-4 (translate task only) |
| `ttfb` | Time to first delta (sec) — how soon the user sees text after speaking |
| `total_latency` | End-of-stream latency (sec) |
| `max_delta_gap` | Largest pause between deltas — catches stream stalls |
| `rep_rate` | Fraction of output chars that are stuck repeats — catches model stutter |
| `missing_rate_zh` | Cheap proxy for "did the model hear everything" |

`normalize_asr` (used by MER) does **NFKC → traditional→simplified → drop
punctuation/symbols → lowercase Latin → collapse spaces**. The 繁→简 step
needs `opencc-python-reimplemented` (optional, falls back to identity if
missing) — Pro model will sometimes emit 繁體, which would otherwise
inflate CER_zh.

## Setup

```bash
cd tools/eval
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
export DASHSCOPE_API_KEY=sk-...
```

## Manifest format

JSON Lines, one sample per line. `id` and `audio` are required. For
`--task transcribe`, add `ref` (the verbatim transcript). For `--task
translate`, add `ref_zh` / `ref_en` (gold translations). Refs are optional
— without them only latency / stream-health metrics are computed.

```jsonl
{"id": "cs_001", "audio": "/abs/cs_001.wav", "ref": "我今天去 office 开 meeting"}
{"id": "cs_002", "audio": "/abs/cs_002.wav", "ref_zh": "你好世界", "ref_en": "hello world"}
```

Audio must be a WAV readable by `soundfile` (any sample rate — it gets
resampled to 16 kHz mono PCM16 internally). For very long files,
`--limit` is your friend.

## Run

```bash
# full eval
python eval.py --manifest manifest.jsonl --report report.json

# quick smoke test (first 3 samples)
python eval.py --manifest manifest.jsonl --limit 3 --report smoke.json

# try a different model / VAD settings
python eval.py --manifest manifest.jsonl \\
    --model qwen3.5-omni-plus-realtime-2026-03-15 \\
    --vad-silence 300 --vad-threshold 0.3
```

Output goes to stdout (per-sample + summary table) and `report.json`.

## Where to get audio

### Option A: CS-Dialogue (BAAI) — the original ask

CS-Dialogue is a Chinese-English code-switch **ASR** dataset. Its `text`
file holds verbatim mixed-language transcripts — perfect for `--task
transcribe`, and the reason MER exists.

**1. Download.** The dataset is gated (accept the terms on ModelScope/HF
first). The audio lives in `data/short_wav/*.tar.gz`; the index
(`data/index/...`) is separate and tiny — make sure your download patterns
include the `WAVE/` audio, not just the index:

```bash
pip install modelscope
python -c "
from modelscope import snapshot_download
snapshot_download('BAAI/CS-Dialogue', repo_type='dataset',
                  cache_dir='./data/cs')
"
# then extract the audio archives so short_wav/WAVE/... exists on disk
find ./data/cs -name 'short_wav*.tar.gz' -execdir tar xzf {} \;
```

**2. Build a manifest** from the index (`build_manifest.py` joins `text`
+ `wav.scp`, strips `<FIL/>`/`<SPK/>`/… tags, resolves absolute audio
paths):

```bash
python build_manifest.py \
    --index-root /path/to/BAAI--CS-Dialogue/snapshots/master/data/index \
    --split dev --limit 30 \
    --only-existing \
    --out manifest.cs_dialogue.jsonl
```

- `--audio-root` defaults to the `data/` dir the index sits under (what
  `wav.scp` paths are relative to); override if you extracted elsewhere.
- `--only-existing` drops rows whose `.wav` isn't on disk yet — omit it to
  see the full list with a missing-audio warning.
- `--task transcribe` (default) writes the transcript as `ref`.

**3. Run** the transcription eval:

```bash
python eval.py --manifest manifest.cs_dialogue.jsonl --task transcribe \
    --limit 30 --report report.cs_dialogue.json
```

> Note: `long_wav` (whole conversations + TextGrid segmentation) isn't
> handled by `build_manifest.py` — use `short_wav`, whose utterances are
> already segmented.

#### Two-tier sample (recommended)

`select_tiers.py` builds a debug tier and a conclusion tier in one pass —
stratified by speaker × code-switch, filler acks (`嗯/哦/对`) filtered by
`--min-len`, and the two tiers **disjoint** so tuning on tier 1 doesn't
inflate the tier-2 number. Selection is deterministic (md5(utt_id)), so
re-runs reproduce the same sample.

```bash
python select_tiers.py --tier1 40 --tier2 240   # writes manifest.tier{1,2}.jsonl

python eval.py --manifest manifest.tier1.jsonl --report report.tier1.json  # debug
python eval.py --manifest manifest.tier2.jsonl --report report.tier2.json  # conclusion
```

Only the dev split ships labels in this download, so the tier-2 conclusion
is measured on dev — note that when reporting.

> **Multi-turn / trailing silence.** These clips are trimmed to the speech
> with no tail silence, and server_vad splits a long utterance on internal
> pauses into several turns. The client appends `--trailing-silence` ms
> (default 800) so the final turn commits, and accumulates text across
> *all* turns of a clip — don't lower the trailing silence below the VAD
> `--vad-silence` (300 ms) or long utterances come back truncated/empty.

### Option B: your own recordings

Record 30–60 short sentences (Chinese + English) on your phone, push
them, and score. Best for catching model regressions on your specific
domain (places, names, jargon).

### Option C: synthetic

Generate a few TTS clips (any TTS service) with known transcripts. The
shipped `manifest.example.jsonl` shows the schema but doesn't include
audio — drop in any `.wav` you have to test the tool.

## Improvement loop (recommended)

1. **Baseline first**: run with the current `--model` and current
   VAD settings; record `report.json` as `baseline.json`. Note the
   average CER/WER and TTFB — these are your starting points.
2. **Pick the weakest metric** from the summary table.
   - High CER but low TTFB → translation quality issue
   - Low CER but high TTFB → latency issue
   - High `rep_rate` or `max_delta_gap` → stream stability issue
3. **Make one change** (model swap, VAD tweak, prompt edit, app code
   change). Re-run the same manifest. Diff `baseline.json` vs the new
   `report.json` to see if the change moved the metric you cared about
   without hurting others.
4. **Is the eval representative of prod?** Use `audio_diff.py` to score
   the same manifest with synthetically degraded audio (or, ideally,
   re-recorded prod audio via `prod_audio:` in a paired manifest). If
   ΔMER ≥ +0.02, the eval understates what users see — fix the capture
   pipeline before tuning the model.
5. Repeat. Keep the changes that improve, revert the ones that don't.

## Sampling knobs you can sweep

Two `eval.py` flags expose session-level knobs the model is willing to
accept (DashScope may silently ignore unsupported ones):

- `--repetition-penalty 1.05` — gentle anti-stutter. `1.0` = off.
- `--instructions '…'` — full prompt override. Sentinels:
  - `v1` (default — best on tier1: avg MER 0.134)
  - `v2` — 5 strict rules. **Do not use as default**: tier1 A/B showed
    a +0.025 regression and 1 empty output (see `REPORT.tier1.ab_round1.md`).
    The "wait for ≥0.5s silence before writing" rule + the "output nothing
    if unclear" rule combined make the model drop sentence heads and
    hallucinate substitutions to fill the gap.
  - `v3` — v1 + the three v2 rules that didn't backfire (ASCII digits /
    no full-width / no fillers). Worth a tier2 A/B before promotion.
  - anything else is passed verbatim to the model.

The Android production app uses `v1`-equivalent instructions
(verified by the tier1 A/B). When you change Android, mirror the same
sentinel here so apples-to-apples comparisons stay that way.

## Comparing transcription runs (`cascade_compare.py`)

`tools/eval/cascade_compare.py` is the canonical A/B helper for any
transcription-direction work. It pairs two or more `report.json` files
(``--baseline``, ``--challenger``) and prints:

- headline summary (avg / median MER, per-language breakdown)
- per-ref-length MER bucket (≤10 / 10-30 / 30-60 / 60+)
- pairwise win / tie / loss counts vs the baseline
- top-K regressions and wins
- a CSV block that pastes straight into a spreadsheet

This is what produced the cascade-step-1 numbers; example:

```bash
python cascade_compare.py \
    --baseline report.tier2.v1.json \
    --challenger report.cascade.step1.tier2.json
```

Re-run it after any code change that affects transcription quality
(model swap, prompt tweak, audio path, paired-recorded audio diff).

The companion runs: `cascade_step1.py` (Paraformer-V2 verbatim ASR
only) and `cascade_step2.py` (Paraformer-V2 ASR + qwen-plus text MT
end-to-end). All three keep translation out of scope — transcription
MER is the only forward-looking metric per the project's optimisation
target.

## Notes & gotchas

- The eval connects to the same DashScope endpoint the Android app
  uses, so the numbers reflect the production pipeline — but it
  doesn't include the Android audio capture path. If you suspect mic
  quality / resampling bugs, run an on-device eval separately.
- The first request after a long idle can be slow (cold start ~1s).
  Warm-up is on you; consider running the first sample twice and
  discarding the first result.
- `--vad-silence 300` is more aggressive than the Android default
  (600ms). Faster turn commits help latency but can chop rapid
  speakers — tune per your data.
- For very long audio (>30s), you may want to split into chunks to
  keep `response.done` from taking too long.
