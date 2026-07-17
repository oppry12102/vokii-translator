package com.vokii.translator;

import org.json.JSONArray;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared infrastructure for every QwenMtClient call in the app.
 *
 * <p>Two things are centralised here:
 * <ol>
 *   <li><b>A single-thread executor</b> that serializes all MT turns —
 *       cascade step 2, re-translate, and summarize. Serializing keeps
 *       response order aligned with request order and guarantees we never
 *       run two {@link QwenMtClient}s concurrently (they share one
 *       {@link QwenMtClient#SHARED_HTTP} connection pool). The worker is a
 *       daemon thread so it never blocks process exit and is shared across
 *       engine rebuilds (no per-restart leak).</li>
 *   <li><b>A client factory</b> so every call site constructs its
 *       {@link QwenMtClient} with the same model id and argument order.</li>
 * </ol>
 *
 * <p>{@link QwenMtClient#translate} blocks the calling thread, so callers
 * submit a runnable to {@link #executor()} and marshal listener callbacks
 * to the UI thread themselves.
 */
final class MtRunner {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vokii-mt");
        t.setDaemon(true);
        return t;
    });

    private MtRunner() {}

    /** Shared single-thread MT executor. */
    static ExecutorService executor() { return EXEC; }

    /** Build a {@link QwenMtClient} for {@link QwenMtClient#DEFAULT_MODEL}.
     *  {@code systemPrompt} null/empty falls back to the client's default;
     *  {@code tools} null disables tool-use. */
    static QwenMtClient client(String apiKey, String systemPrompt,
                               JSONArray tools, float temperature,
                               DebugLogger debug) {
        return new QwenMtClient(apiKey, QwenMtClient.DEFAULT_MODEL,
                systemPrompt, tools, temperature, debug);
    }
}
