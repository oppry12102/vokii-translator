package com.vokii.translator;

import org.json.JSONObject;

/**
 * Splits a Qwen-Omni turn into a Chinese / English pair for the two UI
 * columns. Handles the streaming {@code ZH: .. / EN: ..} line format we
 * instruct the model to use, and tolerates a {@code {"zh":..,"en":..}}
 * JSON blob as a fallback.
 *
 * Crucially it does NOT trust the model's labels: after extracting two
 * candidate strings it routes them by content — whichever side contains
 * more Han characters becomes {@link #zh} (left column). This
 * deterministically fixes the occasional zh/en swap regardless of how the
 * model tagged them.
 */
final class Bilingual {

    final String zh;
    final String en;

    private Bilingual(String zh, String en) {
        this.zh = zh == null ? "" : zh;
        this.en = en == null ? "" : en;
    }

    static Bilingual parse(String raw) {
        if (raw == null) return new Bilingual("", "");
        String s = raw.trim();
        if (s.isEmpty()) return new Bilingual("", "");

        // JSON fallback: {"zh":"..","en":".."}
        if (s.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(extractJson(s));
                return route(o.optString("zh", "").trim(), o.optString("en", "").trim());
            } catch (Throwable ignored) { /* fall through to line parsing */ }
        }

        int zi = s.indexOf("ZH:");
        int ei = s.indexOf("EN:");
        String zh, en;
        if (ei >= 0) {
            int zStart = zi >= 0 && zi < ei ? zi + 3 : 0;
            zh = s.substring(zStart, ei);
            en = s.substring(ei + 3);
        } else {
            zh = zi >= 0 ? s.substring(zi + 3) : s;
            en = "";
        }
        return route(clean(zh), clean(en));
    }

    /** Put the Han-heavy string on the left (zh), the other on the right (en). */
    private static Bilingual route(String a, String b) {
        if (han(b) > han(a)) return new Bilingual(b, a);
        return new Bilingual(a, b);
    }

    private static String clean(String s) {
        if (s == null) return "";
        // Drop leading label punctuation / whitespace that can leak in.
        return s.replaceAll("^[\\s:：]+", "").trim();
    }

    private static int han(String s) {
        int c = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) c++;
        }
        return c;
    }

    private static String extractJson(String s) {
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        return (a >= 0 && b > a) ? s.substring(a, b + 1) : s;
    }
}
