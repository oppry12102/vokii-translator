package com.vokii.translator;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Real-time translator UI. Single scrollable transcript card with one
 * Chinese line above one English line per turn, plus the mic control and
 * an optional debug panel.
 *
 *   microphone → Qwen-Omni Realtime WS → streaming ZH:.. / EN:.. turns
 *                                                  │
 *                                                  ▼
 *                              transcript card (history, scrollable)
 */
public class MainActivity extends AppCompatActivity implements TranslationController.Listener {

    private static final int REQ_RECORD_AUDIO = 101;

    private ConfigStore config;
    private DebugLogger debug;

    private TextView textTranscript;
    private ScrollView scrollTranscript;
    private TextView statusLabel;
    private View statusDot;
    private ImageButton btnMic;
    private View debugPanel;
    private TextView debugText;

    /** Committed transcript as "中文\n英文\n\n" entries, newest at the end. */
    private final StringBuilder transcript = new StringBuilder();
    /** Latest streaming pair (current turn), merged into the rendered view. */
    private final StringBuilder currentTurn = new StringBuilder();
    /**
     * Generation counter for "scroll to bottom after the next layout". Each
     * renderTranscript that wants to auto-scroll bumps this; the
     * OnGlobalLayoutListener checks its captured generation against the
     * current one and discards itself if superseded by a newer render call.
     */
    private int scrollGen;

    private AsrEngine asr;
    private TranslationController controller;
    /** Engine type at last build — used by onResume to detect a Settings
     *  change that requires recreating the engine (e.g. cascade toggle). */
    private String currentEngineName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashReporter.install(getApplicationContext());

        setContentView(R.layout.activity_main);

        config = new ConfigStore(this);
        debug = new DebugLogger((TextView) findViewById(R.id.debug_text));

        textTranscript  = findViewById(R.id.text_transcript);
        scrollTranscript = findViewById(R.id.scroll_transcript);
        statusLabel     = findViewById(R.id.status_label);
        statusDot       = findViewById(R.id.status_dot);
        btnMic          = findViewById(R.id.btn_mic);
        debugPanel      = findViewById(R.id.debug_panel);
        debugText       = findViewById(R.id.debug_text);
        Button btnClear = findViewById(R.id.btn_clear_debug);
        ImageButton btnSettings = findViewById(R.id.btn_settings);

        applyDebugVisibility(config.isDebugVisible());

        btnMic.setOnClickListener(v -> toggleListening());
        btnClear.setOnClickListener(v -> debug.clear());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        try {
            asr = AsrEngineFactory.create(this, config, debug);
        } catch (Throwable t) {
            debug.log("boot", "ASR init failed: " + t);
            android.util.Log.e("VokiiBoot", "ASR init failed", t);
            asr = null;
        }
        controller = new TranslationController(asr, this);
        currentEngineName = (asr == null ? "null" : asr.name());

        debug.log("boot", "endpoint=" + config.getEndpoint());
        debug.log("boot", "model=" + config.getModel());
        debug.log("boot", "api_key=" + (config.getApiKey().isEmpty() ? "EMPTY" : "set"));
        debug.log("boot", "debug_visible=" + config.isDebugVisible());
        debug.log("boot", "asr_engine=" + currentEngineName);

        setStatus(Status.IDLE, getString(R.string.hint_speak));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-read SharedPreferences after returning from Settings — values
        // changed there don't reach this Activity otherwise (we read them
        // once in onCreate).
        applyDebugVisibility(config.isDebugVisible());
        reconcileEngineIfNeeded();
    }

    /** If the user changed cascade_mode in Settings while we were paused,
     *  tear down the old engine and build a new one so the next mic tap
     *  picks the right pipeline. No-op if nothing changed. */
    private void reconcileEngineIfNeeded() {
        if (controller == null) return;
        if (controller.isActive()) {
            // Don't hot-swap while listening — the user would lose audio.
            // They'll get the new engine on the next start.
            return;
        }
        String desiredName = config.isCascadeMode()
                ? "Cascade(Paraformer→qwen-mt-plus)"
                : "QwenOmniRealtime";
        if (desiredName.equals(currentEngineName)) return;
        debug.log("engine", "rebuilding: " + currentEngineName + " -> " + desiredName);
        AsrEngine fresh;
        try {
            fresh = AsrEngineFactory.create(this, config, debug);
        } catch (Throwable t) {
            debug.log("engine", "rebuild failed: " + t);
            return;
        }
        asr = fresh;
        controller = new TranslationController(asr, this);
        currentEngineName = asr.name();
        debug.log("engine", "now running " + currentEngineName);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (controller != null) controller.stop();
    }

    private void applyDebugVisibility(boolean visible) {
        debugPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // applyDebugVisibility already idempotent — exposed package-private via
    // the public method above for clarity.

    private void toggleListening() {
        if (controller != null && controller.isActive()) {
            controller.stop();
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
                return;
            }
            if (asr == null) {
                setStatus(Status.IDLE, getString(R.string.err_asr_unavailable));
                debug.log("pipeline", "asr is null — bailing");
                return;
            }
            currentTurn.setLength(0);
            btnMic.setImageResource(R.drawable.ic_mic);
            btnMic.setBackground(getDrawable(R.drawable.bg_mic_recording));
            controller.start();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleListening();
            } else {
                debug.log("perm", "RECORD_AUDIO denied");
                setStatus(Status.IDLE, getString(R.string.permission_required));
            }
        }
    }

    // ----- TranslationController.Listener -----

    @Override public void onPreparing() {
        setStatus(Status.PREPARING, getString(R.string.hint_preparing));
    }

    @Override public void onListening() {
        setStatus(Status.LISTENING, getString(R.string.hint_listening));
    }

    @Override public void onIdle() {
        setStatus(Status.IDLE, getString(R.string.hint_speak));
        btnMic.setImageResource(R.drawable.ic_mic);
        btnMic.setBackground(getDrawable(R.drawable.bg_mic));
    }

    /** Live partial for the in-progress turn: render history + streaming tail. */
    @Override public void onStreaming(String zh, String en) {
        currentTurn.setLength(0);
        currentTurn.append(zh == null ? "" : zh).append('\n');
        currentTurn.append(en == null ? "" : en);
        renderTranscript();
    }

    /** Finished turn: fold into history, then render history alone. */
    @Override public void onCommitted(String zh, String en) {
        if ((zh != null && !zh.trim().isEmpty()) || (en != null && !en.trim().isEmpty())) {
            // Only append each line if it's non-empty — a single-language
            // turn (zh missing or en missing) shouldn't add a stray blank
            // line where the other language would have been.
            if (zh != null && !zh.trim().isEmpty()) transcript.append(zh.trim()).append('\n');
            if (en != null && !en.trim().isEmpty()) transcript.append(en.trim()).append('\n');
            transcript.append('\n');  // blank-line separator between turns
        }
        currentTurn.setLength(0);
        renderTranscript();
    }

    /**
     * Re-render the transcript view as {@code history + currentTurn} and, if
     * the user was already at the bottom, keep it pinned there. If the user
     * has scrolled up to read history, leave their scroll position alone.
     *
     * The auto-scroll runs from an {@link ViewTreeObserver.OnGlobalLayoutListener}
     * — NOT from a {@code scroll.post()} — because setText only schedules a
     * layout pass, and a posted runnable typically runs BEFORE the layout.
     * fullScroll on the old layout scrolls to the OLD bottom; the new
     * content (added at the end) is then laid out below that scroll position
     * and stays clipped. Waiting for the layout to complete is what actually
     * pins the new line to the visible bottom.
     */
    private void renderTranscript() {
        String text = (transcript.toString() + currentTurn).replaceAll("\\s+$", "");
        boolean wasAtBottom = isAtBottom(scrollTranscript);
        textTranscript.setText(text);
        if (wasAtBottom) {
            final int myGen = ++scrollGen;
            final ViewTreeObserver vto = scrollTranscript.getViewTreeObserver();
            vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    if (myGen != scrollGen) return;  // a newer render superseded us
                    vto.removeOnGlobalLayoutListener(this);
                    scrollTranscript.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    private boolean isAtBottom(ScrollView scroll) {
        if (scroll.getChildCount() == 0) return true;
        View child = scroll.getChildAt(0);
        int diff = child.getBottom() - (scroll.getHeight() + scroll.getScrollY());
        // 128px ≈ 3–4 lines of 16sp text. With the smaller 24px threshold,
        // a single new committed line (~30px) would push diff above the limit
        // and break stick-to-bottom, leaving the newest pair half-cut off.
        // 128px tolerates normal incremental growth but still yields control
        // once the user has actually scrolled up more than a few lines.
        return diff <= 128;
    }

    @Override public void onError(String where, int code, String message) {
        debug.log(where, "error " + code + " " + message);
        if ("ASR".equals(where) && code != 0) {
            setStatus(Status.IDLE, getString(R.string.err_asr_unavailable) + " (" + code + ")");
        }
    }

    private enum Status { IDLE, PREPARING, LISTENING }

    private void setStatus(Status s, String label) {
        int color;
        switch (s) {
            case LISTENING:   color = Color.parseColor("#FFFF7E5F"); break;
            case PREPARING:   color = Color.parseColor("#FF7AB7FF"); break;
            default:          color = Color.parseColor("#FF6E7681");
        }
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        statusDot.setBackground(d);
        statusLabel.setText(label);
    }
}
