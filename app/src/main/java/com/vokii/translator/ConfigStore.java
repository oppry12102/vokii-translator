package com.vokii.translator;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * Persistent configuration store. Values fall back to {@link BuildConfig}
 * defaults (populated at build time from local.properties) when the user
 * hasn't overridden them in the settings screen.
 *
 * Security: the bundled API key is shipped inside the APK. Anyone who
 * decompiles the binary can recover it. The settings UI lets a user
 * paste their own key; that override takes precedence over the bundled
 * default and is persisted to {@link android.content.SharedPreferences}.
 */
public class ConfigStore {

    private final SharedPreferences prefs;

    public ConfigStore(Context ctx) {
        this.prefs = ctx.getApplicationContext()
                .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
    }

    public String getEndpoint() {
        return prefs.getString(Constants.KEY_ENDPOINT, BuildConfig.DEFAULT_QWEN_ENDPOINT);
    }

    public void setEndpoint(String v) {
        prefs.edit().putString(Constants.KEY_ENDPOINT,
                safe(v, BuildConfig.DEFAULT_QWEN_ENDPOINT)).apply();
    }

    public String getModel() {
        // Default to Pro: CS-Dialogue eval (N=240) showed Pro MER 0.100 vs
        // Flash 0.163 (–39%), with 2x the perfect-clip count. Worth the
        // extra cost / latency. Users can downgrade to Flash from Settings.
        return prefs.getString(Constants.KEY_MODEL, BuildConfig.QWEN_MODEL_PLUS);
    }

    public void setModel(String v) {
        // Empty → fall back to Pro. Anything else is stored verbatim so
        // custom model ids the user types survive across restarts.
        prefs.edit().putString(Constants.KEY_MODEL,
                safe(v, BuildConfig.QWEN_MODEL_PLUS)).apply();
    }

    /**
     * Active API key. Order of precedence:
     *   1. SharedPreferences override (user-typed value)
     *   2. BuildConfig.DEFAULT_QWEN_API_KEY (bundled default from
     *      local.properties at build time)
     * The bundled default ships in the APK; the settings UI exposes an
     * EditText pre-filled with it that the user can blank to force
     * fallback to (2), or replace to override.
     */
    public String getApiKey() {
        String userKey = prefs.getString(Constants.KEY_API_KEY, "");
        if (!TextUtils.isEmpty(userKey)) return userKey.trim();
        return BuildConfig.DEFAULT_QWEN_API_KEY;
    }

    /**
     * Store an API key override. Empty string clears the override so the
     * bundled default takes over again on next read.
     */
    public void setApiKey(String v) {
        prefs.edit().putString(Constants.KEY_API_KEY, v == null ? "" : v.trim()).apply();
    }

    /**
     * Returns just the user-typed override, or empty string if none has
     * been set. The settings UI uses this so it never has to display the
     * bundled default value (which would defeat the point of pre-filling
     * the key at build time).
     */
    public String getApiKeyForUi() {
        return prefs.getString(Constants.KEY_API_KEY, "");
    }

    public float getTemperature() {
        return prefs.getFloat(Constants.KEY_TEMPERATURE, Constants.DEFAULT_TEMPERATURE);
    }

    public void setTemperature(float v) {
        prefs.edit().putFloat(Constants.KEY_TEMPERATURE, v).apply();
    }

    public boolean isDebugVisible() {
        return prefs.getBoolean(Constants.KEY_DEBUG_VISIBLE, Constants.DEFAULT_DEBUG_VISIBLE);
    }

    public void setDebugVisible(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_DEBUG_VISIBLE, v).apply();
    }

    /** Cascade (fun-asr→qwen-turbo) mode toggle. Default ON (see
     *  {@link Constants#DEFAULT_CASCADE_MODE}) — the cascade pipeline wins
     *  on transcription MER per the CS-Dialogue eval. Users can fall back
     *  to the joint Qwen-Omni path from Settings. */
    public boolean isCascadeMode() {
        return prefs.getBoolean(Constants.KEY_CASCADE_MODE, Constants.DEFAULT_CASCADE_MODE);
    }

    public void setCascadeMode(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_CASCADE_MODE, v).apply();
    }

    /** Experimental: when ON, the last few committed turns (corrected
     *  source + committed translation) are injected into the MT system
     *  prompt as conversation-history context, for terminology / register
     *  consistency across turns. Default OFF ({@link
     *  Constants#DEFAULT_MT_HISTORY_CONTEXT}). */
    public boolean isMtHistoryContext() {
        return prefs.getBoolean(Constants.KEY_MT_HISTORY_CONTEXT,
                Constants.DEFAULT_MT_HISTORY_CONTEXT);
    }

    public void setMtHistoryContext(boolean v) {
        prefs.edit().putBoolean(Constants.KEY_MT_HISTORY_CONTEXT, v).apply();
    }

    /** Source language code for the MT prompt (e.g. "zh", "en", "ja").
     *  BCP-47 short codes; the MT prompt builder uppercases them for label
     *  generation (e.g. "zh" -> "ZH:"). */
    public String getSourceLang() {
        return prefs.getString(Constants.KEY_SRC_LANG, Constants.DEFAULT_SRC_LANG);
    }

    public void setSourceLang(String v) {
        prefs.edit().putString(Constants.KEY_SRC_LANG,
                safe(v, Constants.DEFAULT_SRC_LANG)).apply();
    }

    public String getTargetLang() {
        return prefs.getString(Constants.KEY_TGT_LANG, Constants.DEFAULT_TGT_LANG);
    }

    public void setTargetLang(String v) {
        prefs.edit().putString(Constants.KEY_TGT_LANG,
                safe(v, Constants.DEFAULT_TGT_LANG)).apply();
    }

    /** Display mode: "both" / "source_only" / "target_only". */
    public String getDisplayMode() {
        return prefs.getString(Constants.KEY_DISPLAY_MODE, Constants.DEFAULT_DISPLAY_MODE);
    }

    public void setDisplayMode(String v) {
        String normalized = "source_only".equals(v) || "target_only".equals(v) ? v : "both";
        prefs.edit().putString(Constants.KEY_DISPLAY_MODE, normalized).apply();
    }

    public void reset() {
        prefs.edit().clear().apply();
    }

    /** Term→translation glossary built by the remember_term command. Stored
     *  as a JSON object string; returns an empty map on missing/invalid. */
    public java.util.Map<String, String> getGlossary() {
        String json = prefs.getString(Constants.KEY_GLOSSARY, "{}");
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = o.optString(k, "");
                if (!v.isEmpty()) out.put(k, v);
            }
        } catch (org.json.JSONException ignored) {
            // Corrupt or missing → empty glossary.
        }
        return out;
    }

    public void setGlossary(java.util.Map<String, String> glossary) {
        org.json.JSONObject o = new org.json.JSONObject();
        if (glossary != null) {
            for (java.util.Map.Entry<String, String> e : glossary.entrySet()) {
                try { o.put(e.getKey(), e.getValue() == null ? "" : e.getValue()); }
                catch (org.json.JSONException ignored) {}
            }
        }
        prefs.edit().putString(Constants.KEY_GLOSSARY, o.toString()).apply();
    }

    /** Transcript font-scale multiplier. */
    public float getFontScale() {
        return prefs.getFloat(Constants.KEY_FONT_SCALE, Constants.DEFAULT_FONT_SCALE);
    }

    public void setFontScale(float v) {
        prefs.edit().putFloat(Constants.KEY_FONT_SCALE, v).apply();
    }

    private static String safe(String v, String fallback) {
        return TextUtils.isEmpty(v) ? fallback : v.trim();
    }
}