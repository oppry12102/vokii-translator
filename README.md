# Vokii

Android voice translator: speak in Chinese or English, see the other
language live as you talk. One-stage cloud ASR + translation via
Qwen-Omni Realtime over a WebSocket — no on-device speech model.

Target: Huawei / HarmonyOS phones (HMS works, GMS is *not* required).

## How it works

```
mic PCM (16 kHz, mono)
  → Qwen-Omni Realtime (WebSocket, DashScope)
  → streamed {zh, en} deltas
  → bilingual UI cards
```

The cloud model does recognition AND translation in one shot, so the app
works on ROMs that don't ship GMS-backed `SpeechRecognizer` (HarmonyOS).

### Models

| Model | Speed | Quality | Default? |
|-------|-------|---------|----------|
| `qwen3.5-omni-flash-realtime` | ~2× faster | lower | opt-in (Settings) |
| `qwen3.5-omni-plus-realtime` | slower | **better** | **default** |

The Pro model is the default because it scored **-39% relative MER** over
Flash on the CS-Dialogue eval (see [Eval](#eval) below). Switch in
Settings → Model.

## Build

Requires JDK 17, Android SDK with platform 34, and a DashScope API key.

1. Get a key: [Bailian console](https://dashscope.console.aliyun.com/)
2. Put it in `local.properties` (gitignored):

   ```properties
   QWEN_API_KEY=sk-...
   ```

3. Build the release APK:

   ```bash
   ./gradlew :app:assembleRelease
   ```

   Output: `app/build/outputs/apk/release/app-release.apk` (also copied
   to `../vokii-release.apk` by the `buildFinished` hook in
   `app/build.gradle`).

The API key is read at configuration time and compiled into
`BuildConfig.DEFAULT_QWEN_API_KEY`, so the resulting APK ships with a
working default — anyone who decompiles it can recover the key. That's
the trade-off for "fresh install just works". Override at runtime in
Settings; the override is stored in `SharedPreferences` and takes
precedence.

The release keystore lives at `keystore/vokii-release.jks` and is checked
into this repo (a real release process would not do this; see
[Security notes](#security-notes)).

## Eval

`tools/eval/` is a reproducible ASR-accuracy harness. It scores the
pipeline against [BAAI/CS-Dialogue](https://huggingface.co/datasets/BAAI/CS-Dialogue)
(Chinese-English code-switch) with **MER (Mixed Error Rate)**: Chinese
by character, English by word, with NFKC / punctuation-stripped /
lowercased / 繁→简 normalization.

### Two-tier sample (`select_tiers.py`)

| Tier | Purpose | N | Selection |
|------|---------|---|-----------|
| 1 | dev / debug | 40 | stratified by speaker × code-switch, min-len 8 |
| 2 | **conclusion** | 240 | 8/speaker × 30 speakers, disjoint from tier 1 |

```bash
cd tools/eval
pip install --user -r requirements.txt  # websockets, soundfile, numpy
pip install --user opencc-python-reimplemented  # optional, for 繁→简

python select_tiers.py --tier1 40 --tier2 240 --min-len 8

# Quick smoke
python eval.py --manifest manifest.tier1.jsonl --commit-mode manual \
    --no-realtime --limit 5

# Conclusion run
python eval.py --manifest manifest.tier2.jsonl --commit-mode manual \
    --no-realtime --report report.tier2.json
```

`--commit-mode manual` disables server VAD, pushes the whole clip, then
issues a single `input_audio_buffer.commit` + `response.create` — this
eliminates the segment-dropping that server VAD does on long clips.
`--no-realtime` skips the real-time upload pacing (~5× faster) and is
fine for accuracy; for latency-faithful runs drop it and use
`--commit-mode vad`.

### Conclusion: Flash vs Pro (N=240, CS-Dialogue dev)

| | Flash | Pro | Δ |
|--|------:|----:|---:|
| **MER mean** | 0.163 | **0.100** | **-0.063 (-39%)** |
| MER median | 0.095 | **0.045** | -0.049 |
| CER (zh) | 0.111 | **0.066** | -0.045 |
| WER (en) | 0.193 | **0.146** | -0.047 |
| code-switch MER | 0.154 | **0.130** | |
| pure-Chinese MER | 0.115 | **0.051** | |
| perfect (MER=0) | 29/240 | **57/240** | |
| pair-wise | Pro wins 112 · tie 73 · Flash wins 54 | | |

Two Pro-specific failure modes: 6.7% empty outputs (re-runnable
transients + 2 persistent 6-7s Mandarin clips the model declines to
transcribe); and a tendency to emit 繁體 Chinese — the harness
normalizes this via `opencc`, and the transcribe prompt asks for
simplified explicitly.

Full writeup: `tools/eval/RESULTS.cs_dialogue.md`.

## Repo layout

```
.
├── app/                   # Android Studio project (Java)
│   ├── build.gradle       #   BuildConfig fields from local.properties
│   └── src/main/java/com/vokii/translator/
│       ├── AsrEngineFactory.java
│       ├── ConfigStore.java        # model + key + endpoint
│       ├── SettingsActivity.java
│       ├── MainActivity.java
│       ├── TranslationController.java
│       ├── asr/QwenOmniEngine.java
│       └── ...
├── tools/eval/            # ASR-accuracy harness
│   ├── eval.py            #   main runner
│   ├── metrics.py         #   MER + latency + rep_rate
│   ├── qwen_client.py     #   DashScope WS client (mirrors Android)
│   ├── build_manifest.py  #   CS-Dialogue index → manifest.jsonl
│   ├── select_tiers.py    #   stratified tier-1 / tier-2 sampling
│   ├── RESULTS.cs_dialogue.md
│   ├── report.tier1.manual.json
│   ├── report.tier1.plus.json
│   ├── report.tier2.json
│   └── report.tier2.plus.json
├── keystore/              # release.jks (see Security notes)
├── build.gradle           # project-level (AGP + AGCP classpath)
└── local.properties       # (gitignored) QWEN_API_KEY
```

## Security notes

- `local.properties` is gitignored. Don't commit it.
- The QWEN_API_KEY in `BuildConfig` is recoverable from the APK. The
  build is configured to compile it in for "fresh install just works".
  If you distribute the APK publicly, treat the bundled key as leaked
  and rotate it.
- The release keystore (`keystore/vokii-release.jks`) is committed for
  reproducibility. A real release process would store it in CI secrets
  and rotate it per release.
- `app/agconnect-services.json` is gitignored — the AGC debug/release
  SHA-256 fingerprints let anyone with the key push crash/analytics to
  the same AGC project.

## License

Code: see `LICENSE` (or add one). The CS-Dialogue dataset used by the
eval harness is gated and CC-BY-NC-SA-4.0 — it is **not** redistributed
in this repo; the harness reads the audio from a local extract.
