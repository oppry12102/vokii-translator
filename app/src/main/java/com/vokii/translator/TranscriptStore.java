package com.vokii.translator;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists the transcript ({@link Turn} list) to SharedPreferences so it
 * survives process death and app restarts. Serialized as a JSON array;
 * both TRANSLATION and COMMAND turns round-trip.
 *
 * <p>Bounded at {@link #MAX_TURNS} (oldest dropped first) so a long-
 * running session can't grow the prefs file without limit — at ~200
 * turns × ~200 chars the payload stays well under 100 KB, fine for
 * SharedPreferences.
 *
 * <p>Corrupt or partial JSON (e.g. from a killed mid-write — shouldn't
 * happen with {@code apply()} but defensive) loads as an empty history
 * rather than crashing the activity.
 */
public final class TranscriptStore {

    /** Cap on persisted turns. Oldest are trimmed on save. */
    public static final int MAX_TURNS = 200;

    private final SharedPreferences prefs;

    public TranscriptStore(Context ctx) {
        this.prefs = ctx.getApplicationContext()
                .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
    }

    /** Serialize and persist. Trims to the newest {@link #MAX_TURNS}. */
    public void save(List<Turn> history) {
        JSONArray arr = new JSONArray();
        if (history != null) {
            int from = Math.max(0, history.size() - MAX_TURNS);
            for (int i = from; i < history.size(); i++) {
                Turn t = history.get(i);
                if (t == null) continue;
                JSONObject o = new JSONObject();
                try {
                    o.put("kind", t.kind == Turn.Kind.COMMAND ? "cmd" : "tr");
                    if (t.kind == Turn.Kind.COMMAND) {
                        o.put("cmd", t.commandText);
                    } else {
                        o.put("src", t.source);
                        o.put("tgt", t.target);
                        o.put("sl", t.sourceLang);
                        o.put("tl", t.targetLang);
                    }
                    arr.put(o);
                } catch (JSONException ignored) {
                    // Skip the single bad entry rather than losing the rest.
                }
            }
        }
        prefs.edit().putString(Constants.KEY_TRANSCRIPT, arr.toString()).apply();
    }

    /** Load the persisted history. Never null; empty on first run or
     *  corrupt data. */
    public List<Turn> load() {
        List<Turn> out = new ArrayList<>();
        String raw = prefs.getString(Constants.KEY_TRANSCRIPT, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if ("cmd".equals(Json.optString(o, "kind"))) {
                    out.add(Turn.command(Json.optString(o, "cmd", "")));
                } else {
                    out.add(Turn.translation(
                            Json.optString(o, "src", ""), Json.optString(o, "tgt", ""),
                            Json.optString(o, "sl", ""), Json.optString(o, "tl", "")));
                }
            }
        } catch (JSONException ignored) {
            // Corrupt payload — start fresh rather than crash.
        }
        return out;
    }

    /** Wipe the persisted history (clear_transcript). */
    public void clear() {
        prefs.edit().remove(Constants.KEY_TRANSCRIPT).apply();
    }
}
