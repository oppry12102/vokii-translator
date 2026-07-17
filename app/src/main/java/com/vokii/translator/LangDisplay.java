package com.vokii.translator;

import java.util.Locale;

/**
 * Maps a BCP-47 short language code to a human-readable English name.
 * Single source of truth shared by the tool summaries, the session-context
 * prompt section, and anywhere else that needs to show a language name —
 * previously three near-identical switch tables lived in {@code BuiltInTools},
 * {@code SessionContext}, and {@code MtPromptBuilder}.
 *
 * <p>({@code MtPromptBuilder} keeps its own variant that says "Mandarin
 * Chinese" for zh, since that wording is tuned for the MT instruction
 * prompt; this helper is for UI / summary text.)
 */
final class LangDisplay {

    private LangDisplay() {}

    /** English display name for a language code, e.g. "zh" → "Chinese".
     *  Unknown codes fall back to the code itself. Null → "?". */
    static String name(String code) {
        if (code == null) return "?";
        switch (code.toLowerCase(Locale.ROOT)) {
            case "zh": return "Chinese";
            case "en": return "English";
            case "ja": return "Japanese";
            case "ko": return "Korean";
            case "fr": return "French";
            case "de": return "German";
            case "es": return "Spanish";
            case "ru": return "Russian";
            case "ar": return "Arabic";
            default:   return code;
        }
    }
}
