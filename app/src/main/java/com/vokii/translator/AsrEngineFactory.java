package com.vokii.translator;

import android.content.Context;

/**
 * Picks the ASR engine at runtime. Two production paths are available:
 *
 *   1. {@link CascadeEngine} (DEFAULT) — chained pipeline:
 *      fun-asr-realtime verbatim ASR → qwen-turbo text MT →
 *      ZH/EN pair. CS-Dialogue tier2 MER cut from 0.144 → 0.069 (-52 %);
 *      see tools/eval/REPORT.cascade.step1.md. Slightly higher first-
 *      delta latency (~100-300 ms) until the WS pool warms.
 *
 *   2. {@link QwenOmniEngine} (opt-in fallback via Settings) — joint cloud
 *      model that streams mic PCM to Qwen-Omni Realtime and returns ZH/EN
 *      in one stage. Works on HarmonyOS (no GMS/HMS dependency).
 *
 *   3. {@link NoOpEngine} (last resort) — surfaced when no API key is
 *      configured so the failure is obvious in the debug panel.
 */
public final class AsrEngineFactory {

    private AsrEngineFactory() {}

    public static AsrEngine create(Context ctx, ConfigStore config, SessionContext sessionContext,
                                   ToolRegistry toolRegistry, DebugLogger debug) {
        if (config.getApiKey().isEmpty()) {
            debug.log("ASR", "FATAL: no Qwen API key configured");
            return new NoOpEngine();
        }
        if (config.isCascadeMode()) {
            debug.log("ASR", "engine: Cascade (fun-asr-realtime → qwen-turbo)");
            return new CascadeEngine(ctx, config, sessionContext.session(), sessionContext,
                    toolRegistry, debug);
        }
        debug.log("ASR", "engine: Qwen-Omni Realtime (joint) — voice commands disabled");
        return new QwenOmniEngine(ctx, config, debug);
    }
}
