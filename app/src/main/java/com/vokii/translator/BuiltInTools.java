package com.vokii.translator;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * The five voice tools Vokii ships with. Grouped in one file because they
 * share the same trivial shape: a schema, and a stateless {@code apply}
 * that mutates {@link SessionConfig} and/or {@link ConfigStore}. Adding
 * a new tool is a matter of writing a new class implementing
 * {@link VoiceTool} and registering it in {@link ToolRegistry}.
 *
 * <p>All tools return {@link CommandResult}s — never throw on bad input.
 * The dispatcher never sees an exception; bad calls surface as a
 * "rejected" chip so the user gets feedback instead of a crash.
 */
public final class BuiltInTools {

    private BuiltInTools() {}

    // -----------------------------------------------------------------
    // 1. set_translation_languages
    // -----------------------------------------------------------------

    public static final class SetTranslationLanguages implements VoiceTool {
        @Override public String name() { return "set_translation_languages"; }

        private static final JSONObject SCHEMA = buildSchema(
                "set_translation_languages",
                "Change the language pair the interpreter translates between. " +
                "Takes effect on the NEXT utterance. If the user asks to " +
                "translate TO the language currently being spoken (i.e. the " +
                "pair would come out as source == target), emit the call " +
                "anyway with that language in both slots — the app will " +
                "auto-flip the direction so that language becomes the target.",
                new ParamDef[]{
                        new ParamDef("source", "string",
                                "BCP-47 short code for the source language, e.g. \"zh\", \"en\", \"ja\".", true),
                        new ParamDef("target", "string",
                                "BCP-47 short code for the target language.", true),
                        new ParamDef("trigger_text", "string",
                                "The original spoken phrase that triggered this command.", false)
                }
        );

        @Override public JSONObject functionSchema() { return SCHEMA; }

        @Override public CommandResult apply(JSONObject args, SessionConfig session, ConfigStore config) {
            if (args == null) return CommandResult.rejected("缺少参数");
            String src = Json.optString(args, "source", "").trim().toLowerCase(Locale.ROOT);
            String tgt = Json.optString(args, "target", "").trim().toLowerCase(Locale.ROOT);
            if (src.isEmpty() || tgt.isEmpty()) {
                return CommandResult.rejected("需要同时指定源语言和目标语言");
            }
            if (src.equals(tgt)) {
                // src == tgt means the LLM was asked to "translate to X" and
                // put X in both slots. We can only auto-flip when X is the
                // language currently being SPOKEN (the source): then the user
                // wants the direction reversed so X becomes the target. If X
                // is a third language (neither current source nor target) the
                // direction is genuinely ambiguous — reject and ask for both
                // slots rather than silently picking the wrong source.
                String curSrc = session.sourceLang();
                String curTgt = session.targetLang();
                if (src.equals(curTgt)) {
                    return CommandResult.ok("已经在翻译成 " + displayName(src)).build();
                }
                if (!src.equals(curSrc)) {
                    return CommandResult.rejected(
                            "有歧义：'" + displayName(src)
                                    + "' 既不是当前源语言（"
                                    + displayName(curSrc) + "）也不是目标语言（"
                                    + displayName(curTgt) + "），请同时指定两者");
                }
                SessionConfig.Snapshot snap = session.snapshot();
                session.setLanguages(curTgt, src);
                config.setSourceLang(curTgt);
                config.setTargetLang(src);
                return CommandResult.ok(
                        "翻译语言 → " + displayName(curTgt) + " ↔ " + displayName(src)
                                + "（已翻转，原 " + displayName(curSrc)
                                + " ↔ " + displayName(curTgt) + "）")
                        .effect(CommandResult.Effect.RERENDER)
                        .snapshot(snap)
                        .build();
            }
            SessionConfig.Snapshot snap = session.snapshot();
            String prevSrc = session.sourceLang();
            String prevTgt = session.targetLang();
            session.setLanguages(src, tgt);
            // Persist so the user doesn't have to re-set it next session.
            config.setSourceLang(src);
            config.setTargetLang(tgt);
            return CommandResult.ok(
                    "翻译语言 → " + displayName(src) + " ↔ " + displayName(tgt)
                            + "（原 " + displayName(prevSrc) + " ↔ " + displayName(prevTgt) + "）")
                    .effect(CommandResult.Effect.RERENDER)
                    .snapshot(snap)
                    .build();
        }
    }

    // -----------------------------------------------------------------
    // 2. set_display_mode
    // -----------------------------------------------------------------

    public static final class SetDisplayMode implements VoiceTool {
        @Override public String name() { return "set_display_mode"; }

        private static final JSONObject SCHEMA = buildSchema(
                "set_display_mode",
                "Filter what the transcript shows. \"both\" = source + target; " +
                "\"source_only\" = only the source language; \"target_only\" = only the target.",
                new ParamDef[]{
                        new ParamDef("mode", "string",
                                "One of \"both\", \"source_only\", \"target_only\".", true),
                        new ParamDef("trigger_text", "string",
                                "The original spoken phrase that triggered this command.", false)
                }
        );

        @Override public JSONObject functionSchema() { return SCHEMA; }

        @Override public CommandResult apply(JSONObject args, SessionConfig session, ConfigStore config) {
            if (args == null) return CommandResult.rejected("缺少参数");
            String mode = Json.optString(args, "mode", "").trim();
            SessionConfig.DisplayMode m;
            if ("source_only".equals(mode)) m = SessionConfig.DisplayMode.SOURCE_ONLY;
            else if ("target_only".equals(mode)) m = SessionConfig.DisplayMode.TARGET_ONLY;
            else if ("both".equals(mode)) m = SessionConfig.DisplayMode.BOTH;
            else return CommandResult.rejected("未知显示模式：" + mode);
            SessionConfig.Snapshot snap = session.snapshot();
            SessionConfig.DisplayMode prev = session.displayMode();
            session.setDisplayMode(m);
            config.setDisplayMode(m.key());
            return CommandResult.ok("显示 → " + displayModeName(m)
                            + "（原 " + displayModeName(prev) + "）")
                    .effect(CommandResult.Effect.RERENDER)
                    .snapshot(snap)
                    .build();
        }
    }

    // -----------------------------------------------------------------
    // 3. toggle_cascade
    // -----------------------------------------------------------------

    public static final class ToggleCascade implements VoiceTool {
        @Override public String name() { return "toggle_cascade"; }

        private static final JSONObject SCHEMA = buildSchema(
                "toggle_cascade",
                "Switch between the cascade (fun-asr ASR + qwen-turbo MT) " +
                "and the joint (Qwen-Omni Realtime) pipeline. Takes effect on " +
                "the NEXT session start — voice commands are only supported " +
                "in cascade mode.",
                new ParamDef[]{
                        new ParamDef("enabled", "boolean",
                                "true = cascade, false = joint.", true),
                        new ParamDef("trigger_text", "string",
                                "The original spoken phrase that triggered this command.", false)
                }
        );

        @Override public JSONObject functionSchema() { return SCHEMA; }

        @Override public CommandResult apply(JSONObject args, SessionConfig session, ConfigStore config) {
            if (args == null || !args.has("enabled")) {
                return CommandResult.rejected("缺少 enabled 参数");
            }
            boolean enabled = args.optBoolean("enabled", true);
            boolean prev = config.isCascadeMode();
            if (prev == enabled) {
                // Idempotent — no need for an UNDO entry.
                return CommandResult.ok("级联模式已" + (enabled ? "开启" : "关闭")).build();
            }
            SessionConfig.Snapshot snap = session.snapshot();
            config.setCascadeMode(enabled);
            return CommandResult.ok("级联模式 → " + (enabled ? "开" : "关")
                            + "（下次开始生效）")
                    .effect(CommandResult.Effect.ENGINE_RECONCILE)
                    .snapshot(snap)
                    .prevCascade(prev)
                    .build();
        }
    }

    // -----------------------------------------------------------------
    // 4. toggle_debug
    // -----------------------------------------------------------------

    public static final class ToggleDebug implements VoiceTool {
        @Override public String name() { return "toggle_debug"; }

        private static final JSONObject SCHEMA = buildSchema(
                "toggle_debug",
                "Show or hide the debug log panel at the bottom of the screen.",
                new ParamDef[]{
                        new ParamDef("enabled", "boolean",
                                "true = show debug panel, false = hide.", true),
                        new ParamDef("trigger_text", "string",
                                "The original spoken phrase that triggered this command.", false)
                }
        );

        @Override public JSONObject functionSchema() { return SCHEMA; }

        @Override public CommandResult apply(JSONObject args, SessionConfig session, ConfigStore config) {
            if (args == null || !args.has("enabled")) {
                return CommandResult.rejected("缺少 enabled 参数");
            }
            boolean enabled = args.optBoolean("enabled", true);
            boolean prev = config.isDebugVisible();
            if (prev == enabled) {
                return CommandResult.ok("调试已" + (enabled ? "开启" : "关闭")).build();
            }
            SessionConfig.Snapshot snap = session.snapshot();
            config.setDebugVisible(enabled);
            return CommandResult.ok("调试 → " + (enabled ? "开" : "关"))
                    .effect(CommandResult.Effect.DEBUG_TOGGLE)
                    .snapshot(snap)
                    .prevDebug(prev)
                    .build();
        }
    }

    // -----------------------------------------------------------------
    // 5. set_translation_mode  (merged: style + temperature)
    // -----------------------------------------------------------------

    public static final class SetTranslationMode implements VoiceTool {
        @Override public String name() { return "set_translation_mode"; }

        private static final JSONObject SCHEMA = buildSchema(
                "set_translation_mode",
                "Adjust translation behaviour. BOTH 'style' and 'temperature' are " +
                "optional — pass whichever the user mentioned, omit the rest to leave " +
                "unchanged. 'style' is a free-form modifier injected into the prompt " +
                "(e.g. 'more formal', 'more concise', 'casual'); pass an empty string " +
                "to clear the current style. 'temperature' is the LLM sampling " +
                "temperature in [0, 1] — 0 is deterministic, 1 is maximum creativity; " +
                "values are clamped. Style changes take effect on the NEXT utterance; " +
                "temperature is persisted across sessions.",
                new ParamDef[]{
                        new ParamDef("style", "string",
                                "Optional. Natural-language style description in English or " +
                                "Chinese. Empty string clears the previous style. Omit to leave " +
                                "unchanged.", false),
                        new ParamDef("temperature", "number",
                                "Optional. New temperature in [0, 1]. Clamped. Omit to leave " +
                                "unchanged.", false),
                        new ParamDef("trigger_text", "string",
                                "The original spoken phrase that triggered this command.", false)
                }
        );

        @Override public JSONObject functionSchema() { return SCHEMA; }

        @Override public CommandResult apply(JSONObject args, SessionConfig session, ConfigStore config) {
            if (args == null) return CommandResult.rejected("缺少参数");
            boolean hasStyle = args.has("style");
            boolean hasTemp = args.has("temperature");
            if (!hasStyle && !hasTemp) {
                return CommandResult.rejected("至少指定风格或温度之一");
            }
            // Validate temperature BEFORE mutating any state. Previously the
            // style was applied first and then a non-numeric temperature
            // rejected the call — leaving style changed with no UNDO path and
            // a "rejected" chip that lied about what happened.
            Float newTemp = null;
            if (hasTemp) {
                double raw = args.optDouble("temperature", Double.NaN);
                if (Double.isNaN(raw)) return CommandResult.rejected("温度不是数字");
                newTemp = (float) raw;
            }
            SessionConfig.Snapshot snap = session.snapshot();
            StringBuilder summary = new StringBuilder("");
            if (hasStyle) {
                String style = Json.optString(args, "style", "").trim();
                String prev = session.stylePrompt();
                session.setStylePrompt(style.isEmpty() ? null : style);
                if (style.isEmpty()) {
                    summary.append("风格已清除").append(prev == null ? "" : "（原：\"" + prev + "\"）");
                } else {
                    summary.append("风格=\"").append(style).append("\"")
                           .append(prev == null ? "" : "（原：\"" + prev + "\"）");
                }
            }
            if (newTemp != null) {
                float prev = session.temperature();
                session.setTemperature(newTemp);
                float now = session.temperature();
                config.setTemperature(now);
                if (hasStyle) summary.append("  •");
                summary.append("温度=").append(String.format(Locale.ROOT, "%.2f", now))
                       .append("（原 ").append(String.format(Locale.ROOT, "%.2f", prev)).append("）");
            }
            return CommandResult.ok(summary.toString()).snapshot(snap).build();
        }
    }

    // -----------------------------------------------------------------
    // 6. get_current_settings
    // -----------------------------------------------------------------

    public static final class GetCurrentSettings implements VoiceTool {
        @Override public String name() { return "get_current_settings"; }

        private static final JSONObject SCHEMA = buildSchema(
                "get_current_settings",
                "Read-only: return a summary of the interpreter's current " +
                "configuration (languages, display mode, style, temperature, " +
                "cascade mode, debug visibility). The user sees the answer " +
                "as a chip in the transcript.",
                new ParamDef[]{
                        new ParamDef("trigger_text", "string",
                                "The original spoken phrase that triggered this command.", false)
                }
        );

        @Override public JSONObject functionSchema() { return SCHEMA; }

        @Override public CommandResult apply(JSONObject args, SessionConfig session, ConfigStore config) {
            // Read-only — no snapshot, so MainActivity doesn't offer an UNDO
            // toast for a no-op.
            String style = session.stylePrompt();
            String summary = "语言：" + displayName(session.sourceLang())
                    + " ↔ " + displayName(session.targetLang())
                    + "  •  显示：" + displayModeName(session.displayMode())
                    + "  •  风格：" + (style == null ? "（无）" : "\"" + style + "\"")
                    + "  •  温度：" + String.format(Locale.ROOT, "%.2f", session.temperature())
                    + "  •  级联：" + (config.isCascadeMode() ? "开" : "关")
                    + "  •  调试：" + (config.isDebugVisible() ? "开" : "关");
            return CommandResult.ok(summary).build();
        }
    }

    // -----------------------------------------------------------------
    // 7. clear_transcript
    // -----------------------------------------------------------------

    public static final class ClearTranscript implements VoiceTool {
        @Override public String name() { return "clear_transcript"; }

        private static final JSONObject SCHEMA = buildSchema(
                "clear_transcript",
                "Wipe the transcript history (all previously-rendered turns). " +
                "The current mic session is unaffected. The command's own " +
                "chip is preserved so the user can see what just happened.",
                new ParamDef[]{
                        new ParamDef("trigger_text", "string",
                                "The original spoken phrase that triggered this command.", false)
                }
        );

        @Override public JSONObject functionSchema() { return SCHEMA; }

        @Override public CommandResult apply(JSONObject args, SessionConfig session, ConfigStore config) {
            // Destructive by design — the wiped Turn list lives in
            // MainActivity and a SessionConfig.Snapshot can't restore it,
            // so we deliberately carry NO snapshot. That suppresses the
            // UNDO toast (which would otherwise offer a no-op undo) and
            // signals "this one is not undoable".
            return CommandResult.ok("已清空记录")
                    .effect(CommandResult.Effect.CLEAR_TRANSCRIPT)
                    .build();
        }
    }

    // -----------------------------------------------------------------
    // 9. toggle_mic
    // -----------------------------------------------------------------

    public static final class ToggleMic implements VoiceTool {
        @Override public String name() { return "toggle_mic"; }

        private static final JSONObject SCHEMA = buildSchema(
                "toggle_mic",
                "Pause or resume the microphone WITHOUT stopping the engine. " +
                "While paused, the audio capture thread is alive but skips " +
                "AudioRecord.read() so no new audio reaches the ASR server. " +
                "The MT worker and ASR WebSocket stay open, so toggling is " +
                "instantaneous (no reconnection cost). Toggling back to 'resumed' " +
                "continues capture seamlessly. Takes effect IMMEDIATELY.",
                new ParamDef[]{
                        new ParamDef("paused", "boolean",
                                "true = pause the mic, false = resume.", true),
                        new ParamDef("trigger_text", "string",
                                "The original spoken phrase that triggered this command.", false)
                }
        );

        @Override public JSONObject functionSchema() { return SCHEMA; }

        @Override public CommandResult apply(JSONObject args, SessionConfig session, ConfigStore config) {
            if (args == null || !args.has("paused")) {
                return CommandResult.rejected("缺少 paused 参数");
            }
            boolean paused = args.optBoolean("paused", false);
            boolean prev = session.micPaused();
            if (prev == paused) {
                // Idempotent — no need for an UNDO entry.
                return CommandResult.ok("麦克风已" + (paused ? "暂停" : "继续")).build();
            }
            SessionConfig.Snapshot snap = session.snapshot();
            session.setMicPaused(paused);
            return CommandResult.ok(
                    "麦克风 → " + (paused ? "暂停" : "继续")
                            + "（原 " + (prev ? "暂停" : "继续") + "）")
                    .effect(CommandResult.Effect.MIC_REFRESH)
                    .snapshot(snap)
                    .build();
        }
    }

    // -----------------------------------------------------------------
    // 10. set_log_level
    // -----------------------------------------------------------------

    public static final class SetLogLevel implements VoiceTool {
        @Override public String name() { return "set_log_level"; }

        private static final JSONObject SCHEMA = buildSchema(
                "set_log_level",
                "Set the verbosity of the in-app debug log panel. " +
                "'verbose' shows everything (every ASR packet, every MT chunk — " +
                "useful for diagnosing network or streaming issues but very " +
                "noisy). 'normal' shows only high-signal events (status " +
                "changes, tool calls, errors). 'quiet' shows only critical " +
                "events (errors, boot). Takes effect immediately.",
                new ParamDef[]{
                        new ParamDef("level", "string",
                                "One of \"verbose\", \"normal\", \"quiet\".", true),
                        new ParamDef("trigger_text", "string",
                                "The original spoken phrase that triggered this command.", false)
                }
        );

        @Override public JSONObject functionSchema() { return SCHEMA; }

        @Override public CommandResult apply(JSONObject args, SessionConfig session, ConfigStore config) {
            if (args == null) return CommandResult.rejected("缺少参数");
            String lvl = Json.optString(args, "level", "").trim().toLowerCase(java.util.Locale.ROOT);
            DebugLogger.Level newLevel;
            switch (lvl) {
                case "verbose": newLevel = DebugLogger.Level.VERBOSE; break;
                case "normal":  newLevel = DebugLogger.Level.NORMAL;  break;
                case "quiet":   newLevel = DebugLogger.Level.QUIET;   break;
                default: return CommandResult.rejected("未知日志级别：" + lvl);
            }
            String lvlZh = "verbose".equals(lvl) ? "详细" : "normal".equals(lvl) ? "普通" : "安静";
            return CommandResult.ok("日志级别 → " + lvlZh).logLevel(newLevel).build();
        }
    }

    // -----------------------------------------------------------------
    // 11. export_transcript
    // -----------------------------------------------------------------

    public static final class ExportTranscript implements VoiceTool {
        @Override public String name() { return "export_transcript"; }

        private static final JSONObject SCHEMA = buildSchema(
                "export_transcript",
                "Copy the current transcript (history of all turns) to the " +
                "system clipboard. The user can then paste it into any other " +
                "app. Returns the character count in the chip.",
                new ParamDef[]{
                        new ParamDef("trigger_text", "string",
                                "The original spoken phrase that triggered this command.", false)
                }
        );

        @Override public JSONObject functionSchema() { return SCHEMA; }

        @Override public CommandResult apply(JSONObject args, SessionConfig session, ConfigStore config) {
            // The actual clipboard write happens in MainActivity (it owns
            // the history list). We just signal it here.
            return CommandResult.ok("正在复制到剪贴板")
                    .effect(CommandResult.Effect.EXPORT_CLIPBOARD)
                    .build();
        }
    }

    // -----------------------------------------------------------------
    // 12. summarize_session
    // -----------------------------------------------------------------

    public static final class SummarizeSession implements VoiceTool {
        @Override public String name() { return "summarize_session"; }

        private static final JSONObject SCHEMA = buildSchema(
                "summarize_session",
                "Summarize the entire transcript so far into a single " +
                "concise paragraph (or bullet points) in the same languages " +
                "as the transcript. The summary is sent as a new " +
                "command-style chip in the transcript. Useful at the end of " +
                "a meeting to capture the discussion in one place.",
                new ParamDef[]{
                        new ParamDef("trigger_text", "string",
                                "The original spoken phrase that triggered this command.", false)
                }
        );

        @Override public JSONObject functionSchema() { return SCHEMA; }

        @Override public CommandResult apply(JSONObject args, SessionConfig session, ConfigStore config) {
            return CommandResult.ok("正在总结…")
                    .effect(CommandResult.Effect.SUMMARIZE_SESSION)
                    .build();
        }
    }

    // -----------------------------------------------------------------
    // 13. re_translate_last
    // -----------------------------------------------------------------

    public static final class RetranslateLast implements VoiceTool {
        @Override public String name() { return "re_translate_last"; }

        private static final JSONObject SCHEMA = buildSchema(
                "re_translate_last",
                "Re-feed the LAST turn's verbatim source to the MT LLM with " +
                "the CURRENT style/temperature (so any recent style change " +
                "applies). Useful after the user has changed translation " +
                "style and wants the previous turn re-rendered. The " +
                "previous turn is replaced in-place; its chip turns into " +
                "the new translation.",
                new ParamDef[]{
                        new ParamDef("trigger_text", "string",
                                "The original spoken phrase that triggered this command.", false)
                }
        );

        @Override public JSONObject functionSchema() { return SCHEMA; }

        @Override public CommandResult apply(JSONObject args, SessionConfig session, ConfigStore config) {
            return CommandResult.ok("正在重新翻译…")
                    .effect(CommandResult.Effect.RETRANSLATE_LAST)
                    .build();
        }
    }

    // -----------------------------------------------------------------
    // 14. list_commands
    // -----------------------------------------------------------------

    public static final class ListCommands implements VoiceTool {
        @Override public String name() { return "list_commands"; }

        private static final JSONObject SCHEMA = buildSchema(
                "list_commands",
                "List all available voice commands and what they do. " +
                "Call this when the user asks 'what can you do', 'help', " +
                "'what commands are available', '你能做什么', '有什么命令', " +
                "etc. The command catalog will be returned and shown to " +
                "the user as a chip in the transcript.",
                new ParamDef[]{
                        new ParamDef("trigger_text", "string",
                                "The original spoken phrase that triggered this command.", false)
                }
        );

        @Override public JSONObject functionSchema() { return SCHEMA; }

        @Override public CommandResult apply(JSONObject args, SessionConfig session, ConfigStore config) {
            // MainActivity intercepts this tool name in onCommand and
            // builds the catalog from the live ToolRegistry. We just
            // signal it via a special result.
            return CommandResult.ok("正在列出命令…")
                    .effect(CommandResult.Effect.LIST_COMMANDS)
                    .build();
        }
    }

    // -----------------------------------------------------------------
    // Schema helpers
    // -----------------------------------------------------------------

    /** Tuple describing one parameter in a tool schema. */
    private static final class ParamDef {
        final String name;
        final String type;
        final String description;
        final boolean required;
        ParamDef(String name, String type, String description, boolean required) {
            this.name = name;
            this.type = type;
            this.description = description;
            this.required = required;
        }
    }

    /** Build an OpenAI-compat function schema with all JSONException
     *  handling hidden in one place. Called once per tool at class
     *  init; the result is stored in a static final. */
    private static JSONObject buildSchema(String name, String description, ParamDef[] params) {
        try {
            JSONObject props = new JSONObject();
            JSONArray required = new JSONArray();
            for (ParamDef p : params) {
                JSONObject pj = new JSONObject();
                pj.put("type", p.type);
                pj.put("description", p.description);
                props.put(p.name, pj);
                if (p.required) required.put(p.name);
            }
            JSONObject parameters = new JSONObject();
            parameters.put("type", "object");
            parameters.put("properties", props);
            if (required.length() > 0) parameters.put("required", required);

            JSONObject fn = new JSONObject();
            fn.put("name", name);
            fn.put("description", description);
            fn.put("parameters", parameters);

            JSONObject wrapper = new JSONObject();
            wrapper.put("type", "function");
            wrapper.put("function", fn);
            return wrapper;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    // -----------------------------------------------------------------
    // Display helpers
    // -----------------------------------------------------------------

    private static String displayName(String lang) {
        return LangDisplay.name(lang);
    }

    private static String displayModeName(SessionConfig.DisplayMode m) {
        switch (m) {
            case SOURCE_ONLY: return "仅原文";
            case TARGET_ONLY: return "仅译文";
            default:          return "双语";
        }
    }
}
