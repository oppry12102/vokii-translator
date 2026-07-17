package com.vokii.translator;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for the {@link CommandResult} builder — the EnumSet/Effect
 * mechanics that replaced the old positional-boolean factories. These cover
 * the invariants {@link CommandResult.Builder#build()} enforces and the
 * undo/effect data contracts; tool wiring (which needs a ConfigStore/Context)
 * is not exercised here.
 */
public class CommandResultTest {

    private static SessionConfig.Snapshot snap() {
        return new SessionConfig().snapshot();
    }

    @Test
    public void pureDisplayResultHasNoEffectsOrSnapshot() {
        CommandResult r = CommandResult.ok("hello").build();
        assertEquals("hello", r.summary);
        assertTrue(r.effects.isEmpty());
        assertNull(r.sessionSnapshot);
        assertFalse(r.rejected);
    }

    @Test
    public void rerenderWithSnapshot() {
        SessionConfig.Snapshot s = snap();
        CommandResult r = CommandResult.ok("Languages → zh↔en")
                .effect(CommandResult.Effect.RERENDER)
                .snapshot(s)
                .build();
        assertTrue(r.effects.contains(CommandResult.Effect.RERENDER));
        assertEquals(1, r.effects.size());
        assertEquals(s, r.sessionSnapshot);
    }

    @Test
    public void logLevelSetsEffectAndFieldTogether() {
        CommandResult r = CommandResult.ok("Log level → verbose")
                .logLevel(DebugLogger.Level.VERBOSE)
                .build();
        assertTrue(r.effects.contains(CommandResult.Effect.LOG_LEVEL));
        assertEquals(DebugLogger.Level.VERBOSE, r.logLevelChange);
    }

    @Test
    public void logLevelEffectWithoutLevelIsRejected() {
        // Adding LOG_LEVEL via effect() without a level violates the invariant.
        try {
            CommandResult.ok("x").effect(CommandResult.Effect.LOG_LEVEL).build();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // good
        }
    }

    @Test
    public void rejectedResultHasNoEffectsOrSnapshot() {
        CommandResult r = CommandResult.rejected("bad args");
        assertTrue(r.rejected);
        assertEquals("bad args", r.rejectReason);
        assertTrue(r.effects.isEmpty());
        assertNull(r.sessionSnapshot);
        assertNull(r.logLevelChange);
    }

    @Test
    public void rejectedWithEffectsIsRejected() {
        try {
            CommandResult.ok("x").rejected(true).effect(CommandResult.Effect.RERENDER).build();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // good
        }
    }

    @Test
    public void prevCascadeAndPrevDebugCarried() {
        CommandResult r = CommandResult.ok("Cascade → on")
                .effect(CommandResult.Effect.ENGINE_RECONCILE)
                .snapshot(snap())
                .prevCascade(false)
                .build();
        assertEquals(Boolean.FALSE, r.prevCascade);
        assertNull(r.prevDebug);
    }

    @Test
    public void effectsIsUnmodifiable() {
        CommandResult r = CommandResult.ok("x").effect(CommandResult.Effect.RERENDER).build();
        try {
            r.effects.add(CommandResult.Effect.LOG_LEVEL);
            fail("expected unmodifiable set");
        } catch (UnsupportedOperationException expected) {
            // good
        }
    }

    @Test
    public void multipleEffectsCoexist() {
        CommandResult r = CommandResult.ok("x")
                .effect(CommandResult.Effect.RERENDER)
                .effect(CommandResult.Effect.MIC_REFRESH)
                .build();
        assertEquals(2, r.effects.size());
        assertTrue(r.effects.contains(CommandResult.Effect.RERENDER));
        assertTrue(r.effects.contains(CommandResult.Effect.MIC_REFRESH));
    }

    @Test
    public void clearTranscriptHasEffectButNoSnapshot() {
        // clear_transcript is destructive and not undoable — effect but no snapshot.
        CommandResult r = CommandResult.ok("Transcript cleared")
                .effect(CommandResult.Effect.CLEAR_TRANSCRIPT)
                .build();
        assertTrue(r.effects.contains(CommandResult.Effect.CLEAR_TRANSCRIPT));
        assertNull(r.sessionSnapshot);
    }
}
