package com.hee.tempshot;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Periodic cleanup that doesn't depend on the accessibility service being on. Android runs it about
 * every hour (its minimum is 15 minutes, and it may batch it with other jobs).
 */
public class CleanupJob extends JobService {
    private static final int ID = 1;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(ID) != null) return;
        js.schedule(new JobInfo.Builder(ID, new ComponentName(c, CleanupJob.class))
                .setPeriodic(60 * 60 * 1000L)
                .build());
    }

    @Override public boolean onStartJob(JobParameters p) {
        new Thread(() -> {
            try {
                ShotService.cleanup(this);
            } catch (Throwable e) {
                TempShotApp.record(this, e);
            }
            jobFinished(p, false);
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters p) { return true; }
}
