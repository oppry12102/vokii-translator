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
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
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
    private SessionConfig session;
    private SessionContext sessionContext;
    private ToolRegistry toolRegistry;
    private ToolDispatcher toolDispatcher;
    private DebugLogger debug;
    /** Snapshot of the last successfully-applied tool, used to power the
     *  UNDO Toast. Nulled out when a new tool overwrites it. */
    private CommandResult lastUndoable;

    private TextView textTranscript;
    private ScrollView scrollTranscript;
    private TextView statusLabel;
    private View statusDot;
    private ImageButton btnMic;
    private View debugPanel;
    private TextView debugText;

    /** Committed transcript as a list of typed turns (TRANSLATION or
     *  COMMAND), newest at the end. The display filter is applied at
     *  render time so changing the display mode re-renders the entire
     *  history without losing any information. */
    private final List<Turn> history = new ArrayList<>();
    /** Latest in-progress turn. Null when no turn is streaming. */
    private Turn currentTurn;
    /**
     * Generation counter for "scroll to bottom after the next layout". Each
     * renderTranscript that wants to auto-scroll bumps this; the
     * OnGlobalLayoutListener checks its captured generation against the
     * current one and discards itself if superseded by a newer render call.
     */
    private int scrollGen;

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

        textTranscript  = findViewById(R.id.text_transcript);
        scrollTranscript = findViewById(R.id.scroll_transcript);
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

        // Tap the most recent command chip to UNDO it. TextView is
        // clickable; the click handler consults the bottom of the
        // transcript text and the lastUndoable field.
        textTranscript.setClickable(true);
        textTranscript.setOnClickListener(v -> {
            // Only treat as UNDO if the last history entry is a COMMAND.
            if (lastUndoable != null && !history.isEmpty()
                    && history.get(history.size() - 1).kind == Turn.Kind.COMMAND) {
                undoLastCommand();
            }
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
            renderTranscript();
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
            renderTranscript();
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
                ? "Cascade(Paraformer→qwen-mt-plus)"
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
            currentTurn = null;
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

    /** Live partial for the in-progress turn: stage a TRANSLATION turn and
     *  render history + streaming tail. */
    @Override public void onStreaming(String source, String target, String srcLang, String tgtLang) {
        currentTurn = Turn.translation(source, target, srcLang, tgtLang);
        renderTranscript();
    }

    /** Finished turn: fold into history, then render history alone. */
    @Override public void onCommitted(String source, String target, String srcLang, String tgtLang) {
        boolean hasContent = (source != null && !source.trim().isEmpty())
                || (target != null && !target.trim().isEmpty());
        if (hasContent) {
            Turn t = Turn.translation(source, target, srcLang, tgtLang);
            history.add(t);
            // Feed into SessionContext so the next MT LLM call sees the
            // recent utterance for "再翻一次" / partial-reference style
            // commands.
            sessionContext.recordUtterance(t);
        }
        currentTurn = null;
        renderTranscript();
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
        // The chip in the transcript uses the primary (last non-rejected)
        // result if any; otherwise we surface a short "rejected" chip.
        if (applied.primaryIndex >= 0) {
            CommandResult primary = applied.results.get(applied.primaryIndex);
            ToolCall primaryCall = calls.get(applied.primaryIndex);
            history.add(Turn.command(formatChip(primary, primaryCall)));
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
                        renderTranscript();
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
            // Save the snapshot for UNDO before clobbering the field.
            if (primary.sessionSnapshot != null) {
                lastUndoable = primary;
                showUndoToast(primary);
            }
        } else {
            // All rejected — show a single rejection chip + no UNDO.
            history.add(Turn.command("» rejected: " + shortRejection(applied.results)));
            renderTranscript();
        }
    }

    /** clear_transcript handler: wipe history but keep the just-added chip so
     *  the user sees what happened, then persist immediately. */
    private void doClearTranscript() {
        Turn lastChip = history.remove(history.size() - 1);
        history.clear();
        currentTurn = null;
        history.add(lastChip);
        renderTranscript();
        // Persist the wipe immediately — if the process dies before onPause,
        // the cleared state should still stick.
        if (transcriptStore != null) transcriptStore.save(history);
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

    /** Show a Toast offering UNDO of the most recently applied tool. */
    private void showUndoToast(CommandResult applied) {
        final CommandResult captured = applied;
        Toast toast = Toast.makeText(this, "» " + applied.summary, Toast.LENGTH_LONG);
        // We attach a "UNDO" action via a slightly different approach —
        // system Toasts can't have buttons, so we use a Snackbar if the
        // layout has a CoordinatorLayout; here we fall back to a long
        // Toast plus a debug-log hint. UNDO is invoked by tapping the
        // most recent chip in the transcript (chips are tappable — see
        // setupTranscriptClickHandler). This Toast is just visibility.
        toast.show();
    }

    /** Invoked when the user taps the most recent command chip in the
     *  transcript. Restores the snapshot and applies the side effects
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
        renderTranscript();
        reconcileEngineIfNeeded();
        Toast.makeText(this, "Undone", Toast.LENGTH_SHORT).show();
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
        // Update the just-added chip to show the character count.
        Turn last = history.get(history.size() - 1);
        history.set(history.size() - 1,
                Turn.command(last.commandText + "  (" + text.length() + " chars)"));
        renderTranscript();
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
            // Update the just-added chip to reflect the failure.
            Turn last = history.get(history.size() - 1);
            history.set(history.size() - 1, Turn.command(last.commandText + "  (no turn to re-translate)"));
            renderTranscript();
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
                        renderTranscript();
                        debug.log("MT", "retranslated turn " + turnIdx);
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        Turn last = history.get(history.size() - 1);
                        history.set(history.size() - 1,
                                Turn.command(last.commandText + "  (failed: " + message + ")"));
                        renderTranscript();
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
        history.set(chipIndex, Turn.command(sb.toString()));
        renderTranscript();
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
            Turn last = history.get(history.size() - 1);
            history.set(history.size() - 1,
                    Turn.command(last.commandText + "  (transcript is empty)"));
            renderTranscript();
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
                        // Replace the in-progress chip with the summary chip.
                        String summary = p.source.isEmpty() ? p.target : p.source;
                        if (summary.isEmpty()) summary = text;
                        history.set(history.size() - 1,
                                Turn.command("» Summary: " + summary));
                        renderTranscript();
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        Turn last = history.get(history.size() - 1);
                        history.set(history.size() - 1,
                                Turn.command(last.commandText + "  (failed: " + message + ")"));
                        renderTranscript();
                    });
                }
                @Override public void onToolCalls(java.util.List<ToolCall> calls) { }
            });
        });
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
        StringBuilder sb = new StringBuilder();
        SessionConfig.DisplayMode mode = session.displayMode();
        for (int i = 0; i < history.size(); i++) {
            appendTurn(sb, history.get(i), mode);
            if (i < history.size() - 1) sb.append('\n');  // blank line between turns
        }
        if (currentTurn != null) {
            if (sb.length() > 0) sb.append('\n');
            appendTurn(sb, currentTurn, mode);
        }
        String text = sb.toString().replaceAll("\\s+$", "");
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

    /** Append one turn's text to the renderer buffer, respecting the
     *  current display mode. COMMAND turns always render as a chip —
     *  the display mode doesn't filter them. */
    private static void appendTurn(StringBuilder sb, Turn turn, SessionConfig.DisplayMode mode) {
        if (turn.kind == Turn.Kind.COMMAND) {
            sb.append("» ").append(turn.commandText);
            return;
        }
        switch (mode) {
            case BOTH:
                if (!turn.source.isEmpty()) sb.append(turn.source);
                if (!turn.source.isEmpty() && !turn.target.isEmpty()) sb.append('\n');
                if (!turn.target.isEmpty()) sb.append(turn.target);
                break;
            case SOURCE_ONLY:
                if (!turn.source.isEmpty()) sb.append(turn.source);
                break;
            case TARGET_ONLY:
                if (!turn.target.isEmpty()) sb.append(turn.target);
                break;
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
