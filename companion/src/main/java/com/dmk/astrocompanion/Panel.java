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
 * The face's bottom panel: tide, earthquakes, rain, wind, pressure trend, sea state or air quality. A tap on the panel moves to the next one.
 * When rain is less than 30 minutes away the rain screen comes up by itself (unless you tapped
 * in the last 20 minutes), and the panel goes back to your choice once it's dry.
 */
public class Panel extends BroadcastReceiver {
    static final int TIDE = 0, QUAKES = 1, RAIN = 2, WIND = 3, PRESSURE = 4, SEA = 5, AIR = 6, COUNT = 7;

    /** The screen showing now. */
    static int current(SharedPreferences p, long now) {
        int chosen = p.getInt("panel", TIDE);
        if (chosen < 0 || chosen >= COUNT) chosen = TIDE;
        boolean recentTap = now - p.getLong("panelTap", 0) < 20 * 60_000L;
        if (!recentTap && p.getBoolean(Link.K_RAIN, true) && Rain.soon(p, now)) return RAIN;
        return chosen;
    }

    /** What the panel complication does on a tap. */
    static PendingIntent tap(Context c) {
        Intent i = new Intent(c, Panel.class).setAction("com.dmk.astrocompanion.NEXT_PANEL");
        return PendingIntent.getBroadcast(c, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---- turning the globe: each tap on it turns it 60 degrees east (six taps = once round);
    // it goes back to showing your place after two minutes
    private static final long SPIN_BACK = 2 * 60_000L;

    /** How many 60-degree turns the globe is showing (0 = you in the middle). */
    static int spinSteps(SharedPreferences p, long now) {
        return now - p.getLong("spinAt", 0) > SPIN_BACK ? 0 : p.getInt("spin", 0) % 6;
    }

    /** What a tap on the globe does. */
    static PendingIntent spinTap(Context c) {
        Intent i = new Intent(c, Panel.class).setAction("com.dmk.astrocompanion.SPIN_GLOBE");
        return PendingIntent.getBroadcast(c, 8, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void globeUpdate(Context c) {
        try {
            ComplicationDataSourceUpdateRequester.create(c, new ComponentName(c, GlobeService.class)).requestUpdateAll();
        } catch (Throwable t) {
            Log.w(Engine.TAG, "globe: " + t);
        }
    }

    @Override
    public void onReceive(Context c, Intent in) {
        SharedPreferences p = Store.p(c);
        long now = System.currentTimeMillis();
        String act = in == null ? null : in.getAction();
        if ("com.dmk.astrocompanion.SPIN_GLOBE".equals(act)) {
            int next = (spinSteps(p, now) + 1) % 6;
            p.edit().putInt("spin", next).putLong("spinAt", now).commit();
            globeUpdate(c);
            if (next != 0) {
                try {
                    Intent back = new Intent(c, Panel.class).setAction("com.dmk.astrocompanion.SPIN_BACK");
                    PendingIntent pi = PendingIntent.getBroadcast(c, 9, back,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    ((android.app.AlarmManager) c.getSystemService(Context.ALARM_SERVICE))
                            .set(android.app.AlarmManager.RTC, now + SPIN_BACK + 2000, pi);
                } catch (Throwable t) {
                    Log.w(Engine.TAG, "spin alarm: " + t);
                }
            }
            return;
        }
        if ("com.dmk.astrocompanion.SPIN_BACK".equals(act)) {
            if (now - p.getLong("spinAt", 0) >= SPIN_BACK) {
                p.edit().putInt("spin", 0).commit();
                globeUpdate(c);
            }
            return;
        }
        int next = (current(p, now) + 1) % COUNT;
        p.edit().putInt("panel", next).putLong("panelTap", now).putInt("panelShown", next).commit();
        refresh(c);
    }

    /** Redraws the panel text and picture. */
    static void refresh(Context c) {
        try {
            ComplicationDataSourceUpdateRequester.create(c, new ComponentName(c, DataService.class)).requestUpdateAll();
            ComplicationDataSourceUpdateRequester.create(c, new ComponentName(c, TideGraphService.class)).requestUpdateAll();
            ComplicationDataSourceUpdateRequester.create(c, new ComponentName(c, GlobeService.class)).requestUpdateAll();
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
                ComplicationDataSourceUpdateRequester.create(c, new ComponentName(c, GlobeService.class)).requestUpdateAll();
            } catch (Throwable ignored) {
            }
        }
        return cur;
    }
}
