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
    public static final String KEY_ASR_LANG = "asr_lang";
    public static final String KEY_DEBUG_VISIBLE = "debug_visible";
    public static final String KEY_CASCADE_MODE = "cascade_mode";

    /** Default sampling temperature. */
    public static final float DEFAULT_TEMPERATURE = 0.3f;

    /** Default ASR language hint. Both Chinese and English are enabled. */
    public static final String DEFAULT_ASR_LANG = "zh en";

    /** Whether the bottom debug panel is visible by default. */
    public static final boolean DEFAULT_DEBUG_VISIBLE = false;

    /** Whether to use the cascade (Paraformer→qwen-mt-plus) pipeline.
     *  Default off so existing users keep the joint Qwen-Omni behaviour.
     *  Toggle from Settings after the v0 ship has been validated on tier2. */
    public static final boolean DEFAULT_CASCADE_MODE = false;
}