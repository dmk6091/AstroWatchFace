package com.dmk.astrocompanion;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/** Background refresh of weather + tide every 30 minutes (when there's a connection). */
public class Worker extends androidx.work.Worker {

    public Worker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    @NonNull
    @Override
    public Result doWork() {
        Engine.refresh(getApplicationContext(), false);
        return Result.success();
    }

    private static boolean sScheduled = false;

    static synchronized void schedule(Context ctx) {
        if (sScheduled) return;
        sScheduled = true;
        Constraints c = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        WorkManager wm = WorkManager.getInstance(ctx.getApplicationContext());
        wm.enqueueUniquePeriodicWork("astro_refresh", ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(Worker.class, 30, TimeUnit.MINUTES).setConstraints(c).build());
        // and once right away if the data is old
        if (System.currentTimeMillis() - Store.p(ctx).getLong("wTime", 0) > 40 * 60_000L) {
            wm.enqueueUniqueWork("astro_now", ExistingWorkPolicy.KEEP,
                    new OneTimeWorkRequest.Builder(Worker.class).setConstraints(c).build());
        }
    }
}
