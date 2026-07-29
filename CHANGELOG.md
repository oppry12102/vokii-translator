# Changelog

All notable changes to Vokii are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the
project adheres loosely to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added — experimental
- **History context (实验特性 toggle, default OFF)**: the last 6 committed
  turns — corrected (final) source text + their committed translations —
  are injected into the MT system prompt as a `CONVERSATION HISTORY
  (reference only)` section, for terminology / coreference / register
  consistency across turns. Settings-only (not in the voice catalog);
  spec drafts share the same prompt so drafts stay consistent with
  finals. Costs ~10–20 ms prefill per sentence; the cache structure is
  unchanged (the session-context tail was already dynamic). Validated on
  CS-Dialogue (`tools/eval/REPORT.mt_context_ab.md`, 8 sessions × 30
  turns, blind LLM-judge): on turns where history actually changed the
  translation (n=100), context wins 58:42 — modest positive, verbatim
  fidelity cost −0.4 pt. Stays experimental, default OFF.

## [v2.5.2] — 2026-07-29

### Performance
- **Spec-MT fires earlier**: `SPEC_MIN_CHARS` 6 → 3 after on-device latency
  re-measure showed the 6-char gate itself was the dominant first-draft
  cost (~0.73 s of the ~1.0 s from first ASR partial to first draft
  visible; spec TTFB only ~0.24 s). Expected first draft ~−30%.

### Eval
- **Next-gen ASR A/B** (tier3, n=1000): fun-asr-realtime-2026-02-28
  (+2.9% MER, not adopted) and qwen3-asr-flash-realtime (+34% MER,
  dropped — deficit survives filler-equivalence re-scoring, so it's
  genuine). fun-asr-realtime remains the step-1 default; DashScope
  realtime ASR model space exhausted. Report:
  `tools/eval/REPORT.step1.nextgen.tier3.md`; new OpenAI-Realtime protocol
  driver `cascade_step1_qwen3asr.py`.
- **Filler-equivalence secondary metric** (`mer_fe`): backchannel
  written-form noise (嗯哼→"uh-huh.") no longer counts as full error in
  the secondary track — full-set mean MER 0.112 → 0.095. Raw MER stays
  the headline. New runs emit it; `rescore_fillereq.py` backfills old
  reports offline.

### Verified on device (2026-07-29)
- Voice-command hallucination containment: spec-draft `toggle_mic`
  cancelled by P1 guard; final-MT `clear_transcript` on "现在做单元测试"
  dropped by GroundingCues + no-tools fallback retranslate — transcript
  survived. Final-MT residual TTFB measured: 585 ms.

## [v2.5.1] — 2026-07-28

### Eval
- **Full CS-Dialogue transcription** (n=6,186, all 30 speakers, 18.3 h):
  fun-asr-realtime avg MER 0.112 / median 0.020; 46.1% perfect, 0 errors.
  Report: `tools/eval/REPORT.cs_dialogue.full.md`.
- `cascade_step1.py` / `cascade_step1_alt.py`: added `--workers N` for
  parallel ASR eval (8 workers → 6,186 samples in ~55 min).

### Docs
- README: voice-command examples switched from zh↔ja to zh↔fr.
- README: Voice Commands table reformatted to 3 columns (Chinese command,
  English command, tool fired).

## [v2.5.0] — 2026-07-26

### Added — voice commands (13 → 16)
- **remember_term**: persist a term→translation glossary (e.g. "记住张三叫
  Zhang San", "人工智能简称AI"). Stored in SharedPreferences, loaded on
  startup, injected into the MT prompt so names/terms translate
  consistently. Also covers explicit voice corrections ("改一下张三叫 Lao
  Zhang", "更正") — same list, one category.
- **list_terms**: voice command to view the stored glossary as a card
  (the glossary is otherwise prompt-only / invisible).
- **set_font_size**: "字体变大/变小" — scale transcript text (clamp
  [0.85, 1.6]), persisted across sessions.

### Changed — MT latency
- **Connection warmup** (`QwenMtClient.warmup`): a fire-and-forget
  `max_tokens=1` request at engine start warms the shared OkHttp route
  and primes the qwen-turbo implicit context cache. Measured ~292 ms
  saved on the first MT turn (turn-1 only; steady-state unchanged).
  Quality-neutral — the dummy output is discarded.

### Eval
- `cascade_latency.py` now surfaces qwen-turbo cache-hit tokens
  (`mt_cached_tokens`). Finding: the implicit context cache hits ~97% of
  the static prompt prefix; only qwen-turbo (not qwen-plus/flash) on this
  account; streaming usage hides `cached_tokens` (provider quirk — measure
  TTFT A/B instead).

### Validation
- **240-test command-mis-fire gate** (CS-Dialogue tier-2, qwen-turbo):
  16-tool mis-fire 1/240 vs 13-tool baseline 3/240 — the 3 new commands
  caused zero mis-fires. (The same gate caught and reverted a prompt
  restructure that moved SESSION CONTEXT to the user message: 28/240
  mis-fires.)

## [v2.4.0] — 2026-07-25

### Verified — real-device (2026-07-25)
- **Audio path**: cascade pipeline (mic → fun-asr ASR → qwen-turbo MT →
  UI) confirmed working end-to-end on a real device, closing the
  "real-device verification" open item in
  `tools/eval/REPORT.latency.tier1.cascade2.md`. Mic capture and the
  device audio stack behave as expected — no regression vs the
  emulator-fed eval runs. (No quantitative capture-overhead numbers
  recorded this pass.)
- **Speculative-MT draft flow (P1)**: draft translations surface during
  speech and the final MT refines/commits smoothly — no flicker, no
  stale-draft clobbering observed on real audio. Closes the last
  device-verification blocker.

### Eval — production-fidelity MT latency
- `tools/eval/cascade_latency.py` gains a `cascade2_prod` mode that
  reproduces the production MT step (`MtPromptBuilder` prompt +
  `qwen-turbo` over the OpenAI-compat endpoint). n=30 tier1: the
  production path is **~20 % faster** to first bilingual text than the
  simplified `qwen-mt-plus` baseline — see
  `tools/eval/REPORT.latency.tier1.cascade2_prod.md`.

### Changed — transcript UX (no more jumping text)
- **Per-turn cards** replace the single all-in-one TextView: committed
  sentences are immutable views, so lines already on screen never move
  while new text streams in. Only the bottom "active" card (the sentence
  in flight) updates, in place.
- **Typewriter translation**: the target line reveals char-by-char
  (~25 chars/s baseline with adaptive burst catch-up) instead of landing
  as a paragraph. Speculative-MT revisions snap back to the changed
  prefix and resume typing.
- **Glide scrolling**: a frame-paced chase scroller (≈270 dp/s cap) eases
  the window to the bottom instead of `fullScroll` jumping. Scrolling up
  disengages follow mode; returning to the bottom re-engages it.
- **Command results moved out of the transcript**: short confirmations
  ("» Languages → zh↔ja") show on a dedicated one-line status hint under
  the transcript instead of in-text chips. Tap the status line to UNDO
  (replaces tap-chip-to-undo). Long-form results the user asks for
  (session summary, commands catalog) still appear as in-transcript note
  cards.
- **Sentence spacing**: a 12 dp gap now separates sentence pairs.

## [v2.3.0] — 2026-07-17

Voice-control subsystem + a top-to-bottom hardening pass. Speak natural
commands ("下面改成中日翻译", "只显示日文就好", "暂停", "总结一下",
"重新翻译上一句") and the MT LLM fires tools that change languages,
display mode, style/temperature, mic pause, log level, and more — no
Settings taps needed. Shipped alongside fixes for the concurrency,
resource-leak, and correctness issues a full review turned up.

> **Release policy** (unchanged from v2.2.0): GitHub releases ship
> **source only** — no APK assets. Build locally with
> `./gradlew :app:assembleRelease` after putting a DashScope key in
> `local.properties`. The bundled `QWEN_API_KEY` is recoverable from any
> APK that ships it, so the public-release APK is treated as
> leaked-by-default.

### Added — voice commands
- 13 tools (registered in `ToolRegistry`): `set_translation_languages`,
  `set_display_mode`, `toggle_cascade`, `toggle_debug`, `toggle_mic`,
  `set_translation_mode` (style + temperature), `set_log_level`,
  `get_current_settings`, `clear_transcript`, `export_transcript`,
  `summarize_session`, `re_translate_last`, `list_commands`.
- `SessionContext` — injects current state + recent commands + recent
  utterances into the MT prompt so the LLM can disambiguate ("改成中文"
  → which slot?). Backed by bounded ring buffers.
- `ToolCallAccumulator` — reassembles streamed `delta.tool_calls`
  fragments (split across many SSE chunks) into complete `ToolCall`s.
- `MtPromptBuilder` — builds the system prompt + OpenAI-compat tools
  array from the live `SessionConfig` / `ToolRegistry`.
- `ToolDispatcher` — applies a batch of tool calls to `SessionConfig` /
  `ConfigStore`, returning `CommandResult`s (never throws; bad calls
  become "rejected" chips).
- `TranscriptStore` — persists the transcript to SharedPreferences
  (survives process death), capped at 200 turns.
- `Turn` / `TurnParser` — replaces the deleted `Bilingual` class;
  parses any language pair (`ZH:`/`JA:`/…), with a Han-count safety net
  for the zh↔en swap the model still occasionally produces.
- An inject panel (debug builds only) for exercising the full tool chain
  without recording audio.

### Added — test coverage (new)
- 41 JUnit tests: `TurnParser`, `ToolCallAccumulator` (incl. the
  JSON-null regression), `SessionContext` (incl. concurrent read/write
  stress), `Json`, `CommandResult` builder invariants.

### Changed — architecture
- `CommandResult` refactored from 13 positional booleans to
  `EnumSet<Effect>` + `Builder` with invariant validation;
  `MainActivity.onCommand` dispatches via a `switch` with a throwing
  `default` (a forgotten case now fails loudly instead of silent no-op).
- `MtRunner` — shared single-thread MT executor + client factory,
  unifying cascade step 2 / re-translate / summarize (was 3 separate
  executors, one created per call).
- `Json` — null-safe `optString` helper; replaces all 28 `optString`
  call sites (org.json returns the literal `"null"` for a JSON null,
  which silently corrupted language codes / transcript columns / tool
  args).
- `LangDisplay` — single source of truth for language-code → name.
- `OkHttpClient` is now a process-wide singleton in `QwenMtClient`,
  `ParaformerAsrClient`, and `QwenOmniRealtimeClient` (was one per MT
  turn → dispatcher-thread + connection-pool leak).

### Fixed — correctness & concurrency
- `SessionContext` ArrayDeque race (prompt built on the MT worker while
  record* mutated on the UI thread) → synchronized snapshots.
- `AudioRecord` release race in `CascadeEngine` / `QwenOmniEngine` —
  the capture thread now holds a local ref so `stop()` nulling the field
  can't skip `release()` and leak the native mic.
- `ToolCallAccumulator` no longer appends literal `"null"` for
  JSON-null `arguments` (broke every tool call when the server emitted
  the closing `{"arguments":null}` fragment).
- `SetTranslationMode` validates temperature **before** mutating style
  (was: apply style, then reject on bad temperature → state changed
  with no UNDO and a lying "rejected" chip).
- `SetTranslationLanguages` auto-flip now only triggers when the
  requested language is the one being spoken; third-language requests
  reject with disambiguation instead of silently picking the wrong
  source.
- `retranslateLastTurn` re-validates the captured turn index before
  `history.set` (clear/new-turn during the async MT call could IOOBE or
  clobber an unrelated turn).
- `CommandHistoryEntry` `SimpleDateFormat` → `ThreadLocal` (rendered
  from both UI and MT threads).
- `QwenOmniRealtimeClient` fires `onReady` on `session.updated` ack (not
  on open), flushes `turnBuf` on close/failure, shares `OkHttpClient`.
- WS send paths use the `onOpen` `webSocket` param instead of the
  mutable `ws` field (NPE race with `close()` left the engine stuck in
  "preparing").
- `QwenMtClient` gains a 120 s `callTimeout` (a stalled SSE can no
  longer hang the shared executor) + `cancel()` (stop() aborts in-flight
  MT instead of letting it run on).
- `CascadeEngine` half-start cleanup when `AudioRecord` init fails
  (previously left `started=true` with an open socket and no mic).
- `clear_transcript` no longer advertises a no-op UNDO.

### Fixed — fun-asr −20 % MER reclaimed
- The v2.2.0 build carried a stale comment claiming `fun-asr-realtime`
  rejected the Java run-task payload ("format is empty", WS 1007) and
  pinned `paraformer-realtime-v2` as default. Captured the Python
  dashscope SDK's actual on-wire frame (`tools/eval/capture_funasr_frame.py`)
  and confirmed `ParaformerAsrClient.sendStartTask` is byte-identical;
  the failure described the pre-port OpenAI-Realtime `session.update`
  payload, not the current DashScope task protocol.
  `DEFAULT_ASR_MODEL` flipped back to `fun-asr-realtime`.

### Removed (dead config / code)
- DeepSeek `BuildConfig` fields (`DEFAULT_DEEPSEEK_*`, `DEEPSEEK_MODEL_*`)
  — v2 is Qwen-only.
- `agcp` Gradle plugin classpath + Huawei Maven repositories — no HMS.
- HMS `meta-data` from the manifest; `usesCleartextTraffic=true` →
  `false`; `network_security_config` tightened (cleartext off globally,
  stale `api.minimax.chat` domain removed).
- `SetTranslationTemperature` tool (unregistered, subsumed by
  `set_translation_mode`) and `BuiltInTools.withName` (unused).
- `MainActivity` duplicate `toolRegistry`/`toolDispatcher` init.
- Stale docs (cascade default, "opt-in" engine factory, `Bilingual`
  javadoc references).

### Empirical numbers (unchanged targets, now actually shipping)

CS-Dialogue tier2 n=240 — transcription MER (the optimization target):

| Path | avg MER | Δ vs joint |
|------|--------:|-----------:|
| v1 joint (Qwen-Omni Realtime) | 0.1436 | — |
| cascade + paraformer-realtime-v2 step 1 | 0.0869 | −39 % |
| **cascade + fun-asr-realtime step 1** (default since v2.3.0) | **0.0693** | **−52 %** |

versionCode 4 → 5, versionName "2.2.0" → "2.3.0".

## [v2.2.0] — 2026-07-16

Cascade pipeline ships as the **default** behaviour. Two-stage
mic → ASR → MT replaces the joint Qwen-Omni call. Settings UI is
slimmed to only the controls that actually change behaviour.

> **Release policy**: as of v2.2.0, GitHub releases ship **source only**
> — no APK assets attached. Build locally with `./gradlew :app:assembleRelease`
> after putting a DashScope key in `local.properties`. The bundled QWEN_API_KEY
> is recoverable from any APK that ships it, so we treat the public-release
> APK as leaked-by-default and don't fan the binary out from a tag.
> The `keystore/vokii-release.jks` committed to this repo is for local
> development-build reproducibility only — not a distribution channel.

### Added
- `CascadeEngine` — orchestrates the chained pipeline (`mic → step 1
  ASR → step 2 MT → bilingual UI`). Implements `AsrEngine` so it
  drops into the existing factory + controller without UI changes.
- `ParaformerAsrClient` — DashScope WebSocket client for
  `fun-asr-realtime` (and other compatible ASR models like
  `paraformer-realtime-v2`). Custom task protocol with
  header/payload envelope + raw binary audio frames + `event`-typed
  result frames.
- `QwenMtClient` — qwen-plus chat-completion client via
  OpenAI-compatible `/compatible-mode/v1/chat/completions` with SSE
  streaming. Per-chunk `onPartial` drives incremental UI; `onResult`
  folds the card into history.
- Per-sentence commit detection in `ParaformerAsrClient.handleEvent`:
  handles `output.sentence` as a single dict (the wire shape — not
  an array), parses `sentence_end` / `end_time`, fires
  `onResult(text)` when VAD finalizes a sentence.
- Eval scripts: `cascade_step1.py`, `cascade_step1_alt.py`,
  `cascade_step2.py`, `cascade_compare.py`, `cascade_latency.py`.
- Eval reports: `REPORT.cascade.step1/2/3.md`,
  `REPORT.latency.tier1.md`, `REPORT.latency.tier1.cascade2.md`,
  `REPORT.tier1.ab_round1.md`, `REPORT.tier2.ab_round2.md`.

### Changed
- `Constants.DEFAULT_CASCADE_MODE`: `false` → **`true`**. Fresh
  installs land on cascade.
- `SettingsActivity`: surface trimmed to (a) DashScope API key,
  (b) Cascade toggle, (c) Debug toggle. Endpoint URL and ASR
  language hint removed.
- Layout order: Cascade switch above Debug switch (both under the
  API key input).
- Settings toggle labels shortened: "Cascade (Paraformer →
  qwen-mt-plus)" → "Cascade"; "Show debug panel" → "Debug".
- `QwenMtClient`: switched from `qwen-mt-plus` (native DashScope SDK
  only, not in the OpenAI-compat catalog — returns empty body) to
  `qwen-plus` (in the compat catalog; `cascade_step2.py` was already
  using it).
- versionCode 3 → 4, versionName "2.1" → "2.2.0".

### Removed (dead config)
- `Constants.KEY_ASR_LANG`, `Constants.DEFAULT_ASR_LANG` — never
  consumed by either Cascade or joint engine.
- `ConfigStore.getAsrLang` / `setAsrLang` — same reason.
- `setting_endpoint` / `setting_endpoint_hint` strings — endpoint
  is hardcoded for the bundled model.
- `setting_cascade_desc` string — superseded by the verbal
  README eval results.

### Empirical numbers

CS-Dialogue tier2 n=240 (conclusion run):

| Path | avg MER | Δ vs joint |
|------|--------:|-----------:|
| v1 joint (Qwen-Omni Realtime) | 0.1436 | — |
| cascade + paraformer-realtime-v2 step 1 | 0.0869 | −39 % |
| **cascade + fun-asr-realtime step 1** | **0.0693** | **−52 %** |

CS-Dialogue tier1 n=30 latency (real-time pacing):

| | joint | cascade2 | speedup |
|---|------:|---------:|--------:|
| TTFB median | 2.97 s | **0.49 s** | **6.05 ×** |
| Total median | 16.32 s | 5.89 s | 2.77 × |
| Total p95 | 44.68 s | 17.46 s | 2.56 × |

Full write-ups in `tools/eval/REPORT.cascade.step{1,2,3}.md`,
`tools/eval/REPORT.latency.tier1.cascade2.md`,
`tools/eval/REPORT.latency.tier1.md`.

### Notes for existing users

- Existing installs preserve their previous `cascade_mode` preference
  via `SharedPreferences`. To force the new default, clear app data
  once (`adb shell pm clear com.vokii.translator`) or toggle Cascade
  in Settings.

## [v2.1.0] — 2026-07-13

Initial commit. Joint Qwen-Omni Realtime pipeline + CS-Dialogue
eval harness + minimal settings. Flash vs Pro eval showed
−39 % MER for the Pro model on tier2 (n=240). Single-stage
end-to-end latency on tier1 (n=30): TTFB median ≈ 2.9 s, total
median ≈ 16.5 s.

[v2.4.0]: https://github.com/oppry12102/vokii-translator/releases/tag/v2.4.0
[v2.3.0]: https://github.com/oppry12102/vokii-translator/releases/tag/v2.3.0
[v2.2.0]: https://github.com/oppry12102/vokii-translator/releases/tag/v2.2.0
[v2.1.0]: https://github.com/oppry12102/vokii-translator/tree/7a52129
