package com.vokii.translator;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Catches every uncaught exception (UI thread AND background), dumps the
 * full stack trace to a file in the app's external files dir, and forwards
 * to the previous default handler so the OS still terminates the process —
 * we don't want to silently swallow fatal errors.
 *
 * Path: {@code <getExternalFilesDir>/vokii-crashes/crash-<ts>.log}.
 * The app-private external dir is the only storage Android 10+ lets us
 * write to without runtime permission, and it is still readable via
 * {@code adb pull} on userdebug builds without root:
 *
 *   adb pull /sdcard/Android/data/com.vokii.translator/files/vokii-crashes/
 *
 * Two reasons this exists:
 *   1. The user reported 闪退 (process death) we can't repro locally
 *      because no HMS-equipped device is attached to the dev machine.
 *   2. Logcat may be wiped between the crash and us reading it; the file
 *      persists until the user pulls it.
 */
public final class CrashReporter {

    private static final String TAG = "VokiiCrash";
    private static final String DIR = "vokii-crashes";
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US);

    private static volatile File crashDir;

    private CrashReporter() {}

    public static void install(Context appCtx) {
        // Resolve the dir eagerly so we don't have to do it from the
        // crashing thread (where getExternalFilesDir may already be
        // returning null if the storage subsystem is wedged).
        crashDir = new File(appCtx.getExternalFilesDir(null), DIR);
        if (crashDir != null && !crashDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            crashDir.mkdirs();
        }

        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    writeCrash(t, e);
                } catch (Throwable inner) {
                    Log.e(TAG, "crash writer failed", inner);
                }
                if (prev != null) prev.uncaughtException(t, e);
                else {
                    // No previous handler: explicitly exit so the process
                    // actually dies (the framework would have done this for
                    // us otherwise).
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    private static void writeCrash(Thread t, Throwable e) throws java.io.IOException {
        String ts = FMT.format(new Date());
        StringBuilder sb = new StringBuilder(4096);
        sb.append("---- Vokii crash ").append(ts).append(" ----\n");
        sb.append("thread: ").append(t.getName())
          .append(" (id=").append(t.getId()).append(")\n");
        sb.append("android.os.Build.MANUFACTURER=").append(android.os.Build.MANUFACTURER).append('\n');
        sb.append("android.os.Build.MODEL=").append(android.os.Build.MODEL).append('\n');
        sb.append("android.os.Build.VERSION.SDK_INT=").append(android.os.Build.VERSION.SDK_INT).append('\n');
        sb.append("android.os.Build.VERSION.RELEASE=").append(android.os.Build.VERSION.RELEASE).append('\n');
        sb.append('\n');
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        sb.append(sw);
        sb.append('\n');

        // Also dump the cause chain separately so we don't lose it if
        // printStackTrace got truncated by the OS logger buffer.
        Throwable cause = e.getCause();
        int depth = 0;
        while (cause != null && depth < 8) {
            sb.append("  caused by: ").append(cause.getClass().getName());
            if (cause.getMessage() != null) sb.append(": ").append(cause.getMessage());
            sb.append('\n');
            StringWriter csw = new StringWriter();
            cause.printStackTrace(new PrintWriter(csw));
            sb.append(csw).append('\n');
            cause = cause.getCause();
            depth++;
        }

        File dir = crashDir;
        if (dir == null) {
            Log.e(TAG, "no crash dir available; crash not persisted");
            Log.e(TAG, sb.toString());
            return;
        }
        File out = new File(dir, "crash-" + ts + ".log");
        try (FileWriter fw = new FileWriter(out)) {
            fw.write(sb.toString());
        }
        Log.e(TAG, "crash written to " + out.getAbsolutePath());
        // Also echo to logcat so `adb logcat -s VokiiCrash:E` picks it up
        // even if the file is later wiped or we can't read external storage.
        Log.e(TAG, sb.toString());
    }

    /** Best-effort accessor for the live debug panel / dev tools. */
    public static File getCrashDir() { return crashDir; }
}