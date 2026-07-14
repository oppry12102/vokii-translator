package com.vokii.translator;

import android.content.Context;

/**
 * Picks the ASR engine at runtime. The active pipeline is cloud-based:
 *
 *   1. {@link QwenOmniEngine} (PRIMARY) — captures mic PCM on-device and
 *      streams it to Qwen-Omni Realtime (DashScope) over a WebSocket. The
 *      cloud model does recognition AND translation in one stage, so this
 *      works on GMS/HMS-less ROMs (e.g. HarmonyOS running Android apps),
 *      where the platform {@code SpeechRecognizer} reports "no engine".
 *
 *   2. {@link NoOpEngine} (last resort) — surfaced as a clear "no ASR"
 *      error when no API key is configured, so the failure is obvious in
 *      the debug panel instead of a silent hang.
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
        debug.log("ASR", "engine: Qwen-Omni Realtime");
        return new QwenOmniEngine(ctx, config, debug);
    }
}
