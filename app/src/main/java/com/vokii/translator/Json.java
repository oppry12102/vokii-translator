package com.vokii.translator;

import org.json.JSONObject;

/**
 * Null-safe {@link JSONObject} string accessor.
 *
 * <p>org.json's {@code optString(key, fallback)} has a long-standing quirk:
 * for an explicit JSON {@code null} literal it returns the <em>string</em>
 * {@code "null"} (via {@code JSONObject.NULL.toString()}) — not the
 * fallback. Callers that treat the result as a real value then see the
 * literal text {@code "null"} flow into language codes, transcript
 * columns, tool args, etc., silently corrupting behaviour.
 *
 * <p>This helper returns the fallback for an absent key <b>or</b> a JSON
 * {@code null}, while preserving org.json's number/boolean → toString
 * coercion for non-string, non-null values (so it is a safe drop-in for
 * {@code optString}).
 *
 * <p>All callers live in this package, so the methods are package-private
 * — no import needed at call sites.
 */
final class Json {

    private Json() {}

    /** Like {@link JSONObject#optString(String, String)} but returns
     *  {@code fallback} for an explicit JSON {@code null} too. Null-safe
     *  on the object itself (returns {@code fallback}). */
    static String optString(JSONObject o, String key, String fallback) {
        if (o == null || !o.has(key) || o.isNull(key)) return fallback;
        Object v = o.opt(key);
        return v == null ? fallback : v.toString();
    }

    /** {@link #optString(JSONObject, String, String)} with an empty-string
     *  fallback. */
    static String optString(JSONObject o, String key) {
        return optString(o, key, "");
    }
}
