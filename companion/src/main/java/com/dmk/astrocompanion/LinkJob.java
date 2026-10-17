package com.dmk.astrocompanion;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Work the phone asked for, run in the background so it finishes even if the screen goes off. */
public class LinkJob extends androidx.work.Worker {
    static final String REFRESH = "refresh", UPDATE = "update", FILES = "files";

    public LinkJob(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    static void start(Context c, String job) {
        WorkManager.getInstance(c.getApplicationContext()).enqueueUniqueWork("link_" + job, ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(LinkJob.class).setInputData(new Data.Builder().putString("job", job).build())
                        .build());
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        String job = getInputData().getString("job");
        try {
            if (REFRESH.equals(job)) {
                Engine.refresh(c, true);
                WatchLink.publish(c);
            } else if (UPDATE.equals(job)) {
                Updater.run(c, false, s -> WatchLink.progress(c, s));
            } else if (FILES.equals(job)) {
                File zip = WatchLink.zipFile(c);
                if (!zip.exists()) return Result.success();
                File face = null, app = null;
                try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
                    ZipEntry ze;
                    byte[] buf = new byte[64 * 1024];
                    while ((ze = in.getNextEntry()) != null) {
                        String name = new File(ze.getName()).getName();   // no folders in the zip
                        if (!name.equals(Installer.FACE_APK) && !name.equals(Installer.APP_APK)) continue;
                        File out = new File(Installer.dir(c), name);
                        try (FileOutputStream fo = new FileOutputStream(out)) {
                            int n;
                            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                        }
                        if (name.equals(Installer.FACE_APK)) face = out;
                        else app = out;
                    }
                }
                zip.delete();
                Updater.installFiles(c, face, app, s -> WatchLink.progress(c, s));
            }
        } catch (Throwable t) {
            WatchLink.status(c, "Update: " + t.getMessage());
        }
        return Result.success();
    }
}
