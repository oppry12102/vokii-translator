package com.vokii.translator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Builds the system prompt and tool schema for the MT LLM call.
 *
 * <p>When a {@link ToolRegistry} is supplied, the system prompt is
 * extended with a command-recognition paragraph that instructs the LLM
 * to detect voice commands alongside the normal translation work, and
 * the registry's tool schemas are returned by
 * {@link #buildToolsJson(ToolRegistry)} for the request body.
 *
 * <h2>Why dynamic labels</h2>
 * The labels the model emits ({@code ZH: ..} / {@code JA: ..} / etc.) are
 * the contract {@link TurnParser} consumes. Hardcoding them to
 * {@code ZH}/{@code EN} in the LLM is the bug Phase 0 fixes — instead we
 * emit whatever the current session pair is.
 *
 * <h2>Style suffix</h2>
 * Free-form style modifiers from {@link SessionConfig#stylePrompt()} are
 * appended as a single sentence. Empty/blank = no style line.
 *
 * <h2>Command recognition (Phase 1)</h2>
 * The model is told:
 * <ul>
 *   <li>A "pure control command" utterance (e.g. "下面改成中日翻译")
 *       must emit tool_call(s) and NO translation lines.</li>
 *   <li>A "mixed" utterance (command + content) must emit tool_call(s)
 *       and STILL output NO translation lines — the command is meant
 *       to take effect on the NEXT utterance, so translating the
 *       current one is misleading (it'd appear under the old pair).</li>
 *   <li>Plain content must be translated normally; no tool_call.</li>
 *   <li><b>Default to translation when uncertain</b> — false positives
 *       on commands are worse than missed commands (the user can
 *       re-say the command; a wrongly-mutated setting is annoying).</li>
 * </ul>
 */
public final class MtPromptBuilder {

    private MtPromptBuilder() {}

    /** Build the system prompt for the current session. Pass {@code null}
     *  for {@code registry} to disable tool_use (translation-only mode).
     *  When {@code ctx.session().sourceLang()} is "auto", the prompt is a
     *  generic bilingual one: the LLM auto-detects the spoken language
     *  (Chinese or English) and always emits both labels.
     *
     *  The {@code ctx} still drives the (semi-static) language-pair + style
     *  lines in this prompt, but its live "SESSION CONTEXT" section (state
     *  + recent commands + recent utterances) is NOT appended here — it
     *  opens the USER message via {@link #buildUserMessage} instead, so this
     *  system prompt stays byte-static across turns and the qwen-turbo
     *  implicit context cache can cover the tools schema too (a dynamic tail
     *  here broke the cache for everything after it, ~2048 tok lost). */
    public static String buildSystemPrompt(SessionContext ctx, ToolRegistry registry) {
        SessionConfig session = ctx.session();
        String src = session.sourceLang();
        String tgt = session.targetLang();
        String srcName = displayName(src);
        String tgtName = displayName(tgt);
        String srcLabel = labelFor(src);
        String tgtLabel = labelFor(tgt);

        // CORE PRINCIPLE: the source-language line is the VERBATIM transcript
        // of what the user said. The other line is the translation, which can
        // be styled freely. Voice commands control translation only, never
        // the transcription itself.
        StringBuilder sb = new StringBuilder();
        sb.append("CORE PRINCIPLE — VERBATIM SOURCE + FREE TRANSLATION:\n")
          .append("1. The line matching the SPOKEN language must be EXACTLY what ")
          .append("the user said — FROM THE VERY FIRST WORD to the last. ")
          .append("Preserve every word, every '呃'/'嗯'/'那个' filler, every ")
          .append("repetition, every grammatical error, every slang, every ")
          .append("non-standard expression. Do NOT correct, improve, paraphrase, ")
          .append("or 'clean up' the source text. If the user said ")
          .append("'呃那个我今天呃想要吃苹果', output exactly that — NOT ")
          .append("'我今天想吃苹果'.\n")
          .append("2. CRITICAL FOR CODE-SWITCHING: when the user mixes English ")
          .append("words inside a Chinese sentence (or vice versa), keep the ")
          .append("foreign-language words EXACTLY as the user said them. Do NOT ")
          .append("translate them. This applies at the START of the sentence too ")
          .append("— if the user starts in English and switches to Chinese, the ")
          .append("English opening must remain English. Example: user says ")
          .append("'Alright. 是这样的，好像怎么说，是因为社会的快速发展才使我们 ")
          .append("had to make some innovation' → ZH: must contain the verbatim ")
          .append("'Alright. 是这样的...' including the English 'Alright. had to ")
          .append("make some innovation' fragments, NOT '好的。是这样的...'.\n")
          .append("3. The OTHER line is the translation. It can be styled per the ")
          .append("user's preferences (formal, casual, concise, literary, etc.).\n")
          .append("4. Voice commands (e.g. '下面改成中日翻译', '翻译得更正式一些') ")
          .append("are CONTROL COMMANDS that adjust translation behavior. They ")
          .append("are NOT part of the source text. They do NOT affect the ")
          .append("transcription line in any way.\n\n");
        if ("auto".equalsIgnoreCase(src)) {
            sb.append("You are a real-time bilingual interpreter. The user may speak ")
              .append("either ").append(displayName("zh")).append(" or ").append(displayName("en"))
              .append(" — auto-detect the spoken language.\n\n")
              .append("OUTPUT FORMAT — TWO LINES, ONE LABEL EACH:\n")
              .append("Line 1: the VERBATIM SOURCE TRANSCRIPT, prefixed with the matching label:\n")
              .append("  - If user spoke Chinese, prefix with 'ZH: '\n")
              .append("  - If user spoke English, prefix with 'EN: '\n")
              .append("Line 2: the TRANSLATION into the OTHER language, prefixed with the OTHER label:\n")
              .append("  - If source was Chinese, line 2 is 'EN: <English translation>'\n")
              .append("  - If source was English, line 2 is 'ZH: <Chinese translation>'\n\n")
              .append("RULE: line 1 is always the VERBATIM source. The label on line 1 ")
              .append("must match the language of the source. Line 2 is the translation ")
              .append("with the OTHER language's label.\n\n")
              .append("EXAMPLES:\n")
              .append("User says '我去学校' (Chinese) →\n")
              .append("ZH: 我去学校\n")
              .append("EN: I go to school\n\n")
              .append("User says 'i go to school' (English) →\n")
              .append("EN: i go to school\n")
              .append("ZH: 我去学校\n\n")
              .append("User says '呃那个我今天呃想吃苹果' (Chinese with fillers) →\n")
              .append("ZH: 呃那个我今天呃想吃苹果\n")
              .append("EN: Um, that, um, I want to eat an apple today\n\n")
              .append("Use no labels other than 'ZH:' and 'EN:'. ")
              .append("No extra commentary, no markdown, no apologies.");
        } else {
            sb.append("You are a real-time interpreter translating from ")
              .append(srcName).append(" to ").append(tgtName).append(". ")
              .append("Output BOTH lines for every utterance in this EXACT format:\n")
              .append(srcLabel).append(" <verbatim ").append(srcName).append(" source>\n")
              .append(tgtLabel).append(" <").append(tgtName).append(" translation of that source>\n\n")
              .append("CRITICAL: the ").append(srcLabel).append(" line is the VERBATIM ")
              .append("transcript of what the user said (preserve every word, every ")
              .append("'呃'/'嗯'/'那个' filler, every repetition, every grammatical ")
              .append("error, every slang). The ").append(tgtLabel).append(" line is the ")
              .append("translation, which may be styled per the user's preference.\n\n")
              .append("Use no labels other than '").append(srcLabel).append("' and '")
              .append(tgtLabel).append("'. ")
              .append("No extra commentary, no markdown, no apologies.");
        }

        String style = session.stylePrompt();
        if (style != null && !style.isEmpty()) {
            sb.append("\n\nStyle preference: ").append(style).append(".");
        }

        if (registry != null && !registry.names().isEmpty()) {
            sb.append("\n\nYou are EAVESDROPPING on a real conversation between two ")
              .append("people and translating it. Almost EVERY utterance is content ")
              .append("to translate — even when it mentions languages, translation, ")
              .append("podcasts, settings, styles, or the word 'mode'. A SYSTEM ")
              .append("CONTROL COMMAND is RARE: it is SHORT, STANDALONE, and speaks ")
              .append("directly TO you with an imperative verb, e.g. '下面改成中日翻译', ")
              .append("'只显示日文就好', '打开调试', '暂停', '复制到剪贴板', '总结一下', ")
              .append("'重新翻译上一句', '温度调到0.7', '你能做什么'. The available ")
              .append("commands cover: switching languages, hiding one language ")
              .append("column, opening the debug panel, pausing/resuming the ")
              .append("microphone (toggle_mic {paused:true/false} for '暂停'/'继续'/" )
              .append("'mute'/'unmute'), copying the transcript, summarizing the ")
              .append("session, re-translating the last turn, adjusting log ")
              .append("verbosity, changing translation style/temperature ")
              .append("(set_translation_mode — pass only the fields the user ")
              .append("mentioned; do NOT confuse the literal word '模式' with this ")
              .append("tool: '切换到普通模式' means toggle_cascade), and listing ")
              .append("commands.\n")
              .append("NEVER treat the following as commands — translate them ")
              .append("instead:\n")
              .append("- talking ABOUT a language, a show, or translation itself ")
              .append("('我会说一点日语', '我平常还特别喜欢一个播客叫无聊斋, have you ")
              .append("heard of it?')\n")
              .append("- code-switching mid-sentence (mixing English into Chinese ")
              .append("speech is CONTENT, not a language-switch request)\n")
              .append("- questions or quotes ('What are you actually writing ")
              .append("about?')\n")
              .append("- mentioning modes, styles, or settings in conversation.\n")
              .append("Rules:\n")
              .append("1. PURE control command → call the matching tool and output ")
              .append("NO translation lines (not even empty ones).\n")
              .append("2. Mixed (a clear standalone command clause plus content) → ")
              .append("STILL call the tool and output NO translation lines — the ")
              .append("command takes effect on the NEXT utterance, so translating ")
              .append("the current one would mislead the user.\n")
              .append("3. Plain content → translate normally and DO NOT call any ")
              .append("tool.\n")
              .append("4. When unsure whether an utterance is a command, do NOT ")
              .append("call a tool — translate it normally. False-positive tool ")
              .append("calls are far worse than missed commands.\n")
              .append("5. When you do call a tool, pass the exact command phrase ")
              .append("as \"trigger_text\" — it must appear VERBATIM in the ")
              .append("utterance, and it must contain the command keyword (e.g. ")
              .append("'翻译'/'语言' for a language switch, '调试' for debug, ")
              .append("'暂停' for mic pause). If you cannot quote such a phrase, ")
              .append("it is not a command — translate instead.");
        }

        // NOTE: the live SESSION CONTEXT (current state + recent commands +
        // recent utterances) is NO LONGER appended here — it now opens the
        // USER message via buildUserMessage. Keeping this system prompt
        // byte-static across turns lets the qwen-turbo implicit context
        // cache cover the tools schema too (a dynamic tail here broke the
        // cache for everything after it, including tools — ~2048 tok lost).

        return sb.toString();
    }

    /** Build the user-message content for an MT turn: the live SESSION
     *  CONTEXT (current state + recent commands + recent utterances) that
     *  the LLM uses to disambiguate commands like "改成中文" or "再翻一次",
     *  followed by the verbatim utterance to translate. The utterance is
     *  LAST so the model treats it as the turn to respond to; the preceding
     *  SESSION CONTEXT block is self-describing (it ends with a "use the
     *  above to disambiguate" line).
     *  <p>Quality A/B (n=12: plain translation zh/en/code-switch, voice
     *  commands incl. disambiguation, and anti-cases like "我会说一点日语"):
     *  12/12 identical translate-vs-command classification vs the old
     *  system-message placement, so the cache win comes at no behaviour
     *  change. */
    public static String buildUserMessage(SessionContext ctx, String verbatim) {
        return ctx.buildPromptSection() + verbatim;
    }

    /** Phase 0 backwards-compat overload. */
    public static String buildSystemPrompt(SessionContext ctx) {
        return buildSystemPrompt(ctx, null);
    }

    /** Tools JSON array for the LLM request. Returns null when no
     *  registry is supplied (translation-only mode). */
    public static JSONArray buildToolsJson(ToolRegistry registry) {
        if (registry == null || registry.names().isEmpty()) return null;
        return registry.toJsonArray();
    }

    /** Phase 0 backwards-compat overload. */
    public static JSONArray buildToolsJson() {
        return null;
    }

    private static String labelFor(String lang) {
        if (lang == null || lang.isEmpty()) return "";
        int dash = lang.indexOf('-');
        String primary = dash >= 0 ? lang.substring(0, dash) : lang;
        return primary.toUpperCase(Locale.ROOT) + ":";
    }

    private static String displayName(String lang) {
        if (lang == null) return "the source language";
        switch (lang.toLowerCase(Locale.ROOT)) {
            case "zh": return "Mandarin Chinese";
            case "en": return "English";
            case "ja": return "Japanese";
            case "ko": return "Korean";
            case "fr": return "French";
            case "de": return "German";
            case "es": return "Spanish";
            case "ru": return "Russian";
            case "ar": return "Arabic";
            default:  return lang;
        }
    }
}
