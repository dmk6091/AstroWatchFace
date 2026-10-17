package com.dmk.astrocompanion;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;

/**
 * The face's bottom panel: tide, earthquakes or rain. A tap on the panel moves to the next one.
 * When rain is less than 30 minutes away the rain screen comes up by itself (unless you tapped
 * in the last 20 minutes), and the panel goes back to your choice once it's dry.
 */
public class Panel extends BroadcastReceiver {
    static final int TIDE = 0, QUAKES = 1, RAIN = 2, COUNT = 3;

    /** The screen showing now. */
    static int current(SharedPreferences p, long now) {
        int chosen = p.getInt("panel", TIDE);
        if (chosen < 0 || chosen >= COUNT) chosen = TIDE;
        boolean recentTap = now - p.getLong("panelTap", 0) < 20 * 60_000L;
        if (!recentTap && Rain.soon(p, now)) return RAIN;
        return chosen;
    }

    /** What the panel complication does on a tap. */
    static PendingIntent tap(Context c) {
        Intent i = new Intent(c, Panel.class).setAction("com.dmk.astrocompanion.NEXT_PANEL");
        return PendingIntent.getBroadcast(c, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Opens the Astro app (tap on the globe centre or the ISS line). */
    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 8, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context c, Intent in) {
        SharedPreferences p = Store.p(c);
        long now = System.currentTimeMillis();
        int next = (current(p, now) + 1) % COUNT;
        p.edit().putInt("panel", next).putLong("panelTap", now).putInt("panelShown", next).commit();
        refresh(c);
    }

    /** Redraws the panel text and picture. */
    static void refresh(Context c) {
        try {
            ComplicationDataSourceUpdateRequester.create(c, new ComponentName(c, DataService.class)).requestUpdateAll();
            ComplicationDataSourceUpdateRequester.create(c, new ComponentName(c, TideGraphService.class)).requestUpdateAll();
        } catch (Throwable t) {
            Log.w(Engine.TAG, "panel: " + t);
        }
    }

    /** Called when the face asks for data: if the screen changed by itself (rain), redraw the picture too. */
    static int checkAuto(Context c, SharedPreferences p, long now) {
        int cur = current(p, now);
        if (p.getInt("panelShown", -1) != cur) {
            p.edit().putInt("panelShown", cur).apply();
            try {
                ComplicationDataSourceUpdateRequester.create(c, new ComponentName(c, TideGraphService.class)).requestUpdateAll();
            } catch (Throwable ignored) {
            }
        }
        return cur;
    }
}
