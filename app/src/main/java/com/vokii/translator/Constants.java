package com.vokii.translator;

/**
 * SharedPreferences keys and string identifiers. The actual default
 * values (endpoint URL, model name, API key) live in BuildConfig — they
 * are populated at build time from local.properties so secrets never
 * touch the tracked source tree.
 */
public final class Constants {

    private Constants() {}

    /** SharedPreferences name. */
    public static final String PREFS = "vokii_prefs";

    public static final String KEY_ENDPOINT = "endpoint";
    public static final String KEY_MODEL = "model";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_TEMPERATURE = "temperature";
    public static final String KEY_DEBUG_VISIBLE = "debug_visible";
    public static final String KEY_CASCADE_MODE = "cascade_mode";

    /** Default sampling temperature. */
    public static final float DEFAULT_TEMPERATURE = 0.3f;

    /** Whether the bottom debug panel is visible by default. */
    public static final boolean DEFAULT_DEBUG_VISIBLE = false;

    /** Whether to use the cascade (fun-asr-realtime → qwen-plus) pipeline.
     *  Default ON: tier2 MER cuts 0.1436 → 0.0693 (-52% relative to the
     *  joint Qwen-Omni path) and median TTFB is 6× faster on tier1
     *  (cascade_latency.py n=30). Toggle from Settings if the user
     *  needs to fall back to the joint path. */
    public static final boolean DEFAULT_CASCADE_MODE = true;
}