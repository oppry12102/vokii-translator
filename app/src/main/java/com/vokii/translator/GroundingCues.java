package com.vokii.translator;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Anti-hallucination grounding net for voice commands.
 *
 * <p>qwen-turbo fires tool calls on conversational content far too easily
 * (measured on emulator 2026-07-20: {@code set_translation_languages} on
 * "我平常还特别喜欢一个播客叫无聊斋，have you heard of it？",
 * {@code re_translate_last} on "What you're actually…",
 * {@code set_translation_mode} style="casual" on a UK-travel anecdote).
 * The system prompt already says "when unsure, translate" — it is not
 * enough. This is the second layer: a tool call must be <b>grounded</b>,
 * i.e. its {@code trigger_text} must contain at least one cue word
 * associated with that command. A hallucinated call's trigger is the raw
 * conversational sentence, which carries no command cue.
 *
 * <p>Deliberately a NET, not a proof: semantically plausible-but-wrong
 * calls (content that happens to mention a language name) still pass.
 * Those are the prompt's job to prevent. The failure mode for a real
 * command whose phrasing slips past every cue is graceful: it is
 * translated as content and the user can re-say it — far cheaper than a
 * wrongly-mutated session setting.
 */
final class GroundingCues {

    private static final Map<String, String[]> CUES = new HashMap<>();

    static {
        CUES.put("set_translation_languages", new String[]{
                "翻译", "语言", "改成", "切换", "换成",
                "translate", "translation", "language", "switch",
                "日语", "日文", "中文", "英语", "英文", "韩语", "法语", "德语", "西语",
                "japanese", "chinese", "english", "korean", "french", "german", "spanish"});
        CUES.put("set_display_mode", new String[]{
                "显示", "只看", "隐藏", "display", "show", "hide", "column"});
        CUES.put("toggle_cascade", new String[]{
                "普通模式", "级联", "cascade", "joint", "omni"});
        CUES.put("toggle_debug", new String[]{
                "调试", "debug"});
        CUES.put("set_translation_mode", new String[]{
                "风格", "文雅", "正式", "随意", "随便", "简洁", "文学", "温度",
                "style", "temperature", "formal", "casual", "concise", "literary"});
        CUES.put("get_current_settings", new String[]{
                "设置", "settings", "setting", "config"});
        CUES.put("clear_transcript", new String[]{
                "清空", "清除", "删除", "clear", "delete", "wipe"});
        CUES.put("toggle_mic", new String[]{
                "暂停", "继续", "静音", "麦克风", "话筒",
                "mute", "unmute", "pause", "resume", "mic"});
        CUES.put("set_log_level", new String[]{
                "日志", "详细", "log", "verbose", "quiet"});
        CUES.put("export_transcript", new String[]{
                "复制", "剪贴板", "导出", "copy", "clipboard", "export"});
        CUES.put("summarize_session", new String[]{
                "总结", "摘要", "概括", "summar"});
        CUES.put("re_translate_last", new String[]{
                "翻", "translate", "again", "redo", "重新", "再来"});
        CUES.put("list_commands", new String[]{
                "做什么", "命令", "功能", "help", "command", "你能"});
        CUES.put("remember_term", new String[]{
                "记住", "简称", "缩写", "以后叫", "以后翻成", "统一叫", "统一翻成",
                "改一下", "更正", "改成", "记成", "remember", "glossary"});
        CUES.put("list_terms", new String[]{
                "术语", "记住了", "记住了哪些", "记住的词", "有哪些词", "记了什么",
                "list terms", "show terms"});
        CUES.put("set_font_size", new String[]{
                "字体", "字号", "字大", "字小", "变大", "变小", "大一点", "小一点",
                "更大", "更小", "font", "larger", "smaller", "bigger"});
    }

    private GroundingCues() {}

    /** True when the trigger text carries at least one cue for this tool.
     *  Unknown tools pass (don't block on our own ignorance); an empty
     *  trigger never grounds — the model is instructed to echo the exact
     *  command phrase. */
    static boolean isGrounded(String toolName, String triggerText) {
        if (triggerText == null || triggerText.trim().isEmpty()) return false;
        String[] cues = CUES.get(toolName);
        if (cues == null) return true;
        String t = triggerText.toLowerCase(Locale.ROOT);
        for (String c : cues) {
            if (t.contains(c.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
