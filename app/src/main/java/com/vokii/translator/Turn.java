package com.vokii.translator;

/**
 * One history entry in the transcript. A turn is either a TRANSLATION
 * (with a source and target column) or a COMMAND (chip rendering only).
 * TRANSLATION turns also store the language codes they were produced
 * under, so the transcript renders correctly even after the user
 * switches language pairs mid-session.
 */
final class Turn {

    enum Kind { TRANSLATION, COMMAND }

    final Kind kind;
    // TRANSLATION fields
    final String source;
    final String target;
    final String sourceLang;
    final String targetLang;
    // COMMAND fields
    final String commandText;

    private Turn(Kind kind, String source, String target, String sLang, String tLang, String cmd) {
        this.kind = kind;
        this.source = source == null ? "" : source;
        this.target = target == null ? "" : target;
        this.sourceLang = sLang == null ? "" : sLang;
        this.targetLang = tLang == null ? "" : tLang;
        this.commandText = cmd == null ? "" : cmd;
    }

    static Turn translation(String source, String target, String sLang, String tLang) {
        return new Turn(Kind.TRANSLATION, source, target, sLang, tLang, null);
    }

    static Turn command(String text) {
        return new Turn(Kind.COMMAND, null, null, null, null, text);
    }
}
