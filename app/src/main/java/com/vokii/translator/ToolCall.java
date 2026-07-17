package com.vokii.translator;

import org.json.JSONObject;

/**
 * One tool-use invocation extracted from the MT LLM's stream. Carries the
 * tool's registered {@link #name}, the parsed {@link #argsJson}, and the
 * verbatim spoken phrase that triggered it ({@link #triggerText}, taken
 * from the LLM's own {@code trigger_text} argument — provenance so the
 * UI can show the user what the system understood them to have said).
 */
public final class ToolCall {

    public final String name;
    public final JSONObject argsJson;
    public final String triggerText;

    public ToolCall(String name, JSONObject argsJson, String triggerText) {
        this.name = name == null ? "" : name;
        this.argsJson = argsJson;
        this.triggerText = triggerText == null ? "" : triggerText;
    }

    @Override
    public String toString() {
        return "ToolCall{" + name + ", trigger=\"" + triggerText + "\"}";
    }
}
