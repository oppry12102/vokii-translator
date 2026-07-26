package com.vokii.translator;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Default on-device ASR engine. Uses Android's built-in
 * {@link SpeechRecognizer}. On Android 13+ the system service can run the
 * recognition model fully on-device for the languages enabled in the
 * system settings.
 *
 * We pass the user's preferred locale tag from {@link ConfigStore}; the
 * system returns the recognised text in the matching language. Both
 * Chinese (zh-CN) and English (en-US) are supported by the default
 * Android speech service on stock devices.
 */
public class AndroidSpeechEngine implements AsrEngine {

    private final Context appCtx;
    private final DebugLogger debug;
    private final String langHint;

    private SpeechRecognizer sr;
    private Callback cb;
    private volatile boolean started;

    public AndroidSpeechEngine(Context ctx, DebugLogger debug, String langHint) {
        this.appCtx = ctx.getApplicationContext();
        this.debug = debug;
        this.langHint = langHint;
    }

    @Override public String name() { return "AndroidSpeechRecognizer"; }

    @Override public boolean isStarted() { return started; }

    @Override
    public void start(Callback callback) {
        if (started) return;
        this.cb = callback;
        if (!SpeechRecognizer.isRecognitionAvailable(appCtx)) {
            debug.log("ASR", "SpeechRecognizer not available on this device");
            callback.onError(-1, "Speech recognition not available");
            return;
        }
        try {
            sr = SpeechRecognizer.createSpeechRecognizer(appCtx);
        } catch (Throwable t) {
            debug.log("ASR", "createSpeechRecognizer: " + t.getMessage());
            callback.onError(-2, t.getMessage());
            return;
        }
        sr.setRecognitionListener(new Listener());
        Intent intent = buildIntent();
        try {
            sr.startListening(intent);
            started = true;
            debug.log("ASR", "startListening ok, lang=" + resolvedLocale());
        } catch (Throwable t) {
            debug.log("ASR", "startListening: " + t.getMessage());
            callback.onError(-3, t.getMessage());
        }
    }

    private Intent buildIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, resolvedLocale());
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, resolvedLocale());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appCtx.getPackageName());
        }
        return intent;
    }

    private String resolvedLocale() {
        if (langHint == null || langHint.trim().isEmpty()) return Locale.getDefault().toLanguageTag();
        // Take the first token of the language hint
        String first = langHint.trim().split("\\s+")[0].toLowerCase(Locale.US);
        if (first.startsWith("zh")) return "zh-CN";
        if (first.startsWith("en")) return "en-US";
        return Locale.getDefault().toLanguageTag();
    }

    @Override
    public void write(byte[] data, int length) {
        // The Android SpeechRecognizer streams audio via its own internal
        // mic capture. We do not forward PCM frames into it — this method
        // exists so the interface stays symmetric across engines.
    }

    @Override
    public void stop() {
        if (!started) return;
        started = false;
        destroyRecognizer();
        debug.log("ASR", "stopped");
    }

    /** Tear down the live SpeechRecognizer (stop → cancel → destroy → null).
     *  Shared by {@link #stop} and the system {@code onError} path — a system
     *  error (NO_MATCH / SPEECH_TIMEOUT / RECOGNIZER_BUSY) used to leave
     *  {@code sr} alive, and the next {@code start()} created a new one over
     *  it without destroying, leaking the IPC binding until GC. Repeated
     *  enough times the speech service refused to bind with RECOGNIZER_BUSY. */
    private void destroyRecognizer() {
        if (sr != null) {
            try { sr.stopListening(); } catch (Throwable ignored) {}
            try { sr.cancel(); } catch (Throwable ignored) {}
            try { sr.destroy(); } catch (Throwable ignored) {}
            sr = null;
        }
    }

    private class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) {
            debug.log("ASR", "readyForSpeech");
            Callback c = cb;
            if (c != null) c.onReady();
        }
        @Override public void onBeginningOfSpeech() { debug.log("ASR", "beginSpeech"); }
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() { debug.log("ASR", "endSpeech"); }
        @Override public void onError(int error) {
            String msg = errorMessage(error);
            debug.log("ASR", "err " + error + " " + msg);
            Callback c = cb;
            if (c != null) c.onError(error, msg);
            // System errors fire asynchronously and leave `sr` alive. Tear it
            // down exactly as stop() does so a subsequent start() doesn't
            // leak the old SpeechRecognizer (see destroyRecognizer).
            destroyRecognizer();
            started = false;
        }
        @Override public void onResults(Bundle results) {
            if (!started) return;  // stale final arrived after stop()/onError
            ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = list == null || list.isEmpty() ? "" : list.get(0);
            debug.log("ASR", "final: " + text);
            Callback c = cb;
            if (c != null && !text.isEmpty()) c.onFinal(text);
        }
        @Override public void onPartialResults(Bundle partialResults) {
            if (!started) return;  // stale partial arrived after stop()/onError
            ArrayList<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = list == null || list.isEmpty() ? "" : list.get(0);
            if (text.isEmpty()) return;
            Callback c = cb;
            if (c != null) c.onPartial(text);
        }
        @Override public void onEvent(int eventType, Bundle params) {}
    }

    private static String errorMessage(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_AUDIO: return "audio recording error";
            case SpeechRecognizer.ERROR_CLIENT: return "client side error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "insufficient permissions";
            case SpeechRecognizer.ERROR_NETWORK: return "network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH: return "no match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "recognizer busy";
            case SpeechRecognizer.ERROR_SERVER: return "server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "speech timeout";
            default: return "unknown";
        }
    }
}