package com.vokii.translator;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link ToolCallAccumulator} — streaming tool_call delta
 * accumulation, including the regression where a JSON {@code null}
 * arguments fragment must NOT be concatenated as the literal "null".
 */
public class ToolCallAccumulatorTest {

    private static JSONArray toolCalls(String json) throws Exception {
        // Wrap a single delta's tool_calls array.
        JSONObject delta = new JSONObject("{\"tool_calls\":" + json + "}");
        return delta.getJSONArray("tool_calls");
    }

    @Test
    public void singleCallFragmentedArguments() throws Exception {
        ToolCallAccumulator acc = new ToolCallAccumulator();
        acc.feed(toolCalls("[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"set_languages\",\"arguments\":\"\"}}]"));
        acc.feed(toolCalls("[{\"index\":0,\"function\":{\"arguments\":\"{\\\"source\\\":\"}}]"));
        acc.feed(toolCalls("[{\"index\":0,\"function\":{\"arguments\":\" \\\"zh\\\",\\\"target\\\":\\\"en\\\"}\"}}]"));
        java.util.List<ToolCall> calls = acc.build();
        assertEquals(1, calls.size());
        assertEquals("set_languages", calls.get(0).name);
        assertNotNull(calls.get(0).argsJson);
        assertEquals("zh", calls.get(0).argsJson.getString("source"));
        assertEquals("en", calls.get(0).argsJson.getString("target"));
    }

    @Test
    public void jsonNullArgumentsFragmentNotConcatenated() throws Exception {
        // Regression for the org.json "null" bug: the closing fragment
        // {"arguments":null} must be skipped, not appended as "null".
        ToolCallAccumulator acc = new ToolCallAccumulator();
        acc.feed(toolCalls("[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"set_languages\","
                + "\"arguments\":\"{\\\"source\\\":\\\"zh\\\",\\\"target\\\":\\\"en\\\"}\"}}]"));
        acc.feed(toolCalls("[{\"index\":0,\"function\":{\"arguments\":null}}]"));
        java.util.List<ToolCall> calls = acc.build();
        assertEquals(1, calls.size());
        // Without the fix the args buffer would be "{...}null" → malformed →
        // argsJson null. With the fix it parses cleanly.
        assertNotNull("null-arguments fragment must not corrupt the JSON", calls.get(0).argsJson);
        assertEquals("zh", calls.get(0).argsJson.getString("source"));
    }

    @Test
    public void multipleCallsByIndex() throws Exception {
        ToolCallAccumulator acc = new ToolCallAccumulator();
        acc.feed(toolCalls("[{\"index\":0,\"function\":{\"name\":\"toggle_mic\","
                + "\"arguments\":\"{\\\"paused\\\":true}\"}}]"));
        acc.feed(toolCalls("[{\"index\":1,\"function\":{\"name\":\"toggle_debug\","
                + "\"arguments\":\"{\\\"enabled\\\":true}\"}}]"));
        java.util.List<ToolCall> calls = acc.build();
        assertEquals(2, calls.size());
        assertEquals("toggle_mic", calls.get(0).name);
        assertEquals("toggle_debug", calls.get(1).name);
    }

    @Test
    public void malformedArgsYieldsNullArgsJsonButKeepsName() throws Exception {
        ToolCallAccumulator acc = new ToolCallAccumulator();
        acc.feed(toolCalls("[{\"index\":0,\"function\":{\"name\":\"set_languages\","
                + "\"arguments\":\"not valid json\"}}]"));
        java.util.List<ToolCall> calls = acc.build();
        assertEquals(1, calls.size());
        assertEquals("set_languages", calls.get(0).name);
        assertNull(calls.get(0).argsJson);
    }

    @Test
    public void triggerTextExtracted() throws Exception {
        ToolCallAccumulator acc = new ToolCallAccumulator();
        acc.feed(toolCalls("[{\"index\":0,\"function\":{\"name\":\"set_languages\","
                + "\"arguments\":\"{\\\"source\\\":\\\"zh\\\",\\\"trigger_text\\\":\\\"下面改成中日翻译\\\"}\"}}]"));
        java.util.List<ToolCall> calls = acc.build();
        assertEquals("下面改成中日翻译", calls.get(0).triggerText);
    }

    @Test
    public void emptyBuild() throws Exception {
        ToolCallAccumulator acc = new ToolCallAccumulator();
        assertTrue(acc.build().isEmpty());
    }
}
