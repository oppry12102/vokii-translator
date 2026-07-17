package com.vokii.translator;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * One entry in the session's command history. Captured by MainActivity
 * when {@link com.vokii.translator.TranslationController.Listener#onCommand}
 * fires, then passed (in aggregate) to the MT LLM as part of
 * {@link SessionContext} so the LLM can disambiguate commands like
 * "改成中文" or "再翻一次" against what has already been done.
 *
 * <p>Fields are intentionally minimal: just enough for the LLM to
 * reconstruct the conversational state. The full tool args (JSON) and
 * the full chip text are not stored here — we keep only the most useful
 * summary form (e.g. {@code "zh → ja"}).
 */
public final class CommandHistoryEntry {

    public final long timestampMs;
    public final String sourceText;   // the original spoken phrase, e.g. "下面改成中日翻译"
    public final String toolName;     // e.g. "set_translation_languages"
    public final String argsSummary;  // human-readable, e.g. "zh → ja"

    public CommandHistoryEntry(long timestampMs, String sourceText,
                              String toolName, String argsSummary) {
        this.timestampMs = timestampMs;
        this.sourceText = sourceText == null ? "" : sourceText;
        this.toolName = toolName == null ? "" : toolName;
        this.argsSummary = argsSummary == null ? "" : argsSummary;
    }

    /**
     * Render one entry as a prompt-section line. Format:
     *   [12:35:18] User said "下面改成中日翻译"
     *     → set_translation_languages(zh → ja)
     * The user phrase and args are quoted so the LLM can see the literal
     * text without ambiguity. The timestamp is local HH:mm:ss.
     */
    public String render() {
        String ts = TIMESTAMP_FMT.get().format(new Date(timestampMs));
        StringBuilder sb = new StringBuilder("  - [").append(ts).append("] User said \"")
                .append(escapeQuotes(sourceText)).append("\"");
        if (!toolName.isEmpty()) {
            sb.append("\n    → ").append(toolName);
            if (!argsSummary.isEmpty()) sb.append("(").append(argsSummary).append(")");
        }
        return sb.toString();
    }

    private static String escapeQuotes(String s) {
        // Embedded newlines would break the prompt formatting. Replace
        // them with a single space. Quotes within the source text
        // could in theory break the literal — we don't see them in
        // CS-Dialogue but escape defensively.
        return s.replace('\n', ' ').replace("\"", "\\\"");
    }

    /** Render tool args (JSONObject) as a short human-readable summary
     *  for the history. Skips {@code trigger_text} (it's the source
     *  phrase, already shown on the previous line). */
    public static String summarizeArgs(JSONObject args) {
        if (args == null) return "";
        // Order: a small whitelist of common keys first, then anything else.
        StringBuilder sb = new StringBuilder();
        String[] preferred = {"source", "target", "mode", "enabled", "paused",
                "level", "style", "temperature"};
        for (String k : preferred) {
            if (!args.has(k)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(k).append("=").append(prettyValue(args.opt(k)));
        }
        // Any remaining keys (defensive)
        java.util.Iterator<String> it = args.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.equals("trigger_text")) continue;
            boolean already = false;
            for (String p : preferred) if (p.equals(k)) { already = true; break; }
            if (already) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(k).append("=").append(prettyValue(args.opt(k)));
        }
        return sb.toString();
    }

    private static String prettyValue(Object v) {
        if (v == null || v == JSONObject.NULL) return "null";
        if (v instanceof Boolean) return v.toString();
        if (v instanceof Number) {
            // Drop trailing ".0" on integer-valued doubles for readability.
            double d = ((Number) v).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        String s = v.toString();
        if (s.length() > 20) s = s.substring(0, 17) + "...";
        return s;
    }

    // Local-time HH:mm:ss. SimpleDateFormat is NOT thread-safe, and render()
    // is called from both the UI thread and the MT worker thread (via
    // SessionContext.buildPromptSection), so use a ThreadLocal — one
    // formatter per thread. The session doesn't span days in practice, so
    // we don't include the date.
    private static final ThreadLocal<SimpleDateFormat> TIMESTAMP_FMT = ThreadLocal.withInitial(() -> {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f;
    });
}
