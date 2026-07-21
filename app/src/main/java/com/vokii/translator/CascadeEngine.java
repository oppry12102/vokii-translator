package com.vokii.translator;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cascade ASR engine — chained cloud pipeline that replaces the
 * joint Qwen-Omni call with two specialised stages:
 *
 * <pre>
 *   mic PCM
 *     → Paraformer-realtime-v2 (verbatim zh+en mixed)   [step 1]
 *     → qwen-mt-plus (verbatim → ZH: .. / EN: .. pair) [step 2]
 *     → bilingual UI cards
 * </pre>
 *
 * <p>The motivation: the joint Qwen-Omni model is split between ASR and
 * translation, which dilutes its attention on code-switch boundaries.
 * Step 1 alone cuts MER ~39 % on CS-Dialogue tier2 (see
 * {@code tools/eval/REPORT.cascade.step1.md}). Step 2 reuses the
 * existing zh/en UI surface via {@link TurnParser}.
 *
 * <p>This class implements {@link AsrEngine} so it drops into
 * {@link TranslationController} without UI changes. The factory selects
 * it via a Settings toggle ({@code cascade_mode} SharedPreferences key).
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li>UI thread: {@link #start} / {@link #stop} and the
 *       {@link AsrEngine.Callback} deliveries.</li>
 *   <li>Capture thread: reads AudioRecord PCM and forwards to
 *       {@link ParaformerAsrClient}.</li>
 *   <li>MT worker: a single-thread {@link ExecutorService} that runs
 *       one {@link QwenMtClient} turn at a time. Sequential so the
 *       response ordering matches the turn ordering Paraformer emits.</li>
 * </ul>
 *
 * <h2>Build / run note</h2>
 * APK size impact: 0 (no new dependency — both endpoints use OkHttp +
 * {@code org.json} which are already on the classpath). Cold-start
 * latency will be ~100–300 ms higher than the joint path until the WS
 * connection pool warms; see
 * {@code tools/eval/REPORT.cascade.step2.md} (TODO: measure in prod).
 */
public class CascadeEngine implements AsrEngine {

    private static final int SAMPLE_RATE = 16000;
    // 20 ms frame @ 16 kHz mono PCM16 = 640 bytes — matches DashScope
    // Python SDK's `send_audio_frame(pcm[offset:offset+640])` cadence.
    // Sending 100 ms frames (3200 bytes) caused server close 1007
    // "Invalid payload data" — the server expects a ~20 ms cadence.
    private static final int FRAME_BYTES = 640;

    /** Default cascade step 1 ASR model.
     *
     *  <p><b>fun-asr-realtime</b> (DashScope 2025 release) won the tier2
     *  A/B vs paraformer-realtime-v2: MER 0.0693 vs 0.0869 (−20 % relative)
     *  and lower TTFB (REPORT.latency.tier1.md). Combined with the cascade
     *  architecture that's −52 % MER vs the joint v1 path.
     *
     *  <p><b>Payload verification 2026-07-17:</b> the start-task envelope
     *  sent by {@link ParaformerAsrClient#sendStartTask} is byte-for-byte
     *  identical to what the Python dashscope SDK emits for fun-asr-realtime
     *  (captured via {@code tools/eval/capture_funasr_frame.py} with SDK
     *  debug logging). The SDK connects to fun-asr successfully; the old
     *  "format is empty" / WS 1007 note in this file was stale — it
     *  described the pre-port OpenAI-Realtime {@code session.update}
     *  payload, not the current DashScope task protocol. paraformer-realtime-v2
     *  remains available by passing it to
     *  {@link ParaformerAsrClient#connect}. */
    private static final String DEFAULT_ASR_MODEL = "fun-asr-realtime";

    private final Context appCtx;
    private final ConfigStore config;
    private final SessionConfig session;
    private final SessionContext sessionContext;
    private final ToolRegistry toolRegistry;
    private final DebugLogger debug;

    private ParaformerAsrClient asrClient;
    private AudioRecord record;
    private Thread captureThread;
    private Callback cb;
    /** The in-flight MT client, if a turn is currently being translated.
     *  Held so {@link #stop()} can cancel it instead of letting it run to
     *  completion (wasting bandwidth) after the user tapped stop. Volatile:
     *  written on the MT worker, cancelled from the UI thread in stop(). */
    private volatile QwenMtClient currentMt;
    /** Logs "asr_first_partial" once per turn cycle (reset in translateTurn).
     *  Race with the WS-thread onDelta is harmless — at worst a duplicate or
     *  missed log line. */
    private volatile boolean loggedAsrPartial;

    // ----- P1: speculative MT on ASR partials -----
    /** Monotonic generation tag. Every speculative fire AND every final
     *  translateTurn bumps it; each MT callback captures the gen it was
     *  started with and drops itself if {@code gen != mtGeneration}. This
     *  stops a cancelled speculative's late-arriving onPartial from
     *  clobbering the newer speculative / final translation. */
    private volatile long mtGeneration;
    /** The in-flight speculative MT client (non-committing draft), or null.
     *  Cancelled when a newer speculative fires, when the final MT starts,
     *  or on stop(). */
    private volatile QwenMtClient inFlightSpec;
    /** nanoTime of the last speculative fire — throttles spec MT to at most
     *  one per {@link #SPEC_MIN_INTERVAL_NS}. */
    private volatile long lastSpecNanos;
    /** Minimum partial length before a speculative MT is worth firing —
     *  shorter partials are too incomplete to translate meaningfully. */
    private static final int SPEC_MIN_CHARS = 6;
    /** Minimum gap between speculative fires. The spec MT itself takes
     *  ~0.5 s TTFB, so firing faster than this just wastes calls. */
    private static final long SPEC_MIN_INTERVAL_NS = 700_000_000L;
    /** Process-wide single-thread MT executor shared with re-translate /
     *  summarize (see {@link MtRunner}). Only one engine is ever active at
     *  a time (reconcileEngineIfNeeded refuses to hot-swap while listening),
     *  so a single shared worker preserves turn-ordering. Stale turns
     *  queued by a stopped engine are no-ops: every callback checks
     *  {@code started}. Never shut down — lives for the app process. */
    private volatile boolean started;
    // Mic-mute state lives on session.micPaused() (single source of
    // truth, mutated by the toggle_mic tool). The capture loop reads
    // it on every iteration.

    public CascadeEngine(Context ctx, ConfigStore config, SessionConfig session,
                         SessionContext sessionContext,
                         ToolRegistry toolRegistry, DebugLogger debug) {
        this.appCtx = ctx.getApplicationContext();
        this.config = config;
        this.session = session;
        this.sessionContext = sessionContext;
        this.toolRegistry = toolRegistry;
        this.debug = debug;
    }

    @Override public String name() { return "Cascade(fun-asr→qwen-turbo)"; }

    @Override public boolean isStarted() { return started; }

    /** Convenience proxy: read mic-pause state from SessionConfig. */
    public boolean isMicPaused() { return session.micPaused(); }

    @Override
    public void start(Callback callback) {
        if (started) return;
        this.cb = callback;

        if (ContextCompat.checkSelfPermission(appCtx, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            callback.onError(-4, "microphone permission not granted");
            return;
        }
        if (config.getApiKey().isEmpty()) {
            callback.onError(-5, "Qwen API key is empty — set it in Settings");
            return;
        }

        started = true;
        asrClient = new ParaformerAsrClient(config.getApiKey(),
                                            DEFAULT_ASR_MODEL, debug);
        // DEFAULT_ASR_MODEL is fun-asr-realtime (eval winner) — see its
        // javadoc for the 2026-07-17 payload verification.
        asrClient.connect(new ParaformerAsrClient.Listener() {
            @Override public void onReady() {
                if (!started) return;
                startCapture();
                cb.onReady();
            }
            /** A partial verbatim transcript for the sentence in flight.
             *  Forwarded to the UI as a "live caption" so the user sees
             *  text while speaking — first text lands at ASR TTFB (~0.4 s)
             *  instead of after the sentence-final commit + MT TTFB. The
             *  translation card replaces this line once MT streams. */
            @Override public void onDelta(String turnText) {
                if (!started || turnText == null || turnText.isEmpty()) return;
                if (!loggedAsrPartial) {
                    loggedAsrPartial = true;
                    debug.log("LAT", "asr_first_partial (verbatim caption → UI)");
                }
                cb.onPartialTranscript(turnText);
                // P1: speculatively translate the partial so a draft shows
                // during speech — hides the ~0.5 s MT TTFB the user would
                // otherwise wait after pausing. maybeSpeculate throttles and
                // skips while a final MT is in flight.
                maybeSpeculate(turnText);
            }
            @Override public void onResult(String text) {
                if (!started || text == null || text.isEmpty()) return;
                // Sentence-final verbatim: the UI locks the verbatim column
                // here — partials arriving after this belong to the next
                // sentence and must not rewrite this card.
                cb.onFinalTranscript(text);
                // Hand the verbatim transcript to the MT worker.
                MtRunner.executor().execute(() -> translateTurn(text));
            }
            @Override public void onError(String message) {
                if (started) cb.onError(-1, "ASR step1: " + message);
                stop();
            }
            @Override public void onClosed() {
                debug.log("ASR", "Paraformer WS closed");
            }
        });
    }

    /** Step 2 — verbatim transcript → source/target pair. Runs on the MT
     *  worker thread; calls back to the UI thread before invoking the
     *  listener. The prompt is rebuilt per turn from the live
     *  {@link SessionConfig}, so a language change made by voice command
     *  or Settings takes effect on the next turn automatically. */
    private void translateTurn(String verbatim) {
        final long t0 = System.nanoTime();
        // New generation: drops any in-flight speculative's late callbacks,
        // and tags this final MT so it can't be clobbered by a stale spec.
        final long gen = ++mtGeneration;
        cancelSpec();  // the final is authoritative; kill the draft in flight
        loggedAsrPartial = false;  // next sentence's first ASR partial will log
        debug.log("LAT", "mt_start (asr final → mt request)");
        debug.log("MT", "translateTurn len=" + verbatim.length() + " src=" + session.sourceLang()
                + " tgt=" + session.targetLang());
        String prompt = MtPromptBuilder.buildSystemPrompt(sessionContext, toolRegistry);
        org.json.JSONArray tools = MtPromptBuilder.buildToolsJson(toolRegistry);
        QwenMtClient mt = MtRunner.client(config.getApiKey(), prompt, tools,
                                          session.temperature(), debug);
        currentMt = mt;
        Handler main = new Handler(Looper.getMainLooper());
        final boolean[] sawToolCalls = {false};
        // For tool_calls turns the client fires onResult("") BEFORE
        // onToolCalls. Defer that empty result so the grounding filter can
        // decide the turn's fate first: a real command closes the card; a
        // hallucinated one gets re-translated as content instead.
        final boolean[] deferredEmptyResult = {false};
        final boolean[] toolCallsHandled = {false};
        mt.translate(verbatim, new QwenMtClient.Listener() {
            @Override public void onReady() {
                if (gen != mtGeneration) return;
                debug.log("LAT", "mt_ttfb_ms=" + (System.nanoTime() - t0) / 1_000_000);
            }
            @Override public void onDelta(String turnText) {
                if (!started || gen != mtGeneration) return;
                // Pass the raw MT output (containing ZH:/EN: labels) to
                // the TranslationController — it knows how to split the
                // bilingual pair via TurnParser. Pre-parsing here and
                // re-joining with "\n" loses the labels and TurnParser
                // silently routes everything to the src column.
                main.post(() -> {
                    if (!started || gen != mtGeneration) return;
                    cb.onPartial(turnText);
                });
            }
            @Override public void onResult(String text) {
                if (!started || gen != mtGeneration) return;
                if ((text == null || text.trim().isEmpty()) && tools != null) {
                    // Tool-calls turn — hold the empty result until
                    // onToolCalls decides (see deferredEmptyResult above).
                    deferredEmptyResult[0] = true;
                    return;
                }
                debug.log("LAT", "mt_total_ms=" + (System.nanoTime() - t0) / 1_000_000);
                String out = text;
                // Chatter fallback: qwen-turbo sometimes answers short
                // inputs conversationally ("I'm not sure what you're
                // referring to…") instead of translating — no labels, no
                // tool calls. Committing that would write garbage into
                // the transcript (observed on emulator run for the
                // one-word utterance "Questions."). Preserve the verbatim
                // instead: the transcript keeps the heard text and simply
                // lacks a translation for this sentence.
                if (out != null && !out.trim().isEmpty() && !sawToolCalls[0]
                        && !out.contains("ZH:") && !out.contains("EN:")
                        && !out.contains("ZH：") && !out.contains("EN：")) {
                    debug.log("MT", "chatter fallback (no labels in final MT): len="
                            + out.length() + " → committing verbatim");
                    out = (hanCount(verbatim) * 2 >= verbatim.length() ? "ZH: " : "EN: ")
                            + verbatim;
                }
                final String committed = out;
                main.post(() -> {
                    if (!started || gen != mtGeneration) return;
                    cb.onFinal(committed);
                });
            }
            @Override public void onToolCalls(java.util.List<ToolCall> calls) {
                if (!started || gen != mtGeneration || calls == null || calls.isEmpty()) return;
                toolCallsHandled[0] = true;
                // Grounding filter: drop tool calls whose trigger text
                // carries no command cue (see GroundingCues). qwen-turbo
                // hallucinates commands on conversational content; with the
                // "command wins, no translation lines" rule a hallucinated
                // command would also erase the sentence's translation, so
                // when EVERY call is ungrounded we re-translate the
                // utterance as plain content (tools stripped) rather than
                // committing nothing.
                java.util.List<ToolCall> grounded = new java.util.ArrayList<>();
                for (ToolCall c : calls) {
                    if (GroundingCues.isGrounded(c.name, c.triggerText)) {
                        grounded.add(c);
                    } else {
                        debug.log("CMD", "ungrounded tool_call dropped: " + c.name
                                + " trigger=\"" + c.triggerText + "\"");
                    }
                }
                if (grounded.isEmpty()) {
                    debug.log("CMD", "all " + calls.size()
                            + " tool call(s) ungrounded → retranslate as content");
                    fallbackTranslateNoTools(verbatim, t0);
                    return;
                }
                sawToolCalls[0] = true;
                if (deferredEmptyResult[0]) {
                    deferredEmptyResult[0] = false;
                    main.post(() -> {
                        if (!started || gen != mtGeneration) return;
                        cb.onFinal("");  // close the card; the chip follows
                    });
                }
                final java.util.List<ToolCall> keep = grounded;
                main.post(() -> {
                    if (!started || gen != mtGeneration) return;
                    cb.onCommand(keep);
                });
            }
            @Override public void onError(String message) {
                if (!started || gen != mtGeneration) return;
                main.post(() -> {
                    if (!started || gen != mtGeneration) return;
                    cb.onError(-2, "MT step2: " + message);
                });
            }
        });
        // Degenerate edge: an empty result with no tool calls arriving at
        // all — close the card so it doesn't hang mid-state.
        if (deferredEmptyResult[0] && !toolCallsHandled[0] && started && gen == mtGeneration) {
            main.post(() -> {
                if (!started || gen != mtGeneration) return;
                cb.onFinal("");
            });
        }
        // translate() blocks until the turn finishes; clear the in-flight
        // reference so a later stop() doesn't cancel an already-completed call.
        currentMt = null;
    }

    /** Hallucinated-command recovery: re-translate the utterance as pure
     *  content with the tool list stripped, so the sentence isn't lost.
     *  Runs synchronously on the MT worker (called from within the outer
     *  turn's listener at stream end). Gets its own generation so the
     *  outer turn's late callbacks drop. Any failure commits the ASR
     *  verbatim — the transcript always survives. */
    private void fallbackTranslateNoTools(String verbatim, long t0) {
        final long fbGen = ++mtGeneration;
        debug.log("MT", "fallback translate (no tools) len=" + verbatim.length());
        String prompt = MtPromptBuilder.buildSystemPrompt(sessionContext, toolRegistry);
        QwenMtClient mt = MtRunner.client(config.getApiKey(), prompt, null,
                                          session.temperature(), debug);
        currentMt = mt;
        Handler main = new Handler(Looper.getMainLooper());
        mt.translate(verbatim, new QwenMtClient.Listener() {
            @Override public void onReady() {
                if (fbGen == mtGeneration)
                    debug.log("LAT", "fallback_mt_ttfb_ms=" + (System.nanoTime() - t0) / 1_000_000);
            }
            @Override public void onDelta(String turnText) {
                if (fbGen != mtGeneration) return;
                main.post(() -> {
                    if (started && fbGen == mtGeneration) cb.onPartial(turnText);
                });
            }
            @Override public void onResult(String text) {
                if (fbGen != mtGeneration) return;
                String out = text;
                if (out == null || out.trim().isEmpty()
                        || (!out.contains("ZH:") && !out.contains("EN:")
                            && !out.contains("ZH：") && !out.contains("EN："))) {
                    out = (hanCount(verbatim) * 2 >= verbatim.length() ? "ZH: " : "EN: ")
                            + verbatim;
                }
                final String committed = out;
                main.post(() -> {
                    if (started && fbGen == mtGeneration) cb.onFinal(committed);
                });
            }
            @Override public void onError(String message) {
                if (fbGen != mtGeneration) return;
                debug.log("MT", "fallback failed: " + message + " — committing verbatim");
                final String committed =
                        (hanCount(verbatim) * 2 >= verbatim.length() ? "ZH: " : "EN: ")
                                + verbatim;
                main.post(() -> {
                    if (started && fbGen == mtGeneration) cb.onFinal(committed);
                });
            }
        });
        if (currentMt == mt) currentMt = null;
    }

    // ----- P1: speculative MT on ASR partials -----

    /** Maybe fire a speculative MT on an ASR partial so a draft translation
     *  appears during speech. Throttled, length-gated, and skipped while a
     *  final MT is in flight (avoids cross-sentence currentTurn contention
     *  — the live caption still shows during the skip). */
    private void maybeSpeculate(String partial) {
        if (partial.length() < SPEC_MIN_CHARS) return;
        if (currentMt != null) return;  // a final MT is committing — don't contend
        long now = System.nanoTime();
        if (now - lastSpecNanos < SPEC_MIN_INTERVAL_NS) return;
        lastSpecNanos = now;
        cancelSpec();
        speculativeTranslate(partial);
    }

    /** Run a non-committing MT draft for {@code partial} on the speculative
     *  executor. Only {@code onPartial} is forwarded (as a live translation
     *  draft via {@code cb.onPartial}); {@code onResult}/{@code onToolCalls}
     *  are dropped — the final MT (triggered by ASR sentence-final) is the
     *  one that commits and fires commands. Gen-guarded so a cancelled spec
     *  can't clobber a newer spec / final. */
    private void speculativeTranslate(String partial) {
        final long gen = ++mtGeneration;
        debug.log("LAT", "spec_mt_start len=" + partial.length());
        String prompt = MtPromptBuilder.buildSystemPrompt(sessionContext, toolRegistry);
        org.json.JSONArray tools = MtPromptBuilder.buildToolsJson(toolRegistry);
        final QwenMtClient mt = MtRunner.client(config.getApiKey(), prompt, tools,
                                                session.temperature(), debug);
        inFlightSpec = mt;
        final Handler main = new Handler(Looper.getMainLooper());
        MtRunner.specExecutor().execute(() -> {
            if (gen != mtGeneration) return;  // superseded before we even started
            final long t0 = System.nanoTime();
            mt.translate(partial, new QwenMtClient.Listener() {
                @Override public void onReady() {
                    if (gen == mtGeneration)
                        debug.log("LAT", "spec_mt_ttfb_ms=" + (System.nanoTime() - t0) / 1_000_000);
                }
                @Override public void onDelta(String turnText) {
                    if (gen != mtGeneration) return;  // superseded — drop
                    // Only forward drafts that actually look like a
                    // bilingual translation. A draft that degenerated into
                    // a tool call streams plain chatter instead of ZH:/EN:
                    // lines; painting that over the card is worse than
                    // keeping the previous draft (the gate can't displace
                    // it once adopted — measured on emulator: a 53-char
                    // garbage draft survived to commit and got swapped
                    // wholesale, 0-char common prefix).
                    String t = turnText == null ? "" : turnText;
                    if (!t.contains("ZH:") && !t.contains("EN:")
                            && !t.contains("ZH：") && !t.contains("EN：")) return;
                    main.post(() -> {
                        if (!started || gen != mtGeneration) return;
                        cb.onPartial(turnText);  // draft translation → UI
                    });
                }
                @Override public void onResult(String text) {
                    // Draft complete — do NOT commit. The final MT commits.
                    if (gen == mtGeneration) debug.log("LAT", "spec_mt_done (draft, not committed)");
                }
                @Override public void onToolCalls(java.util.List<ToolCall> calls) {
                    // A translation draft must never fire commands — and a
                    // draft that WANTS to call a tool has stopped
                    // translating (qwen-turbo does this on partials that
                    // resemble commands). Kill it so its non-label text
                    // never reaches the card; the final MT is the only
                    // command channel.
                    if (gen == mtGeneration && calls != null && !calls.isEmpty()) {
                        debug.log("MT", "spec draft emitted tool_call — cancelled");
                        try { mt.cancel(); } catch (Throwable ignored) {}
                    }
                }
                @Override public void onError(String message) {
                    // Silent: a failed draft just means the user waits for final.
                }
            });
            if (inFlightSpec == mt) inFlightSpec = null;
        });
    }

    /** Cancel any in-flight speculative MT. */
    private void cancelSpec() {
        QwenMtClient s = inFlightSpec;
        inFlightSpec = null;
        if (s != null) {
            try { s.cancel(); } catch (Throwable ignored) {}
        }
    }

    private static int hanCount(String s) {
        int c = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) c++;
        }
        return c;
    }

    /** Tear down a half-started engine: AudioRecord init failed after the
     *  ASR WebSocket was already opened. Without this, {@code started} would
     *  stay true and the socket would leak — the engine would look "running"
     *  with no mic capture. Marks stopped, closes the ASR client, then
     *  surfaces the error so the controller's onError→stop chain is clean. */
    private void failStart(String message) {
        started = false;
        if (asrClient != null) {
            try { asrClient.close(); } catch (Throwable ignored) {}
            asrClient = null;
        }
        record = null;
        cb.onError(-6, message);
    }

    private void startCapture() {
        // DEBUG-only test hook: if a raw PCM file (16 kHz mono s16le) was
        // pushed to the app's external files dir, stream it to the ASR at
        // the same 20 ms cadence the mic would produce, instead of opening
        // AudioRecord. Lets the full ASR→spec-MT→final-MT→render chain run
        // on an emulator with no audio device (the CI emulator boots with
        // -no-audio, so AudioRecord would only ever read silence).
        if (BuildConfig.DEBUG && startFileFeed()) return;

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        // Buffer = min(system_min, frame * 16). 16 frames @ 20 ms = 320 ms
        // of audio buffered; enough headroom for the capture thread to
        // be slightly behind real time without underrun.
        int bufSize = Math.max(minBuf, FRAME_BYTES * 16);
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize);
        } catch (Throwable t) {
            debug.log("ASR", "AudioRecord init ex: " + t.getMessage());
            failStart("AudioRecord init failed: " + t.getMessage());
            return;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            debug.log("ASR", "AudioRecord not initialized");
            failStart("AudioRecord not initialized");
            return;
        }

        captureThread = new Thread(() -> {
            // Capture a local reference: stop() may null the `record` field
            // while this thread is still inside read() (a blocking native
            // call that interrupt() does NOT unblock). If the finally block
            // read the field, the null would make record.stop() throw NPE,
            // be swallowed by catch(Throwable), and skip release() —
            // leaking the native mic so the next start() can't open it.
            final AudioRecord r = record;
            byte[] frame = new byte[FRAME_BYTES];
            try {
                r.startRecording();
                debug.log("ASR", "cascade capture started @" + SAMPLE_RATE + "Hz");
                while (started) {
                    if (session.micPaused()) {
                        // Don't drain AudioRecord while paused — its
                        // internal buffer will overflow, which is fine
                        // (Android drops samples silently). When we
                        // resume, record.read() returns current mic
                        // input seamlessly.
                        try { Thread.sleep(50); } catch (InterruptedException ie) {
                            if (!started) break;
                        }
                        continue;
                    }
                    int n = r.read(frame, 0, frame.length);
                    if (n > 0 && asrClient != null) {
                        asrClient.sendAudio(frame, n);
                    } else if (n < 0) {
                        debug.log("ASR", "AudioRecord.read error " + n);
                        break;
                    }
                }
            } catch (Throwable t) {
                debug.log("ASR", "cascade capture ex: " + t.getMessage());
            } finally {
                try { r.stop(); } catch (Throwable ignored) {}
                try { r.release(); } catch (Throwable ignored) {}
            }
        }, "vokii-cascade-capture");
        captureThread.start();
    }

    /** Try to start the file-feed capture path (see startCapture). Returns
     *  true when the feed thread took over. DEBUG builds only; the file is
     *  {@code <external-files>/feed.pcm} — raw 16 kHz mono s16le, pushed via
     *  adb. After EOF the thread stays alive emitting silence so the server
     *  VAD can commit the final turn and the session doesn't look dead. */
    private boolean startFileFeed() {
        // External location first; fall back to the internal files dir, which
        // adb can reach via run-as on a headless emulator (scoped storage
        // blocks /storage/emulated/Android/data/<pkg>). Assigned once so the
        // capture lambda can capture it.
        java.io.File ext = new java.io.File(appCtx.getExternalFilesDir(null), "feed.pcm");
        final java.io.File f = ext.exists() ? ext
                : new java.io.File(appCtx.getFilesDir(), "feed.pcm");
        if (!f.exists() || f.length() < FRAME_BYTES) return false;
        debug.log("ASR", "file feed ON: " + f.getAbsolutePath()
                + " (" + f.length() + " bytes)");
        captureThread = new Thread(() -> {
            byte[] frame = new byte[FRAME_BYTES];
            java.io.InputStream in = null;
            try {
                in = new java.io.BufferedInputStream(new java.io.FileInputStream(f));
                long nextWake = System.nanoTime();
                boolean eof = false;
                while (started) {
                    if (session.micPaused()) {
                        try { Thread.sleep(50); } catch (InterruptedException ie) {
                            if (!started) break;
                        }
                        continue;
                    }
                    int n = frame.length;
                    if (!eof) {
                        n = in.read(frame, 0, frame.length);
                        if (n <= 0) {
                            eof = true;
                            debug.log("ASR", "file feed EOF — streaming silence");
                            n = frame.length;
                            java.util.Arrays.fill(frame, (byte) 0);
                        }
                    } else {
                        // After EOF keep streaming silence at cadence: the
                        // server VAD needs trailing silence to commit the
                        // final sentence, and a quiet sender gets the task
                        // killed (observed: WS close 1007 after ~23 s idle).
                        java.util.Arrays.fill(frame, (byte) 0);
                    }
                    if (asrClient != null) {
                        asrClient.sendAudio(frame, n);
                    }
                    // Pace at real time: one 20 ms frame per 20 ms.
                    nextWake += 20_000_000L;
                    long sleepMs = (nextWake - System.nanoTime()) / 1_000_000L;
                    if (sleepMs > 0) {
                        try { Thread.sleep(sleepMs); } catch (InterruptedException ie) {
                            if (!started) break;
                        }
                    } else {
                        nextWake = System.nanoTime();  // fell behind; resync
                    }
                }
            } catch (Throwable t) {
                debug.log("ASR", "file feed ex: " + t.getMessage());
            } finally {
                if (in != null) { try { in.close(); } catch (Throwable ignored) {} }
            }
        }, "vokii-cascade-filefeed");
        captureThread.start();
        return true;
    }

    @Override
    public void write(byte[] data, int length) {
        // Cascade captures its own audio — this engine reports capturesOwnAudio()=true.
    }

    /** Test-only entry point: feed {@code text} directly to the MT worker
     *  as if the ASR had just produced it. Bypasses the mic + ASR
     *  entirely so the full tool_use / dispatch / UI chain can be
     *  exercised without recording audio. No-op when the engine isn't
     *  started (i.e. the user hasn't pressed the mic), so the test
     *  panel needs the engine to be running. */
    public void injectVerbatim(String text) {
        if (text == null || text.isEmpty()) return;
        if (!started) return;
        debug.log("INJECT", "verbatim=\"" + text + "\"");
        final String t = text;
        MtRunner.executor().execute(() -> translateTurn(t));
    }

    @Override
    public void stop() {
        if (!started) return;
        started = false;
        Thread t = captureThread;
        captureThread = null;
        if (t != null) {
            try { t.interrupt(); } catch (Throwable ignored) {}
        }
        if (asrClient != null) {
            try { asrClient.close(); } catch (Throwable ignored) {}
            asrClient = null;
        }
        // Cancel an in-flight MT turn instead of letting it run to completion
        // after the user stopped. The shared executor worker unblocks and the
        // listener callbacks bail on the !started / gen check.
        QwenMtClient mt = currentMt;
        currentMt = null;
        if (mt != null) {
            try { mt.cancel(); } catch (Throwable ignored) {}
        }
        cancelSpec();  // also kill any speculative draft still running
        record = null;
        debug.log("ASR", "cascade stopped");
    }
}