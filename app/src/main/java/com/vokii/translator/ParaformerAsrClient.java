package com.vokii.translator;

import android.util.Base64;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * WebSocket client for the DashScope <b>Paraformer-realtime-v2</b> (and
 * related ASR models like fun-asr-realtime) streaming endpoint.
 *
 * <p><b>Protocol note — different from Qwen-Omni.</b> DashScope's
 * ASR-realtime endpoint ({@code /api-ws/v1/inference}) speaks a custom
 * task-based protocol, NOT the OpenAI-Realtime protocol that
 * {@link QwenOmniRealtimeClient} speaks against
 * {@code /api-ws/v1/realtime}. Concretely:
 *
 * <ol>
 *   <li>First client → server frame (text): a {@code {"header","payload"}}
 *       envelope with {@code action:"run-task"}, model id, and
 *       parameters (sample_rate, format, stream=true). See
 *       {@link #sendStartTask}.</li>
 *   <li>Client → server audio: <b>raw binary frames</b> (no base64, no
 *       JSON wrapping) carrying the PCM samples directly. See
 *       {@link #sendAudio}.</li>
 *   <li>Final client → server frame (text): {@code action:"finish-task"}
 *       with empty input. See {@link #sendFinishTask}.</li>
 *   <li>Server → client frames are also JSON envelopes with
 *       {@code header.action ∈ {"task-started","result-generated",
 *       "task-finished","task-failed"}}.</li>
 * </ol>
 *
 * <p>Discovered 2026-07-16 by capturing the Python dashscope SDK's
 * actual on-wire frames via debug logging
 * ({@code logging.getLogger('dashscope').setLevel(DEBUG)}). The
 * previous "send session.update + base64 audio" approach (mirroring
 * OpenAI Realtime) was rejected by the server with WS close 1007
 * "Invalid payload data".
 */
public class ParaformerAsrClient {

    /** ASR endpoint. Distinct from the Qwen-Omni realtime endpoint. */
    private static final String ASR_ENDPOINT =
            "wss://dashscope.aliyuncs.com/api-ws/v1/inference";

    public interface Listener {
        /** WS connected and start-task acknowledged by server. */
        void onReady();
        /** Incremental transcript for the in-progress turn. */
        void onDelta(String turnText);
        /** Completed turn's full transcript. */
        void onResult(String text);
        /** Transport or server error. */
        void onError(String message);
        /** Socket closed. */
        void onClosed();
    }

    private final String apiKey;
    private final String model;
    private final DebugLogger debug;
    private final OkHttpClient http;

    private WebSocket ws;
    private Listener listener;
    private String taskId;
    /** Running hypothesis for the latest sentence — overwritten on every
     *  result-generated update. Drained to {@link #Listener#onResult}
     *  only when VAD finalizes the sentence (see {@code handleEvent}). */
    private final StringBuilder pendingBuf = new StringBuilder();
    private volatile boolean open;
    private volatile boolean taskStarted;

    public ParaformerAsrClient(String apiKey, String model, DebugLogger debug) {
        this.apiKey = apiKey;
        this.model = model;
        this.debug = debug;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    public boolean isOpen() { return open; }

    public void connect(@NonNull Listener l) {
        this.listener = l;
        String url = ASR_ENDPOINT;
        Request req = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        debug.log("ASR", "Paraformer WS connect " + url);
        ws = http.newWebSocket(req, new Socket());
    }

    /**
     * Send raw PCM bytes as a binary frame. The DashScope ASR protocol
     * does NOT wrap audio in JSON or base64 — these are sent as
     * WebSocket binary messages directly.
     */
    public void sendAudio(byte[] data, int length) {
        WebSocket s = ws;
        if (s == null || !open) return;
        if (!taskStarted) {
            // Server hasn't acknowledged task-started yet — drop the
            // frame. Sending before task-started is a protocol error.
            return;
        }
        try {
            s.send(ByteString.of(data, 0, length));
        } catch (Throwable t) {
            debug.log("ASR", "sendAudio ex: " + t.getMessage());
        }
    }

    public void close() {
        // Send a finish-task envelope before closing so the server
        // commits the in-flight utterance. Without this, the server
        // discards the last partial sentence.
        if (taskStarted && ws != null && open) {
            try {
                sendFinishTask();
            } catch (Throwable t) {
                debug.log("ASR", "sendFinishTask ex: " + t.getMessage());
            }
        }
        open = false;
        WebSocket s = ws;
        ws = null;
        if (s != null) {
            try { s.close(1000, "client stop"); } catch (Throwable ignored) {}
        }
    }

    /** Send the start-task envelope. Called once after WS open. */
    private void sendStartTask() {
        try {
            taskId = UUID.randomUUID().toString().replace("-", "");
            JSONObject header = new JSONObject();
            header.put("streaming", "duplex");
            header.put("task_id", taskId);
            header.put("action", "run-task");

            JSONObject payload = new JSONObject();
            payload.put("model", model);
            JSONObject parameters = new JSONObject();
            parameters.put("sample_rate", 16000);
            parameters.put("format", "pcm");
            parameters.put("stream", true);
            payload.put("parameters", parameters);
            payload.put("input", new JSONObject());
            payload.put("task", "asr");
            payload.put("task_group", "audio");
            payload.put("function", "recognition");

            JSONObject envelope = new JSONObject();
            envelope.put("header", header);
            envelope.put("payload", payload);

            String msg = envelope.toString();
            debug.log("ASR", "start-task payload: " + msg);
            ws.send(msg);
            debug.log("ASR", "Paraformer start-task sent (model=" + model + ")");
        } catch (Throwable t) {
            debug.log("ASR", "Paraformer start-task ex: " + t.getMessage());
        }
    }

    private void sendFinishTask() {
        try {
            JSONObject header = new JSONObject();
            header.put("streaming", "duplex");
            header.put("task_id", taskId);
            header.put("action", "finish-task");

            JSONObject payload = new JSONObject();
            payload.put("input", new JSONObject());

            JSONObject envelope = new JSONObject();
            envelope.put("header", header);
            envelope.put("payload", payload);

            ws.send(envelope.toString());
        } catch (Throwable t) {
            debug.log("ASR", "sendFinishTask ex: " + t.getMessage());
        }
    }

    /**
     * Parse a server → client frame. DashScope uses the same envelope
     * format we send: {@code {"header":{...}, "payload":{...}}}.
     */
    private void handleEvent(String text) {
        try {
            JSONObject env = new JSONObject(text);
            JSONObject header = env.optJSONObject("header");
            JSONObject payload = env.optJSONObject("payload");
            if (header == null) return;
            String action = header.optString("event", "");
            switch (action) {
                case "task-started":
                    taskStarted = true;
                    debug.log("ASR", "Paraformer task-started");
                    // onReady fires now (not on WS open) so capture
                    // doesn't push audio before the server is ready.
                    if (listener != null) listener.onReady();
                    break;
                case "result-generated":
                    if (payload != null) {
                        // server_vad emits `payload.output.sentence` as a
                        // SINGLE dict (one sentence per event, identified
                        // by sentence_id). Some older or alternate events
                        // ship it as an array — accept both shapes.
                        JSONObject output = payload.optJSONObject("output");
                        if (output != null) {
                            JSONObject sent = null;
                            Object raw = output.opt("sentence");
                            if (raw instanceof JSONObject) {
                                sent = (JSONObject) raw;
                            } else if (raw instanceof JSONArray) {
                                JSONArray arr = (JSONArray) raw;
                                if (arr.length() > 0) sent = arr.optJSONObject(arr.length() - 1);
                            }
                            if (sent != null) {
                                String sentText = sent.optString("text", "");
                                if (!sentText.isEmpty()) {
                                    double endTime = sent.optDouble("end_time", -1.0);
                                    boolean sentenceEnd = sent.optBoolean("sentence_end", false);
                                    boolean finalized = (endTime >= 0.0) || sentenceEnd;
                                    int sid = sent.optInt("sentence_id", -1);
                                    debug.log("ASR", "sentence#" + sid
                                            + " endTime=" + endTime
                                            + " sentenceEnd=" + sentenceEnd
                                            + " textLen=" + sentText.length());
                                    // Latest hypothesis for THIS sentence — every
                                    // subsequent result-generated overwrites it
                                    // until the sentence is finalized.
                                    pendingBuf.setLength(0);
                                    pendingBuf.append(sentText);
                                    if (finalized && listener != null) {
                                        // VAD committed this sentence — this is
                                        // a turn boundary. Ship the finalized
                                        // sentence verbatim to step 2 (MT) which
                                        // returns ZH:/EN: pair for one UI card.
                                        // Per-sentence (not cumulative) matches the
                                        // joint Qwen-Omni "one card per turn" UX
                                        // and avoids re-translating earlier text.
                                        listener.onResult(sentText);
                                        pendingBuf.setLength(0);
                                    } else if (listener != null) {
                                        // In-flight hypothesis — debug-only signal,
                                        // CascadeEngine.onDelta drops it as a no-op.
                                        listener.onDelta(sentText);
                                    }
                                }
                            }
                        }
                    }
                    break;
                case "task-finished":
                    // End of WS task — flush any in-flight sentence that
                    // never got a finalize signal (user stopped mid-utterance).
                    String finalText = pendingBuf.toString().trim();
                    pendingBuf.setLength(0);
                    debug.log("ASR", "Paraformer task-finished (" + finalText.length() + " chars)");
                    if (!finalText.isEmpty() && listener != null) {
                        listener.onResult(finalText);
                    }
                    break;
                case "task-failed":
                    String errMsg = (payload != null) ? payload.optString("message",
                            "task-failed") : "task-failed";
                    String errCode = header.optString("error_code", "");
                    debug.log("ASR", "Paraformer server error: " + errCode + " " + errMsg);
                    if (listener != null) listener.onError(errCode + " " + errMsg);
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            debug.log("ASR", "bad event: " + t.getMessage() + " raw=" + text.substring(0, Math.min(120, text.length())));
        }
    }

    private class Socket extends WebSocketListener {
        @Override public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
            open = true;
            debug.log("ASR", "Paraformer WS open");
            sendStartTask();
            // onReady is fired when task-started comes back, not here —
            // the user-visible "listening" state only makes sense once
            // the server has acknowledged our session.
        }

        @Override public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
            debug.log("ASR", "ws recv: " + text.substring(0, Math.min(400, text.length())));
            handleEvent(text);
        }

        @Override public void onMessage(@NonNull WebSocket webSocket, @NonNull okio.ByteString bytes) {
            // DashScope ASR server only sends JSON, no binary. If we
            // get binary, log it for debugging.
            debug.log("ASR", "unexpected binary frame: " + bytes.size() + " bytes");
        }

        @Override public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            open = false;
            debug.log("ASR", "Paraformer WS closing " + code + " " + reason);
            // Flush any in-flight sentence as a final result — server may
            // have closed early on a transient error without finalizing.
            String finalText = pendingBuf.toString().trim();
            pendingBuf.setLength(0);
            if (!finalText.isEmpty() && listener != null) {
                listener.onResult(finalText);
            }
        }

        @Override public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            open = false;
            taskStarted = false;
            if (listener != null) listener.onClosed();
        }

        @Override public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response response) {
            open = false;
            taskStarted = false;
            String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            int code = response != null ? response.code() : -1;
            debug.log("ASR", "Paraformer WS failure code=" + code + " " + msg);
            if (listener != null) listener.onError("WS " + code + ": " + msg);
        }
    }
}