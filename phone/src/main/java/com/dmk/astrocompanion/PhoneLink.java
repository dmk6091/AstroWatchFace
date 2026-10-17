package com.dmk.astrocompanion;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.WearableListenerService;

/** Runs in the background: keeps the watch's battery, for WatchBattery (the status bar module reads it). */
public class PhoneLink extends WearableListenerService {
    @Override
    public void onDataChanged(DataEventBuffer events) {
        for (DataEvent ev : events) {
            if (ev.getType() == DataEvent.TYPE_CHANGED && Link.STATUS.equals(ev.getDataItem().getUri().getPath())) {
                try {
                    store(getApplicationContext(), DataMapItem.fromDataItem(ev.getDataItem()).getDataMap()
                            .getInt(Link.K_BATT, -1), DataMapItem.fromDataItem(ev.getDataItem()).getDataMap()
                            .getBoolean(Link.K_CHG, false), DataMapItem.fromDataItem(ev.getDataItem()).getDataMap()
                            .getString(Link.K_MODEL, ""));
                } catch (Throwable ignored) {
                }
            }
        }
    }

    static void store(Context c, int pct, boolean charging, String model) {
        SharedPreferences.Editor e = c.getSharedPreferences("watchbatt", Context.MODE_PRIVATE).edit();
        e.putInt("pct", pct).putBoolean("chg", charging).putString("model", model)
                .putLong("t", System.currentTimeMillis()).apply();
    }
}
