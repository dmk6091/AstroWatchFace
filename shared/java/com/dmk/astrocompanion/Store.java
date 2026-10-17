package com.dmk.astrocompanion;

import android.content.Context;
import android.content.SharedPreferences;

/** Everything the companion remembers between runs. */
final class Store {
    static final String TAG = "AstroCompanion";

    private Store() {}

    static SharedPreferences p(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("astro", 0);
    }

    static double d(SharedPreferences p, String k, double def) {
        try {
            return Double.parseDouble(p.getString(k, String.valueOf(def)));
        } catch (Exception e) {
            return def;
        }
    }

    static void putD(SharedPreferences.Editor e, String k, double v) {
        e.putString(k, String.valueOf(v));
    }

    static boolean hasLocation(SharedPreferences p) {
        return p.contains("lat") && p.contains("lon");
    }
}
