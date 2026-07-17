package com.vokii.translator;

import org.json.JSONObject;

/**
 * Splits a Qwen-MT turn into a (source, target) pair for the two UI columns.
 * Generalized from the original zh/en-only parser to support any
 * language pair — the LLM is told which labels to use (e.g. {@code ZH: ..}
 * / {@code JA: ..}) and we parse by those labels.
 *
 * <h2>Parsing strategy</h2>
 * <ol>
 *   <li>Try JSON fallback: {@code {"<src>":"..","<tgt>":".."}}</li>
 *   <li>Try line-based parsing using the two labels derived from the
 *       current language pair (e.g. {@code ZH:} and {@code JA:}). Labels
 *       are matched case-insensitively and tolerate a colon of either
 *       ASCII {@code :} or fullwidth {@code ：}.</li>
 *   <li>For the special case of a {@code zh<->en} pair, apply the
 *       original Han-character-count safety net to undo the occasional
 *       label swap the model still produces. For all other pairs the
 *       labels are trusted as-is (we don't have a reliable cross-language
 *       content heuristic to fall back on).</li>
 * </ol>
 *
 * <p>Returns a {@link ParsedTurn} that always carries both fields,
 * possibly empty — empty after a pure command turn is expected and
 * should not be appended to the transcript.
 */
final class TurnParser {

    final String source;
    final String target;

    private TurnParser(String source, String target) {
        this.source = source == null ? "" : source;
        this.target = target == null ? "" : target;
    }

    /** Parse a streaming or final MT turn text. {@code srcLang} and
     *  {@code tgtLang} are BCP-47 short codes (e.g. "zh", "en", "ja"), or
     *  the special value "auto" meaning the LLM auto-detected the source.
     *  When srcLang is "auto", we always look for both ZH: and EN: labels. */
    static TurnParser parse(String raw, String srcLang, String tgtLang) {
        if (raw == null) return new TurnParser("", "");
        String s = raw.trim();
        if (s.isEmpty()) return new TurnParser("", "");

        // "auto" mode: the prompt always emits ZH: and EN: regardless of
        // what's spoken. Treat both as fixed labels and put ZH on the left.
        if ("auto".equalsIgnoreCase(srcLang)) {
            return parseAuto(s);
        }

        String srcLabel = labelFor(srcLang);
        String tgtLabel = labelFor(tgtLang);

        // JSON fallback first: {"zh":"..","en":".."} (lowercase keys).
        if (s.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(extractJson(s));
                return route(Json.optString(o, srcLang, "").trim(),
                             Json.optString(o, tgtLang, "").trim(),
                             srcLang, tgtLang);
            } catch (Throwable ignored) { /* fall through to line parsing */ }
        }

        // Line-based: find both labels. We don't assume which appears
        // first — the model is instructed to follow the order, but we
        // also tolerate swaps.
        int si = indexOfLabel(s, srcLabel);
        int ti = indexOfLabel(s, tgtLabel);
        if (ti >= 0 && si >= 0) {
            String srcText, tgtText;
            if (si < ti) {
                srcText = s.substring(si + srcLabel.length(), ti);
                tgtText = s.substring(ti + tgtLabel.length());
            } else {
                tgtText = s.substring(ti + tgtLabel.length(), si);
                srcText = s.substring(si + srcLabel.length());
            }
            return route(clean(srcText), clean(tgtText), srcLang, tgtLang);
        }
        if (ti >= 0) return route("", clean(s.substring(ti + tgtLabel.length())), srcLang, tgtLang);
        if (si >= 0) return route(clean(s.substring(si + srcLabel.length())), "", srcLang, tgtLang);
        // No labels at all — treat the whole text as source.
        return route(clean(s), "", srcLang, tgtLang);
    }

    /** "auto" mode: always expects ZH: and EN: labels, regardless of
     *  what the user spoke. We still apply the Han-count safety net
     *  (line 95) so an occasional ZH/EN swap from the LLM is fixed. */
    private static TurnParser parseAuto(String s) {
        if (s.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(extractJson(s));
                String src = Json.optString(o, "zh", "").trim();
                String tgt = Json.optString(o, "en", "").trim();
                return route(src, tgt, "zh", "en");
            } catch (Throwable ignored) { /* fall through */ }
        }
        String zhLabel = "ZH:";
        String enLabel = "EN:";
        int zi = indexOfLabel(s, zhLabel);
        int ei = indexOfLabel(s, enLabel);
        if (ei >= 0 && zi >= 0) {
            String zhText, enText;
            if (zi < ei) {
                zhText = s.substring(zi + zhLabel.length(), ei);
                enText = s.substring(ei + enLabel.length());
            } else {
                enText = s.substring(ei + enLabel.length(), zi);
                zhText = s.substring(zi + zhLabel.length());
            }
            return route(clean(zhText), clean(enText), "zh", "en");
        }
        if (ei >= 0) return route("", clean(s.substring(ei + enLabel.length())), "zh", "en");
        if (zi >= 0) return route(clean(s.substring(zi + zhLabel.length())), "", "zh", "en");
        return route(clean(s), "", "zh", "en");
    }

    /** Right-thing routing. For zh<->en we still apply the Han-count
     *  safety net to undo the occasional swap. For all other pairs the
     *  content may be in scripts we can't reliably distinguish (ja/zh
     *  share Han characters; ar vs fa need script tables we don't want
     *  to maintain), so we trust the labels. */
    private static TurnParser route(String src, String tgt, String srcLang, String tgtLang) {
        if (isZhEnPair(srcLang, tgtLang) && han(tgt) > han(src)) {
            return new TurnParser(tgt, src);
        }
        return new TurnParser(src, tgt);
    }

    private static boolean isZhEnPair(String a, String b) {
        boolean zh = "zh".equalsIgnoreCase(a) || "zh".equalsIgnoreCase(b);
        boolean en = "en".equalsIgnoreCase(a) || "en".equalsIgnoreCase(b);
        return zh && en;
    }

    /** Build the parsing label from a BCP-47 code. We use the primary
     *  subtag uppercased plus an ASCII colon, e.g. "zh" -> "ZH:". */
    private static String labelFor(String lang) {
        if (lang == null || lang.isEmpty()) return "";
        int dash = lang.indexOf('-');
        String primary = dash >= 0 ? lang.substring(0, dash) : lang;
        return primary.toUpperCase(java.util.Locale.ROOT) + ":";
    }

    private static int indexOfLabel(String s, String label) {
        if (label.isEmpty()) return -1;
        // Tolerate fullwidth colon.
        int idx = s.indexOf(label);
        if (idx >= 0) return idx;
        if (label.endsWith(":")) {
            return s.indexOf(label.substring(0, label.length() - 1) + "：");
        }
        return -1;
    }

    private static String clean(String s) {
        if (s == null) return "";
        // Strip leading whitespace, the other label's residue, and stray
        // newlines so consecutive deltas concatenate cleanly.
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
