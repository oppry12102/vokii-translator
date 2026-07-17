package com.vokii.translator;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link SessionContext} — command/utterance ring buffers,
 * prompt rendering, and that buildPromptSection is safe to call
 * concurrently with record* (the ArrayDeque race that motivated the
 * synchronized snapshot).
 */
public class SessionContextTest {

    @Test
    public void recordsAndRendersCommands() {
        SessionConfig session = new SessionConfig();
        SessionContext ctx = new SessionContext(session);
        ctx.recordCommand("下面改成中日翻译", "set_translation_languages", "zh → ja");
        String section = ctx.buildPromptSection();
        assertTrue("command tool name should appear", section.contains("set_translation_languages"));
        assertTrue("spoken phrase should appear", section.contains("下面改成中日翻译"));
    }

    @Test
    public void commandHistoryCappedAtMax() {
        SessionConfig session = new SessionConfig();
        SessionContext ctx = new SessionContext(session);
        for (int i = 0; i < SessionContext.MAX_HISTORY + 5; i++) {
            ctx.recordCommand("cmd" + i, "tool" + (i % 2), "a" + i);
        }
        // Most-recent-first rendering — count the "→ tool" lines.
        String section = ctx.buildPromptSection();
        int arrows = 0;
        for (int i = 0; i < section.length(); i++) {
            if (section.charAt(i) == '→') arrows++;
        }
        assertEquals(SessionContext.MAX_HISTORY, arrows);
    }

    @Test
    public void utterancesCappedAndRendered() {
        SessionConfig session = new SessionConfig();
        SessionContext ctx = new SessionContext(session);
        for (int i = 0; i < SessionContext.MAX_UTTERANCES + 3; i++) {
            ctx.recordUtterance(Turn.translation("src" + i, "tgt" + i, "zh", "en"));
        }
        String section = ctx.buildPromptSection();
        // Only the newest MAX_UTTERANCES should appear; the oldest "src0" gone.
        assertFalse(section.contains("src0"));
        assertTrue(section.contains("src" + (SessionContext.MAX_UTTERANCES + 2)));
    }

    @Test
    public void commandOnlyTurnsNotRecordedAsUtterances() {
        SessionConfig session = new SessionConfig();
        SessionContext ctx = new SessionContext(session);
        ctx.recordUtterance(Turn.command("just a chip"));
        String section = ctx.buildPromptSection();
        assertFalse(section.contains("just a chip"));
    }

    @Test
    public void promptSectionContainsCurrentState() {
        SessionConfig session = new SessionConfig();
        session.setLanguages("zh", "en");
        SessionContext ctx = new SessionContext(session);
        String section = ctx.buildPromptSection();
        assertTrue(section.contains("Source language:"));
        assertTrue(section.contains("Target language:"));
        assertTrue(section.contains("Chinese"));
        assertTrue(section.contains("English"));
    }

    @Test
    public void concurrentBuildWhileRecordingDoesNotThrow() throws Exception {
        // Regression for the ArrayDeque race: buildPromptSection (MT worker
        // thread) iterating while recordCommand/recordUtterance (UI thread)
        // mutate. Run many iterations across threads; any
        // ArrayIndexOutOfBoundsException or ConcurrentModification fails the
        // test.
        final SessionConfig session = new SessionConfig();
        final SessionContext ctx = new SessionContext(session);
        final int N = 2000;
        Runnable writer = () -> {
            for (int i = 0; i < N; i++) {
                ctx.recordCommand("p" + i, "tool", "x" + i);
                ctx.recordUtterance(Turn.translation("s" + i, "t" + i, "zh", "en"));
            }
        };
        Runnable reader = () -> {
            for (int i = 0; i < N; i++) {
                ctx.buildPromptSection();
            }
        };
        Thread t1 = new Thread(writer, "writer-1");
        Thread t2 = new Thread(writer, "writer-2");
        Thread t3 = new Thread(reader, "reader-1");
        t1.start(); t2.start(); t3.start();
        t1.join(); t2.join(); t3.join();
        // If we got here without an exception, the race is fixed.
        assertTrue(ctx.buildPromptSection().contains("SESSION CONTEXT"));
    }
}
