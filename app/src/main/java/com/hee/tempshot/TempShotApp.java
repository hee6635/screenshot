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
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            getSharedPreferences(ShotService.PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_CRASH, "thread: " + t.getName() + "\n" + sw).commit(); // commit: process dies next
            if (system != null) system.uncaughtException(t, e);
        });
    }
}
