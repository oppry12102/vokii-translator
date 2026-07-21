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
        return parse(raw, srcLang, tgtLang, false);
    }

    /** Streaming-delta variant: never applies the Han-swap verdict. A swap
     *  decided mid-stream flips which column the UI feeds from between
     *  deltas/generations — each flip visibly wipes the line (measured on
     *  emulator: the typewriter filled with the EN line from an EN-first
     *  draft, then the ZH-first final wiped it, common prefix 0). Trust
     *  the labels while streaming; the final commit's {@link #parse} still
     *  corrects a genuinely swapped pair, once, at the sentence boundary. */
    static TurnParser parseStreaming(String raw, String srcLang, String tgtLang) {
        return parse(raw, srcLang, tgtLang, true);
    }

    private static TurnParser parse(String raw, String srcLang, String tgtLang, boolean streaming) {
        if (raw == null) return new TurnParser("", "");
        String s = raw.trim();
        if (s.isEmpty()) return new TurnParser("", "");

        // "auto" mode: the prompt always emits ZH: and EN: regardless of
        // what's spoken. Treat both as fixed labels and put ZH on the left.
        if ("auto".equalsIgnoreCase(srcLang)) {
            return parseAuto(s, streaming);
        }

        String srcLabel = labelFor(srcLang);
        String tgtLabel = labelFor(tgtLang);

        // Streaming hygiene: a trailing label-in-flight fragment ("Z",
        // "ZH", "ZH：") is not content — showing it flickers the column
        // (2 chars → 0 → real text on the next delta). Cut it so each
        // parsed column grows monotonically within one MT generation.
        s = cutTrailingLabelPrefix(s, srcLabel, tgtLabel);
        if (s.isEmpty()) return new TurnParser("", "");

        // JSON fallback first: {"zh":"..","en":".."} (lowercase keys).
        if (s.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(extractJson(s));
                return streaming
                        ? new TurnParser(Json.optString(o, srcLang, "").trim(),
                                         Json.optString(o, tgtLang, "").trim())
                        : route(Json.optString(o, srcLang, "").trim(),
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
            return streaming
                    ? new TurnParser(clean(srcText), clean(tgtText))
                    : route(clean(srcText), clean(tgtText), srcLang, tgtLang);
        }
        if (ti >= 0) return new TurnParser("", clean(s.substring(ti + tgtLabel.length())));
        if (si >= 0) return new TurnParser(clean(s.substring(si + srcLabel.length())), "");
        // No labels at all — treat the whole text as source.
        return new TurnParser(clean(s), "");
    }

    /** "auto" mode: always expects ZH: and EN: labels, regardless of
     *  what the user spoke. We still apply the Han-count safety net
     *  (line 95) so an occasional ZH/EN swap from the LLM is fixed. */
    private static TurnParser parseAuto(String s, boolean streaming) {
        // Same streaming hygiene as parse(): drop a trailing label-in-flight
        // fragment before looking for the labels.
        s = cutTrailingLabelPrefix(s, "ZH:", "EN:");
        if (s.isEmpty()) return new TurnParser("", "");
        if (s.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(extractJson(s));
                String src = Json.optString(o, "zh", "").trim();
                String tgt = Json.optString(o, "en", "").trim();
                return streaming ? new TurnParser(src, tgt) : route(src, tgt, "zh", "en");
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
            return streaming
                    ? new TurnParser(clean(zhText), clean(enText))
                    : route(clean(zhText), clean(enText), "zh", "en");
        }
        // Single-label / no-label streaming parses must TRUST the label —
        // no swap verdict is meaningful without both sides, and the swap
        // rule would misfire here every time a draft streams EN: first:
        // the Han-free empty src side satisfies han(src)==0 while the EN
        // line's code-switch Han segments satisfy han(tgt)>=4, so the EN
        // text got routed into the source column (the typewriter filled
        // with English and the final ZH-first commit wiped it, prefix 0).
        if (ei >= 0) return new TurnParser("", clean(s.substring(ei + enLabel.length())));
        if (zi >= 0) return new TurnParser(clean(s.substring(zi + zhLabel.length())), "");
        return new TurnParser(clean(s), "");
    }

    /** Right-thing routing. For zh<->en we keep a Han-count safety net to
     *  undo the occasional wholesale label swap — but ONLY an unambiguous
     *  one (the ZH-labelled side literally Han-free while the EN-labelled
     *  side has Han). The old any-majority rule (han(tgt) > han(src)) was
     *  broken by code-switch content: the EN: line legitimately contains
     *  raw Han segments, so as those streamed in the swap verdict flipped
     *  between drafts and the final — the UI's translate column got fed
     *  the EN line by one generation and the ZH line by the next, a
     *  full-text wipe at every flip (measured on emulator: common prefix
     *  0 at commit). Streaming parses skip this entirely (see
     *  {@link #parseStreaming}); only final commits route through here.
     *  For all other pairs the labels are trusted as-is (we don't have a
     *  reliable cross-language content heuristic). */
    private static TurnParser route(String src, String tgt, String srcLang, String tgtLang) {
        if (isZhEnPair(srcLang, tgtLang) && han(src) == 0 && han(tgt) > 0) {
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

    /** If the text (or its last line) is a partial label in flight — a
     *  proper prefix of one of the labels, like "Z", "ZH" or "ZH：" —
     *  cut it. Streaming deltas split labels across chunk boundaries;
     *  without this the fragment parses as column content for one delta
     *  and vanishes on the next, a visible 2→0 flicker at every stream
     *  head and label boundary. Returns "" when the whole text is such
     *  a fragment. */
    private static String cutTrailingLabelPrefix(String s, String... labels) {
        int nl = s.lastIndexOf('\n');
        String head = nl >= 0 ? s.substring(0, nl) : "";
        String tail = nl >= 0 ? s.substring(nl + 1) : s;
        String t = tail.trim().toUpperCase(java.util.Locale.ROOT);
        while (t.endsWith(":") || t.endsWith("：")) t = t.substring(0, t.length() - 1);
        if (t.isEmpty()) return s;  // line of colons/space — not our case
        for (String label : labels) {
            if (label == null || label.isEmpty()) continue;
            String bare = label.endsWith(":") ? label.substring(0, label.length() - 1) : label;
            if (bare.startsWith(t)) {
                return head;  // drop the fragment line, keep everything above
            }
        }
        return s;
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
