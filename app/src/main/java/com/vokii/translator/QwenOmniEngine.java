package com.vokii.translator;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import androidx.core.content.ContextCompat;

/**
 * On-device ASR-replacement engine backed by <b>Qwen-Omni Realtime</b>.
 *
 * The phone only captures microphone PCM; all recognition and translation
 * happen in the cloud. We open a {@link QwenOmniRealtimeClient} WebSocket,
 * then stream raw 16 kHz mono PCM16 frames to it. The server performs VAD
 * and, for each spoken turn, streams back a {@code ZH: .. / EN: ..} pair.
 * We forward the incremental text via {@link Callback#onPartial(String)}
 * and the finished turn via {@link Callback#onFinal(String)};
 * {@link TranslationController} parses it into the zh/en columns with
 * {@link TurnParser}. This single engine covers both the recognition and
 * translation stages — no second network call.
 */
public class QwenOmniEngine implements AsrEngine {

    /** Realtime PCM: 16 kHz, mono, signed 16-bit. */
    static final int SAMPLE_RATE = 16000;
    /** ~100 ms per frame (16000 * 0.1 * 2 bytes). */
    private static final int FRAME_BYTES = 3200;

    private final Context appCtx;
    private final ConfigStore config;
    private final DebugLogger debug;

    private QwenOmniRealtimeClient client;
    private AudioRecord record;
    private Thread captureThread;
    private Callback cb;
    private volatile boolean started;
    /** Session identity — bumped on every start(). Each anonymous WS listener
     *  captures the sid it was created with and bails on every callback if
     *  {@code sid != sessionSeq}, so a stale frame from the previous session's
     *  closing socket can't deliver text to — or stop()/tear down — the
     *  current session (see CascadeEngine for the full rationale). */
    private volatile long sessionSeq;

    public QwenOmniEngine(Context ctx, ConfigStore config, DebugLogger debug) {
        this.appCtx = ctx.getApplicationContext();
        this.config = config;
        this.debug = debug;
    }

    @Override public String name() { return "QwenOmniRealtime"; }

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
        // Capture this session's identity + callback snapshot so a stale WS
        // callback from the previous session's closing socket can't act on
        // (or stop()) the current one.
        final long sid = ++sessionSeq;
        final Callback cb0 = callback;
        client = new QwenOmniRealtimeClient(config, debug);
        client.connect(new QwenOmniRealtimeClient.Listener() {
            @Override public void onReady() {
                if (sid != sessionSeq || !started) return;
                startCapture();
                cb0.onReady();
            }
            @Override public void onDelta(String turnText) {
                if (sid != sessionSeq || !started) return;
                cb0.onPartial(turnText);
            }
            @Override public void onResult(String text) {
                if (sid != sessionSeq || !started) return;
                cb0.onFinal(text);
            }
            @Override public void onError(String message) {
                // Only act if this socket still owns the current session; a
                // stale onError from the previous session's closing socket
                // must not tear down the new one.
                if (sid == sessionSeq && started) cb0.onError(-1, message);
                if (sid == sessionSeq) stop();
            }
            @Override public void onClosed() {
                debug.log("ASR", "WS closed");
            }
        });
    }

    private void startCapture() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, FRAME_BYTES * 4);
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
            // Local reference — see CascadeEngine for why the finally block
            // must not read the `record` field (stop() nulls it while read()
            // is still in a blocking native call, which would skip release()
            // and leak the mic).
            final AudioRecord r = record;
            byte[] frame = new byte[FRAME_BYTES];
            try {
                r.startRecording();
                debug.log("ASR", "capture started @" + SAMPLE_RATE + "Hz");
                while (started) {
                    int n = r.read(frame, 0, frame.length);
                    if (n > 0 && client != null) {
                        client.sendAudio(frame, n);
                    } else if (n < 0) {
                        debug.log("ASR", "AudioRecord.read error " + n);
                        break;
                    }
                }
            } catch (Throwable t) {
                debug.log("ASR", "capture ex: " + t.getMessage());
            } finally {
                try { r.stop(); } catch (Throwable ignored) {}
                try { r.release(); } catch (Throwable ignored) {}
            }
        }, "vokii-omni-capture");
        captureThread.start();
    }

    @Override
    public void write(byte[] data, int length) {
        // Unused: this engine captures its own audio (capturesOwnAudio()==true)
        // and streams it directly to the realtime socket.
    }

    @Override
    public void stop() {
        if (!started) return;
        started = false;
        // Invalidate this session so in-flight WS callbacks bail.
        sessionSeq++;
        Thread t = captureThread;
        captureThread = null;
        if (t != null) {
            try { t.interrupt(); } catch (Throwable ignored) {}
            // Wait briefly for the capture loop's finally (r.stop()/r.release()) —
            // a blocking native read() isn't unblocked by interrupt(), so a
            // rapid start()→stop()→start() could otherwise re-open AudioRecord
            // before the previous one released it.
            try { t.join(200); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        if (client != null) {
            try { client.close(); } catch (Throwable ignored) {}
            client = null;
        }
        record = null;
        debug.log("ASR", "stopped");
    }
}
