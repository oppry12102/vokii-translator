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
    public static final String KEY_SRC_LANG = "src_lang";
    public static final String KEY_TGT_LANG = "tgt_lang";
    public static final String KEY_DISPLAY_MODE = "display_mode";
    /** Serialized transcript history (JSON array of turns). */
    public static final String KEY_TRANSCRIPT = "transcript_history";
    /** Serialized term→translation glossary (JSON object) for remember_term. */
    public static final String KEY_GLOSSARY = "glossary";
    /** Transcript font-scale multiplier (float) for set_font_size. */
    public static final String KEY_FONT_SCALE = "font_scale";

    /** Default sampling temperature. */
    public static final float DEFAULT_TEMPERATURE = 0.3f;

    /** Whether the bottom debug panel is visible by default. */
    public static final boolean DEFAULT_DEBUG_VISIBLE = false;

    /** Default source / target languages for the cascade MT prompt.
     *  "auto" = the LLM auto-detects the spoken language (Chinese or
     *  English) and outputs both labels regardless. tgt "en" is the
     *  canonical "second" language for display pairing. */
    public static final String DEFAULT_SRC_LANG = "auto";
    public static final String DEFAULT_TGT_LANG = "en";

    /** Default display mode. Values: "both" | "source_only" | "target_only". */
    public static final String DEFAULT_DISPLAY_MODE = "both";

    /** Default transcript font-scale (1.0 = baseline 16sp). Clamped to
     *  [0.85, 1.6] by SessionConfig. */
    public static final float DEFAULT_FONT_SCALE = 1.0f;

    /** Whether to use the cascade (fun-asr-realtime → qwen-turbo) pipeline.
     *  Default ON: tier2 MER cuts 0.1436 → 0.0693 (-52% relative to the
     *  joint Qwen-Omni path) and median TTFB is 6× faster on tier1
     *  (cascade_latency.py n=30). Toggle from Settings if the user
     *  needs to fall back to the joint path. */
    public static final boolean DEFAULT_CASCADE_MODE = true;
}