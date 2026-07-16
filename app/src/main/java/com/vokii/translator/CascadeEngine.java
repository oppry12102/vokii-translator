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
 * existing zh/en UI surface via {@link Bilingual}.
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

    /** Default cascade step 1 ASR model. fun-asr-realtime (DashScope
     *  2025 release) won the tier2 A/B vs paraformer-realtime-v2:
     *  MER 0.0693 vs 0.0869 (−20 % relative) and also lower TTFB
     *  (REPORT.latency.tier1.md). Paraformer remains supported by
     *  passing {@code "paraformer-realtime-v2"} to
     *  {@link ParaformerAsrClient#connect} — useful if a user hits
     *  fun-asr's tendency to drop disfluent fillers/repeats that were
     *  actually spoken.
     *
     *  TEMPORARY 2026-07-16: paraformer-realtime-v2 pinned here for
     *  emulator verification — fun-asr rejected the Java client's
     *  session schema with "format is empty" (WS close 1007). Python
     *  dashscope SDK works for both, so the protocol gap is in our
     *  manual session.update payload. Fix once we know the exact
     *  param fun-asr wants. */
    private static final String DEFAULT_ASR_MODEL = "fun-asr-realtime";

    private final Context appCtx;
    private final ConfigStore config;
    private final DebugLogger debug;

    private ParaformerAsrClient asrClient;
    private AudioRecord record;
    private Thread captureThread;
    private Callback cb;
    private final ExecutorService mtExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean started;

    public CascadeEngine(Context ctx, ConfigStore config, DebugLogger debug) {
        this.appCtx = ctx.getApplicationContext();
        this.config = config;
        this.debug = debug;
    }

    @Override public String name() { return "Cascade(Paraformer→qwen-mt-plus)"; }

    @Override public boolean isStarted() { return started; }

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
        // Default to fun-asr-realtime — CS-Dialogue tier2 showed −20 %
        // MER relative vs paraformer-realtime-v2 on top of the cascade's
        // −39 % vs joint (see REPORT.cascade.step3.md). Paraformer is
        // kept as an opt-in via Settings if a user hits fun-asr's
        // filler-drop bias on deliberately disfluent speech.
        asrClient.connect(new ParaformerAsrClient.Listener() {
            @Override public void onReady() {
                if (!started) return;
                startCapture();
                cb.onReady();
            }
            /** A partial verbatim transcript. NOT surfaced to the UI —
             *  raw transcript alone is not useful to the user. We wait
             *  for the turn commit before forwarding to MT. */
            @Override public void onDelta(String turnText) {
                // no-op; step 1 deltas are noise to the UI.
            }
            @Override public void onResult(String text) {
                if (!started || text == null || text.isEmpty()) return;
                // Hand the verbatim transcript to the MT worker.
                mtExecutor.execute(() -> translateTurn(text));
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

    /** Step 2 — verbatim transcript → ZH/EN pair. Runs on the MT worker
     *  thread; calls back to the UI thread before invoking the listener. */
    private void translateTurn(String verbatim) {
        debug.log("MT", "translateTurn len=" + verbatim.length());
        QwenMtClient mt = new QwenMtClient(config.getApiKey(),
                                           QwenMtClient.DEFAULT_MODEL, debug);
        Handler main = new Handler(Looper.getMainLooper());
        mt.translate(verbatim, new QwenMtClient.Listener() {
            @Override public void onReady() { /* TTFB marker; could log */ }
            @Override public void onDelta(String turnText) {
                if (!started) return;
                // Pass the raw MT output (containing ZH:/EN: labels) to
                // the TranslationController — it knows how to split the
                // bilingual pair via Bilingual.parse. Pre-parsing here
                // and re-joining with "\n" loses the labels and Bilingual
                // silently routes everything to the zh column.
                main.post(() -> {
                    if (!started) return;
                    cb.onPartial(turnText);
                });
            }
            @Override public void onResult(String text) {
                if (!started) return;
                main.post(() -> {
                    if (!started) return;
                    cb.onFinal(text);
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
            cb.onError(-6, "AudioRecord init failed: " + t.getMessage());
            return;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            debug.log("ASR", "AudioRecord not initialized");
            cb.onError(-6, "AudioRecord not initialized");
            return;
        }

        captureThread = new Thread(() -> {
            byte[] frame = new byte[FRAME_BYTES];
            try {
                record.startRecording();
                debug.log("ASR", "cascade capture started @" + SAMPLE_RATE + "Hz");
                while (started) {
                    int n = record.read(frame, 0, frame.length);
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
                try { record.stop(); } catch (Throwable ignored) {}
                try { record.release(); } catch (Throwable ignored) {}
            }
        }, "vokii-cascade-capture");
        captureThread.start();
    }

    @Override
    public void write(byte[] data, int length) {
        // Cascade captures its own audio — this engine reports capturesOwnAudio()=true.
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
        record = null;
        debug.log("ASR", "cascade stopped");
    }
}