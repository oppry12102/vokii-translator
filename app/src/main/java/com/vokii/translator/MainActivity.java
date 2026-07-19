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
    /** Latest full source text for the active card (verbatim, no "› "). */
    private String activeSource = "";
    /** False while the card shows only the live ASR caption; true once MT
     *  (final or speculative) has streamed for this sentence. */
    private boolean activeHasMt;

    // ----- target-line typewriter (see feedTypewriter) -----
    private TextView typeView;
    private String typeFull = "";
    private int typeShown;
    private boolean typeTicking;
    private static final int TYPE_TICK_MS = 40;
    private static final int TYPE_CATCHUP_DIVISOR = 8;

    // ----- chase scroller (see maybeChase) -----
    private boolean pinned = true;
    private boolean chasing;
    private int chaseSettledFrames;
    private int lastScrollY;
    private float maxScrollStepPx;
    private static final int SCROLL_CHASE_DIVISOR = 4;
    private static final float MAX_SCROLL_STEP_DP = 4.5f;

    /** Vertical gap between sentence cards — just enough to tell sentences
     *  apart without wasting space. */
    private static final int TURN_GAP_DP = 12;

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
        // Follow-bottom bookkeeping. The chase scroller only ever scrolls
        // DOWN, so any upward scrollY change means the user dragged away —
        // stop following until they return to the bottom. Content shorter
        // than the viewport can't be scrolled at all → always "at bottom".
        scrollTranscript.getViewTreeObserver().addOnScrollChangedListener(() -> {
            int y = scrollTranscript.getScrollY();
            boolean contentFits = turnsContainer.getBottom() <= scrollTranscript.getHeight();
            if (y < lastScrollY - 2 && !programmaticScroll && !contentFits) {
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
        boolean changed = !newSrc.equals(session.sourceLang())
                || !newTgt.equals(session.targetLang())
                || newMode != session.displayMode()
                || newTemp != session.temperature()
                || newCascade != session.cascadeEnabled();
        session.setLanguages(newSrc, newTgt);
        session.setDisplayMode(newMode);
        session.setTemperature(newTemp);
        session.setCascadeEnabled(newCascade);
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
        asr = fresh;
        controller = new TranslationController(asr, session, this);
        currentEngineName = asr.name();
        debug.log("engine", "now running " + currentEngineName);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Persist the transcript so it survives process death / restart.
        if (transcriptStore != null) transcriptStore.save(history);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Drop any pending typewriter ticks so they don't fire into a
        // destroyed view.
        uiHandler.removeCallbacksAndMessages(null);
        if (controller != null) controller.stop();
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
        // Mic stopped — drop a lingering caption-only card so it doesn't sit
        // on screen after the user stops talking. A card that already has MT
        // content stays (mirrors the old currentTurn semantics: it remains
        // visible until the next start drops it).
        if (activeCard != null && !activeHasMt) {
            dropActiveCard();
            maybeChase();
        }
    }

    /** Live verbatim ASR partial (cascade only). Shown as a "live caption"
     *  line while the user is speaking so first text lands at ASR TTFB
     *  (~0.4 s) instead of after sentence-final + MT TTFB. Only staged when
     *  no MT card is streaming for the current sentence; once MT streams
     *  ({@link #onStreaming}) the translation takes over the same card. */
    @Override public void onPartialTranscript(String text) {
        if (text == null || text.isEmpty()) return;
        if (activeCard != null && activeHasMt) {
            // An MT card is already streaming for an earlier sentence — don't
            // show a second caption alongside it; the next sentence's caption
            // will stage once that card commits.
            return;
        }
        if (activeCard != null && text.equals(activeSource)) return;  // no change
        activeSource = text;
        attachActiveCard();
        maybeChase();
    }

    @Override public void onStreaming(String source, String target, String srcLang, String tgtLang) {
        // MT is streaming for this sentence — the verbatim caption (if any)
        // becomes the bilingual card in place, at the same bottom position.
        // The source line updates directly (that IS the transcription
        // rhythm); the target line feeds the typewriter.
        activeHasMt = true;
        activeSource = source == null ? "" : source;
        attachActiveCard();
        feedTypewriter(target == null ? "" : target);
        maybeChase();
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
            if (activeCard != null) {
                // Finalize in place: the source locks to the final text; the
                // target keeps its already-typed prefix and drains the rest.
                activeHasMt = true;
                activeSource = src;
                typeFull = tgt;
                typeShown = Math.min(typeShown, typeFull.length());
                refreshActiveCardText();
                ensureTypeTicking();  // no-op if already drained
                // typeView keeps pointing at this card's target line so the
                // ticker finishes it even though the card is now history.
                activeCard = null;
                activeSourceView = null;
                activeTargetView = null;
                activeSource = "";
                activeHasMt = false;
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
        ToolDispatcher.Applied applied = toolDispatcher.apply(calls, session, config);
        for (int i = 0; i < applied.results.size(); i++) {
            CommandResult r = applied.results.get(i);
            if (r.rejected) {
                debug.log("CMD", "  reject: " + r.rejectReason);
            } else {
                debug.log("CMD", "  " + calls.get(i).name + " -> " + r.summary);
            }
        }
        // Settings-style results go to the one-line status hint under the
        // transcript (kept short: summary only, no "heard:" echo) so the
        // transcript itself stays pure conversation. Only long-form results
        // the user explicitly asked for (session summary, commands catalog)
        // still get an in-transcript note card.
        if (applied.primaryIndex >= 0) {
            CommandResult primary = applied.results.get(applied.primaryIndex);
            ToolCall primaryCall = calls.get(applied.primaryIndex);
            boolean addsNoteCard = primary.effects.contains(CommandResult.Effect.SUMMARIZE_SESSION)
                    || primary.effects.contains(CommandResult.Effect.LIST_COMMANDS);
            if (addsNoteCard) {
                history.add(Turn.command(formatChip(primary, primaryCall)));
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
                        rebuildAllTurns();
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
                    default:
                        throw new IllegalStateException("unhandled effect: " + e);
                }
            }
            // Save the snapshot for UNDO before clobbering the field. The
            // status hint (already showing the summary, with a ↩ marker)
            // is the tap target.
            if (primary.sessionSnapshot != null) {
                lastUndoable = primary;
            }
            statusHint.setClickable(lastUndoable != null);
        } else {
            // All rejected — one short status line, no UNDO.
            setStatusHint("» rejected: " + shortRejection(applied.results));
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
        if (r.sessionSnapshot != null) {
            session.restore(r.sessionSnapshot);
            config.setSourceLang(session.sourceLang());
            config.setTargetLang(session.targetLang());
            config.setDisplayMode(session.displayMode().key());
        }
        if (r.prevCascade != null) config.setCascadeMode(r.prevCascade);
        if (r.prevDebug != null) {
            config.setDebugVisible(r.prevDebug);
            applyDebugVisibility(r.prevDebug);
        }
        lastUndoable = null;
        statusHint.setClickable(false);
        setStatusHint("» Undone");
        rebuildAllTurns();
        reconcileEngineIfNeeded();
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
                if (!t.source.isEmpty()) {
                    sb.append(appendLabel(session.sourceLang(), t.source)).append('\n');
                }
                if (!t.target.isEmpty() && mode != SessionConfig.DisplayMode.SOURCE_ONLY) {
                    sb.append(appendLabel(session.targetLang(), t.target)).append('\n');
                }
                sb.append('\n');
            }
        }
        String text = sb.toString().trim();
        ClipData clip = ClipData.newPlainText("Vokii transcript", text);
        ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(clip);
        setStatusHint("» Copied " + text.length() + " chars");
    }

    private static String appendLabel(String lang, String text) {
        if (lang == null) return text;
        return lang.toUpperCase() + ": " + text;
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
            setStatusHint("» No turn to re-translate");
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
                        setStatusHint("» Re-translated");
                        debug.log("MT", "retranslated turn " + turnIdx);
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> setStatusHint("» Re-translate failed: " + message));
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
        StringBuilder sb = new StringBuilder("» Available commands (")
                .append(toolRegistry.names().size()).append("):\n");
        for (String name : toolRegistry.names()) {
            // Pull the description out of the tool's function schema.
            // Avoids duplicating the catalog in a separate list.
            String desc = "";
            try {
                org.json.JSONObject schema = toolRegistry.get(name).functionSchema();
                desc = Json.optString(schema.getJSONObject("function"), "description", "");
                // Collapse multi-line descriptions to a single short line.
                int nl = desc.indexOf('\n');
                if (nl > 0) desc = desc.substring(0, nl).trim();
                if (desc.length() > 100) desc = desc.substring(0, 97) + "...";
            } catch (Throwable ignored) {}
            sb.append("  • ").append(name);
            if (!desc.isEmpty()) sb.append(" — ").append(desc);
            sb.append('\n');
        }
        sb.append("\nSay any of the above in Chinese or English. " +
                "Example: \"下面改成中日翻译\" or \"open debug\".");
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
            updateLastNoteCard("  (transcript is empty)");
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
                        TurnParser p = TurnParser.parse(text, "zh", "en");
                        // Replace the in-progress note card with the summary.
                        String summary = p.source.isEmpty() ? p.target : p.source;
                        if (summary.isEmpty()) summary = text;
                        int i = lastCommandIndex();
                        if (i < 0) return;  // transcript cleared mid-flight
                        history.set(i, Turn.command("» Summary: " + summary));
                        updateTurnViewAt(i);
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> updateLastNoteCard("  (failed: " + message + ")"));
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
        lp.bottomMargin = (int) (TURN_GAP_DP * getResources().getDisplayMetrics().density + 0.5f);
        return lp;
    }

    private TextView makeTranscriptLine() {
        TextView tv = new TextView(this);
        tv.setTextSize(16);
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
            note.setTextSize(14);
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
            attachActiveCard(false);  // keep typewriter state — typing resumes
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

    /** Remove the in-flight card (if any) without committing it, and reset
     *  the typewriter. Used when listening stops/starts and on clear —
     *  mirrors the old currentTurn=null / liveAsrPartial=null reset. */
    private void dropActiveCard() {
        if (activeCard != null) {
            turnsContainer.removeView(activeCard);
            activeCard = null;
            activeSourceView = null;
            activeTargetView = null;
        } else {
            // No in-flight card means typeView (if set) belongs to a
            // COMMITTED card still draining — complete it first so it
            // isn't left frozen with a half-typed target line.
            flushTypewriter();
        }
        activeSource = "";
        activeHasMt = false;
        typeView = null;
        typeFull = "";
        typeShown = 0;
    }

    /** Ensure the in-flight card exists as the last child, then refresh its
     *  text. Creating it also hands the typewriter a fresh target line. */
    private void attachActiveCard() {
        attachActiveCard(true);
    }

    private void attachActiveCard(boolean resetTypewriter) {
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
            typeView = activeTargetView;
            if (resetTypewriter) {
                typeFull = "";
                typeShown = 0;
            }
        }
        refreshActiveCardText();
    }

    /** Push the saved active-card state into its views. The caption phase
     *  (pre-MT) shows "› verbatim" and bypasses the display-mode filter —
     *  it's the only live signal the user has while speaking. */
    private void refreshActiveCardText() {
        if (activeCard == null) return;
        if (!activeHasMt) {
            activeSourceView.setVisibility(View.VISIBLE);
            activeSourceView.setText("› " + activeSource);
        } else if (session.displayMode() != SessionConfig.DisplayMode.TARGET_ONLY
                && !activeSource.isEmpty()) {
            activeSourceView.setVisibility(View.VISIBLE);
            activeSourceView.setText(activeSource);
        } else {
            activeSourceView.setVisibility(View.GONE);
        }
        applyTypedText();
    }

    // ----- target-line typewriter -----
    //
    // The MT engine delivers the full accumulated target on every delta;
    // dumping it straight into the view reads as a paragraph paste. Instead
    // we reveal it character-by-character: every TYPE_TICK_MS the ticker
    // advances by max(1, backlog / 8) chars — a calm ~25 chars/s baseline
    // that automatically speeds up to drain bursts, so it always looks like
    // typing yet never lags far behind the stream. Revisions (speculative
    // MT rewriting a draft) snap back to the common prefix instantly, then
    // resume typing forward.

    private void feedTypewriter(String latestFull) {
        String shown = typeFull.substring(0, typeShown);
        if (!latestFull.startsWith(shown)) {
            typeShown = commonPrefix(latestFull, shown);
        }
        typeFull = latestFull;
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
        if (typeView == null) return;
        typeShown = Math.min(typeShown, typeFull.length());
        String typed = typeFull.substring(0, typeShown);
        boolean show = session.displayMode() != SessionConfig.DisplayMode.SOURCE_ONLY
                && !typed.isEmpty();
        typeView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) typeView.setText(typed);
    }

    // ----- chase scroller -----
    //
    // fullScroll() used to jump instantly — that's what made the window
    // feel like it was leaping. The chaser instead runs every frame while
    // pinned and moves a fraction of the remaining distance (capped at
    // MAX_SCROLL_STEP_DP per frame ≈ 270 dp/s): one new line eases in over
    // ~150 ms, bigger appends glide at a calm uniform speed. Content
    // shrinking (clear, dropped caption) snaps immediately — animating
    // upward motion onto removed text looks broken.

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
                int step = Math.min(Math.max(remaining / SCROLL_CHASE_DIVISOR, 2),
                        (int) maxScrollStepPx);
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
            case LISTENING:   color = Color.parseColor("#FFFF7E5F"); break;
            case PREPARING:   color = Color.parseColor("#FF7AB7FF"); break;
            case PAUSED:       color = Color.parseColor("#FFB8A53A"); break;  // amber
            default:          color = Color.parseColor("#FF6E7681");
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
