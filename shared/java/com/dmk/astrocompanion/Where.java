package com.dmk.astrocompanion;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

/** Where you are: Google's fused location, then the device's own providers; saved for later runs. */
final class Where {
    private static final String TAG = Store.TAG;

    private Where() {}

    /** Pune, used until the watch reports a real position (marked as approximate). */
    static final double FALLBACK_LAT = 18.62, FALLBACK_LON = 73.80;

    /**
     * Best location of the watch, saved so later runs (even without permission) have one.
     * Order: Google's fused location (comes from the phone on Wear OS), then the watch's own
     * providers. Blocking (up to ~20 s): never call on the main thread.
     */
    static void update(Context ctx) {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            note(ctx, "location permission not granted");
            return;
        }
        Location best = null;
        try {
            com.google.android.gms.location.FusedLocationProviderClient f =
                    com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(ctx);
            try {
                best = com.google.android.gms.tasks.Tasks.await(f.getCurrentLocation(
                        com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, null),
                        20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Throwable t) {
                Log.w(TAG, "fused current: " + t);
            }
            if (best == null) {
                best = com.google.android.gms.tasks.Tasks.await(f.getLastLocation(), 5,
                        java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Throwable t) {
            Log.w(TAG, "fused: " + t);
        }
        if (best == null) {
            try {
                LocationManager lm = ctx.getSystemService(LocationManager.class);
                for (String prov : lm.getProviders(true)) {
                    Location l = lm.getLastKnownLocation(prov);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                }
            } catch (Throwable t) {
                Log.w(TAG, "location: " + t);
            }
        }
        if (best != null) {
            saveLocation(ctx, best);
        } else {
            note(ctx, "no position from the phone/watch yet");
            SharedPreferences p = Store.p(ctx);
            if (!Store.hasLocation(p)) {
                SharedPreferences.Editor e = p.edit();
                Store.putD(e, "lat", FALLBACK_LAT);
                Store.putD(e, "lon", FALLBACK_LON);
                e.putBoolean("locApprox", true);
                e.apply();
            }
        }
    }

    static void note(Context ctx, String s) {
        Store.p(ctx).edit().putString("locNote", s).apply();
    }

    static void saveLocation(Context ctx, Location l) {
        SharedPreferences.Editor e = Store.p(ctx).edit();
        Store.putD(e, "lat", l.getLatitude());
        Store.putD(e, "lon", l.getLongitude());
        e.putLong("locTime", l.getTime());
        e.putBoolean("locApprox", false);
        e.putString("locNote", "");
        e.apply();
    }

}
