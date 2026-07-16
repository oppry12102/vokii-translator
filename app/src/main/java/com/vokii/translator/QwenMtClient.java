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
 * parse the deltas through {@link Bilingual} so callers get the same
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
 * <p><b>Why {@code qwen-plus} and not {@code qwen-mt-plus}.</b>
 * {@code qwen-mt-plus} is a translation-specialised model accessible
 * only through DashScope's <em>native</em> SDK ({@code dashscope.Generation.call}),
 * which uses a different request envelope ({@code input.messages},
 * {@code parameters.incremental_output}, {@code result_format="message"})
 * and a different chunk shape ({@code output.choices[0].message.content},
 * no {@code delta} wrapper). The OpenAI-compatible mode used here does
 * not list {@code qwen-mt-plus} in its model catalog — a request for it
 * returns status 200 with an empty body, which surfaces in the app as
 * "empty MT response". {@code qwen-plus} is in the compat catalog,
 * handles the bilingual instruction set, and the eval harness's
 * {@code cascade_step2.py} already uses it with the same URL.
 *
 * <h2>Trade-offs</h2>
 * <ul>
 *   <li><b>Latency</b>: chat-completion adds an extra hop (~300 ms TTFB
 *       warm, more cold) versus Qwen-Omni's joint model. Acceptable
 *       because the MER win on step 1 is the dominant cost/quality
 *       lever — see REPORT.cascade.step1.md.</li>
 *   <li><b>Quality</b>: qwen-plus is a general LLM, not an MT-tuned
 *       model. Translation quality is slightly below qwen-mt-plus on
 *       code-switch boundaries but well within "shippable" range for
 *       the live-translator UX, and the Bilingual prompt format keeps
 *       it on-spec.</li>
 *   <li><b>Streaming</b>: SSE deltas are emitted as the model generates.
 *       {@link Listener#onDelta(String)} fires for each delta so the UI
 *       gets live ZH/EN updates during the MT turn.</li>
 *   <li><b>Cost</b>: ~30 % of Qwen-Omni per minute; total cascade is
 *       ~1.3 × the joint path.</li>
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
     * Default MT model. {@code qwen-plus} via the OpenAI-compatible mode
     * (URL below); see class docs for why not {@code qwen-mt-plus}.
     */
    public static final String DEFAULT_MODEL = "qwen-plus";

    /** Default MT prompt. Mirrors the Python harness's TRANSLATE_INSTRUCTIONS
     *  (qwen_client.py) so behaviour is identical between eval and prod. */
    private static final String MT_INSTRUCTIONS =
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
        /** Stream finished — accumulated text is the final translation. */
        void onResult(String text);
        /** Transport, HTTP, or server error. */
        void onError(String message);
    }

    private final String apiKey;
    private final String model;
    private final DebugLogger debug;
    private final OkHttpClient http;

    public QwenMtClient(String apiKey, String model, DebugLogger debug) {
        this.apiKey = apiKey;
        this.model = model;
        this.debug = debug;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // streaming
                .build();
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

            try (Response resp = http.newCall(httpReq).execute()) {
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
                int chunkCount = 0;
                String firstSseLine = null;
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
                    String delta = parseDelta(payload);
                    chunkCount++;
                    if (delta != null && !delta.isEmpty()) {
                        accumulated.append(delta);
                        l.onDelta(accumulated.toString());
                    }
                }
                debug.log("MT", "chunks=" + chunkCount + " accumulated=" + accumulated.length()
                        + " first=" + (firstSseLine == null ? "<no-data-line>" : firstSseLine));
                String finalText = accumulated.toString().trim();
                if (finalText.isEmpty()) {
                    l.onError("empty MT response (" + chunkCount + " chunks, model=" + model + ")");
                } else {
                    l.onResult(finalText);
                }
            }
        } catch (IOException ioe) {
            l.onError("MT IO: " + ioe.getMessage());
        } catch (Throwable t) {
            l.onError("MT ex: " + t.getMessage());
        }
    }

    private JSONObject buildRequest(String verbatim) throws IOException, org.json.JSONException {
        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", MT_INSTRUCTIONS);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", verbatim);

        JSONArray messages = new JSONArray().put(sysMsg).put(userMsg);

        JSONObject req = new JSONObject();
        req.put("model", model);
        req.put("stream", true);
        req.put("temperature", 0.3);
        req.put("messages", messages);
        return req;
    }

    /** Pull the incremental text out of one SSE chunk. The DashScope
     *  OpenAI-compatible stream emits
     *  {@code {"choices":[{"delta":{"content":"..."}}]}}.
     *  Returns null for non-content chunks (role-only, etc). */
    private String parseDelta(String payload) {
        try {
            JSONObject o = new JSONObject(payload);
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
            if (delta == null) return null;
            return delta.optString("content", "");
        } catch (Throwable t) {
            debug.log("MT", "parseDelta ex: " + t.getMessage());
            return null;
        }
    }
}