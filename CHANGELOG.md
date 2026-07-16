# Changelog

All notable changes to Vokii are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the
project adheres loosely to [Semantic Versioning](https://semver.org/).

## [v2.2.0] — 2026-07-16

Cascade pipeline ships as the **default** behaviour. Two-stage
mic → ASR → MT replaces the joint Qwen-Omni call. Settings UI is
slimmed to only the controls that actually change behaviour.

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

[v2.2.0]: https://github.com/oppry12102/vokii-translator/releases/tag/v2.2.0
[v2.1.0]: https://github.com/oppry12102/vokii-translator/tree/7a52129
