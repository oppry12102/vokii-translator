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
 * {@link TurnParser} and surface:
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
        /** Live partial translation for the current turn. The {@code srcLang}
         *  and {@code tgtLang} arguments are the language codes current
         *  when the turn was produced, so a listener that stores them
         *  alongside the text can re-render correctly after a language
         *  change. */
        void onStreaming(String source, String target, String srcLang, String tgtLang);
        /** Final translation for a finished turn — append to history. */
        void onCommitted(String source, String target, String srcLang, String tgtLang);
        /** Voice control command(s) emitted by the MT LLM's tool_use.
         *  Default empty so the joint Qwen-Omni path needs no override. */
        default void onCommand(java.util.List<ToolCall> calls) {}
        /** Live verbatim ASR partial (cascade only) — raw transcript before
         *  MT. Lets the UI show a "live caption" line while the user is
         *  still speaking, so first text arrives at ASR TTFB (~0.4 s) rather
         *  than after sentence-final + MT TTFB. */
        default void onPartialTranscript(String text) {}
        /** Sentence-final verbatim (cascade only) — the verbatim column
         *  locks here; later partials belong to the next sentence. */
        default void onFinalTranscript(String text) {}
        /** Something failed. */
        void onError(String where, int code, String message);
    }

    private final AsrEngine asr;
    private final Listener listener;
    private final SessionConfig session;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean active = new AtomicBoolean(false);
    /** Set once in {@link #cancel()} (called from Activity onDestroy). Once
     *  true every posted callback bails, so a stop()/onError/onIdle scheduled
     *  while the owning Activity is being torn down never touches a destroyed
     *  view tree (the cause of intermittent 闪退 IllegalStateExceptions). */
    private volatile boolean cancelled;

    public TranslationController(AsrEngine asr, SessionConfig session, Listener listener) {
        this.asr = asr;
        this.session = session;
        this.listener = listener;
    }

    public boolean isActive() { return active.get(); }

    public void start() {
        if (!active.compareAndSet(false, true)) return;
        main.post(() -> { if (!cancelled) listener.onPreparing(); });
        asr.start(new AsrEngine.Callback() {
            @Override public void onPartial(String text) {
                final String src = session.sourceLang();
                final String tgt = session.targetLang();
                main.post(() -> {
                    if (!active.get()) return;
                    // Streaming parse: labels trusted, never Han-swapped —
                    // a mid-stream swap verdict would flip which column the
                    // UI feeds from and wipe the line at every flip. The
                    // final parse in onFinal below still corrects a truly
                    // swapped pair, once, at the sentence boundary.
                    TurnParser p = TurnParser.parseStreaming(text, src, tgt);
                    listener.onStreaming(p.source, p.target, src, tgt);
                });
            }
            @Override public void onFinal(String text) {
                final String src = session.sourceLang();
                final String tgt = session.targetLang();
                main.post(() -> {
                    if (!active.get()) return;
                    TurnParser p = TurnParser.parse(text, src, tgt);
                    listener.onCommitted(p.source, p.target, src, tgt);
                });
            }
            @Override public void onReady() {
                main.post(() -> { if (!cancelled) listener.onListening(); });
            }
            @Override public void onError(int code, String message) {
                main.post(() -> { if (!cancelled) listener.onError("ASR", code, message); });
                stop();
            }
            @Override public void onCommand(java.util.List<ToolCall> calls) {
                main.post(() -> {
                    if (!active.get()) return;
                    listener.onCommand(calls);
                });
            }
            @Override public void onPartialTranscript(String text) {
                main.post(() -> {
                    if (!active.get()) return;
                    listener.onPartialTranscript(text);
                });
            }
            @Override public void onFinalTranscript(String text) {
                main.post(() -> {
                    if (!active.get()) return;
                    listener.onFinalTranscript(text);
                });
            }
        });
    }

    public void stop() {
        if (!active.compareAndSet(true, false)) return;
        try { asr.stop(); } catch (Throwable ignored) {}
        main.post(() -> { if (!cancelled) listener.onIdle(); });
    }

    /** Cancel the controller and drain its main-handler queue so nothing fires
     *  after the owning Activity is destroyed. Idempotent. Unlike {@link #stop},
     *  this does NOT post onIdle — there is no live UI to receive it by the
     *  time this runs (Activity.onDestroy). It also stops the engine and
     *  removes every callback the controller ever posted, which is the fix
     *  for the post-destroy UI crashes: <code>stop()</code> alone still
     *  scheduled an <code>onIdle</code> that ran on a dead view tree. */
    public void cancel() {
        cancelled = true;
        active.set(false);
        main.removeCallbacksAndMessages(null);
        try { asr.stop(); } catch (Throwable ignored) {}
    }
}
