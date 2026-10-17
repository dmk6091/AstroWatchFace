package com.dmk.astrocompanion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

import java.util.concurrent.BlockingQueue;

/**
 * Answers from Android's installer for Installer.install. When the installer wants a confirmation
 * and the update was started from a screen, its Install / Cancel prompt is shown.
 */
public class InstallStatus extends BroadcastReceiver {
    static final String EXTRA_FG = "fg";

    @Override
    public void onReceive(Context c, Intent in) {
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
                    Log.w(Store.TAG, "install confirm: " + t);
                    status = PackageInstaller.STATUS_FAILURE;
                    msg = "couldn't show the install screen";
                }
            }
        }
        BlockingQueue<Object[]> q = Installer.WAIT.get(id);
        if (q != null) q.offer(new Object[]{status, msg});
    }
}
