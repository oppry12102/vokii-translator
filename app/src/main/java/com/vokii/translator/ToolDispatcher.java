package com.vokii.translator;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies a batch of {@link ToolCall}s to {@link SessionConfig} and
 * {@link ConfigStore}, and returns the corresponding
 * {@link CommandResult}s. The dispatcher is a stateless function: it
 * just routes by name through {@link ToolRegistry}. All side effects
 * (undo, toast, re-render) are handled by the caller using the returned
 * results.
 *
 * <p>Malformed calls — unknown tool name, missing/invalid args, bad
 * JSON — are converted into {@link CommandResult#rejected} entries
 * rather than exceptions, so a single bad call cannot abort the batch.
 */
public final class ToolDispatcher {

    private final ToolRegistry registry;

    public ToolDispatcher(ToolRegistry registry) {
        this.registry = registry;
    }

    public static final class Applied {
        /** One result per input call, in the same order. Never null. */
        public final List<CommandResult> results;
        /** Index in {@code results} that should be the chip in the
         *  transcript (typically the last non-rejected one). -1 if all
         *  were rejected. */
        public final int primaryIndex;
        /** SessionConfig snapshot taken BEFORE any tool in this batch mutated
         *  state. For a single-mutating-tool batch this equals that tool's
         *  own snapshot; for a multi-mutating-tool batch it captures the
         *  true pre-batch state so UNDO rolls back every mutation, not just
         *  the last one. Null when the batch was empty. */
        public final SessionConfig.Snapshot preSnapshot;

        Applied(List<CommandResult> results, int primaryIndex, SessionConfig.Snapshot preSnapshot) {
            this.results = results;
            this.primaryIndex = primaryIndex;
            this.preSnapshot = preSnapshot;
        }
    }

    /** Apply a list of tool calls. Order is preserved. */
    public Applied apply(List<ToolCall> calls, SessionConfig session, ConfigStore config) {
        List<CommandResult> results = new ArrayList<>();
        int primary = -1;
        if (calls == null) return new Applied(results, -1, null);
        // Snapshot once, before the first mutation, so UNDO restores the
        // pre-batch state even when several tools in this batch each mutate.
        SessionConfig.Snapshot preSnapshot = session.snapshot();
        for (ToolCall call : calls) {
            CommandResult r = dispatchOne(call, session, config);
            results.add(r);
            if (!r.rejected) primary = results.size() - 1;
        }
        return new Applied(results, primary, preSnapshot);
    }

    private CommandResult dispatchOne(ToolCall call, SessionConfig session, ConfigStore config) {
        if (call == null || call.name == null || call.name.isEmpty()) {
            return CommandResult.rejected("empty tool call");
        }
        VoiceTool tool = registry.get(call.name);
        if (tool == null) {
            return CommandResult.rejected("unknown tool: " + call.name);
        }
        // Anti-hallucination net (see GroundingCues): a tool call whose
        // trigger text carries no command cue is almost certainly the LLM
        // misfiring on conversational content — refuse to mutate session
        // state on its say-so. CascadeEngine pre-filters before dispatch;
        // this is the belt-and-braces layer for any future producer.
        if (!GroundingCues.isGrounded(call.name, call.triggerText)) {
            return CommandResult.rejected(call.name
                    + ": ungrounded (no command cue in trigger)");
        }
        if (call.argsJson == null) {
            return CommandResult.rejected(call.name + ": malformed args");
        }
        try {
            return tool.apply(call.argsJson, session, config);
        } catch (Throwable t) {
            return CommandResult.rejected(call.name + ": " + t.getMessage());
        }
    }
}
