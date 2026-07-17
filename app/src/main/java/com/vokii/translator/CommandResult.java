package com.vokii.translator;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Result of applying a single {@link ToolCall}. Encapsulates the visible
 * summary string (used both for the in-transcript chip and the Toast), the
 * UI-side {@link Effect}s to trigger after application, and the
 * pre-application snapshot that powers the UNDO affordance.
 *
 * <h2>Side effects</h2>
 * What the {@link MainActivity} should do in response is expressed as an
 * {@link EnumSet} of {@link Effect} constants — not a row of positional
 * booleans. This keeps the constructor readable (one mis-counted {@code true}
 * in a 15-arg list used to silently flip the wrong flag), makes adding a new
 * effect a local change (one constant + one {@code case}), and lets
 * {@link MainActivity#onCommand} dispatch with a {@code switch} whose
 * {@code default} throws — so a forgotten case fails loudly in debug instead
 * of being a silent no-op.
 *
 * <h2>Data vs effects</h2>
 * {@link Effect} carries only "do this now". The <em>parameters</em> an
 * effect needs ({@link #logLevelChange}) and the <em>undo state</em>
 * ({@link #sessionSnapshot}, {@link #prevCascade}, {@link #prevDebug}) stay
 * as nullable fields, gated by the effect / undo contract:
 * <ul>
 *   <li>{@link Effect#LOG_LEVEL} ⇒ {@link #logLevelChange} is non-null.</li>
 *   <li>{@link #sessionSnapshot} non-null ⇒ the tool is undoable (MainActivity
 *       shows the UNDO toast and stores the result for {@code undoLastCommand}).</li>
 *   <li>{@link #prevCascade} / {@link #prevDebug} are the pre-application
 *       values restored on UNDO, only meaningful alongside
 *       {@link Effect#ENGINE_RECONCILE} / {@link Effect#DEBUG_TOGGLE}.</li>
 * </ul>
 *
 * <p>Constructed via {@link #ok(String)} / {@link #rejected(String)} builders;
 * {@link #build()} validates the invariants above.
 */
public final class CommandResult {

    /** UI-side actions MainActivity must perform after applying the tool. */
    public enum Effect {
        /** Re-render the transcript (display-mode / language change). */
        RERENDER,
        /** Re-evaluate the engine type (cascade toggle, on next mic tap). */
        ENGINE_RECONCILE,
        /** Toggle the debug panel visibility. */
        DEBUG_TOGGLE,
        /** Wipe the transcript history (keep the just-added chip). */
        CLEAR_TRANSCRIPT,
        /** Copy the transcript to the system clipboard. */
        EXPORT_CLIPBOARD,
        /** Re-translate the last TRANSLATION turn with current style/temp. */
        RETRANSLATE_LAST,
        /** Summarize the transcript into a chip. */
        SUMMARIZE_SESSION,
        /** Replace the chip with the full command catalog. */
        LIST_COMMANDS,
        /** Refresh the mic status row (toggle_mic changed pause state). */
        MIC_REFRESH,
        /** Change the DebugLogger level (parameter: {@link #logLevelChange}). */
        LOG_LEVEL
    }

    /** Short user-visible description, e.g. "Languages → zh↔ja". */
    public final String summary;
    /** Side effects MainActivity should run, in enum-declaration order.
     *  Empty for a pure-display or rejected result. Unmodifiable. */
    public final Set<Effect> effects;
    /** New debug log level; non-null iff {@link Effect#LOG_LEVEL} is set. */
    public final DebugLogger.Level logLevelChange;
    /** Snapshot taken before the tool was applied; null = not undoable. */
    public final SessionConfig.Snapshot sessionSnapshot;
    /** Pre-application cascade_mode for UNDO; null = no change. */
    public final Boolean prevCascade;
    /** Pre-application debug_visible for UNDO; null = no change. */
    public final Boolean prevDebug;
    /** True when the call was rejected (bad args, unknown tool, illegal
     *  state). The activity shows "rejected: …" instead of a success chip. */
    public final boolean rejected;
    public final String rejectReason;

    private CommandResult(String summary, Set<Effect> effects,
                          DebugLogger.Level logLevelChange,
                          SessionConfig.Snapshot sessionSnapshot,
                          Boolean prevCascade, Boolean prevDebug,
                          boolean rejected, String rejectReason) {
        this.summary = summary;
        this.effects = effects;
        this.logLevelChange = logLevelChange;
        this.sessionSnapshot = sessionSnapshot;
        this.prevCascade = prevCascade;
        this.prevDebug = prevDebug;
        this.rejected = rejected;
        this.rejectReason = rejectReason;
    }

    /** Begin a successful result. Add effects/data fluently, then {@link #build()}. */
    public static Builder ok(String summary) {
        return new Builder(summary);
    }

    /** A rejected result: no effects, no undo, reason shown as the chip. */
    public static CommandResult rejected(String reason) {
        return new Builder(reason).rejected(true).rejectReason(reason).build();
    }

    /** Fluent builder — replaces the old positional-boolean factories. */
    public static final class Builder {
        private final String summary;
        private final EnumSet<Effect> effects = EnumSet.noneOf(Effect.class);
        private DebugLogger.Level logLevelChange;
        private SessionConfig.Snapshot snapshot;
        private Boolean prevCascade;
        private Boolean prevDebug;
        private boolean rejected;
        private String rejectReason;

        public Builder(String summary) { this.summary = summary; }

        public Builder effect(Effect e) { effects.add(e); return this; }
        public Builder snapshot(SessionConfig.Snapshot s) { this.snapshot = s; return this; }
        public Builder prevCascade(Boolean v) { this.prevCascade = v; return this; }
        public Builder prevDebug(Boolean v) { this.prevDebug = v; return this; }
        /** Set the log level + record the LOG_LEVEL effect together so they
         *  can't drift apart. */
        public Builder logLevel(DebugLogger.Level l) {
            this.effects.add(Effect.LOG_LEVEL);
            this.logLevelChange = l;
            return this;
        }
        public Builder rejected(boolean r) { this.rejected = r; return this; }
        public Builder rejectReason(String r) { this.rejectReason = r; return this; }

        public CommandResult build() {
            if (effects.contains(Effect.LOG_LEVEL) && logLevelChange == null) {
                throw new IllegalStateException("LOG_LEVEL effect requires a level");
            }
            if (rejected && !effects.isEmpty()) {
                throw new IllegalStateException("rejected result must carry no effects");
            }
            return new CommandResult(summary, Collections.unmodifiableSet(effects),
                    logLevelChange, snapshot, prevCascade, prevDebug, rejected, rejectReason);
        }
    }
}
