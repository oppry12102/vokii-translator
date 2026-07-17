package com.vokii.translator;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates streaming {@code delta.tool_calls} chunks into complete
 * {@link ToolCall} objects. OpenAI-compat streams split a single
 * function-call across many chunks: the function name comes first, then
 * the arguments are emitted as a string in many small fragments. We hold
 * one entry per {@code tool_calls[*].index} until the stream ends.
 *
 * <p>Usage:
 * <pre>
 *   ToolCallAccumulator acc = new ToolCallAccumulator();
 *   for each SSE delta: acc.feed(delta.tool_calls);
 *   List&lt;ToolCall&gt; calls = acc.build();
 * </pre>
 *
 * <p>Per-chunk format we expect (from a verified DashScope run):
 * <pre>
 *   chunk 1: {"tool_calls":[{"index":0,"id":"call_xxx","type":"function",
 *              "function":{"name":"set_languages","arguments":""}}]}
 *   chunk 2: {"tool_calls":[{"index":0,"function":{"arguments":"{\"source\":"}}]}
 *   chunk 3: {"tool_calls":[{"index":0,"function":{"arguments":" \"zh\", ..."}}]}
 *   ...     (more argument fragments)
 *   final : {"tool_calls":[{"index":0,"function":{"arguments":null}}]}
 *   then  : {"finish_reason":"tool_calls"}
 * </pre>
 *
 * <p>Edge cases handled:
 * <ul>
 *   <li>Missing or null {@code arguments} on a chunk (e.g. the closing
 *       fragment) — skipped, not concatenated.</li>
 *   <li>Empty {@code arguments} string on chunk 1 — allowed; name is
 *       captured, JSON is built up from later chunks.</li>
 *   <li>Malformed JSON at the end — emitted as a ToolCall with
 *       {@code argsJson=null} so the dispatcher can surface a soft error
 *       rather than crash.</li>
 * </ul>
 */
final class ToolCallAccumulator {

    private static class Entry {
        String id;
        String name;
        StringBuilder args = new StringBuilder();
    }

    private final Map<Integer, Entry> entries = new HashMap<>();

    /** Accept one {@code tool_calls} array (already parsed from a delta). */
    void feed(org.json.JSONArray toolCalls) {        if (toolCalls == null) return;
        for (int i = 0; i < toolCalls.length(); i++) {
            org.json.JSONObject tc = toolCalls.optJSONObject(i);
            if (tc == null) continue;
            int index = tc.optInt("index", i);
            Entry e = entries.get(index);
            if (e == null) {
                e = new Entry();
                entries.put(index, e);
            }
            // id and name are typically set on the first chunk; we tolerate
            // them being re-sent in later chunks. Use the null-safe Json
            // helper: org.json's optString returns the literal "null" for a
            // JSON null, which would corrupt the args/id buffers.
            String id = Json.optString(tc, "id");
            if (!id.isEmpty() && e.id == null) e.id = id;

            org.json.JSONObject fn = tc.optJSONObject("function");
            if (fn != null) {
                String name = Json.optString(fn, "name");
                if (!name.isEmpty() && e.name == null) e.name = name;
                String args = Json.optString(fn, "arguments");
                // Empty string (absent / JSON null) is a no-op append.
                e.args.append(args);
            }
        }
    }

    /** Snapshot the accumulated state as complete tool calls. Each entry's
     *  argument string is parsed into a JSONObject (best-effort; null if
     *  malformed). Order matches {@code index} ascending. */
    List<ToolCall> build() {
        List<Integer> indices = new ArrayList<>(entries.keySet());
        java.util.Collections.sort(indices);
        List<ToolCall> out = new ArrayList<>(indices.size());
        for (int idx : indices) {
            Entry e = entries.get(idx);
            String name = e.name == null ? "" : e.name;
            String raw = e.args.toString();
            JSONObject parsed = null;
            if (!raw.isEmpty()) {
                try { parsed = new JSONObject(raw); } catch (JSONException ignored) {}
            }
            String triggerText = parsed == null ? "" : Json.optString(parsed, "trigger_text", "");
            out.add(new ToolCall(name, parsed, triggerText));
        }
        return out;
    }
}
