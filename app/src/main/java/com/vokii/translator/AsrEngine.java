package com.vokii.translator;

/**
 * Strategy interface for on-device ASR. The only current production
 * implementation is {@link AndroidSpeechEngine}, which uses the platform's
 * built-in {@link android.speech.SpeechRecognizer} — backed by Huawei
 * HiSpeech on Huawei ROMs.
 *
 * The interface stays in place so a future PCM-fed engine (e.g. Vosk)
 * can drop in without UI changes; the PCM path is exercised by
 * {@link #write(byte[], int)}.
 */
public interface AsrEngine {

    interface Callback {
        void onPartial(String text);
        void onFinal(String text);
        void onError(int code, String message);
        void onReady();
        /** Voice control command(s) detected by the MT LLM's tool_use.
         *  Default empty so the joint Qwen-Omni path (no MT LLM) needs no
         *  override. Only {@link CascadeEngine} implements this. */
        default void onCommand(java.util.List<ToolCall> calls) {}
    }

    void start(Callback callback);
    void write(byte[] data, int length);
    void stop();
    boolean isStarted();
    String name();

    /**
     * True when the engine captures microphone audio on its own (Android
     * SpeechRecognizer does). For such engines the app must NOT also open
     * a separate audio client, or the two mic clients starve each other.
     * Only a PCM-fed engine (e.g. Vosk) overrides this to false.
     */
    default boolean capturesOwnAudio() { return true; }
}