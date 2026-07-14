package com.vokii.translator;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the mic → Qwen-Omni Realtime pipeline. The activity holds one of
 * these and listens to {@link Listener}; the activity only knows about
 * TextView updates. This keeps the pipeline testable and reusable.
 *
 * Since Qwen-Omni is one-stage (audio → {@code {zh,en}} in a single cloud
 * call) there is no separate LLM translate step here. The engine streams a
 * turn's text as it arrives; we parse it into a zh/en pair with
 * {@link Bilingual} and surface:
 *   - {@link Listener#onStreaming} on every delta (live, in-progress turn)
 *   - {@link Listener#onCommitted} once the turn finishes (server VAD)
 *
 * Threading: {@link #start}/{@link #stop} run on the UI thread;
 * {@link AsrEngine.Callback} fires on engine/socket threads and is marshalled
 * to main via {@link #main}.
 */
public class TranslationController {

    public interface Listener {
        /** Pipeline is waiting for the engine/socket to come up. */
        void onPreparing();
        /** Pipeline is actively listening. */
        void onListening();
        /** Pipeline returned to idle (mic off). */
        void onIdle();
        /** Live partial translation for the current turn. */
        void onStreaming(String zh, String en);
        /** Final translation for a finished turn — append to history. */
        void onCommitted(String zh, String en);
        /** Something failed. */
        void onError(String where, int code, String message);
    }

    private final AsrEngine asr;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean active = new AtomicBoolean(false);

    public TranslationController(AsrEngine asr, Listener listener) {
        this.asr = asr;
        this.listener = listener;
    }

    public boolean isActive() { return active.get(); }

    public void start() {
        if (!active.compareAndSet(false, true)) return;
        main.post(listener::onPreparing);
        asr.start(new AsrEngine.Callback() {
            @Override public void onPartial(String text) {
                main.post(() -> {
                    if (!active.get()) return;
                    Bilingual b = Bilingual.parse(text);
                    listener.onStreaming(b.zh, b.en);
                });
            }
            @Override public void onFinal(String text) {
                main.post(() -> {
                    if (!active.get()) return;
                    Bilingual b = Bilingual.parse(text);
                    listener.onCommitted(b.zh, b.en);
                });
            }
            @Override public void onReady() {
                main.post(listener::onListening);
            }
            @Override public void onError(int code, String message) {
                main.post(() -> listener.onError("ASR", code, message));
                stop();
            }
        });
    }

    public void stop() {
        if (!active.compareAndSet(true, false)) return;
        try { asr.stop(); } catch (Throwable ignored) {}
        main.post(listener::onIdle);
    }
}
