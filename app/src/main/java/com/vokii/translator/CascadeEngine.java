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
            }
            @Override public void onResult(String text) {
                if (!started || text == null || text.isEmpty()) return;
                // Show the finalized verbatim immediately too — the MT TTFB
                // (~0.5 s) after the pause would otherwise be dead time.
                cb.onPartialTranscript(text);
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
        mt.translate(verbatim, new QwenMtClient.Listener() {
            @Override public void onReady() {
                debug.log("LAT", "mt_ttfb_ms=" + (System.nanoTime() - t0) / 1_000_000);
            }
            @Override public void onDelta(String turnText) {
                if (!started) return;
                // Pass the raw MT output (containing ZH:/EN: labels) to
                // the TranslationController — it knows how to split the
                // bilingual pair via TurnParser. Pre-parsing here and
                // re-joining with "\n" loses the labels and TurnParser
                // silently routes everything to the src column.
                main.post(() -> {
                    if (!started) return;
                    cb.onPartial(turnText);
                });
            }
            @Override public void onResult(String text) {
                if (!started) return;
                debug.log("LAT", "mt_total_ms=" + (System.nanoTime() - t0) / 1_000_000);
                main.post(() -> {
                    if (!started) return;
                    cb.onFinal(text);
                });
            }
            @Override public void onToolCalls(java.util.List<ToolCall> calls) {
                if (!started || calls == null || calls.isEmpty()) return;
                main.post(() -> {
                    if (!started) return;
                    cb.onCommand(calls);
                });
            }
            @Override public void onError(String message) {
                if (!started) return;
                main.post(() -> {
                    if (!started) return;
                    cb.onError(-2, "MT step2: " + message);
                });
            }
        });
        // translate() blocks until the turn finishes; clear the in-flight
        // reference so a later stop() doesn't cancel an already-completed call.
        currentMt = null;
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
        // listener callbacks bail on the !started check.
        QwenMtClient mt = currentMt;
        currentMt = null;
        if (mt != null) {
            try { mt.cancel(); } catch (Throwable ignored) {}
        }
        record = null;
        debug.log("ASR", "cascade stopped");
    }
}