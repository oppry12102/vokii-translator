package com.vokii.translator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the registered {@link VoiceTool}s, produces the OpenAI-compat
 * tools array for the LLM request, and dispatches {@link ToolCall}s by
 * name. Order is preserved (LinkedHashMap) so the LLM sees a stable
 * schema list across calls.
 */
public final class ToolRegistry {

    private final Map<String, VoiceTool> tools = new LinkedHashMap<>();

    public ToolRegistry register(VoiceTool t) {
        if (t != null && t.name() != null && !t.name().isEmpty()) {
            tools.put(t.name(), t);
        }
        return this;
    }

    public VoiceTool get(String name) {
        return name == null ? null : tools.get(name);
    }

    public List<String> names() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(tools.keySet()));
    }

    /** Aggregate every registered tool's schema into a JSON array.
     *  Returns an empty array (not null) so callers can always put it
     *  in the request body without conditionals. The tool's own
     *  {@code functionSchema()} already carries the correct {@code name}
     *  field. */
    public JSONArray toJsonArray() {
        JSONArray arr = new JSONArray();
        for (VoiceTool t : tools.values()) {
            try {
                arr.put(new JSONObject(t.functionSchema().toString()));
            } catch (Throwable ignored) {}
        }
        return arr;
    }

    /** Build the default registry with the built-in tools. */
    public static ToolRegistry defaultRegistry() {
        return new ToolRegistry()
                .register(new BuiltInTools.SetTranslationLanguages())
                .register(new BuiltInTools.SetDisplayMode())
                .register(new BuiltInTools.ToggleCascade())
                .register(new BuiltInTools.ToggleDebug())
                .register(new BuiltInTools.SetTranslationMode())
                .register(new BuiltInTools.GetCurrentSettings())
                .register(new BuiltInTools.ClearTranscript())
                .register(new BuiltInTools.ToggleMic())
                .register(new BuiltInTools.SetLogLevel())
                .register(new BuiltInTools.ExportTranscript())
                .register(new BuiltInTools.SummarizeSession())
                .register(new BuiltInTools.RetranslateLast())
                .register(new BuiltInTools.ListCommands());
    }
}
