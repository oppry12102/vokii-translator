# Vokii

Android voice translator: speak in Chinese or English, see the other
language live as you talk. You can also speak control commands ("下面
改成中法翻译", "暂停", "总结一下") and they fire as LLM tool calls —
see [Voice commands](#voice-commands). Cloud-only — no on-device speech
model, no GMS/HMS dependency.

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
  └─ step 2 ─► qwen-turbo chat completion
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
chat endpoint, paying an extra ~0.7 s TTFB per committed sentence. Step 2
opens with a one-token warmup request at session start, so the first real
MT call skips the cold TCP+TLS handshake and hits a primed qwen-turbo
context cache (~0.3 s off the first sentence, quality-neutral).

A toggle in Settings switches back to the **joint** Qwen-Omni Realtime
path if needed (e.g. regions without fun-asr-realtime coverage).

### Why cascade wins

On CS-Dialogue tier2 (n=240, code-switch zh+en conversation):

| Path | avg MER | Δ vs joint |
|------|--------:|-----------:|
| v1 joint (Qwen-Omni Realtime) | 0.1436 | — |
| cascade1 (paraformer-realtime-v2 step 1) | 0.0869 | **−39 %** |
| **cascade + fun-asr-realtime step 1** | **0.0693** | **−52 %** |

On the **full CS-Dialogue dev set** (n=6,186, all 30 speakers, 18.3 h audio):

| Metric | Value |
|--------|-------|
| **MER** mean / median | **0.112** / **0.020** |
| CER (Chinese char) | 0.098 (n=4,443) |
| WER (English word) | 0.169 (n=2,904) |
| Perfect (MER = 0) | 46.1 % |
| MER ≤ 0.1 | 76.1 % |
| MER ≤ 0.2 | 87.2 % |
| Errors / empty outputs | 0 / 0 |

And on tier1 (n=30, real-time pacing):

| | joint | cascade2 | speedup |
|---|------:|---------:|--------:|
| **TTFB median** | 2.97 s | **0.49 s** | **6.05 ×** |
| Total median | 16.32 s | **5.89 s** | 2.77 × |
| Total p95 | 44.68 s | 17.46 s | 2.56 × |

Cascade is a **no-trade-off win** — both more accurate AND faster on
every metric that matters to the user. See
`tools/eval/REPORT.cs_dialogue.full.md` for the full-dataset breakdown,
`tools/eval/REPORT.latency.tier1.cascade2.md` and
`tools/eval/REPORT.cascade.step3.md` for the latency and step-3 reports.

## Reading experience

The transcript is built for reading while listening, not just logging:

- **Live caption**: the verbatim ASR partial lands at ~0.45 s (fun-asr
  TTFB) as a `› …` line while you're still speaking; the bilingual card
  takes over the same slot once MT streams.
- **Stable lines**: each committed sentence is an immutable card — lines
  already on screen never move or re-wrap. Only the bottom in-flight
  card updates, in place.
- **Typewriter translation**: the target line reveals char-by-char
  (~25 chars/s with adaptive burst catch-up) instead of landing as a
  paragraph. Speculative-MT drafts refine in place — a revision snaps
  back to the changed prefix and resumes typing.
- **Glide scrolling**: a frame-paced chase scroller eases the window to
  the bottom (~270 dp/s cap) instead of jumping. Scroll up to read back
  and follow mode disengages until you return to the bottom.
- **Status line**: voice-command confirmations ("» Languages → zh↔fr")
  show on a one-line hint under the transcript — tap it to undo. The
  transcript itself stays pure conversation (session summaries and the
  commands catalog still appear in-line as note cards).
- 12 dp of breathing room between sentence pairs.

## Settings

Two real knobs (everything else baked into BuildConfig at compile time):

| Control | Default | Effect |
|--------|---------|--------|
| DashScope API Key | (blank → use bundled default) | Override the key compiled into the APK |
| Cascade toggle | **ON** | Off falls back to joint Qwen-Omni Realtime |
| Debug toggle | OFF | Show the rolling-log panel on the main screen |
| 实验特性 · History context | **OFF** | Inject the last 6 committed turns (corrected source + committed translation) into the MT prompt as a `CONVERSATION HISTORY` section — for terminology / coreference / register consistency across turns. Full-scale CS-Dialogue A/B (6,186 turns, blind LLM judge): weak translation-side win, but verbatim-line contamination without mitigation — see `tools/eval/REPORT.mt_context_ab.md`. Costs ~10–20 ms prefill per sentence. |

The bundled LLM (qwen) has well-known, hardcoded endpoints in the
engines themselves; the ASR language hint is auto-detected — exposing
either as a user-editable field invites typos and doesn't actually
change behaviour. If you need to point at a non-default endpoint or
pick a different ASR language, build with the right `local.properties`
entries instead.

## Voice commands

Besides translating speech, Vokii listens for natural-language control
commands and runs them as LLM tool calls — no Settings taps needed. The
MT model (`qwen-turbo`) is given the live session state plus a tool
catalog; when the user says something that's a control intent rather
than a phrase to translate, it emits `tool_calls` instead of a
translation. Examples (Chinese or English both work):

| Chinese command | English command | Tool fired |
|-----------------|-----------------|------------|
| 下面改成中法翻译 | translate to French | `set_translation_languages` (auto-flips direction) |
| 只显示法文就好 | show French only | `set_display_mode` |
| 翻译得更文雅一些 | — | `set_translation_mode` (style) |
| 温度调到 0.7 | — | `set_translation_mode` (temperature) |
| 暂停 | pause | `toggle_mic` (engine stays warm, capture skips) |
| 切换到普通模式 | switch to joint | `toggle_cascade` (next mic tap) |
| 打开调试 | open debug | `toggle_debug` |
| 把日志设成详细模式 | — | `set_log_level` |
| 现在是什么设置？ | — | `get_current_settings` |
| 清空翻译 | — | `clear_transcript` |
| 复制到剪贴板 | — | `export_transcript` |
| 总结一下 | — | `summarize_session` |
| 重新翻译上一句 | — | `re_translate_last` |
| 你能做什么？ | help | `list_commands` |
| 记住张三叫 Zhang San | — | `remember_term` (持久化专有名词) |
| 人工智能简称AI | — | `remember_term` |
| 改一下张三叫 Lao Zhang | — | `remember_term` (同一清单，语音改错) |
| 有哪些术语 | what did you remember | `list_terms` |
| 字体变大 | — | `set_font_size` |
| 字小一点 | — | `set_font_size` |

State-changing commands carry a snapshot so the most recent one is
undoable — tap the status line under the transcript (the same line that
shows the short "» …" confirmation). `SessionContext` feeds the
current state + recent commands + recent utterances back into the
prompt so follow-ups like "改成中文" (change to *what*?) can be
disambiguated. Destructive commands (`clear_transcript`) are not
undoable.

`remember_term` builds a persistent glossary (SharedPreferences, loaded
on startup) that is injected into the MT prompt's session context, so
names and terms translate consistently on every subsequent turn — it also
absorbs explicit voice corrections ("改一下…", "更正…"). Query the list
any time with `list_terms` ("有哪些术语").

A debug-only inject panel (hidden in release builds) lets you exercise
the full tool chain by typing a phrase instead of speaking.

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

GitHub releases ship **source only** — no APK binaries attached. Build
locally from a tagged checkout to install. The reason: the release APK
embeds the DashScope key from `local.properties` and is reproducible
from the public sources, so fan-out from a GitHub release adds no
value and just creates more copies of a leaked-by-construction key
(see [Security notes](#security-notes)).

The API key is read at configuration time and compiled into
`BuildConfig.DEFAULT_QWEN_API_KEY`; the resulting APK ships with a
working default — anyone who decompiles it can recover the key. That's
the trade-off for "fresh install just works". Override at runtime in
Settings; the override takes precedence over the bundled default.

The release keystore lives at `keystore/vokii-release.jks` (gitignored)
with its passwords in `local.properties` — it is NOT committed. See
[Security notes](#security-notes).

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
│   └── src/main/java/com/vokii/translator/
│       ├── MainActivity.java    #   mic button, per-turn transcript cards, status hint + dot
│       ├── SettingsActivity.java
│       ├── TranslationController.java
│       ├── AsrEngineFactory.java
│       ├── ConfigStore.java     #   API key + cascade + debug toggles
│       ├── CascadeEngine.java   #   ◄── step 1 dispatch + step 2 dispatch
│       ├── ParaformerAsrClient.java  # ◄── DashScope ASR WebSocket
│       ├── QwenMtClient.java    #   ◄── qwen-turbo chat completion (SSE)
│       ├── QwenOmniEngine.java  #   joint path (Settings → off)
│       ├── QwenOmniRealtimeClient.java
│       ├── MtRunner.java        #   shared MT executor + client factory
│       ├── Turn.java / TurnParser.java  #  bilingual ZH/EN (any pair) parse
│       ├── BuiltInTools.java    #   the 16 voice tools
│       ├── ToolRegistry.java / ToolDispatcher.java
│       ├── ToolCall.java / ToolCallAccumulator.java
│       ├── CommandResult.java   #   EnumSet<Effect> + Builder
│       ├── SessionConfig.java / SessionContext.java
│       ├── MtPromptBuilder.java
│       ├── TranscriptStore.java
│       ├── Json.java / LangDisplay.java   #   null-safe opt / lang names
│       ├── Constants.java
│       ├── DebugLogger.java
│       └── CrashReporter.java
├── tools/eval/                  # ASR-accuracy + latency harness
│   ├── cascade_step1.py         #   cascade ASR score
│   ├── cascade_step1_alt.py     #   alternate-model A/B (fun-asr vs paraformer)
│   ├── cascade_step2.py         #   cascade MT score
│   ├── cascade_compare.py       #   paired win/tie/loss against a baseline
│   ├── cascade_latency.py       #   joint vs cascade2 TTFB / total / max_delta_gap
│   ├── capture_funasr_frame.py  #   dump the SDK's on-wire ASR start-task frame
│   ├── eval.py                  #   v1 joint engine eval (pre-cascade)
│   ├── metrics.py
│   ├── qwen_client.py
│   ├── build_manifest.py
│   ├── select_tiers.py
│   └── REPORT.*.md              #   empirical write-ups
├── keystore/                    # vokii-release.jks (gitignored, rotated 2026-07-26)
├── build.gradle                 # project-level (AGP)
└── local.properties             # (gitignored) QWEN_API_KEY
```

## Security notes

- `local.properties` is gitignored. Don't commit it.
- The QWEN_API_KEY in `BuildConfig` is recoverable from the APK. The
  build is configured to compile it in for "fresh install just works".
  If you distribute the APK publicly, treat the bundled key as leaked
  and rotate it.
- The release keystore (`keystore/vokii-release.jks`) is gitignored — it
  used to be committed, which burned the app's signing identity. It was
  removed from git history and the key rotated 2026-07-26 (current cert
  SHA-256 `37:9E:2A:…`). Generate/sign locally; a real release process
  would store it in CI secrets.

## Changelog

See [CHANGELOG.md](CHANGELOG.md). Latest tagged: **v2.5.2** — earlier
spec-MT drafts (device-measured −30% first-draft latency), next-gen ASR
A/B (fun-asr holds), and the filler-equivalence secondary MER metric.
The former `v2-cascade` development branch is merged into `main`,
which is now the mainline.

## License

Code: see `LICENSE` (or add one). The CS-Dialogue dataset used by the
eval harness is gated and CC-BY-NC-SA-4.0 — it is **not** redistributed
in this repo; the harness reads the audio from a local extract.
