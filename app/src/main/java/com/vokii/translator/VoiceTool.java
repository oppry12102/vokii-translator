package com.vokii.translator;

import org.json.JSONObject;

/**
 * A local action the MT LLM can invoke via OpenAI-compat tool_use. One
 * tool = one thing the user can change by voice. The registry aggregates
 * {@link #functionSchema()}s for the LLM request and dispatches
 * {@link #apply} at runtime.
 */
public interface VoiceTool {

    /** Stable identifier used in tool_calls and the schema. Snake_case
     *  per the OpenAI-compat convention. */
    String name();

    /** OpenAI-compat function schema: {type:"function", function:{name, description, parameters}}. */
    org.json.JSONObject functionSchema();

    /** Apply the tool. Must not throw on bad input — return a
     *  {@link CommandResult#rejected} instead. */
    CommandResult apply(JSONObject args, SessionConfig session, ConfigStore config);
}
