package com.dmk.astrocompanion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Plugged in / unplugged / low / okay: tell the phone the new battery state. */
public class BatteryReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        WatchLink.publish(c.getApplicationContext());
    }
}
