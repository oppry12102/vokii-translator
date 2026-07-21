package com.vokii.translator;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Grounding-cue net: real command phrasings (the inject-panel presets
 *  and common English forms) must pass; the hallucinated triggers
 *  observed in emulator runs (2026-07-20) must be rejected. */
public class GroundingCuesTest {

    // ----- real commands must stay grounded -----

    @Test public void presetCommandsAreGrounded() {
        assertTrue(GroundingCues.isGrounded("set_translation_languages", "下面改成中日翻译"));
        assertTrue(GroundingCues.isGrounded("set_display_mode", "只显示日文就好"));
        assertTrue(GroundingCues.isGrounded("toggle_cascade", "切换到普通模式"));
        assertTrue(GroundingCues.isGrounded("toggle_debug", "打开调试"));
        assertTrue(GroundingCues.isGrounded("toggle_mic", "暂停。"));
        assertTrue(GroundingCues.isGrounded("toggle_mic", "继续"));
        assertTrue(GroundingCues.isGrounded("set_log_level", "把日志设成详细模式。"));
        assertTrue(GroundingCues.isGrounded("export_transcript", "复制到剪贴板。"));
        assertTrue(GroundingCues.isGrounded("summarize_session", "总结一下。"));
        assertTrue(GroundingCues.isGrounded("re_translate_last", "重新翻译上一句。"));
        assertTrue(GroundingCues.isGrounded("list_commands", "你能做什么？"));
        assertTrue(GroundingCues.isGrounded("set_translation_mode", "翻译得更文雅一些"));
        assertTrue(GroundingCues.isGrounded("get_current_settings", "现在是什么设置？"));
        assertTrue(GroundingCues.isGrounded("set_translation_mode", "温度调到 0.7"));
        assertTrue(GroundingCues.isGrounded("clear_transcript", "清空翻译"));
    }

    @Test public void englishCommandsAreGrounded() {
        assertTrue(GroundingCues.isGrounded("set_translation_languages", "switch to Japanese"));
        assertTrue(GroundingCues.isGrounded("toggle_mic", "mute"));
        assertTrue(GroundingCues.isGrounded("set_display_mode", "show only English"));
        assertTrue(GroundingCues.isGrounded("list_commands", "help"));
        assertTrue(GroundingCues.isGrounded("summarize_session", "summarize please"));
    }

    // ----- observed hallucinations must be rejected -----

    @Test public void conversationalTriggersAreRejected() {
        // set_translation_languages fired on this in zh run1.
        assertFalse(GroundingCues.isGrounded("set_translation_languages",
                "我平常还特别喜欢一个播客叫无聊斋，have you heard of it？"));
        // re_translate_last fired on these in en runs.
        assertFalse(GroundingCues.isGrounded("re_translate_last", "What you're actually"));
        assertFalse(GroundingCues.isGrounded("re_translate_last", "WellWell"));
        assertFalse(GroundingCues.isGrounded("re_translate_last",
                "But it's not gonna beo feel like sosy"));
        // set_translation_mode (style="casual") fired on this in zh run3.
        assertFalse(GroundingCues.isGrounded("set_translation_mode",
                "确实在在英国，我只只有很嗯很早之也不是很早之前呃"));
    }

    @Test public void emptyOrUnknownPassesCorrectly() {
        assertFalse(GroundingCues.isGrounded("toggle_debug", ""));
        assertFalse(GroundingCues.isGrounded("toggle_debug", null));
        // Unknown tools pass — don't block on our own ignorance.
        assertTrue(GroundingCues.isGrounded("some_future_tool", "anything"));
    }
}
