package com.dmk.astrocompanion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/** Runs right after an update of this app: note it, restart the background refresh, tell the phone. */
public class InstallReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent in) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(in.getAction())) return;
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
                WatchLink.publish(c);
            } catch (Throwable t) {
                Log.w(Engine.TAG, "after update: " + t);
            } finally {
                r.finish();
            }
        }).start();
    }
}
