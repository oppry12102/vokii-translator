package com.vokii.translator;

/**
 * Last-resort ASR engine. Fires an error immediately so the UI can show
 * a clear "no ASR available" message instead of silently hanging.
 */
public class NoOpEngine implements AsrEngine {

    @Override public String name() { return "None"; }
    @Override public boolean isStarted() { return false; }

    @Override
    public void start(Callback callback) {
        callback.onError(-999, "No ASR engine available on this device");
    }

    @Override public void write(byte[] data, int length) {}
    @Override public void stop() {}
}