package com.vokii.translator;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Lightweight rolling log that incrementally appends lines to a TextView
 * on the UI thread. Inspired by app3's `DebugLogPanel`:
 *
 *   - Ring buffer of {@link #MAX_LINES} entries (app3 uses 40; we use 100
 *     because log lines here are denser and stack traces are useful).
 *   - Per-line truncation at {@link #MAX_LINE_CHARS} so a 5KB throwable
 *     doesn't blow up the panel.
 *   - Synchronized append/trim so background threads can call {@link #log}
 *     concurrently without corrupting the buffer.
 *   - Incremental {@link Editable#append} on the TextView — never
 *     {@code setText(entireString)}. {@code setText} triggers a full
 *     measure+layout pass and is the classic TextView performance trap;
 *     repeated calls on long text can ANR the main thread (which the OS
 *     reports to the user as a crash) or push Skia into native-graphics
 *     territory where it's known to crash on overlong measure calls.
 */
public class DebugLogger {

    private static final int MAX_LINES = 100;
    private static final int MAX_LINE_CHARS = 500;

    private final TextView target;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Owned by background threads for write; UI thread reads via the lock
     *  when re-rendering on clear() / setTarget(). */
    private final ArrayDeque<String> buffer = new ArrayDeque<>(MAX_LINES + 4);
    /** How many of {@code buffer}'s entries have already been pushed into
     *  the TextView. Increments monotonically under the buffer lock; the
     *  UI thread reads it (also under the lock) to know what to append. */
    private int appliedCount = 0;
    /** Set by set_log_level tool. NORMAL = show only high-signal events
     *  (status changes, tool calls, errors). VERBOSE = show everything
     *  (every ASR packet, every MT chunk). Reads happen on background
     *  threads; writes are atomic via volatile. */
    private volatile Level level = Level.NORMAL;
    /** Synchronize on the deque itself — it's small and lock churn is fine
     *  at the rate we log. SimpleDateFormat is allocated per call (it's not
     *  thread-safe; a shared instance would corrupt under concurrent writes). */
    private final ThreadLocal<SimpleDateFormat> fmt =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss.SSS", Locale.US));

    public enum Level { QUIET, NORMAL, VERBOSE }

    public DebugLogger(TextView target) {
        this.target = target;
    }

    public Level level() { return level; }

    public void setLevel(Level l) {
        if (l == null) l = Level.NORMAL;
        this.level = l;
    }

    /** Tag → importance. Tags in NORMAL_KEEP are always shown. Tags in
     *  VERBOSE_ONLY are dropped at NORMAL or QUIET. Tags in ALWAYS_SHOW
     *  are shown at every level (critical errors etc). */
    private static final java.util.Set<String> NORMAL_KEEP = new java.util.HashSet<>(java.util.Arrays.asList(
            "boot", "ASR", "MT", "CMD", "INJECT", "engine", "perm", "pipeline"
    ));
    private static final java.util.Set<String> ALWAYS_SHOW = new java.util.HashSet<>(java.util.Arrays.asList(
            "ERROR", "boot"
    ));

    private boolean shouldLog(String tag) {
        if (level == Level.VERBOSE) return true;
        if (level == Level.QUIET) return ALWAYS_SHOW.contains(tag);
        // NORMAL: drop verbose-only tags. Currently no tag is verbose-only;
        // NORMAL is the same as always-show for now. We keep the gate so
        // we can mark future tags as verbose-only without touching this method.
        return true;
    }

    public void log(String tag, String msg) {
        if (!shouldLog(tag)) return;
        final String line = format(tag, msg);
        synchronized (buffer) {
            buffer.addLast(line);
            while (buffer.size() > MAX_LINES) {
                buffer.removeFirst();
                // Drop the applied offset too — we're losing history so
                // the new "first applied" must come forward.
                if (appliedCount > 0) appliedCount--;
            }
        }
        main.post(this::applyToTextView);
    }

    /**
     * If the TextView is hosted inside a ScrollView, scroll to the bottom
     * so the most recent line is visible. Safe to call from any thread —
     * it just posts to main.
     *
     * Bug history: without this, new logs appended at the bottom of the
     * TextView were hidden until the user manually scrolled down, because
     * the ScrollView stays at its old position. Combined with a small
     * viewport (~130dp = ~7 lines at 11sp), the user saw only "one new
     * line" of activity and assumed earlier history was missing.
     */
    public void scrollToBottom() {
        main.post(() -> {
            if (target == null) return;
            View parent = (View) target.getParent();
            if (parent instanceof ScrollView) {
                ((ScrollView) parent).fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    /**
     * Scroll to the bottom only if the TextView's content is taller
     * than the viewport. The naive {@link #scrollToBottom} on every
     * log append was hiding early history (boot logs) when the panel
     * was small — a 7-line buffer in a 240dp panel doesn't overflow,
     * so all 7 lines should be visible at the top, but scroll-to-bottom
     * would force a scroll that left only the last line in view.
     *
     * The check has to run AFTER layout (so getLineCount and
     * getHeight are valid) — we post to main and check there.
     */
    public void scrollToBottomIfOverflow() {
        main.post(() -> {
            if (target == null) return;
            View parent = (View) target.getParent();
            if (!(parent instanceof ScrollView)) return;
            ScrollView sv = (ScrollView) parent;
            target.post(() -> {
                if (target == null) return;
                int contentHeight = target.getHeight();
                int viewHeight = sv.getHeight();
                if (contentHeight > viewHeight) {
                    sv.fullScroll(View.FOCUS_DOWN);
                }
            });
        });
    }

    public void clear() {
        synchronized (buffer) {
            buffer.clear();
            appliedCount = 0;
        }
        main.post(() -> {
            if (target != null) target.setText("");
        });
    }

    /** Re-render the TextView from the current buffer. Called after
     *  level change so the user immediately sees the new verbosity
     *  (and so QUIET mode clears the panel). */
    public void rerender() {
        main.post(this::applyToTextView);
    }

    /** Snapshot the buffer for inspection (tests / debug dumps). */
    public java.util.List<String> snapshot() {
        synchronized (buffer) {
            return new java.util.ArrayList<>(buffer);
        }
    }

    private String format(String tag, String msg) {
        SimpleDateFormat f = fmt.get();
        String stamp = f.format(new Date());
        String body = msg == null ? "" : msg;
        if (body.length() > MAX_LINE_CHARS) {
            body = body.substring(0, MAX_LINE_CHARS) + "…(+" + (msg.length() - MAX_LINE_CHARS) + " chars)";
        }
        return "[" + stamp + "] " + tag + " | " + body;
    }

    /** Apply the ring buffer to the TextView incrementally. The first time
     *  we ever run, we have to use setText (the Editable doesn't exist yet);
     *  after that we append + trim from the front, which keeps every layout
     *  pass local to the new line. */
    private void applyToTextView() {
        if (target == null) return;
        // Snapshot the pending entries while holding the lock, then drop it
        // so the UI thread doesn't stall other loggers.
        String[] pending;
        int startIdx;
        synchronized (buffer) {
            int total = buffer.size();
            if (appliedCount >= total) return;       // nothing new
            startIdx = appliedCount;
            pending = new String[total - startIdx];
            int i = 0;
            for (String s : buffer) {
                if (i >= startIdx) pending[i - startIdx] = s;
                i++;
            }
            appliedCount = total;
        }

        Editable editable = target.getEditableText();
        // Defensive: if the Editable is missing OR empty (cold start,
        // post-clear, or any other reason — sometimes Android returns
        // a fresh empty Editable after layout for reasons we don't
        // fully understand), rebuild from the FULL buffer rather than
        // just `pending`. Otherwise we'd wipe out already-applied lines
        // — the bug we hit on real device where the boot logs vanished
        // after tapping mic.
        if (editable == null || editable.length() == 0) {
            StringBuilder sb = new StringBuilder(total() * 80);
            synchronized (buffer) {
                for (String s : buffer) sb.append(s).append('\n');
            }
            target.setText(sb, TextView.BufferType.EDITABLE);
            trimFromFront(target);
            scrollToBottomIfOverflow();
            return;
        }
        // Normal path: append each new line in order. One Editable
        // mutation per line so a single line never has to be re-measured.
        for (String s : pending) {
            editable.append(s).append('\n');
        }
        trimFromFront(target);
        // Pin to the bottom so the latest log is always visible — but ONLY
        // when the buffer is taller than the viewport. Otherwise the
        // user gets scrolled past history they actually want to see.
        scrollToBottomIfOverflow();
    }

    /** Buffer size under the lock. Used by applyToTextView to rebuild
     *  from the full buffer when we need to. */
    private int total() {
        synchronized (buffer) { return buffer.size(); }
    }

    /** Trim leading lines so the TextView never holds more than MAX_LINES. */
    private void trimFromFront(TextView t) {
        // getLineCount() is O(1) on a laid-out TextView but a no-op until
        // the next layout pass; for our case the layout runs immediately
        // because we just mutated the Editable.
        int safety = 0;
        while (t.getLineCount() > MAX_LINES && safety++ < MAX_LINES * 2) {
            Editable e = t.getEditableText();
            if (e == null) break;
            int firstNewline = e.toString().indexOf('\n');
            if (firstNewline < 0) break;
            e.delete(0, firstNewline + 1);
        }
    }
}