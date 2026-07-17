package com.vokii.translator;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * Streaming text-MT client for DashScope's chat completion endpoint.
 * <p>
 * Cascade architecture step 2: takes the verbatim transcript from
 * {@link ParaformerAsrClient} and produces a ZH / EN pair, returned as a
 * single SSE stream of incremental {@code ZH: .. / EN: ..} text. We
 * parse the deltas through {@link TurnParser} so callers get the same
 * {@link AsrEngine.Callback#onPartial(String)} / {@link #onFinal(String)}
 * shape that {@link QwenOmniEngine} surfaces — which means
 * {@link TranslationController} needs no change to drive the cascade.
 *
 * <h2>Endpoint</h2>
 * <pre>
 *   POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
 *   Headers: Authorization: Bearer &lt;apiKey&gt;
 *   Body: { "model": "qwen-plus", "stream": true, "messages": [...] }
 * </pre>
 *
 * <p><b>Why {@code qwen-turbo} is the default.</b> Measured 2026-07-17
 * against the OpenAI-compatible endpoint (same utterance, n=5):
 * qwen-turbo TTFB median 451 ms vs qwen-plus 728 ms — ~280 ms faster
 * server-side, ~180 ms on-device. For a live translator the MT TTFB is
 * the residual wait after the user pauses (P0's live caption already
 * covers the speech window), so the faster model directly cuts perceived
 * latency. Both emit the {@code ZH:}/{@code EN:} two-line format reliably
 * (verified on zh, en, and code-switch samples). Translation quality is
 * out of scope per the project directive (MER is the target, translation
 * assumed correct via LLM); qwen-plus remains a one-line fallback here if
 * a user hits a complex-translation case.
 *
 * <p><b>Why not {@code qwen-mt-plus}.</b> It's a translation-specialised
 * model accessible only through DashScope's <em>native</em> SDK
 * ({@code dashscope.Generation.call}), which uses a different request
 * envelope ({@code input.messages}, {@code parameters.incremental_output},
 * {@code result_format="message"}) and a different chunk shape
 * ({@code output.choices[0].message.content}, no {@code delta} wrapper).
 * The OpenAI-compatible mode used here does not list {@code qwen-mt-plus}
 * in its model catalog — a request for it returns status 200 with an empty
 * body, which surfaces as "empty MT response". qwen-turbo / qwen-plus are
 * in the compat catalog.
 *
 * <h2>Trade-offs</h2>
 * <ul>
 *   <li><b>Latency</b>: chat-completion adds an extra hop (~0.5 s TTFB
 *       warm) versus Qwen-Omni's joint model. Acceptable because the MER
 *       win on step 1 is the dominant cost/quality lever — see
 *       REPORT.cascade.step1.md. qwen-turbo minimises this hop.</li>
 *   <li><b>Quality</b>: qwen-turbo is a smaller general LLM, not MT-tuned.
 *       Translation quality is slightly below qwen-plus on complex /
 *       code-switch boundaries but the two-line ZH:/EN: prompt format
 *       keeps it on-spec (verified 2026-07-17).</li>
 *   <li><b>Streaming</b>: SSE deltas are emitted as the model generates.
 *       {@link Listener#onDelta(String)} fires for each delta so the UI
 *       gets live ZH/EN updates during the MT turn.</li>
 *   <li><b>Cost</b>: qwen-turbo is cheaper than qwen-plus; total cascade
 *       is ~1.3 × the joint path.</li>
 * </ul>
 *
 * <h2>Build note</h2>
 * Uses OkHttp (already a dependency) + its built-in {@code BufferedSource}
 * to parse SSE lines. Tested against the OpenAI-compatible DashScope
 * endpoint; if DashScope ships a different streaming shape in future, the
 * only change needed is in {@link #parseDelta}.
 */
public class QwenMtClient {

    /**
     * Default MT model. {@code qwen-turbo} via the OpenAI-compatible mode
     * (URL below) — latency-optimised (~280 ms faster TTFB than qwen-plus,
     * measured 2026-07-17); see class docs for why not {@code qwen-mt-plus}.
     */
    public static final String DEFAULT_MODEL = "qwen-turbo";

    /** Fallback prompt used only when the caller does not pass one
     *  explicitly. Kept for tests and ad-hoc invocations; production
     *  always supplies a session-derived prompt from
     *  {@link MtPromptBuilder#buildSystemPrompt}. */
    private static final String DEFAULT_PROMPT =
            "You are a real-time interpreter. The user speaks either Chinese " +
            "or English. For each utterance, output the translation in BOTH " +
            "languages using EXACTLY this two-line format and nothing else:\n" +
            "ZH: <Mandarin translation>\n" +
            "EN: <English translation>\n" +
            "Always output the ZH line first, then the EN line. Use no labels " +
            "other than 'ZH:' and 'EN:'. No extra commentary, no markdown, no " +
            "apologies.";

    public interface Listener {
        /** First byte of the response has been read (TTFB marker). */
        void onReady();
        /** Incremental full text (accumulated since stream start) for
         *  the in-progress MT turn. UI surfaces via {@code onPartial}. */
        void onDelta(String turnText);
        /** Stream finished — accumulated text is the final translation.
         *  When tool_use is active, either {@code onResult} carries the
         *  translation (no commands) OR {@code onResult} carries an
         *  empty string and {@code onToolCalls} carries the commands.
         *  In OpenAI-compat streams the two are mutually exclusive at the
         *  finish_reason level (verified against qwen-plus 2026-07-16). */
        void onResult(String text);
        /** Tool-use calls the LLM wants to invoke. Only fired when the
         *  request was sent with non-null {@code tools}. Fired after
         *  {@code onResult} regardless of which one carries data. */
        default void onToolCalls(java.util.List<ToolCall> calls) {}
        /** Transport, HTTP, or server error. */
        void onError(String message);
    }

    private final String apiKey;
    private final String model;
    private final String systemPrompt;
    private final org.json.JSONArray tools;
    private final float temperature;
    private final DebugLogger debug;
    /** The in-flight HTTP call, if any. Held so {@link #cancel()} can abort
     *  a turn when the engine stops mid-translation. Volatile: set on the MT
     *  worker, read/cancelled from the UI thread via {@link #cancel()}. */
    private volatile Call currentCall;

    /** Process-wide shared client. OkHttp clients own a Dispatcher (up to
     *  64 threads) + a ConnectionPool (its own cleanup thread + pooled
     *  sockets); allocating one per QwenMtClient — and CascadeEngine
     *  constructs a new QwenMtClient every turn — leaked both per turn.
     *  Sharing one client reuses the pool/threads across the whole session
     *  and is the documented OkHttp usage pattern. Never shut down — it
     *  lives for the app lifetime, like the ASR client below. */
    private static final OkHttpClient SHARED_HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // streaming — cadence guarded by callTimeout
            .callTimeout(120, TimeUnit.SECONDS)    // hard ceiling per turn so a stalled SSE
                                                    // can't block the shared MT executor forever
            .build();

    public QwenMtClient(String apiKey, String model, DebugLogger debug) {
        this(apiKey, model, DEFAULT_PROMPT, null, 0.3f, debug);
    }

    /** Full constructor. {@code systemPrompt} replaces the hardcoded
     *  prompt. {@code tools} is the OpenAI-compat tools array (null =
     *  no tools). {@code temperature} is clamped to [0, 1] by the
     *  caller (see {@link SessionConfig#setTemperature}). */
    public QwenMtClient(String apiKey, String model, String systemPrompt,
                        org.json.JSONArray tools, float temperature, DebugLogger debug) {
        this.apiKey = apiKey;
        this.model = model;
        this.systemPrompt = (systemPrompt == null || systemPrompt.isEmpty())
                ? DEFAULT_PROMPT : systemPrompt;
        this.tools = tools;
        this.temperature = clampTemp(temperature);
        this.debug = debug;
    }

    private static float clampTemp(float t) {
        if (Float.isNaN(t)) return 0.3f;
        if (t < 0f) return 0f;
        if (t > 1f) return 1f;
        return t;
    }

    /** Abort the in-flight turn (if any). Safe to call from any thread;
     *  no-op if no turn is running. The blocked {@code execute()} in
     *  {@link #translate} unblocks with an IOException → onError. */
    public void cancel() {
        Call c = currentCall;
        if (c != null && !c.isCanceled()) {
            try { c.cancel(); } catch (Throwable ignored) {}
        }
    }

    /** Fire one streaming chat-completion call. Blocks the calling thread
     *  until the stream ends or errors. The CascadeEngine runs this on a
     *  worker thread per turn, so the caller's event loop is not blocked. */
    public void translate(@NonNull String verbatimTranscript, @NonNull Listener l) {
        try {
            JSONObject req = buildRequest(verbatimTranscript);
            Request httpReq = new Request.Builder()
                    .url("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "text/event-stream")
                    .post(RequestBody.create(req.toString(),
                            okhttp3.MediaType.parse("application/json; charset=utf-8")))
                    .build();

            debug.log("MT", "POST " + model + " verbatim.len=" + verbatimTranscript.length());

            currentCall = SHARED_HTTP.newCall(httpReq);
            try (Response resp = currentCall.execute()) {
                debug.log("MT", "HTTP " + resp.code() + " " + (resp.message() == null ? "" : resp.message()));
                if (!resp.isSuccessful()) {
                    String errBody = "";
                    try { if (resp.body() != null) errBody = resp.body().string(); } catch (Throwable ignored) {}
                    debug.log("MT", "errBody=" + (errBody.length() > 200 ? errBody.substring(0, 200) + "…" : errBody));
                    l.onError("HTTP " + resp.code());
                    return;
                }
                ResponseBody body = resp.body();
                if (body == null) {
                    l.onError("empty body");
                    return;
                }
                BufferedSource src = body.source();
                l.onReady();
                StringBuilder accumulated = new StringBuilder();
                ToolCallAccumulator toolAcc = tools == null ? null : new ToolCallAccumulator();
                int chunkCount = 0;
                String firstSseLine = null;
                boolean finishReasonToolCalls = false;
                // SSE format: lines of "data: {json}\n\n", terminated by "data: [DONE]".
                while (!src.exhausted()) {
                    String line = src.readUtf8Line();
                    if (line == null) break;
                    if (line.isEmpty()) continue;
                    if (firstSseLine == null && line.startsWith("data:")) {
                        firstSseLine = line.length() > 300 ? line.substring(0, 300) + "…" : line;
                    }
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.equals("[DONE]")) break;
                    chunkCount++;
                    String contentDelta = parseContentDelta(payload);
                    if (contentDelta != null && !contentDelta.isEmpty()) {
                        accumulated.append(contentDelta);
                        l.onDelta(accumulated.toString());
                    }
                    if (toolAcc != null) {
                        JSONArray tcArr = parseToolCallsArray(payload);
                        if (tcArr != null) toolAcc.feed(tcArr);
                    }
                    if ("tool_calls".equals(parseFinishReason(payload))) {
                        finishReasonToolCalls = true;
                    }
                }
                debug.log("MT", "chunks=" + chunkCount + " accumulated=" + accumulated.length()
                        + " first=" + (firstSseLine == null ? "<no-data-line>" : firstSseLine));
                String finalText = accumulated.toString().trim();
                // When finish_reason is tool_calls, the LLM has spent its
                // generation budget on emitting the tool_calls envelope;
                // any concurrent "content" deltas in our accumulator are
                // empty strings (verified against qwen-plus — content
                // and tool_calls are mutually exclusive at this level).
                if (finishReasonToolCalls) {
                    finalText = "";
                }
                if (!finalText.isEmpty()) {
                    l.onResult(finalText);
                } else if (!finishReasonToolCalls) {
                    l.onError("empty MT response (" + chunkCount + " chunks, model=" + model + ")");
                } else {
                    // Pure tool_calls turn — still need to fire onResult("")
                    // so the controller's render path reaches a clean
                    // terminal state, and the current streaming card
                    // closes before the tool chip lands.
                    l.onResult("");
                }
                if (toolAcc != null) {
                    java.util.List<ToolCall> calls = toolAcc.build();
                    if (!calls.isEmpty()) {
                        debug.log("MT", "tool_calls=" + calls.size());
                        for (ToolCall c : calls) {
                            debug.log("MT", "  " + c.name + " trigger=\"" + c.triggerText + "\"");
                        }
                        l.onToolCalls(calls);
                    }
                }
            }
        } catch (IOException ioe) {
            l.onError("MT IO: " + ioe.getMessage());
        } catch (Throwable t) {
            l.onError("MT ex: " + t.getMessage());
        } finally {
            currentCall = null;
        }
    }

    private JSONObject buildRequest(String verbatim) throws IOException, org.json.JSONException {
        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", verbatim);

        JSONArray messages = new JSONArray().put(sysMsg).put(userMsg);

        JSONObject req = new JSONObject();
        req.put("model", model);
        req.put("stream", true);
        req.put("temperature", (double) temperature);
        req.put("messages", messages);
        if (tools != null) {
            req.put("tools", tools);
            req.put("tool_choice", "auto");
        }
        return req;
    }

    /** Pull the incremental text out of one SSE chunk. The DashScope
     *  OpenAI-compatible stream emits
     *  {@code {"choices":[{"delta":{"content":"..."}}]}}.
     *  Returns null for non-content chunks (role-only, etc). */
    private String parseContentDelta(String payload) {
        try {
            JSONObject o = new JSONObject(payload);
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
            if (delta == null) return null;
            return Json.optString(delta, "content", "");
        } catch (Throwable t) {
            debug.log("MT", "parseContentDelta ex: " + t.getMessage());
            return null;
        }
    }

    /** Pull the tool_calls array from a delta. Returns null when absent
     *  (most deltas), the empty array when present-but-empty. */
    private JSONArray parseToolCallsArray(String payload) {
        try {
            JSONObject o = new JSONObject(payload);
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
            if (delta == null) return null;
            return delta.optJSONArray("tool_calls");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Pull the finish_reason from a delta. Returns null on any error
     *  (the field is only present on the final chunk of a turn). */
    private String parseFinishReason(String payload) {
        try {
            JSONObject o = new JSONObject(payload);
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject choice = choices.getJSONObject(0);
            return Json.optString(choice, "finish_reason", null);
        } catch (Throwable t) {
            return null;
        }
    }
}