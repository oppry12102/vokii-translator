package com.vokii.translator;

import android.content.Context;

/**
 * Picks the ASR engine at runtime. Two production paths are available:
 *
 *   1. {@link CascadeEngine} (opt-in via Settings) — chained pipeline:
 *      Paraformer-realtime-v2 verbatim ASR → qwen-mt-plus text MT →
 *      ZH/EN pair. CS-Dialogue tier2 MER cut from 0.144 → 0.087 (-39 %);
 *      see tools/eval/REPORT.cascade.step1.md. Slightly higher first-
 *      delta latency (~100-300 ms) until the WS pool warms.
 *
 *   2. {@link QwenOmniEngine} (PRIMARY, default) — joint cloud model
 *      that streams mic PCM to Qwen-Omni Realtime and returns ZH/EN in
 *      one stage. Works on HarmonyOS (no GMS/HMS dependency).
 *
 *   3. {@link NoOpEngine} (last resort) — surfaced when no API key is
 *      configured so the failure is obvious in the debug panel.
 *
 * The on-device {@code android.speech.SpeechRecognizer} path was removed:
 * it returns unavailable on HarmonyOS (no GMS-backed RecognitionService),
 * which was the original source of the {@code -999} error.
 */
public final class AsrEngineFactory {

    private AsrEngineFactory() {}

    public static AsrEngine create(Context ctx, ConfigStore config, DebugLogger debug) {
        if (config.getApiKey().isEmpty()) {
            debug.log("ASR", "FATAL: no Qwen API key configured");
            return new NoOpEngine();
        }
        if (config.isCascadeMode()) {
            debug.log("ASR", "engine: Cascade (Paraformer-realtime-v2 → qwen-mt-plus)");
            return new CascadeEngine(ctx, config, debug);
        }
        debug.log("ASR", "engine: Qwen-Omni Realtime (joint)");
        return new QwenOmniEngine(ctx, config, debug);
    }
}
