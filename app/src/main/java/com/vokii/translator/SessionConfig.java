package com.vokii.translator;

/**
 * Runtime configuration for the live translation session. Single mutable
 * source of truth that the UI, the engine, and the tool dispatcher all
 * read from and (occasionally) write to. Lives only for the lifetime of
 * the app process; values that should survive restarts are seeded from
 * and persisted to {@link ConfigStore}.
 *
 * <p>Threading: every field is {@code volatile} so mutations from the
 * UI thread (tool dispatch, settings UI) are visible to the MT worker
 * thread reading the prompt on the next turn. Reads are racy but always
 * see a recent consistent value, which is fine for "next turn" semantics.
 *
 * <p>Seeded at app start from {@link ConfigStore}, then diverges. The
 * voice-command pipeline (Phase 1) writes back to {@link ConfigStore}
 * for any value the user would expect to persist (languages, display
 * mode, debug visibility, cascade mode). Translation style is
 * session-only and intentionally never persisted.
 */
public final class SessionConfig {

    /** Display filter on the transcript view. */
    public enum DisplayMode {
        BOTH, SOURCE_ONLY, TARGET_ONLY;

        /** String form persisted to SharedPreferences. */
        public String key() {
            switch (this) {
                case SOURCE_ONLY: return "source_only";
                case TARGET_ONLY: return "target_only";
                default:          return "both";
            }
        }

        public static DisplayMode fromKey(String k) {
            if ("source_only".equals(k)) return SOURCE_ONLY;
            if ("target_only".equals(k)) return TARGET_ONLY;
            return BOTH;
        }
    }

    private volatile String sourceLang = "zh";
    private volatile String targetLang = "en";
    private volatile DisplayMode displayMode = DisplayMode.BOTH;
    /** Free-form style modifier (e.g. "more formal", "more concise").
     *  Session-only — never persisted. Null/empty means "no style override". */
    private volatile String stylePrompt = null;
    /** MT LLM sampling temperature. Persisted (ConfigStore) so the
     *  user's preferred "creativity" carries across sessions. */
    private volatile float temperature = 0.3f;
    /** Mic mute toggle. Session-only — never persisted. */
    private volatile boolean micPaused = false;
    /** Transcript font-scale multiplier (1.0 = baseline 16sp). Clamped to
     *  [0.85, 1.6]. Persisted so the user's preferred size survives restarts. */
    private volatile float fontScale = 1.0f;
    /** Term→translation glossary built by the remember_term command.
     *  Immutable map swapped atomically on each mutation. Persisted
     *  (ConfigStore); injected into the MT prompt by
     *  {@link SessionContext#buildPromptSection} so names/terms translate
     *  consistently. Never null. */
    private volatile java.util.Map<String, String> glossary = java.util.Collections.emptyMap();

    public String sourceLang() { return sourceLang; }
    public String targetLang() { return targetLang; }
    public DisplayMode displayMode() { return displayMode; }
    public String stylePrompt() { return stylePrompt; }
    public float temperature() { return temperature; }
    public boolean micPaused() { return micPaused; }
    /** Cascade mode is read from ConfigStore on startup and on resume
     *  (see MainActivity.applySessionFromConfig), but we also expose a
     *  live mirror here so the SessionContext prompt section can show
     *  the current value without holding a separate reference. */
    private volatile boolean cascadeEnabled = true;
    public boolean cascadeEnabled() { return cascadeEnabled; }
    public void setCascadeEnabled(boolean v) { this.cascadeEnabled = v; }
    /** Experimental "conversation history in MT prompt" toggle. Read from
     *  ConfigStore on startup/resume (same pattern as cascadeEnabled);
     *  mirrored here so {@link SessionContext#buildPromptSection} can gate
     *  the CONVERSATION HISTORY section. App-behavior switch, not LLM
     *  state — deliberately NOT part of Snapshot/undo. */
    private volatile boolean mtHistoryContext = false;
    public boolean mtHistoryContext() { return mtHistoryContext; }
    public void setMtHistoryContext(boolean v) { this.mtHistoryContext = v; }
    /** Debug log verbosity (NORMAL/VERBOSE/QUIET) is a UI-controlled
     *  thing owned by DebugLogger; we keep a parallel field here so the
     *  SessionContext can show it in the prompt section. MainActivity
     *  is responsible for keeping the two in sync. */
    private volatile DebugLogger.Level debugLogLevel = DebugLogger.Level.NORMAL;
    public DebugLogger.Level debugLogLevel() { return debugLogLevel; }
    public void setDebugLogLevel(DebugLogger.Level l) {
        if (l == null) l = DebugLogger.Level.NORMAL;
        this.debugLogLevel = l;
    }

    public void setLanguages(String src, String tgt) {
        if (src != null && !src.trim().isEmpty()) this.sourceLang = src.trim().toLowerCase(java.util.Locale.ROOT);
        if (tgt != null && !tgt.trim().isEmpty()) this.targetLang = tgt.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public void setDisplayMode(DisplayMode m) {
        if (m != null) this.displayMode = m;
    }

    public void setStylePrompt(String s) {
        this.stylePrompt = (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    /** Clamp the temperature to a sane range [0, 1]. qwen-plus behaves
     *  erratically above 1.0; below 0.2 translations become
     *  near-deterministic and start dropping. */
    public void setTemperature(float t) {
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        this.temperature = t;
    }

    public void setMicPaused(boolean p) { this.micPaused = p; }

    /** Font-scale, clamped to [0.85, 1.6] (≈13.6sp … 25.6sp on the 16sp base). */
    public float fontScale() { return fontScale; }
    public void setFontScale(float s) {
        if (s < 0.85f) s = 0.85f;
        if (s > 1.6f) s = 1.6f;
        this.fontScale = s;
    }

    /** Glossary (immutable view; never null). */
    public java.util.Map<String, String> glossary() { return glossary; }
    /** Replace the glossary with an immutable copy of {@code m}. Null keys,
     *  empty keys, and null values are dropped. */
    public void setGlossary(java.util.Map<String, String> m) {
        java.util.Map<String, String> copy = new java.util.LinkedHashMap<>();
        if (m != null) {
            for (java.util.Map.Entry<String, String> e : m.entrySet()) {
                if (e.getKey() != null && !e.getKey().isEmpty() && e.getValue() != null) {
                    copy.put(e.getKey(), e.getValue());
                }
            }
        }
        this.glossary = java.util.Collections.unmodifiableMap(copy);
    }

    /** Snapshots all currently-mutable fields for an UNDO restore. Includes
     *  micPaused so toggle_mic is undoable (an earlier version omitted it,
     *  leaving the mic stuck after undoing a pause). */
    public Snapshot snapshot() {
        return new Snapshot(sourceLang, targetLang, displayMode, stylePrompt, temperature, micPaused,
                fontScale, glossary);
    }

    /** Restores from a snapshot taken before a tool was applied. */
    public void restore(Snapshot s) {
        if (s == null) return;
        this.sourceLang = s.sourceLang;
        this.targetLang = s.targetLang;
        this.displayMode = s.displayMode;
        this.stylePrompt = s.stylePrompt;
        this.temperature = s.temperature;
        this.micPaused = s.micPaused;
        this.fontScale = s.fontScale;
        this.glossary = s.glossary;
    }

    /** True iff the display mode is legal for the current language pair —
     *  e.g. SOURCE_ONLY / TARGET_ONLY are trivially legal; this hook is
     *  here for future "TARGET_ONLY is meaningless if target == source"
     *  style invariants. */
    public boolean isDisplayModeValid() {
        return displayMode != null;
    }

    /** Mutable snapshot for UNDO. Plain class — all fields public-by-design. */
    public static final class Snapshot {
        public final String sourceLang;
        public final String targetLang;
        public final DisplayMode displayMode;
        public final String stylePrompt;
        public final float temperature;
        public final boolean micPaused;
        public final float fontScale;
        public final java.util.Map<String, String> glossary;
        Snapshot(String src, String tgt, DisplayMode dm, String sp, float temp, boolean micPaused,
                 float fontScale, java.util.Map<String, String> glossary) {
            this.sourceLang = src;
            this.targetLang = tgt;
            this.displayMode = dm;
            this.stylePrompt = sp;
            this.temperature = temp;
            this.micPaused = micPaused;
            this.fontScale = fontScale;
            this.glossary = glossary;
        }
    }
}
