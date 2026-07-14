package com.vokii.translator;

import android.util.Base64;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Thin client over the DashScope Qwen-Omni <b>Realtime</b> API (WebSocket).
 * The event protocol is OpenAI-Realtime compatible.
 *
 * Lifecycle:
 *   connect() → onOpen → send {@code session.update} (server-VAD, text-only,
 *   pcm16, interpreter instructions) → stream PCM frames via
 *   {@link #sendAudio(byte[], int)} as {@code input_audio_buffer.append}.
 *
 * The server performs voice-activity detection: it emits
 * {@code input_audio_buffer.speech_started/stopped}, auto-commits the turn,
 * and generates a text response. We accumulate {@code response.*.delta}
 * fragments and surface the complete text of each turn on
 * {@code response.done} — which, per our instructions, is a
 * {@code {"zh":..,"en":..}} JSON object.
 *
 * All {@link Listener} callbacks fire on OkHttp's WebSocket thread; the
 * caller ({@link QwenOmniEngine} → {@link TranslationController}) marshals
 * to the main thread itself.
 */
public class QwenOmniRealtimeClient {

    /** Server-VAD trailing-silence before a turn is considered finished. */
    private static final int VAD_SILENCE_MS = 600;
    private static final double VAD_THRESHOLD = 0.5;

    public interface Listener {
        /** WebSocket connected and session.update sent. */
        void onReady();
        /** Incremental text for the in-progress turn (accumulated so far). */
        void onDelta(String turnText);
        /** One completed turn's full response text. */
        void onResult(String text);
        /** Transport or server error. */
        void onError(String message);
        /** Socket closed (remote or local). */
        void onClosed();
    }

    private final ConfigStore config;
    private final DebugLogger debug;
    private final OkHttpClient http;

    private WebSocket ws;
    private Listener listener;
    private final StringBuilder turnBuf = new StringBuilder();
    private volatile boolean open;

    public QwenOmniRealtimeClient(ConfigStore config, DebugLogger debug) {
        this.config = config;
        this.debug = debug;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)   // keep the socket alive
                .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout for a stream
                .build();
    }

    public boolean isOpen() { return open; }

    public void connect(@NonNull Listener l) {
        this.listener = l;
        String endpoint = config.getEndpoint();
        String model = config.getModel();
        String apiKey = config.getApiKey();

        // The realtime endpoint takes the model as a query parameter.
        String url = endpoint + (endpoint.contains("?") ? "&" : "?") + "model=" + model;

        Request req = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        debug.log("ASR", "WS connect " + url);
        ws = http.newWebSocket(req, new Socket());
    }

    /** Base64-encode a PCM16 frame and append it to the server input buffer. */
    public void sendAudio(byte[] data, int length) {
        WebSocket s = ws;
        if (s == null || !open) return;
        String b64 = Base64.encodeToString(data, 0, length, Base64.NO_WRAP);
        try {
            JSONObject ev = new JSONObject();
            ev.put("type", "input_audio_buffer.append");
            ev.put("audio", b64);
            s.send(ev.toString());
        } catch (Throwable t) {
            debug.log("ASR", "sendAudio ex: " + t.getMessage());
        }
    }

    public void close() {
        open = false;
        WebSocket s = ws;
        ws = null;
        if (s != null) {
            try { s.close(1000, "client stop"); } catch (Throwable ignored) {}
        }
    }

    private void sendSessionUpdate() {
        try {
            JSONObject td = new JSONObject();
            td.put("type", "server_vad");
            td.put("silence_duration_ms", VAD_SILENCE_MS);
            td.put("threshold", VAD_THRESHOLD);

            JSONObject session = new JSONObject();
            session.put("modalities", new JSONArray().put("text"));
            session.put("input_audio_format", "pcm16");
            session.put("turn_detection", td);
            session.put("instructions",
                    "You are a real-time interpreter. The user speaks either " +
                    "Chinese or English. For each utterance, output the " +
                    "translation in BOTH languages using EXACTLY this two-line " +
                    "format and nothing else:\n" +
                    "ZH: <the Mandarin Chinese translation>\n" +
                    "EN: <the English translation>\n" +
                    "Always output the ZH line first, then the EN line. Use no " +
                    "labels other than 'ZH:' and 'EN:'. No extra commentary, no " +
                    "markdown, no apologies.");

            JSONObject ev = new JSONObject();
            ev.put("type", "session.update");
            ev.put("session", session);
            ws.send(ev.toString());
            debug.log("ASR", "session.update sent (server_vad, text, pcm16)");
        } catch (Throwable t) {
            debug.log("ASR", "session.update ex: " + t.getMessage());
        }
    }

    private void handleEvent(String text) {
        String type;
        JSONObject ev;
        try {
            ev = new JSONObject(text);
            type = ev.optString("type", "");
        } catch (Throwable t) {
            debug.log("ASR", "bad event: " + t.getMessage());
            return;
        }
        switch (type) {
            case "session.updated":
                debug.log("ASR", "session.updated");
                break;
            case "input_audio_buffer.speech_started":
                debug.log("ASR", "speech_started");
                turnBuf.setLength(0);
                break;
            case "input_audio_buffer.speech_stopped":
                debug.log("ASR", "speech_stopped");
                break;
            // Incremental text — field is "delta". Accept the common variants.
            case "response.text.delta":
            case "response.output_text.delta":
            case "response.audio_transcript.delta":
                turnBuf.append(ev.optString("delta", ""));
                if (listener != null) listener.onDelta(turnBuf.toString());
                break;
            // Turn finished — emit whatever we accumulated. Prefer an explicit
            // full "text" if the server provided one on the *.done event.
            case "response.text.done":
            case "response.output_text.done": {
                String full = ev.optString("text", "");
                if (!full.isEmpty()) { turnBuf.setLength(0); turnBuf.append(full); }
                break;
            }
            case "response.done": {
                String result = turnBuf.toString().trim();
                turnBuf.setLength(0);
                if (!result.isEmpty() && listener != null) listener.onResult(result);
                break;
            }
            case "error": {
                String msg = ev.optJSONObject("error") != null
                        ? ev.optJSONObject("error").optString("message", text)
                        : text;
                debug.log("ASR", "server error: " + msg);
                if (listener != null) listener.onError(msg);
                break;
            }
            default:
                // session.created, rate_limits.updated, response.created, etc.
                break;
        }
    }

    private class Socket extends WebSocketListener {
        @Override public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
            open = true;
            debug.log("ASR", "WS open");
            sendSessionUpdate();
            if (listener != null) listener.onReady();
        }

        @Override public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
            handleEvent(text);
        }

        @Override public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            open = false;
            debug.log("ASR", "WS closing " + code + " " + reason);
        }

        @Override public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            open = false;
            if (listener != null) listener.onClosed();
        }

        @Override public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response response) {
            open = false;
            String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            int code = response != null ? response.code() : -1;
            debug.log("ASR", "WS failure code=" + code + " " + msg);
            if (listener != null) listener.onError("WS " + code + ": " + msg);
        }
    }
}
