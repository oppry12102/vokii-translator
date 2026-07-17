package com.vokii.translator;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for the null-safe {@link Json} helper — specifically the
 * org.json quirk where {@code optString(key, fallback)} returns the literal
 * string "null" for a JSON null literal instead of the fallback.
 */
public class JsonTest {

    @Test
    public void absentKeyReturnsFallback() throws Exception {
        JSONObject o = new JSONObject();
        assertEquals("def", Json.optString(o, "missing", "def"));
        assertEquals("", Json.optString(o, "missing"));
    }

    @Test
    public void nullObjectReturnsFallback() throws Exception {
        assertEquals("def", Json.optString(null, "k", "def"));
        assertEquals("", Json.optString(null, "k"));
    }

    @Test
    public void jsonNullLiteralReturnsFallbackNotStringNull() throws Exception {
        JSONObject o = new JSONObject();
        o.put("k", JSONObject.NULL);
        // The whole point: org.json's optString would return "null" here.
        assertEquals("def", Json.optString(o, "k", "def"));
        assertEquals("", Json.optString(o, "k"));
    }

    @Test
    public void realStringReturned() throws Exception {
        JSONObject o = new JSONObject();
        o.put("k", "hello");
        assertEquals("hello", Json.optString(o, "k", "def"));
    }

    @Test
    public void numberCoercedToString() throws Exception {
        JSONObject o = new JSONObject();
        o.put("k", 42);
        assertEquals("42", Json.optString(o, "k", "def"));
    }

    @Test
    public void finishReasonNullUsesFallback() throws Exception {
        // Mirrors QwenMtClient.parseFinishReason: optString(key, null).
        JSONObject o = new JSONObject();
        o.put("finish_reason", JSONObject.NULL);
        assertNull(Json.optString(o, "finish_reason", null));
    }

    @Test
    public void nestedObjectAccess() throws Exception {
        JSONObject o = new JSONObject();
        JSONObject inner = new JSONObject();
        inner.put("message", JSONObject.NULL);
        o.put("error", inner);
        assertEquals("fallback", Json.optString(o.optJSONObject("error"), "message", "fallback"));
    }

    @Test
    public void nonStringValueCoercedViaToString() throws Exception {
        // Like org.json's optString, a non-string non-null value (number,
        // array, …) is coerced via toString(). We only expect strings or
        // null at these keys in practice; this documents the coercion.
        JSONObject o = new JSONObject();
        o.put("k", new JSONArray().put("a"));
        assertEquals("[\"a\"]", Json.optString(o, "k", "def"));
    }
}
