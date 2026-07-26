package com.vokii.translator;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Aggregates the live session state + recent command history + recent
 * utterances, and renders them as a "SESSION CONTEXT" section that gets
 * injected into the MT LLM system prompt. This gives the LLM the
 * disambiguation data it needs to correctly fire tools for ambiguous
 * inputs (e.g. "改成中文" — change to what? → look at the most recent
 * {@code set_translation_languages} to figure out which slot to change).
 *
 * <p>Three things are exposed:
 * <ol>
 *   <li><b>Current state</b> — every field in {@link SessionConfig} the
 *       LLM might need to act on (langs, display, mic, log, cascade,
 *       style, temperature). Gives the LLM a single snapshot to reason
 *       about instead of having to remember across turns.</li>
 *   <li><b>Recent commands</b> — bounded ring buffer of
 *       {@link CommandHistoryEntry} (max {@link #MAX_HISTORY}). Each
 *       entry records the original spoken phrase, the tool that fired,
 *       and a short arg summary. Critical for "do it again", "undo",
 *       "change to Chinese" type follow-ups.</li>
 *   <li><b>Recent utterances</b> — bounded ring of the last
 *       {@link #MAX_UTTERANCES} translated turns (source text only).
 *       Lets the LLM answer "再翻一次" by re-feeding the previous
 *       source line, and gives continuity for partial references.</li>
 * </ol>
 *
 * <p>Threading: {@code recordCommand} / {@code recordUtterance} are called
 * on the UI thread, but {@code buildPromptSection} is read from the MT
 * worker thread (via {@link MtPromptBuilder} → {@link CascadeEngine}). The
 * two {@link ArrayDeque}s are therefore guarded by {@code synchronized
 * (lock)} — {@code ArrayDeque} is not thread-safe, and an unguarded
 * concurrent {@code addLast}/{@code toArray} can throw
 * {@code ArrayIndexOutOfBoundsException} or return null slots. The prompt
 * is built from a snapshot taken under the lock so the worker thread never
 * iterates the live deque.
 */
public final class SessionContext {

    /** Cap on command history. 5 is enough for typical "undo / do it
     *  again" flows; raising it costs ~80 tokens per entry. */
    public static final int MAX_HISTORY = 5;
    /** Cap on recent utterances. 2 is enough for "再翻一次"; 3 leaves
     *  room for short dialogues. */
    public static final int MAX_UTTERANCES = 3;

    private final SessionConfig session;
    private final Deque<CommandHistoryEntry> commandHistory = new ArrayDeque<>(MAX_HISTORY + 1);
    private final Deque<Turn> recentUtterances = new ArrayDeque<>(MAX_UTTERANCES + 1);
    /** Guards {@link #commandHistory} and {@link #recentUtterances}. */
    private final Object lock = new Object();

    public SessionContext(SessionConfig session) {
        this.session = session;
    }

    public SessionConfig session() { return session; }

    /** Record a successfully-fired tool call. Called from
     *  MainActivity.onCommand after dispatch + chip rendering. */
    public void recordCommand(String sourceText, String toolName, String argsSummary) {
        synchronized (lock) {
            if (commandHistory.size() >= MAX_HISTORY) commandHistory.removeFirst();
            commandHistory.addLast(new CommandHistoryEntry(
                    System.currentTimeMillis(), sourceText, toolName, argsSummary));
        }
    }

    /** Record a just-committed translation turn. Called from
     *  MainActivity.onCommitted. Only TRANSLATION turns go in; COMMAND
     *  turns (chips) are user-visible feedback, not source material. */
    public void recordUtterance(Turn t) {
        if (t == null || t.kind != Turn.Kind.TRANSLATION) return;
        synchronized (lock) {
            if (recentUtterances.size() >= MAX_UTTERANCES) recentUtterances.removeFirst();
            recentUtterances.addLast(t);
        }
    }

    /** Build the "SESSION CONTEXT" prompt section. Empty if there's
     *  no useful state (shouldn't happen, but defensive). */
    public String buildPromptSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\nSESSION CONTEXT\n");
        sb.append("===============\n");
        sb.append("Current state:\n");
        appendState(sb);
        // Known terms glossary (remember_term). Volatile immutable map on
        // session — safe to read here without the deque lock.
        java.util.Map<String, String> terms = session.glossary();
        if (terms != null && !terms.isEmpty()) {
            sb.append("\nKnown terms (whenever the source contains the left phrase, " +
                    "the translation MUST use the right phrase verbatim):\n");
            for (java.util.Map.Entry<String, String> e : terms.entrySet()) {
                sb.append("  - \"").append(e.getKey()).append("\" → \"")
                        .append(e.getValue()).append("\"\n");
            }
        }
        // Snapshot both deques under the lock so the worker thread iterates
        // stable arrays, not the live deques being mutated on the UI thread.
        CommandHistoryEntry[] cmds;
        Turn[] utts;
        synchronized (lock) {
            cmds = commandHistory.toArray(new CommandHistoryEntry[0]);
            utts = recentUtterances.toArray(new Turn[0]);
        }
        if (cmds.length > 0) {
            sb.append("\nRecent commands (most recent first):\n");
            for (int i = cmds.length - 1; i >= 0; i--) {
                sb.append(cmds[i].render()).append('\n');
            }
        }
        if (utts.length > 0) {
            sb.append("\nRecent utterances (most recent first):\n");
            for (int i = utts.length - 1; i >= 0; i--) {
                Turn t = utts[i];
                String txt = t.source.isEmpty() ? t.target : t.source;
                String lang = t.source.isEmpty() ? t.targetLang : t.sourceLang;
                sb.append("  - \"").append(txt).append("\"  (")
                        .append(lang.toUpperCase(java.util.Locale.ROOT)).append(")\n");
            }
        }
        sb.append("\nUse the above to disambiguate commands like \"改成中文\", ")
          .append("\"再翻一次\", \"undo last\", or partial references. ")
          .append("Commands fire IMMEDIATELY, so the current state above ")
          .append("reflects what has actually been applied.\n");
        return sb.toString();
    }

    private void appendState(StringBuilder sb) {
        sb.append("  - Source language: ").append(langDisplay(session.sourceLang())).append('\n');
        sb.append("  - Target language: ").append(langDisplay(session.targetLang())).append('\n');
        sb.append("  - Display mode: ").append(displayDisplay(session.displayMode())).append('\n');
        sb.append("  - Mic: ").append(session.micPaused() ? "paused" : "listening").append('\n');
        sb.append("  - Log level: ").append(session.debugLogLevel()).append('\n');
        sb.append("  - Cascade: ").append(session.cascadeEnabled() ? "on" : "off").append('\n');
        String style = session.stylePrompt();
        sb.append("  - Translation style: ").append(style == null ? "(none)" : "\"" + style + "\"").append('\n');
        sb.append("  - Temperature: ").append(String.format(java.util.Locale.ROOT, "%.2f", session.temperature())).append('\n');
    }

    private static String langDisplay(String code) {
        if (code == null) return "?";
        if ("auto".equalsIgnoreCase(code)) return "auto-detect (中英)";
        // "Chinese (zh)" etc. — base name from the shared helper, code
        // appended so the LLM sees both the human name and the BCP-47 tag.
        return LangDisplay.name(code) + " (" + code.toLowerCase(java.util.Locale.ROOT) + ")";
    }

    private static String displayDisplay(SessionConfig.DisplayMode m) {
        switch (m) {
            case BOTH:        return "both languages";
            case SOURCE_ONLY: return "source only";
            case TARGET_ONLY: return "target only";
            default:          return m.name();
        }
    }
}
