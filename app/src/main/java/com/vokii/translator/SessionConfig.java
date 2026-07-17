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
        if (src != null && !src.trim().isEmpty()) this.sourceLang = src.trim().toLowerCase();
        if (tgt != null && !tgt.trim().isEmpty()) this.targetLang = tgt.trim().toLowerCase();
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

    /** Snapshots all currently-mutable fields for an UNDO restore. */
    public Snapshot snapshot() {
        return new Snapshot(sourceLang, targetLang, displayMode, stylePrompt, temperature);
    }

    /** Restores from a snapshot taken before a tool was applied. */
    public void restore(Snapshot s) {
        if (s == null) return;
        this.sourceLang = s.sourceLang;
        this.targetLang = s.targetLang;
        this.displayMode = s.displayMode;
        this.stylePrompt = s.stylePrompt;
        this.temperature = s.temperature;
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
        Snapshot(String src, String tgt, DisplayMode dm, String sp, float temp) {
            this.sourceLang = src;
            this.targetLang = tgt;
            this.displayMode = dm;
            this.stylePrompt = sp;
            this.temperature = temp;
        }
    }
}
