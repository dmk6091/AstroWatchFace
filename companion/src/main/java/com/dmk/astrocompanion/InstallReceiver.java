package com.dmk.astrocompanion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.util.Log;

import java.util.concurrent.BlockingQueue;

/**
 * Answers from Android's installer (for Updater), and "this app was just updated".
 * When the installer wants a confirmation and the update was started from the app screen,
 * its Install / Cancel screen is shown on the watch.
 */
public class InstallReceiver extends BroadcastReceiver {
    static final String EXTRA_FG = "fg";

    @Override
    public void onReceive(Context c, Intent in) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(in.getAction())) {
            replaced(c);
            return;
        }
        int id = in.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        int status = in.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String msg = in.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && in.getBooleanExtra(EXTRA_FG, false)) {
            @SuppressWarnings("deprecation")
            Intent confirm = android.os.Build.VERSION.SDK_INT >= 33
                    ? in.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : (Intent) in.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try {
                    c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Throwable t) {
                    Log.w(Engine.TAG, "install confirm: " + t);
                    status = PackageInstaller.STATUS_FAILURE;
                    msg = "couldn't show the install screen";
                }
            }
        }
        BlockingQueue<Object[]> q = Updater.WAIT.get(id);
        if (q != null) q.offer(new Object[]{status, msg});
    }

    /** Runs right after an update of this app: note it, restart the background refresh. */
    private void replaced(Context c) {
        SharedPreferences p = Store.p(c);
        long want = p.getLong("updSelf", 0), now = Updater.installed(c, c.getPackageName());
        if (want > 0 && now >= want) {
            p.edit().putString("updStatus", "Updated to build " + now).remove("updSelf")
                    .putLong("updWhen", System.currentTimeMillis()).apply();
        }
        PendingResult r = goAsync();
        new Thread(() -> {
            try {
                Worker.schedule(c);
                Engine.pushUpdates(c);
            } catch (Throwable t) {
                Log.w(Engine.TAG, "after update: " + t);
            } finally {
                r.finish();
            }
        }).start();
    }
}
