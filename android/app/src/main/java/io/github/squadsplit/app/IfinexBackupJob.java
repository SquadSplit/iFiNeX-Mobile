package io.github.squadsplit.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.util.Log;

/** The midnight job. Does the upload on a worker thread, then arms the next night (or a retry in an hour, at most 4 times a night). */
public class IfinexBackupJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    ok = IfinexAutoBackup.runOnce(app);
                } catch (Throwable t) {
                    Log.w(IfinexAutoBackup.TAG, "job: " + t);
                }
                try {
                    int fails = IfinexAutoBackup.prefs(app).getInt("fails", 0);
                    if (ok || fails >= 4) {
                        IfinexAutoBackup.prefs(app).edit().putInt("fails", 0).commit();
                        IfinexAutoBackup.schedule(app);
                    } else {
                        IfinexAutoBackup.scheduleIn(app, 3600000L, 1800000L);
                    }
                } catch (Throwable t) {
                    Log.w(IfinexAutoBackup.TAG, "job re-arm: " + t);
                }
                jobFinished(params, false);
            }
        }, "ifinex-midnight-backup").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
