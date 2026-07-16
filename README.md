# Vokii

Android voice translator: speak in Chinese or English, see the other
language live as you talk. Cloud-only — no on-device speech model, no
GMS/HMS dependency.

Target: Huawei / HarmonyOS phones and any AOSP device with a working
network path to DashScope.

## How it works

The shipped pipeline is **cascade** (the default since v2.2.0):

```
mic PCM (16 kHz, mono, 20 ms frames)
  │
  ├─ step 1 ─► fun-asr-realtime ASR  (wss://dashscope.aliyuncs.com/api-ws/v1/inference)
  │            DashScope task protocol: header/payload envelope + raw
  │            binary audio + `event`-typed result frames. server_vad
  │            segments speech at each pause; each segment commits as
  │            one {sentence_id, text, sentence_end} JSON envelope.
  │            ──► verbatim transcript (zh + en mixed)
  │
  └─ step 2 ─► qwen-plus chat completion
               (POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
                with `stream: true`, SSE `{choices:[{delta:{content:..}}]}` frames)
               prompt: emit two-line `ZH:` / `EN:` pair from verbatim
               ──► bilingual card (zh column / en column)
```

The split exists because a model that does **only** ASR (fun-asr-realtime,
trained natively on code-switch zh+en) pays full attention to the audio,
whereas a joint speech-and-translate model (Qwen-Omni Realtime) splits
its attention budget across both tasks. fun-asr beats the joint model on
verbatim error rate while also being smaller and faster. Step 2 then
delegates translation to a general-purpose LLM via the OpenAI-compatible
chat endpoint, paying an extra ~0.7 s TTFB per committed sentence.

A toggle in Settings switches back to the **joint** Qwen-Omni Realtime
path if needed (e.g. regions without fun-asr-realtime coverage).

### Why cascade wins

On CS-Dialogue tier2 (n=240, code-switch zh+en conversation):

| Path | avg MER | Δ vs joint |
|------|--------:|-----------:|
| v1 joint (Qwen-Omni Realtime) | 0.1436 | — |
| cascade1 (paraformer-realtime-v2 step 1) | 0.0869 | **−39 %** |
| **cascade + fun-asr-realtime step 1** | **0.0693** | **−52 %** |

And on tier1 (n=30, real-time pacing):

| | joint | cascade2 | speedup |
|---|------:|---------:|--------:|
| **TTFB median** | 2.97 s | **0.49 s** | **6.05 ×** |
| Total median | 16.32 s | **5.89 s** | 2.77 × |
| Total p95 | 44.68 s | 17.46 s | 2.56 × |

Cascade is a **no-trade-off win** — both more accurate AND faster on
every metric that matters to the user. See
`tools/eval/REPORT.latency.tier1.cascade2.md` and
`tools/eval/REPORT.cascade.step3.md` for the full breakdown.

## Settings

Two real knobs (everything else baked into BuildConfig at compile time):

| Control | Default | Effect |
|--------|---------|--------|
| DashScope API Key | (blank → use bundled default) | Override the key compiled into the APK |
| Cascade toggle | **ON** | Off falls back to joint Qwen-Omni Realtime |
| Debug toggle | OFF | Show the rolling-log panel on the main screen |

The bundled LLM (qwen) has well-known, hardcoded endpoints in the
engines themselves; the ASR language hint is auto-detected — exposing
either as a user-editable field invites typos and doesn't actually
change behaviour. If you need to point at a non-default endpoint or
pick a different ASR language, build with the right `local.properties`
entries instead.

## Build

Requires JDK 17, Android SDK with platform 34, and a DashScope API key.

1. Get a key: [Bailian console](https://dashscope.console.aliyun.com/)
2. Put it in `local.properties` (gitignored):

   ```properties
   QWEN_API_KEY=sk-...
   ```

3. Build:

   ```bash
   ./gradlew :app:assembleDebug      # ~5.9 MB, debuggable
   ./gradlew :app:assembleRelease    # ~1.7 MB, R8-shrunk, signed
   ```

   Output: `app/build/outputs/apk/<variant>/app-<variant>.apk`. A
   `buildFinished` hook in `app/build.gradle` also copies both APKs up
   one level as `vokii-debug.apk` and `vokii-release.apk`.

The API key is read at configuration time and compiled into
`BuildConfig.DEFAULT_QWEN_API_KEY`; the resulting APK ships with a
working default — anyone who decompiles it can recover the key. That's
the trade-off for "fresh install just works". Override at runtime in
Settings; the override takes precedence over the bundled default.

The release keystore lives at `keystore/vokii-release.jks` and is
checked into this repo (a real release process would not do this; see
[Security notes](#security-notes)).

## Eval

`tools/eval/` is a reproducible ASR-accuracy + latency harness. It
scores against [BAAI/CS-Dialogue](https://huggingface.co/datasets/BAAI/CS-Dialogue)
(Chinese-English code-switch) with **MER (Mixed Error Rate)**: Chinese
by character, English by word, with NFKC / punctuation-stripped /
lowercased / 繁→简 normalization.

```bash
cd tools/eval
pip install -r requirements.txt  # websockets, soundfile, numpy, dashscope

# Cascade ASR step 1 — fun-asr-realtime MER on tier2 (n=240)
python cascade_step1_alt.py \
    --manifest manifest.tier2.jsonl \
    --model fun-asr-realtime \
    --report report.cascade.step1.funasr.tier2.json

# Cascade end-to-end latency — joint vs cascade2 on tier1 (n=30)
python cascade_latency.py \
    --manifest manifest.tier1.jsonl \
    --modes joint,cascade2 \
    --limit 30 \
    --report report.latency.tier1.n30.refresh.json
```

See `tools/eval/README.md` for the full driver list and
`tools/eval/REPORT.*.md` for the empirical write-ups.

## Repo layout

```
.
├── app/                         # Android Studio project (Java)
│   ├── build.gradle             #   BuildConfig fields from local.properties
│   ├── keystore/vokii-release.jks
│   └── src/main/java/com/vokii/translator/
│       ├── MainActivity.java    #   mic button, transcript card, status dot
│       ├── SettingsActivity.java
│       ├── TranslationController.java
│       ├── AsrEngineFactory.java
│       ├── ConfigStore.java     #   API key + cascade + debug toggles
│       ├── CascadeEngine.java   #   ◄── step 1 dispatch + step 2 dispatch
│       ├── ParaformerAsrClient.java  # ◄── DashScope ASR WebSocket
│       ├── QwenMtClient.java    #   ◄── qwen-plus chat completion (SSE)
│       ├── QwenOmniEngine.java  #   joint path (Settings → off)
│       ├── QwenOmniRealtimeClient.java
│       ├── AndroidSpeechEngine.java
│       ├── Bilingual.java       #   ZH/EN route on shared `ZH:`/`EN:` text
│       ├── Constants.java
│       ├── DebugLogger.java
│       └── CrashReporter.java
├── tools/eval/                  # ASR-accuracy + latency harness
│   ├── cascade_step1.py         #   cascade ASR score
│   ├── cascade_step1_alt.py     #   alternate-model A/B (fun-asr vs paraformer)
│   ├── cascade_step2.py         #   cascade MT score
│   ├── cascade_compare.py       #   paired win/tie/loss against a baseline
│   ├── cascade_latency.py       #   joint vs cascade2 TTFB / total / max_delta_gap
│   ├── eval.py                  #   v1 joint engine eval (pre-cascade)
│   ├── metrics.py
│   ├── qwen_client.py
│   ├── build_manifest.py
│   ├── select_tiers.py
│   └── REPORT.*.md              #   empirical write-ups
├── keystore/                    # (duplicate; app uses ../keystore/vokii-release.jks)
├── build.gradle                 # project-level (AGP)
└── local.properties             # (gitignored) QWEN_API_KEY
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

## Changelog

See [CHANGELOG.md](CHANGELOG.md). Latest: **v2.2.0** — cascade as
default + minimal settings UI.

## License

Code: see `LICENSE` (or add one). The CS-Dialogue dataset used by the
eval harness is gated and CC-BY-NC-SA-4.0 — it is **not** redistributed
in this repo; the harness reads the audio from a local extract.
