package com.vokii.translator;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Real-time translator UI. The transcript is a scrollable column of
 * per-turn cards (one source line above one target line) inside a
 * LinearLayout, plus a one-line status hint under the card, the mic
 * control, and an optional debug panel.
 *
 *   microphone → ASR/MT engines → streaming turns
 *                                       │
 *                                       ▼
 *                     transcript cards — committed cards are immutable;
 *                     only the bottom "active" card updates. A typewriter
 *                     reveals the target line char-by-char and a chase
 *                     scroller glides (never jumps) to the bottom, so
 *                     on-screen lines stay put while new text arrives.
 */
public class MainActivity extends AppCompatActivity implements TranslationController.Listener {

    private static final int REQ_RECORD_AUDIO = 101;

    private ConfigStore config;
    private SessionConfig session;
    private SessionContext sessionContext;
    private ToolRegistry toolRegistry;
    private ToolDispatcher toolDispatcher;
    private DebugLogger debug;
    /** Snapshot of the last successfully-applied tool, used to power the
     *  UNDO Toast. Nulled out when a new tool overwrites it. */
    private CommandResult lastUndoable;
    /** Pre-batch SessionConfig snapshot captured by the dispatcher when the
     *  last undoable command ran. Preferred over the per-tool snapshot on
     *  {@link #lastUndoable} so a multi-mutation batch undoes to the true
     *  pre-batch state rather than just before the last mutation. */
    private SessionConfig.Snapshot lastUndoSnapshot;

    private ScrollView scrollTranscript;
    private LinearLayout turnsContainer;
    /** One-line status hint under the transcript — voice-command results
     *  ("» Languages → zh↔ja") surface here so settings noise stays out of
     *  the conversation text. Tappable to UNDO the last undoable command. */
    private TextView statusHint;
    private TextView statusLabel;
    private View statusDot;
    private ImageButton btnMic;
    private View debugPanel;
    private TextView debugText;

    /** Committed transcript as a list of typed turns (TRANSLATION or
     *  COMMAND), newest at the end. Card i of turnsContainer mirrors
     *  history[i]; an optional "active" card trails for the in-flight
     *  sentence. Committed cards are never modified after creation — the
     *  display-mode filter is applied per card at build time. */
    private final List<Turn> history = new ArrayList<>();

    // ----- active (in-flight) card -----
    /** The bottom card while a sentence is streaming; null otherwise.
     *  While non-null it is always turnsContainer's last child. */
    private View activeCard;
    private TextView activeSourceView;
    private TextView activeTargetView;
    /** Latest verbatim ASR text for the in-flight sentence (no "› ").
     *  This — not the MT's re-rendered version of the same language — is
     *  what the verbatim column shows, so the line the user is reading
     *  never gets rewritten by a new MT generation. */
    private String activeVerbatim = "";
    /** True once the ASR sentence-final verbatim arrived. Caption partials
     *  after this point belong to the NEXT sentence and must not touch
     *  this card (it is waiting for its MT commit). */
    private boolean verbatimFinalized;
    /** Which column the verbatim occupies: true = source (top), false =
     *  target (bottom). Fixed sessions: verbatim always IS the source
     *  language. Auto (zh<->en): Han-dominant verbatim → zh/source. Decided
     *  once per sentence at the first caption text (or first MT delta). */
    private boolean verbatimIsSourceCol = true;
    private boolean verbatimColDecided;
    /** False while the card shows only the live ASR caption; true once MT
     *  (final or speculative) has streamed for this sentence. */
    private boolean activeHasMt;
    /** True while a deferred ASR-partial render is already queued on the UI
     *  handler. ASR partials fire at ~20 ms — when this flag is set incoming
     *  partials update {@link #activeVerbatim} but skip posting a duplicate
     *  render, so at most one text-set runs per message-queue cycle. */
    private boolean renderPending;

    // ----- target-line typewriter (see feedTranslation) -----
    private TextView typeView;
    private String typeFull = "";
    private int typeShown;
    private boolean typeTicking;
    /** Whether the line typeView points at is the source column. Tracked
     *  separately from verbatimIsSourceCol because the latter resets when
     *  the card commits while the ticker keeps draining into it. */
    private boolean typeIsSourceCol;
    private static final int TYPE_TICK_MS = 40;
    private static final int TYPE_CATCHUP_DIVISOR = 8;

    // ----- chase scroller (see maybeChase) -----
    private boolean pinned = true;
    private boolean chasing;
    private int chaseSettledFrames;
    private int lastScrollY;
    private float maxScrollStepPx;
    /** TURN_GAP_DP resolved to px once at onCreate; was re-computed (density
     *  lookup + float math + cast) on every turnLayoutParams call, i.e. once
     *  per card in a rebuild. */
    private int turnGapPx;
    private static final float MAX_SCROLL_STEP_DP = 30f;
    // Active card's high-water-mark height, set as minHeight so no in-flight
    // shrink (verbatim REWRITE, MT reword) can reduce the card height and
    // trigger a ScrollView clamp-up — the up-half of the vertical jitter
    // (down-glide on grow + up-clamp on shrink). See trackCardMinHeight.
    private int activeMaxHeight;

    /** Vertical gap between sentence cards — just enough to tell sentences
     *  apart without wasting space. */
    private static final int TURN_GAP_DP = 12;

    // Status-dot colors parsed once at class init — Color.parseColor allocates
    // a Pattern + throws on bad input, and setStatus runs on every state flip
    // (and every refreshMicStatus), so cache the ints.
    private static final int COLOR_LISTENING = Color.parseColor("#FFFF7E5F");
    private static final int COLOR_PREPARING = Color.parseColor("#FF7AB7FF");
    private static final int COLOR_PAUSED    = Color.parseColor("#FFB8A53A");  // amber
    private static final int COLOR_IDLE      = Color.parseColor("#FF6E7681");

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private AsrEngine asr;
    private TranslationController controller;
    /** Transcript persistence — loaded once in onCreate, saved in onPause
     *  and after clear_transcript. */
    private TranscriptStore transcriptStore;
    /** Engine type at last build — used by onResume to detect a Settings
     *  change that requires recreating the engine (e.g. cascade toggle). */
    private String currentEngineName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashReporter.install(getApplicationContext());

        setContentView(R.layout.activity_main);

        config = new ConfigStore(this);
        session = new SessionConfig();
        session.setLanguages(config.getSourceLang(), config.getTargetLang());
        session.setDisplayMode(SessionConfig.DisplayMode.fromKey(config.getDisplayMode()));
        session.setTemperature(config.getTemperature());
        session.setCascadeEnabled(config.isCascadeMode());
        session.setFontScale(config.getFontScale());
        session.setGlossary(config.getGlossary());
        // SessionContext wraps the live session state and grows with
        // command history + recent utterances as the session progresses.
        sessionContext = new SessionContext(session);
        toolRegistry = ToolRegistry.defaultRegistry();
        toolDispatcher = new ToolDispatcher(toolRegistry);
        debug = new DebugLogger((TextView) findViewById(R.id.debug_text));

        turnsContainer  = findViewById(R.id.turns_container);
        statusHint      = findViewById(R.id.status_hint);
        scrollTranscript = findViewById(R.id.scroll_transcript);
        maxScrollStepPx = MAX_SCROLL_STEP_DP * getResources().getDisplayMetrics().density;
        turnGapPx = (int) (TURN_GAP_DP * getResources().getDisplayMetrics().density + 0.5f);
        // Follow-bottom bookkeeping. Unpin only on a genuine USER drag-up:
        // scrollY decreasing while landing clearly above the max scroll.
        // A y-decrease that lands AT the max is the ScrollView clamping
        // after content at the bottom shrank (typewriter snapback
        // unwrapping a line, an ASR caption revision, a rebuild) — pinning
        // there would strand the chaser: nothing re-pins without a touch,
        // and every later line piles up below the fold with the viewport
        // frozen mid-glyph (the "English below the box" bug — the English
        // WAS typing, just off-screen). The chase scroller only ever
        // scrolls DOWN, so this test separates the two cases exactly.
        scrollTranscript.getViewTreeObserver().addOnScrollChangedListener(() -> {
            int y = scrollTranscript.getScrollY();
            boolean contentFits = turnsContainer.getBottom() <= scrollTranscript.getHeight();
            int maxY = Math.max(0, turnsContainer.getBottom() - scrollTranscript.getHeight());
            if (y < lastScrollY - 2 && !programmaticScroll && !contentFits && y < maxY - 2) {
                pinned = false;
            } else if (contentFits || isAtBottom(scrollTranscript)) {
                pinned = true;
                maybeChase();
            }
            lastScrollY = y;
        });
        // The status hint doubles as the UNDO affordance: after an undoable
        // command, tap it to restore the previous settings. Not clickable
        // until an undoable command actually arrives (onCommand flips it).
        statusHint.setOnClickListener(v -> {
            if (lastUndoable != null) undoLastCommand();
        });
        statusHint.setClickable(false);
        statusLabel     = findViewById(R.id.status_label);
        statusDot       = findViewById(R.id.status_dot);
        btnMic          = findViewById(R.id.btn_mic);
        debugPanel      = findViewById(R.id.debug_panel);
        debugText       = findViewById(R.id.debug_text);
        Button btnClear = findViewById(R.id.btn_clear_debug);
        ImageButton btnSettings = findViewById(R.id.btn_settings);
        EditText injectText = findViewById(R.id.inject_text);
        Button btnInject = findViewById(R.id.btn_inject);
        Button presetLang = findViewById(R.id.preset_lang);
        Button presetDisplay = findViewById(R.id.preset_display);
        Button presetCascade = findViewById(R.id.preset_cascade);
        Button presetDebug = findViewById(R.id.preset_debug);
        Button presetStyle = findViewById(R.id.preset_style);
        Button presetGet = findViewById(R.id.preset_get);
        Button presetTemp = findViewById(R.id.preset_temp);
        Button presetClear = findViewById(R.id.preset_clear);
        Button presetMic = findViewById(R.id.preset_mic);
        Button presetLog = findViewById(R.id.preset_log);
        Button presetExport = findViewById(R.id.preset_export);
        Button presetSummary = findViewById(R.id.preset_summary);
        Button presetRetranslate = findViewById(R.id.preset_retranslate);
        Button presetHelp = findViewById(R.id.preset_help);
        View injectPanel = findViewById(R.id.inject_panel);

        // Inject panel is dev/test-only. Hide it in release builds so
        // production users never see the "skip ASR" affordance, even if
        // they have the debug log panel enabled.
        if (!BuildConfig.DEBUG) {
            injectPanel.setVisibility(View.GONE);
        }

        applyDebugVisibility(config.isDebugVisible());

        btnMic.setOnClickListener(v -> toggleListening());
        btnClear.setOnClickListener(v -> debug.clear());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        // Test inject panel — visible only when the debug panel itself is
        // visible (so non-debug users never see it). Requires the engine
        // to be a CascadeEngine; falls back to a Toast when the joint
        // path is selected.
        View.OnClickListener presetClick = v -> {
            int id = v.getId();
            if (id == R.id.preset_lang)      injectText.setText("下面改成中日翻译");
            else if (id == R.id.preset_display) injectText.setText("只显示日文就好");
            else if (id == R.id.preset_cascade) injectText.setText("切换到普通模式");
            else if (id == R.id.preset_debug)   injectText.setText("打开调试");
            else if (id == R.id.preset_mic)     injectText.setText("暂停。");
            else if (id == R.id.preset_log)     injectText.setText("把日志设成详细模式。");
            else if (id == R.id.preset_export)  injectText.setText("复制到剪贴板。");
            else if (id == R.id.preset_summary) injectText.setText("总结一下。");
            else if (id == R.id.preset_retranslate) injectText.setText("重新翻译上一句。");
            else if (id == R.id.preset_help)    injectText.setText("你能做什么？");
            else if (id == R.id.preset_style)   injectText.setText("翻译得更文雅一些");
            else if (id == R.id.preset_get)     injectText.setText("现在是什么设置？");
            else if (id == R.id.preset_temp)    injectText.setText("温度调到 0.7");
            else if (id == R.id.preset_clear)   injectText.setText("清空翻译");
        };
        presetLang.setOnClickListener(presetClick);
        presetDisplay.setOnClickListener(presetClick);
        presetCascade.setOnClickListener(presetClick);
        presetDebug.setOnClickListener(presetClick);
        presetStyle.setOnClickListener(presetClick);
        presetGet.setOnClickListener(presetClick);
        presetTemp.setOnClickListener(presetClick);
        presetClear.setOnClickListener(presetClick);
        presetMic.setOnClickListener(presetClick);
        presetLog.setOnClickListener(presetClick);
        presetExport.setOnClickListener(presetClick);
        presetSummary.setOnClickListener(presetClick);
        presetRetranslate.setOnClickListener(presetClick);
        presetHelp.setOnClickListener(presetClick);

        View.OnClickListener injectClick = v -> doInject(injectText.getText().toString());
        btnInject.setOnClickListener(injectClick);
        injectText.setOnEditorActionListener((v, actionId, event) -> {
            doInject(injectText.getText().toString());
            return true;
        });

        try {
            asr = AsrEngineFactory.create(this, config, sessionContext, toolRegistry, debug);
        } catch (Throwable t) {
            debug.log("boot", "ASR init failed: " + t);
            android.util.Log.e("VokiiBoot", "ASR init failed", t);
            asr = null;
        }
        controller = new TranslationController(asr, session, this);
        currentEngineName = (asr == null ? "null" : asr.name());

        debug.log("boot", "endpoint=" + config.getEndpoint());
        debug.log("boot", "model=" + config.getModel());
        debug.log("boot", "api_key=" + (config.getApiKey().isEmpty() ? "EMPTY" : "set"));
        debug.log("boot", "debug_visible=" + config.isDebugVisible());
        debug.log("boot", "src=" + session.sourceLang() + " tgt=" + session.targetLang()
                + " display=" + session.displayMode().key());
        debug.log("boot", "tools=" + String.join(",", toolRegistry.names()));
        debug.log("boot", "asr_engine=" + currentEngineName);

        // Restore the persisted transcript from the previous session.
        transcriptStore = new TranscriptStore(this);
        history.addAll(transcriptStore.load());
        if (!history.isEmpty()) {
            debug.log("boot", "transcript restored: " + history.size() + " turns");
            rebuildAllTurns();
        }

        setStatus(Status.IDLE, getString(R.string.hint_speak));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-read SharedPreferences after returning from Settings — values
        // changed there don't reach this Activity otherwise (we read them
        // once in onCreate).
        applyDebugVisibility(config.isDebugVisible());
        applySessionFromConfig();
        reconcileEngineIfNeeded();
    }

    /** Re-read the persisted language/display prefs and apply them to the
     *  live session. If either changed the transcript is re-rendered so
     *  the user sees the new filter across the entire history. */
    private void applySessionFromConfig() {
        String newSrc = config.getSourceLang();
        String newTgt = config.getTargetLang();
        SessionConfig.DisplayMode newMode = SessionConfig.DisplayMode.fromKey(config.getDisplayMode());
        float newTemp = config.getTemperature();
        boolean newCascade = config.isCascadeMode();
        float newFont = config.getFontScale();
        boolean changed = !newSrc.equals(session.sourceLang())
                || !newTgt.equals(session.targetLang())
                || newMode != session.displayMode()
                || newTemp != session.temperature()
                || newCascade != session.cascadeEnabled()
                || newFont != session.fontScale();
        session.setLanguages(newSrc, newTgt);
        session.setDisplayMode(newMode);
        session.setTemperature(newTemp);
        session.setCascadeEnabled(newCascade);
        session.setFontScale(newFont);
        session.setGlossary(config.getGlossary());
        if (changed) {
            rebuildAllTurns();
        }
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
                ? "Cascade(fun-asr→qwen-turbo)"
                : "QwenOmniRealtime";
        if (desiredName.equals(currentEngineName)) return;
        debug.log("engine", "rebuilding: " + currentEngineName + " -> " + desiredName);
        AsrEngine fresh;
        try {
            fresh = AsrEngineFactory.create(this, config, sessionContext, toolRegistry, debug);
        } catch (Throwable t) {
            debug.log("engine", "rebuild failed: " + t);
            return;
        }
        if (controller != null) controller.cancel();  // drain the old one's pending callbacks
        asr = fresh;
        controller = new TranslationController(asr, session, this);
        currentEngineName = asr.name();
        debug.log("engine", "now running " + currentEngineName);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Privacy + cost: stop the mic + cloud streaming whenever another
        // Activity covers this one (Settings, recents, HOME, the notification
        // shade). Without this the ASR kept recording and the cascade kept
        // billing tokens for audio the user didn't intend to translate, with
        // no on-screen cue (the recording dot lives on the now-covered
        // MainActivity). The user re-taps the mic to resume — we deliberately
        // do NOT auto-restart on resume, since silently resuming recording
        // would be surprising.
        if (controller != null && controller.isActive()) {
            controller.stop();
        }
        // Persist the transcript so it survives process death / restart.
        if (transcriptStore != null) transcriptStore.save(history);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Drop any pending typewriter ticks so they don't fire into a
        // destroyed view.
        uiHandler.removeCallbacksAndMessages(null);
        // Cancel (not just stop) the controller: stop() posts onIdle, which
        // would otherwise run on a destroyed view tree (the intermittent
        // 闪退 IllegalStateException). cancel() drains the controller's own
        // handler queue and stops the engine. Also stop the self-reposting
        // chase scroller — View.postOnAnimation callbacks are NOT drained by
        // the uiHandler remove above (different target), so it would keep
        // firing into the detached view tree.
        if (scrollTranscript != null) {
            scrollTranscript.removeCallbacks(scrollChaser);
            chasing = false;
        }
        if (controller != null) controller.cancel();
    }

    private void applyDebugVisibility(boolean visible) {
        debugPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Send a text string through the MT LLM as if the ASR had produced
     *  it. No-op (with a Toast) when the engine isn't a CascadeEngine or
     *  isn't started — both are required to exercise the full chain. */
    private void doInject(String text) {
        if (text == null || text.trim().isEmpty()) {
            Toast.makeText(this, "Type something first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!(asr instanceof CascadeEngine)) {
            Toast.makeText(this, "Inject only works in Cascade mode", Toast.LENGTH_LONG).show();
            return;
        }
        if (!controller.isActive()) {
            Toast.makeText(this, "Press the mic first to start the engine", Toast.LENGTH_LONG).show();
            return;
        }
        ((CascadeEngine) asr).injectVerbatim(text.trim());
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
            dropActiveCard();  // an uncommitted card never made it to history
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
                if (!ActivityCompat.shouldShowRequestPermissionRationale(
                        this, Manifest.permission.RECORD_AUDIO)) {
                    // "Don't ask again" selected (or a permanent-deny OEM
                    // path): requestPermissions() will no longer show a
                    // dialog, so the mic would be stuck off forever. Offer a
                    // deep link to the system app-settings page where the
                    // user can grant it manually.
                    showMicPermissionSettingsDialog();
                } else {
                    setStatus(Status.IDLE, getString(R.string.permission_required));
                }
            }
        }
    }

    /** Shown when RECORD_AUDIO was denied with "don't ask again" — the only
     *  recovery path is the system per-app permissions page. */
    private void showMicPermissionSettingsDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.permission_required)
                .setMessage("麦克风权限已被拒绝且设为不再询问。请到系统设置中手动开启录音权限后返回重试。")
                .setPositiveButton("去设置", (d, w) -> {
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(android.net.Uri.fromParts("package", getPackageName(), null));
                    try { startActivity(intent); } catch (Throwable ignored) {}
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setCancelable(false)
                .show();
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
        // Mic stopped — drop a lingering caption-only card so it doesn't sit
        // on screen after the user stops talking. A card that already has MT
        // content stays (mirrors the old currentTurn semantics: it remains
        // visible until the next start drops it).
        if (activeCard != null && !activeHasMt) {
            dropActiveCard();
            maybeChase();
        }
    }

    /** Live verbatim ASR partial (cascade only). Shown in the verbatim
     *  column while the user is speaking so first text lands at ASR TTFB
     *  (~0.4 s). Keeps updating through the whole sentence — including
     *  while speculative MT streams below/above it — until the ASR
     *  sentence-final locks it ({@link #onFinalTranscript}). */
    @Override public void onPartialTranscript(String text) {
        if (text == null || text.isEmpty()) return;
        if (activeCard != null && verbatimFinalized) {
            // The card is waiting for its MT commit; this partial is the
            // next sentence's caption — it stages once the card commits.
            return;
        }
        if (activeCard != null && text.equals(activeVerbatim)) return;  // no change
        debug.log("RNDER", "caption len=" + text.length()
                + " change=" + (activeVerbatim.isEmpty() ? "new"
                        : (text.startsWith(activeVerbatim) ? "extend" : "REWRITE")));
        activeVerbatim = text;
        // Coalesce: ASR partials arrive at ~20 ms but a single setText is
        // enough per message-queue cycle. Defer the render so multiple
        // incoming partials collapse into one UI update. During command
        // processing this also keeps the verbatim buffer up to date while
        // skipping the per-partial measure/setText/maybeChase work on the
        // (congested) UI thread.
        if (!renderPending) {
            renderPending = true;
            uiHandler.post(renderPartial);
        }
    }

    private final Runnable renderPartial = new Runnable() {
        @Override public void run() {
            renderPending = false;
            decideVerbatimColumnOnce(null);
            attachActiveCard();
            refreshActiveCardText();
            maybeChase();
        }
    };

    @Override public void onFinalTranscript(String text) {
        if (text == null || text.isEmpty()) return;
        debug.log("RNDER", "verbatim FINAL len=" + text.length());
        activeVerbatim = text;
        verbatimFinalized = true;
        decideVerbatimColumnOnce(null);
        attachActiveCard();
        refreshActiveCardText();
        maybeChase();
    }

    @Override public void onStreaming(String source, String target, String srcLang, String tgtLang) {
        // MT is streaming for this sentence. The two columns have separate
        // suppliers with one shared rule — VISIBLE TEXT NEVER SHRINKS:
        //   verbatim column  ← ASR partials only (monotonic by nature)
        //   translate column ← MT deltas, adoption-gated (feedTranslation)
        // The MT's own rendering of the verbatim-side language is ignored
        // here: every new MT generation regenerates it, which is what used
        // to wipe the card ~once per second.
        String src = source == null ? "" : source;
        String tgt = target == null ? "" : target;
        if (!activeHasMt) {
            activeHasMt = true;
            decideVerbatimColumnOnce(srcLang);
            attachActiveCard();
            typeView = verbatimIsSourceCol ? activeTargetView : activeSourceView;
            typeIsSourceCol = !verbatimIsSourceCol;
            typeFull = "";
            typeShown = 0;
            debug.log("RNDER", "firstMT verbatimCol=" + (verbatimIsSourceCol ? "src" : "tgt")
                    + " vLen=" + activeVerbatim.length());
        }
        feedTranslation(verbatimIsSourceCol ? tgt : src);
        refreshActiveCardText();
        maybeChase();
    }

    /** Pick the verbatim's column once per sentence: fixed-language
     *  sessions the verbatim is always the source language; in auto
     *  (zh<->en) a Han-dominant verbatim belongs to the zh/source column.
     *  Called at the first caption text (or first MT delta, whichever
     *  comes first) so the caption never has to move columns mid-sentence. */
    private void decideVerbatimColumnOnce(String srcLang) {
        if (verbatimColDecided) return;
        verbatimColDecided = true;
        if (srcLang == null) srcLang = session.sourceLang();  // caption path
        if (!"auto".equalsIgnoreCase(srcLang)) {
            verbatimIsSourceCol = true;  // fixed session: verbatim IS source
            return;
        }
        int han = 0;
        for (int i = 0; i < activeVerbatim.length(); i++) {
            char c = activeVerbatim.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) han++;
        }
        verbatimIsSourceCol = han * 2 >= activeVerbatim.length();
    }

    /** Finished turn: the active card freezes in place and becomes part of
     *  the immutable history — no other line re-layouts. If the typewriter
     *  is still behind on the target line it keeps draining into the (now
     *  committed) card; this reads as "finishing the sentence", never as a
     *  paste. */
    @Override public void onCommitted(String source, String target, String srcLang, String tgtLang) {
        String src = source == null ? "" : source;
        String tgt = target == null ? "" : target;
        boolean hasContent = !src.trim().isEmpty() || !tgt.trim().isEmpty();
        if (hasContent) {
            Turn t = Turn.translation(src, tgt, srcLang, tgtLang);
            history.add(t);
            // Feed into SessionContext so the next MT LLM call sees the
            // recent utterance for "再翻一次" / partial-reference style
            // commands.
            sessionContext.recordUtterance(t);
            trimHistoryIfNeeded();
            if (activeCard != null) {
                // Finalize in place, per column: the verbatim column locks
                // to the final MT-rendered text (one authoritative set at
                // the sentence boundary); the translate column keeps its
                // already-typed prefix and drains the rest.
                String vFinal = verbatimIsSourceCol ? src : tgt;
                String tFinal = verbatimIsSourceCol ? tgt : src;
                TextView vView = verbatimIsSourceCol ? activeSourceView : activeTargetView;
                // The MT occasionally omits the verbatim-side label (e.g.
                // no EN: line for a pure-English utterance) — never erase
                // a shown verbatim with that empty parse; keep the ASR text.
                if (vFinal.isEmpty()) vFinal = activeVerbatim;
                debug.log("RNDER", "commit srcLen=" + src.length() + " tgtLen=" + tgt.length()
                        + " vSameAsShown=" + vFinal.equals(activeVerbatim)
                        + " transPrefixKept=" + commonPrefix(tFinal, typeFull.substring(0, typeShown))
                        + "/" + typeShown);
                if (BuildConfig.DEBUG) {
                    debug.log("RNDER", "  typeFull=[" + typeFull + "]");
                    debug.log("RNDER", "  tFinal  =[" + tFinal + "]");
                }
                vView.setText(vFinal);
                vView.setVisibility(columnVisible(verbatimIsSourceCol, vFinal)
                        ? View.VISIBLE : View.GONE);
                feedTypewriter(tFinal);  // authoritative: adopts even when
                applyTypedText();        // shorter, snapping to common prefix
                ensureTypeTicking();     // then drains forward
                // typeView keeps pointing at this card's translate line so
                // the ticker finishes it even though the card is now history.
                resetActiveCardState();
            } else {
                // Committed without ever streaming — append a finished card.
                turnsContainer.addView(buildTurnView(t, session.displayMode()),
                        turnLayoutParams());
            }
        } else {
            // Empty commit — drop the card entirely.
            dropActiveCard();
        }
        maybeChase();
    }

    /** Voice control command(s) from the MT LLM. Dispatched on the
     *  main thread (the controller's listener contract). */
    @Override public void onCommand(java.util.List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) return;
        debug.log("CMD", "received " + calls.size() + " call(s)");
        // Dispatch tool logic off the UI thread:
        //   toolDispatcher.apply() mutates session (fields are volatile → safe)
        //   and config (SharedPreferences → thread-safe), but runs on the MT
        //   executor so the typewriter, ASR partials, and chase scroller keep
        //   running uninterrupted. Only View updates post back to main.
        final java.util.List<ToolCall> captured = calls;
        MtRunner.executor().execute(() -> {
            ToolDispatcher.Applied applied = toolDispatcher.apply(captured, session, config);
            for (int i = 0; i < applied.results.size(); i++) {
                CommandResult r = applied.results.get(i);
                if (r.rejected) {
                    debug.log("CMD", "  reject: " + r.rejectReason);
                } else {
                    debug.log("CMD", "  " + captured.get(i).name + " -> " + r.summary);
                }
            }
            uiHandler.post(() -> applyCommandResult(captured, applied));
        });
    }

    /** Apply the already-computed {@link ToolDispatcher.Applied} result to the
     *  UI. Called on the main thread from every {@link #onCommand} path. */
    private void applyCommandResult(java.util.List<ToolCall> calls,
                                     ToolDispatcher.Applied applied) {
        // Settings-style results go to the one-line status hint under the
        // transcript (kept short: summary only, no "heard:" echo) so the
        // transcript itself stays pure conversation. Only long-form results
        // the user explicitly asked for (session summary, commands catalog)
        // still get an in-transcript note card.
        if (applied.primaryIndex >= 0) {
            CommandResult primary = applied.results.get(applied.primaryIndex);
            ToolCall primaryCall = calls.get(applied.primaryIndex);
            boolean addsNoteCard = primary.effects.contains(CommandResult.Effect.SUMMARIZE_SESSION)
                    || primary.effects.contains(CommandResult.Effect.LIST_COMMANDS)
                    || primary.effects.contains(CommandResult.Effect.LIST_TERMS);
            if (addsNoteCard) {
                history.add(Turn.command(formatChip(primary, primaryCall)));
                trimHistoryIfNeeded();
                // Insert at the history index, NOT plain append: when an
                // active (in-flight) card is showing it must stay the last
                // child, and the note card belongs right before it.
                int noteIdx = history.size() - 1;
                turnsContainer.addView(
                        buildTurnView(history.get(noteIdx), session.displayMode()),
                        noteIdx, turnLayoutParams());
            } else {
                setStatusHint("» " + primary.summary
                        + (primary.sessionSnapshot != null ? "  ↩" : ""));
            }
            // Record the command in session history. Use the original
            // spoken phrase + tool name + a short args summary — enough
            // for the LLM to disambiguate "改成中文" against prior
            // state without re-asking the user. Only state-mutating
            // commands carry a snapshot; no-ops / read-only / action
            // tools (idempotent "already on", get_current_settings,
            // export, summarize, …) return null and would only pollute
            // the disambiguation context, so skip them.
            if (!primary.rejected && primary.sessionSnapshot != null) {
                String argsSummary = CommandHistoryEntry.summarizeArgs(primaryCall.argsJson);
                sessionContext.recordCommand(
                        primaryCall.triggerText == null ? "" : primaryCall.triggerText,
                        primaryCall.name,
                        argsSummary);
            }
            // Dispatch the side effects the tool requested. A tool sets at
            // most one Effect, so order within a single result doesn't
            // matter; the switch's default throws so a future Effect with no
            // case fails loudly in debug instead of silently no-op'ing.
            for (CommandResult.Effect e : primary.effects) {
                switch (e) {
                    case RERENDER:
                        // Light refresh: text didn't change, only
                        // display mode / font scale — update views
                        // in place instead of destroying + recreating.
                        refreshAllTurnViews();
                        break;
                    case ENGINE_RECONCILE:
                        reconcileEngineIfNeeded();
                        break;
                    case DEBUG_TOGGLE:
                        applyDebugVisibility(config.isDebugVisible());
                        break;
                    case MIC_REFRESH:
                        refreshMicStatus();
                        break;
                    case LOG_LEVEL:
                        debug.setLevel(primary.logLevelChange);
                        session.setDebugLogLevel(primary.logLevelChange);
                        debug.rerender();
                        break;
                    case EXPORT_CLIPBOARD:
                        exportTranscriptToClipboard();
                        break;
                    case RETRANSLATE_LAST:
                        retranslateLastTurn();
                        break;
                    case SUMMARIZE_SESSION:
                        summarizeSession();
                        break;
                    case LIST_COMMANDS:
                        replaceChipWithCommandsCatalog(history.size() - 1);
                        break;
                    case CLEAR_TRANSCRIPT:
                        doClearTranscript();
                        break;
                    case LIST_TERMS:
                        // The note card was already added above (addsNoteCard
                        // path) carrying the formatted glossary as the summary.
                        break;
                    default:
                        throw new IllegalStateException("unhandled effect: " + e);
                }
            }
            // Save the snapshot for UNDO before clobbering the field. The
            // status hint (already showing the summary, with a ↩ marker)
            // is the tap target.
            if (primary.sessionSnapshot != null) {
                lastUndoable = primary;
                lastUndoSnapshot = applied.preSnapshot;  // pre-batch state for UNDO
            }
            statusHint.setClickable(lastUndoable != null);
        } else {
            // All rejected — one short status line, no UNDO.
            setStatusHint("» 已拒绝：" + shortRejection(applied.results));
        }
        maybeChase();
    }

    /** clear_transcript handler: wipe history and every transcript view.
     *  The command's summary already sits in the status hint (set by
     *  onCommand), so the user still sees what happened. Persist the wipe
     *  immediately — if the process dies before onPause, the cleared state
     *  should still stick. */
    private void doClearTranscript() {
        history.clear();
        turnsContainer.removeAllViews();
        dropActiveCard();   // also resets the typewriter
        pinned = true;      // an empty transcript is always "at bottom"
        if (transcriptStore != null) transcriptStore.save(history);
        maybeChase();
    }

    /** Build the chip text shown in the transcript for a successful
     *  command. Format: "» <tool>: <summary>  (heard: \"<trigger>\")". */
    private static String formatChip(CommandResult r, ToolCall call) {
        StringBuilder sb = new StringBuilder("» ");
        sb.append(call.name).append(": ").append(r.summary);
        if (call.triggerText != null && !call.triggerText.isEmpty()) {
            String t = call.triggerText;
            if (t.length() > 32) t = t.substring(0, 32) + "…";
            sb.append("  (heard: \"").append(t).append("\")");
        }
        return sb.toString();
    }

    private static String shortRejection(List<CommandResult> results) {
        if (results == null || results.isEmpty()) return "no reason given";
        return results.get(0).rejectReason == null ? "no reason given"
                : results.get(0).rejectReason;
    }

    /** Invoked when the user taps the status hint while an undoable command
     *  is showing. Restores the snapshot and applies the side effects
     *  implied by the inverse of the tool. */
    private void undoLastCommand() {
        if (lastUndoable == null) return;
        CommandResult r = lastUndoable;
        // Prefer the pre-batch snapshot (restores every mutation in the batch)
        // over the single-tool snapshot on the result — for a batch like
        // [set_languages, set_languages] the per-tool snapshot only captured
        // the state right before the LAST call.
        SessionConfig.Snapshot snap = lastUndoSnapshot != null ? lastUndoSnapshot : r.sessionSnapshot;
        if (snap != null) {
            session.restore(snap);
            config.setSourceLang(session.sourceLang());
            config.setTargetLang(session.targetLang());
            config.setDisplayMode(session.displayMode().key());
            // temperature is persisted across sessions, so restore it too —
            // otherwise a restart would silently revert the undo.
            config.setTemperature(session.temperature());
            config.setFontScale(session.fontScale());
            config.setGlossary(session.glossary());
        }
        if (r.prevCascade != null) config.setCascadeMode(r.prevCascade);
        if (r.prevDebug != null) {
            config.setDebugVisible(r.prevDebug);
            applyDebugVisibility(r.prevDebug);
        }
        lastUndoable = null;
        lastUndoSnapshot = null;
        statusHint.setClickable(false);
        setStatusHint("» 已撤销");
        rebuildAllTurns();
        reconcileEngineIfNeeded();
        // Snapshot now captures micPaused — reflect a restored flip in the
        // status row. (The capture loop reads session.micPaused() directly,
        // so the mic itself resumes/pauses automatically; this just updates
        // the dot/label.)
        refreshMicStatus();
    }

    /** export_transcript handler: serialize current history to plain text
     *  and put on the system clipboard. The chip already shows the user
     *  the action; we don't pop an extra Toast. */
    private void exportTranscriptToClipboard() {
        StringBuilder sb = new StringBuilder();
        SessionConfig.DisplayMode mode = session.displayMode();
        for (Turn t : history) {
            if (t.kind == Turn.Kind.COMMAND) {
                sb.append(t.commandText).append('\n');
            } else {
                // Use each turn's OWN language (stored on Turn) so a language
                // switch mid-session doesn't mislabel all prior turns with the
                // current session language. Fall back to the session langs for
                // legacy persisted turns that carry none.
                String srcLang = (t.sourceLang != null && !t.sourceLang.isEmpty())
                        ? t.sourceLang : session.sourceLang();
                String tgtLang = (t.targetLang != null && !t.targetLang.isEmpty())
                        ? t.targetLang : session.targetLang();
                if (!t.source.isEmpty()) {
                    sb.append(appendLabel(srcLang, t.source)).append('\n');
                }
                if (!t.target.isEmpty() && mode != SessionConfig.DisplayMode.SOURCE_ONLY) {
                    sb.append(appendLabel(tgtLang, t.target)).append('\n');
                }
                sb.append('\n');
            }
        }
        String text = sb.toString().trim();
        ClipData clip = ClipData.newPlainText("Vokii transcript", text);
        ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(clip);
        setStatusHint("» 已复制 " + text.length() + " 字");
    }

    private static String appendLabel(String lang, String text) {
        if (lang == null) return text;
        return lang.toUpperCase(java.util.Locale.ROOT) + ": " + text;
    }

    /** re_translate_last handler: find the most recent TRANSLATION turn
     *  in history, re-feed its source to the LLM with the current mode,
     *  and replace the turn in place. Runs async on the mtExecutor. */
    private void retranslateLastTurn() {
        // Find last TRANSLATION turn.
        int idx = history.size() - 1;
        while (idx >= 0) {
            Turn t = history.get(idx);
            if (t.kind == Turn.Kind.TRANSLATION) break;
            idx--;
        }
        if (idx < 0) {
            setStatusHint("» 没有可重译的句子");
            return;
        }
        final Turn original = history.get(idx);
        final int turnIdx = idx;
        final String sourceText = original.source.isEmpty() ? original.target : original.source;
        // Build a new QwenMtClient with the current prompt (so new style/
        // temperature applies) and call translate. We pass sessionContext
        // so any history captured since the original turn is also visible
        // to the LLM (helps with style/temperature continuity).
        String prompt = MtPromptBuilder.buildSystemPrompt(sessionContext, toolRegistry);
        org.json.JSONArray tools = MtPromptBuilder.buildToolsJson(toolRegistry);
        QwenMtClient mt = MtRunner.client(config.getApiKey(), prompt, tools,
                session.temperature(), debug);
        MtRunner.executor().execute(() -> {
            mt.translate(sourceText, new QwenMtClient.Listener() {
                @Override public void onReady() { }
                @Override public void onDelta(String t) { }
                @Override public void onResult(String text) {
                    runOnUiThread(() -> {
                        // The Activity may have been destroyed/finishing while
                        // the LLM call was in flight (rotation is now handled
                        // in-place via configChanges, but back/leave still
                        // destroys) — bail before touching the view tree.
                        if (isFinishing() || isDestroyed()) return;
                        // The transcript may have changed under us while the
                        // MT call was in flight (clear_transcript, new turns,
                        // another retranslate). Re-validate the captured index
                        // before overwriting — otherwise we'd IndexOutOfBounds
                        // or clobber an unrelated turn / chip.
                        if (turnIdx < 0 || turnIdx >= history.size()
                                || history.get(turnIdx).kind != Turn.Kind.TRANSLATION) {
                            debug.log("MT", "retranslate aborted: turn " + turnIdx
                                    + " no longer a valid translation (history size "
                                    + history.size() + ")");
                            return;
                        }
                        TurnParser p = TurnParser.parse(text,
                                session.sourceLang(), session.targetLang());
                        Turn newTurn = Turn.translation(p.source, p.target,
                                session.sourceLang(), session.targetLang());
                        history.set(turnIdx, newTurn);
                        sessionContext.recordUtterance(newTurn);
                        updateTurnViewAt(turnIdx);
                        setStatusHint("» 已重新翻译");
                        debug.log("MT", "retranslated turn " + turnIdx);
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        setStatusHint("» 重译失败：" + message);
                    });
                }
                @Override public void onToolCalls(java.util.List<ToolCall> calls) { }
            });
        });
    }

    /** list_commands handler: build the full command catalog from the
     *  live ToolRegistry and replace the just-added chip with a single
     *  multi-line "» Available commands" chip listing each tool name +
     *  a short description. This is the discoverability surface for
     *  voice-only controls — the user can ask "你能做什么" or "help" at
     *  any time and get a one-shot reference card. */
    private void replaceChipWithCommandsCatalog(int chipIndex) {
        // Chinese-only catalog with a native example per command. The tool
        // schema descriptions stay English (they guide the LLM); this card is
        // the user-facing discoverability surface, so it reads in Chinese
        // with Chinese trigger examples.
        java.util.Map<String, String> zh = new java.util.LinkedHashMap<>();
        zh.put("set_translation_languages", "切换翻译语言 —— 例：「下面改成中日翻译」");
        zh.put("set_display_mode", "显示模式（双语/仅原文/仅译文）—— 例：「只显示日文」");
        zh.put("toggle_cascade", "普通 / 级联管道 —— 例：「切换到普通模式」");
        zh.put("toggle_debug", "调试面板 —— 例：「打开调试」");
        zh.put("set_translation_mode", "翻译风格 / 温度 —— 例：「翻译得更文雅」或「温度调到0.7」");
        zh.put("get_current_settings", "查看当前设置 —— 例：「现在是什么设置」");
        zh.put("clear_transcript", "清空记录 —— 例：「清空翻译」");
        zh.put("toggle_mic", "暂停 / 继续麦克风 —— 例：「暂停」或「继续」");
        zh.put("set_log_level", "日志详细度 —— 例：「日志设成详细」");
        zh.put("export_transcript", "复制到剪贴板 —— 例：「复制到剪贴板」");
        zh.put("summarize_session", "总结对话 —— 例：「总结一下」");
        zh.put("re_translate_last", "重新翻译上一句 —— 例：「重新翻译上一句」");
        zh.put("list_commands", "显示本帮助 —— 例：「你能做什么」");
        zh.put("remember_term", "记住术语 / 简称 / 改正翻译 —— 例：「记住张三叫 Zhang San」「人工智能简称AI」「改一下张三是 Lao Zhang」");
        zh.put("list_terms", "查看记住的术语 —— 例：「有哪些术语」「记住了什么」");
        zh.put("set_font_size", "字体变大 / 变小 —— 例：「字体变大」或「字小一点」");
        StringBuilder sb = new StringBuilder("» 可用命令（直接说出来即可，中英文都行）：\n");
        for (String name : toolRegistry.names()) {
            String line = zh.get(name);
            if (line == null) {
                // Unknown / future tool: fall back to its schema description.
                String desc = "";
                try {
                    desc = Json.optString(toolRegistry.get(name).functionSchema()
                            .getJSONObject("function"), "description", "");
                    int nl = desc.indexOf('\n');
                    if (nl > 0) desc = desc.substring(0, nl).trim();
                    if (desc.length() > 60) desc = desc.substring(0, 57) + "...";
                } catch (Throwable ignored) {}
                line = name + (desc.isEmpty() ? "" : " — " + desc);
            }
            sb.append("  • ").append(line).append('\n');
        }
        if (chipIndex < 0 || chipIndex >= history.size()) return;  // cleared mid-flight
        history.set(chipIndex, Turn.command(sb.toString()));
        updateTurnViewAt(chipIndex);
    }

    /** summarize_session handler: build a single string from all turns,
     *  call the LLM with a "summarize" prompt, and append the result
     *  as a new chip (replacing the in-progress "Summarizing…" chip). */
    private void summarizeSession() {
        StringBuilder transcript = new StringBuilder();
        for (Turn t : history) {
            if (t.kind == Turn.Kind.COMMAND) continue;
            if (!t.source.isEmpty()) transcript.append(t.source).append('\n');
            if (!t.target.isEmpty()) transcript.append(t.target).append('\n');
        }
        if (transcript.length() == 0) {
            updateLastNoteCard("  （记录为空）");
            return;
        }
        String summaryPrompt =
                "You are a summarization assistant. The user has been having a " +
                "bilingual (Chinese-English) conversation. Below is the full " +
                "transcript so far. Produce a concise summary (3-5 sentences, or " +
                "5-8 bullet points) that captures the main topics, decisions, " +
                "and any action items. Output BOTH lines:\n" +
                "ZH: <summary in Chinese>\n" +
                "EN: <summary in English>\n" +
                "Keep the summary in the same tone as the conversation. No extra " +
                "commentary.\n\nTranscript:\n" + transcript.toString();
        // Replace the "Summarizing…" chip with the result.
        QwenMtClient mt = MtRunner.client(config.getApiKey(), summaryPrompt, null, 0.3f, debug);
        MtRunner.executor().execute(() -> {
            mt.translate("", new QwenMtClient.Listener() {
                @Override public void onReady() { }
                @Override public void onDelta(String t) { }
                @Override public void onResult(String text) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        TurnParser p = TurnParser.parse(text, "zh", "en");
                        // Replace the in-progress note card with the summary.
                        String summary = p.source.isEmpty() ? p.target : p.source;
                        if (summary.isEmpty()) summary = text;
                        int i = lastCommandIndex();
                        if (i < 0) return;  // transcript cleared mid-flight
                        history.set(i, Turn.command("» 总结：" + summary));
                        updateTurnViewAt(i);
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        updateLastNoteCard("  (failed: " + message + ")");
                    });
                }
                @Override public void onToolCalls(java.util.List<ToolCall> calls) { }
            });
        });
    }

    // ----- transcript rendering (per-turn cards) -----
    //
    // Each committed turn owns a card (a vertical LinearLayout holding a
    // source line and a target line). Cards are NEVER modified once
    // committed — only the bottom "active" card (the sentence currently
    // being transcribed/translated) updates. This is what keeps on-screen
    // lines stable: text growth and wrapping only ever happen inside the
    // active card, and the chase scroller glides the window down smoothly
    // instead of jumping.
    //
    // Invariant: turnsContainer children == history views, in order, plus
    // an optional trailing active card. Every mutation goes through
    // buildTurnView / attachActiveCard / updateTurnViewAt / rebuildAllTurns
    // so the invariant can't drift.

    private LinearLayout.LayoutParams turnLayoutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = turnGapPx;
        return lp;
    }

    private TextView makeTranscriptLine() {
        TextView tv = new TextView(this);
        tv.setTextSize(16f * session.fontScale());
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        tv.setLineSpacing(0f, 1.3f);
        return tv;
    }

    /** Build the view for one committed turn. TRANSLATION → source line over
     *  target line (each hidden when empty or filtered out by the display
     *  mode); COMMAND → a single dimmer note line (legacy chips, session
     *  summary, commands catalog). */
    private View buildTurnView(Turn t, SessionConfig.DisplayMode mode) {
        if (t.kind == Turn.Kind.COMMAND) {
            TextView note = new TextView(this);
            note.setText(t.commandText);
            note.setTextSize(14f * session.fontScale());
            note.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            note.setLineSpacing(0f, 1.2f);
            return note;
        }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        TextView src = makeTranscriptLine();
        TextView tgt = makeTranscriptLine();
        src.setText(t.source);
        tgt.setText(t.target);
        src.setVisibility(mode != SessionConfig.DisplayMode.TARGET_ONLY && !t.source.isEmpty()
                ? View.VISIBLE : View.GONE);
        tgt.setVisibility(mode != SessionConfig.DisplayMode.SOURCE_ONLY && !t.target.isEmpty()
                ? View.VISIBLE : View.GONE);
        card.addView(src);
        card.addView(tgt);
        return card;
    }

    /** Rebuild every card from {@link #history} — used for user-initiated
     *  wholesale changes (display-mode flip, undo, boot restore). Streaming
     *  never goes through here. The in-flight card is recreated from its
     *  saved state so an ongoing sentence survives the rebuild. */
    private void rebuildAllTurns() {
        boolean hadActive = activeCard != null;
        activeCard = null;
        activeSourceView = null;
        activeTargetView = null;
        typeView = null;
        if (!hadActive) {
            typeFull = "";
            typeShown = 0;
        }
        turnsContainer.removeAllViews();
        SessionConfig.DisplayMode mode = session.displayMode();
        for (int i = 0; i < history.size(); i++) {
            turnsContainer.addView(buildTurnView(history.get(i), mode), turnLayoutParams());
        }
        if (hadActive) {
            attachActiveCard();       // verbatim/translate state is preserved
            refreshActiveCardText();  // in the fields, so typing resumes
            ensureTypeTicking();
        }
        maybeChase();
    }

    /** Lightweight in-place refresh of every committed turn card's visibility
     *  and font size — NO view destruction or creation. Used for voice-command
     *  RERENDER (set_languages, set_display, set_font_size) where only the
     *  display mode or font-scale changed, not the text content.
     *
     *  <p>Falls back to {@link #rebuildAllTurns()} when the number of committed
     *  cards doesn't match {@code history.size()} (shouldn't happen, but a
     *  mismatch means the invariant is broken and we need a full rebuild). */
    private void refreshAllTurnViews() {
        SessionConfig.DisplayMode mode = session.displayMode();
        float fs = session.fontScale();
        int committedCount = activeCard != null
                ? turnsContainer.getChildCount() - 1
                : turnsContainer.getChildCount();
        if (committedCount != history.size()) {
            rebuildAllTurns();   // invariant broken — safe fallback
            return;
        }
        for (int i = 0; i < history.size(); i++) {
            Turn t = history.get(i);
            View child = turnsContainer.getChildAt(i);
            if (t.kind == Turn.Kind.COMMAND) {
                if (child instanceof TextView) {
                    TextView note = (TextView) child;
                    note.setTextSize(14f * fs);
                }
            } else {
                // TRANSLATION turn: card is a vertical LinearLayout with
                // child 0 = source line, child 1 = target line.
                if (child instanceof LinearLayout) {
                    LinearLayout card = (LinearLayout) child;
                    if (card.getChildCount() >= 2) {
                        TextView src = (TextView) card.getChildAt(0);
                        TextView tgt = (TextView) card.getChildAt(1);
                        src.setVisibility(mode != SessionConfig.DisplayMode.TARGET_ONLY
                                && !t.source.isEmpty() ? View.VISIBLE : View.GONE);
                        tgt.setVisibility(mode != SessionConfig.DisplayMode.SOURCE_ONLY
                                && !t.target.isEmpty() ? View.VISIBLE : View.GONE);
                        src.setTextSize(16f * fs);
                        tgt.setTextSize(16f * fs);
                    }
                }
            }
        }
        if (activeCard != null) {
            refreshActiveCardText();   // verbatim/translate state preserved
            ensureTypeTicking();
        }
        maybeChase();
    }

    /** Replace the view of history[idx] in place (retranslate / summary /
     *  catalog results). The index stays valid because appends never shift
     *  earlier children. */
    private void updateTurnViewAt(int idx) {
        if (idx < 0 || idx >= history.size()) return;
        turnsContainer.removeViewAt(idx);
        turnsContainer.addView(buildTurnView(history.get(idx), session.displayMode()),
                idx, turnLayoutParams());
        maybeChase();
    }

    /** Keep the in-memory history and its mirrored view list bounded at
     *  {@link TranscriptStore#MAX_TURNS} so a long session can't grow the
     *  ScrollView's child list (whose layout is O(children)) without limit.
     *  TranscriptStore already trims on save, but without this the live list
     *  grew unbounded and the overflow was silently dropped on the next
     *  restart. Both lists are trimmed from the front (oldest first) so
     *  indices stay aligned with the active card (always the last child). */
    private void trimHistoryIfNeeded() {
        while (history.size() > TranscriptStore.MAX_TURNS) {
            history.remove(0);
            if (turnsContainer.getChildCount() > 0) turnsContainer.removeViewAt(0);
        }
    }

    /** Detach the active card (it just committed) WITHOUT touching the
     *  typewriter — the ticker keeps draining the now-committed card's
     *  translate line via the still-pointing typeView. */
    private void resetActiveCardState() {
        activeCard = null;
        activeSourceView = null;
        activeTargetView = null;
        activeVerbatim = "";
        verbatimFinalized = false;
        verbatimColDecided = false;
        verbatimIsSourceCol = true;
        activeHasMt = false;
        activeMaxHeight = 0;
        firstHoldNanos = 0;  // clear the reword-hold fuse so it can't leak into the next sentence
    }

    /** Remove the in-flight card (if any) without committing it, and reset
     *  the typewriter. Used when listening stops/starts and on clear —
     *  mirrors the old currentTurn=null / liveAsrPartial=null reset. */
    private void dropActiveCard() {
        if (activeCard != null) {
            turnsContainer.removeView(activeCard);
        } else {
            // No in-flight card means typeView (if set) belongs to a
            // COMMITTED card still draining — complete it first so it
            // isn't left frozen with a half-typed target line.
            flushTypewriter();
        }
        resetActiveCardState();
        typeView = null;
        typeFull = "";
        typeShown = 0;
    }

    /** Ensure the in-flight card exists as the last child. Creating it also
     *  points the typewriter at the translate column once MT has started. */
    private void attachActiveCard() {
        if (activeCard == null) {
            flushTypewriter();  // finish the previous card's drain, if any
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            activeSourceView = makeTranscriptLine();
            activeTargetView = makeTranscriptLine();
            card.addView(activeSourceView);
            card.addView(activeTargetView);
            turnsContainer.addView(card, turnLayoutParams());
            activeCard = card;
            typeView = activeHasMt
                    ? (verbatimIsSourceCol ? activeTargetView : activeSourceView)
                    : null;
            typeIsSourceCol = !verbatimIsSourceCol;
            if (!activeHasMt) {
                typeFull = "";
                typeShown = 0;
            }
        }
    }

    /** Push the saved active-card state into its views. The verbatim column
     *  shows "› verbatim" while in flight and bypasses the display-mode
     *  filter during the caption phase — it's the only live signal the user
     *  has while speaking. The translate column renders via the typewriter. */
    private void refreshActiveCardText() {
        if (activeCard == null) return;
        TextView vView = verbatimIsSourceCol ? activeSourceView : activeTargetView;
        TextView tView = verbatimIsSourceCol ? activeTargetView : activeSourceView;
        boolean show = activeHasMt
                ? columnVisible(verbatimIsSourceCol, activeVerbatim)
                : !activeVerbatim.isEmpty();
        vView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) vView.setText("› " + activeVerbatim);
        // Caption phase (no MT yet): hide the empty translate column so the
        // verbatim caption sits on the FIRST line. A TextView defaults to
        // VISIBLE, so the not-yet-typed translate column otherwise occupies a
        // blank line and — when the verbatim lands in the target column (auto
        // mode, English-led utterance) — pushes the caption down to row 2
        // until MT fills the column and the line "jumps" up at commit. Once MT
        // streams, applyTypedText owns this column's visibility.
        if (!activeHasMt) tView.setVisibility(View.GONE);
        applyTypedText();
    }

    /** Per-column display-mode filter: SOURCE_ONLY hides the target column,
     *  TARGET_ONLY hides the source column — language-wise, so an English
     *  verbatim sitting in the (bottom) target column in auto mode is
     *  treated as the target side. */
    private boolean columnVisible(boolean isSourceCol, String text) {
        if (text == null || text.isEmpty()) return false;
        return isSourceCol
                ? session.displayMode() != SessionConfig.DisplayMode.TARGET_ONLY
                : session.displayMode() != SessionConfig.DisplayMode.SOURCE_ONLY;
    }

    // ----- translate-line typewriter -----
    //
    // The MT engine delivers the full accumulated translate-side text on
    // every delta; dumping it straight into the view reads as a paragraph
    // paste. Instead we reveal it character-by-character: every
    // TYPE_TICK_MS the ticker advances by max(1, backlog / 8) chars — a
    // calm ~25 chars/s baseline that automatically speeds up to drain
    // bursts, so it always looks like typing yet never lags far behind
    // the stream.
    //
    // Stability rule (the anti-jump invariant): THE ADOPTED TEXT NEVER
    // SHRINKS. Every new MT generation (speculative drafts fire ~1/s while
    // speaking, then the final) restarts its accumulated text from empty —
    // adopting those early deltas wiped the whole line ~once per second
    // (measured on emulator: 15 full wipes in 30 s). feedTranslation holds
    // a generation's deltas until its text has caught up to what we already
    // committed to show; only then does it take over. Genuine revisions
    // after adoption snap back to the common prefix, then resume typing.

    /** Gate in front of the typewriter for streaming MT text. Commit uses
     *  feedTypewriter directly (the final text is authoritative). Adoption
     *  rules keep visible text stable WITHOUT letting the line freeze:
     *  <ul>
     *    <li>NEVER SHRINK — a fresh MT generation restarts from empty;
     *        its deltas are held until the text catches up to the adopted
     *        length (used to wipe the line ~1/s while speaking).</li>
     *    <li>SMALL TAIL TRIMS ONLY — qwen-turbo rewords the head of its
     *        own translation between drafts ("但不会…" → "不过…"). A
     *        generation trimming more than the back quarter of what's
     *        SHOWN is held — but only for up to ~1 s, then the newest
     *        generation is force-adopted anyway. Without the timeout the
     *        line froze on the first draft whenever every draft reworded
     *        the head (observed on device with Chinese speech: English
     *        line static all sentence, then the whole translation
     *        appeared at commit).</li>
     *  </ul> */
    private void feedTranslation(String text) {
        if (text.length() < typeFull.length()) {
            // A younger generation still catching up — keep showing the
            // previous text instead of wiping the line.
            return;
        }
        String shown = typeFull.substring(0, typeShown);
        if (!text.startsWith(shown)) {
            int cp = commonPrefix(text, shown);
            if (cp < (typeShown * 3) / 4) {
                // Front-half reword: hold, with a 1 s fuse so the line
                // keeps tracking the sentence (≤1 visible snap per second).
                long now = System.nanoTime();
                if (firstHoldNanos == 0) firstHoldNanos = now;
                if (now - firstHoldNanos < 1_000_000_000L) {
                    debug.log("RNDER", "trans HELD reword cp=" + cp + "/" + typeShown);
                    return;
                }
                debug.log("RNDER", "trans FORCE-ADOPT after 1s hold cp=" + cp + "/" + typeShown);
            }
        }
        firstHoldNanos = 0;
        feedTypewriter(text);
    }

    /** nanoTime when the current reword-hold began; 0 = not holding. */
    private long firstHoldNanos;

    private void feedTypewriter(String latestFull) {
        // Never shrink the shown length mid-stream. Snapping typeShown back to
        // the common prefix on an MT head-reword shortened the translate line,
        // changed its wrap count, shrank the card, and ScrollView auto-clamped
        // the viewport up — then the chaser glided back down as the text
        // regrew, the up-then-down vertical jitter. Keep the shown LENGTH: the
        // characters may change (the reword is real) but line count, card
        // height and scrollY all stay put, leaving nothing to clamp. Shown
        // only shrinks when the new text is genuinely shorter (commit's final,
        // or a shorter generation) — a one-shot settle, not oscillation.
        typeFull = latestFull;
        typeShown = Math.min(typeShown, typeFull.length());
        ensureTypeTicking();
    }

    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    private void ensureTypeTicking() {
        if (typeTicking || typeView == null || typeShown >= typeFull.length()) return;
        typeTicking = true;
        uiHandler.postDelayed(typeTicker, TYPE_TICK_MS);
    }

    private final Runnable typeTicker = new Runnable() {
        @Override public void run() {
            typeTicking = false;
            if (typeView == null) return;
            int backlog = typeFull.length() - typeShown;
            if (backlog <= 0) return;
            typeShown = Math.min(typeFull.length(),
                    typeShown + Math.max(1, backlog / TYPE_CATCHUP_DIVISOR));
            applyTypedText();
            maybeChase();
            ensureTypeTicking();
        }
    };

    /** Reveal the remaining text instantly — used for the previous card when
     *  a new sentence starts mid-drain. */
    private void flushTypewriter() {
        if (typeView == null) return;
        typeShown = typeFull.length();
        applyTypedText();
    }

    private void applyTypedText() {
        // Track BEFORE the typeView==null early-return so the caption phase
        // (typeView null, verbatim-only) also pins the card's minHeight —
        // otherwise the firstMT transition clamps the viewport up by one line.
        trackCardMinHeight();
        if (typeView == null) return;
        typeShown = Math.min(typeShown, typeFull.length());
        String typed = typeFull.substring(0, typeShown);
        boolean show = columnVisible(typeIsSourceCol, typed);
        typeView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) typeView.setText(typed);
    }

    /** Grow the active card's minHeight to its current measured height and
     *  never let it shrink while in flight — so a verbatim REWRITE or any
     *  text shrink can't reduce the card height, make ScrollView clamp the
     *  viewport up, and jitter it back down on the next grow. (translate
     *  rewords are already length-stable via feedTypewriter; this covers the
     *  verbatim column's ASR revisions and any other height change.) */
    private void trackCardMinHeight() {
        if (activeCard == null) return;
        int w = turnsContainer.getWidth();
        if (w <= 0) return;  // not laid out yet
        int wspec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY);
        activeCard.measure(wspec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = activeCard.getMeasuredHeight();
        if (h > activeMaxHeight) {
            activeMaxHeight = h;
            activeCard.setMinimumHeight(h);
        }
    }

    // ----- chase scroller -----
    //
    // fullScroll() used to jump instantly — that's what made the window
    // feel like it was leaping. The chaser instead tracks the bottom
    // EXACTLY each frame, capped at MAX_SCROLL_STEP_DP (≈1.8 in/s) so a
    // big append glides for a few frames instead of snapping. Exact
    // tracking matters during the typewriter drain: the translate line
    // grows AT the viewport's bottom edge, so any steady-state lag leaves
    // it mid-glyph below the fold (the earlier remaining/4 chase lagged
    // ~half a line permanently — the user saw the English line "below the
    // box" for the whole sentence). Content shrinking (clear, dropped
    // caption) snaps immediately — animating upward motion onto removed
    // text looks broken.

    /** Set by the chaser around its programmatic snap-up so the scroll
     *  listener doesn't mistake it for a user drag-up and un-pin. */
    private boolean programmaticScroll;

    private void maybeChase() {
        if (!pinned || chasing) return;
        chasing = true;
        chaseSettledFrames = 0;
        scrollTranscript.postOnAnimation(scrollChaser);
    }

    private final Runnable scrollChaser = new Runnable() {
        @Override public void run() {
            if (!pinned) {
                chasing = false;
                return;
            }
            int target = Math.max(0, turnsContainer.getBottom() - scrollTranscript.getHeight());
            int cur = scrollTranscript.getScrollY();
            int remaining = target - cur;
            if (remaining > 2) {
                // Track exactly, capped so large appends glide a few frames.
                int step = Math.min(remaining, (int) maxScrollStepPx);
                scrollTranscript.scrollTo(0, cur + step);
                chaseSettledFrames = 0;
                scrollTranscript.postOnAnimation(this);
            } else if (remaining < -2) {
                programmaticScroll = true;
                scrollTranscript.scrollTo(0, target);
                programmaticScroll = false;
                chaseSettledFrames = 0;
                scrollTranscript.postOnAnimation(this);
            } else if (++chaseSettledFrames < 2) {
                // Confirm the target is stable across a layout pass before
                // sleeping — the first frame after a content change can read
                // a stale, pre-layout container bottom.
                scrollTranscript.postOnAnimation(this);
            } else {
                chasing = false;
            }
        }
    };

    private void setStatusHint(CharSequence text) {
        statusHint.setText(text);
    }

    /** Append a suffix to the most recent note (COMMAND) card's text — used
     *  by summarize to fold the result/failure back into its own card.
     *  Finds the LAST command turn rather than assuming it is still the
     *  last history entry (new turns may have committed meanwhile). */
    private void updateLastNoteCard(String suffix) {
        int i = lastCommandIndex();
        if (i < 0) return;
        history.set(i, Turn.command(history.get(i).commandText + suffix));
        updateTurnViewAt(i);
    }

    private int lastCommandIndex() {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).kind == Turn.Kind.COMMAND) return i;
        }
        return -1;
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

    private enum Status { IDLE, PREPARING, LISTENING, PAUSED }

    private void setStatus(Status s, String label) {
        int color;
        switch (s) {
            case LISTENING:   color = COLOR_LISTENING; break;
            case PREPARING:   color = COLOR_PREPARING; break;
            case PAUSED:      color = COLOR_PAUSED;    break;  // amber
            default:          color = COLOR_IDLE;
        }
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        statusDot.setBackground(d);
        statusLabel.setText(label);
    }

    /** Reflect current session.micPaused() in the status row. Called
     *  after toggle_mic fires (in onCommand) and on every render. */
    private void refreshMicStatus() {
        if (controller == null || !controller.isActive()) return;
        if (session.micPaused()) {
            setStatus(Status.PAUSED, getString(R.string.hint_paused));
        } else {
            setStatus(Status.LISTENING, getString(R.string.hint_listening));
        }
    }
}
