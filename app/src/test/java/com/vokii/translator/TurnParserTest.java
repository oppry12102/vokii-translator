package com.vokii.translator;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link TurnParser} — the bilingual ZH/EN (and other pairs)
 * label parser that splits one MT turn text into source/target columns.
 */
public class TurnParserTest {

    @Test
    public void autoModeZhFirstThenEn() {
        TurnParser p = TurnParser.parse("ZH: 你好\nEN: hello", "auto", "en");
        assertEquals("你好", p.source);
        assertEquals("hello", p.target);
    }

    @Test
    public void autoModeEnFirstStillRoutesZhLeft() {
        // Labels swapped by the model — Han-count safety net should fix it.
        TurnParser p = TurnParser.parse("EN: hello\nZH: 你好", "auto", "en");
        assertEquals("你好", p.source);
        assertEquals("hello", p.target);
    }

    @Test
    public void autoModeJson() {
        TurnParser p = TurnParser.parse("{\"zh\":\"你好\",\"en\":\"hello\"}", "auto", "en");
        assertEquals("你好", p.source);
        assertEquals("hello", p.target);
    }

    @Test
    public void autoModeJsonNullFieldDoesNotLeakLiteralNull() {
        // Regression: org.json optString would turn a JSON null into "null".
        TurnParser p = TurnParser.parse("{\"zh\":null,\"en\":\"hello\"}", "auto", "en");
        assertEquals("", p.source);
        assertEquals("hello", p.target);
    }

    @Test
    public void jaPairTrustedLabels() {
        TurnParser p = TurnParser.parse("JA: こんにちは\nEN: hello", "ja", "en");
        assertEquals("こんにちは", p.source);
        assertEquals("hello", p.target);
    }

    @Test
    public void fullwidthColonTolerated() {
        TurnParser p = TurnParser.parse("ZH：你好\nEN：hello", "auto", "en");
        assertEquals("你好", p.source);
        assertEquals("hello", p.target);
    }

    @Test
    public void onlyOneLabelPresent() {
        TurnParser p = TurnParser.parse("EN: hello only", "auto", "en");
        assertEquals("", p.source);
        assertEquals("hello only", p.target);
    }

    @Test
    public void noLabelsTreatsAllAsSource() {
        TurnParser p = TurnParser.parse("just some text", "zh", "en");
        assertEquals("just some text", p.source);
        assertEquals("", p.target);
    }

    @Test
    public void emptyInputYieldsEmptyPair() {
        TurnParser p = TurnParser.parse("", "auto", "en");
        assertTrue(p.source.isEmpty());
        assertTrue(p.target.isEmpty());
    }

    @Test
    public void nullInputYieldsEmptyPair() {
        TurnParser p = TurnParser.parse(null, "auto", "en");
        assertTrue(p.source.isEmpty());
        assertTrue(p.target.isEmpty());
    }

    @Test
    public void zhEnSwapCorrectedByHanCount() {
        // zh<->en pair with EN holding Han and ZH holding ascii → swap back.
        TurnParser p = TurnParser.parse("ZH: hello\nEN: 你好", "zh", "en");
        assertEquals("你好", p.source);
        assertEquals("hello", p.target);
    }
}
