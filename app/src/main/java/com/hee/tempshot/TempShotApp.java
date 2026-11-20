package com.hee.tempshot;

import android.app.Application;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Saves the last crash so the settings screen can show it (there is no logcat on the user's phone). */
public class TempShotApp extends Application {
    static final String KEY_CRASH = "last_crash";

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler system = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            record(this, e);
            if (system != null) system.uncaughtException(t, e);
        });
    }

    /** Stores the error for the "최근 오류" card. Also used for errors that are caught and survived. */
    static void record(android.content.Context c, Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        c.getSharedPreferences(ShotService.PREFS, MODE_PRIVATE).edit()
                .putString(KEY_CRASH, "thread: " + Thread.currentThread().getName() + "\n" + sw)
                .commit(); // commit, not apply: on a crash the process dies right after
    }
}
