package com.dmk.astrocompanion;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Rain in the next two hours, in 15-minute steps (Open-Meteo "minutely_15", fetched with the
 * weather). Each value is the rain of the 15 minutes before its time. Shown in the bottom
 * panel's rain screen, which also comes up by itself when rain is less than 30 min away.
 */
final class Rain {
    static final String PARAMS = "&minutely_15=precipitation&forecast_minutely_15=12&past_minutely_15=1";
    private static final long Q = 15 * 60_000L;
    private static final double WET = 0.1;   // mm in 15 min (0.4 mm/h): less than that is a dry slot

    private Rain() {}

    static void save(JSONObject forecast, SharedPreferences.Editor e) throws Exception {
        JSONObject m = forecast.getJSONObject("minutely_15");
        JSONArray t = m.getJSONArray("time"), v = m.getJSONArray("precipitation");
        StringBuilder a = new StringBuilder(), b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            if (v.isNull(i)) continue;
            if (a.length() > 0) {
                a.append(',');
                b.append(',');
            }
            a.append(t.getLong(i));
            b.append(String.format(Locale.US, "%.2f", v.getDouble(i)));
        }
        e.putString("rainT", a.toString());
        e.putString("rainV", b.toString());
    }

    /** {end time ms, mm in the 15 min before it} pairs; empty when none. */
    private static long[] times(SharedPreferences p) {
        String s = p.getString("rainT", "");
        if (s.isEmpty()) return new long[0];
        String[] a = s.split(",");
        long[] out = new long[a.length];
        for (int i = 0; i < a.length; i++) out[i] = Long.parseLong(a[i].trim()) * 1000;
        return out;
    }

    private static double[] amounts(SharedPreferences p, int n) {
        String[] a = p.getString("rainV", "").split(",");
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = i < a.length ? Double.parseDouble(a[i].trim()) : 0;
        return out;
    }

    /** Rain (mm in 15 min) of the slot covering time ms, or NaN when unknown. */
    private static double at(long[] t, double[] v, long ms) {
        for (int i = 0; i < t.length; i++) if (ms > t[i] - Q && ms <= t[i]) return v[i];
        return Double.NaN;
    }

    private static String hm(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms));
    }

    /** Rain now or starting within 30 min (the panel then shows the rain screen by itself). */
    static boolean soon(SharedPreferences p, long now) {
        try {
            long[] t = times(p);
            double[] v = amounts(p, t.length);
            for (long m = now; m <= now + 30 * 60_000L; m += 5 * 60_000L) {
                double a = at(t, v, m);
                if (!Double.isNaN(a) && a >= WET) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    static void values(SharedPreferences p, Map<String, String> v, long now) {
        v.put("rlvl", "");
        v.put("rmax", "");
        v.put("rend", "");
        long[] t;
        double[] a;
        try {
            t = times(p);
            a = amounts(p, t.length);
        } catch (Exception e) {
            t = new long[0];
            a = new double[0];
        }
        if (t.length == 0 || Double.isNaN(at(t, a, now))) {
            v.put("rhead", "RAIN: NO DATA");
            return;
        }
        long end = now + 2 * 3600_000L;
        long start = -1;
        for (long m = now; m <= end; m += 5 * 60_000L) {
            double x = at(t, a, m);
            if (!Double.isNaN(x) && x >= WET) {
                start = m;
                break;
            }
        }
        if (start < 0) {
            v.put("rhead", "NO RAIN IN 2 H");
            return;
        }
        // the wet spell from start: its heaviest rate and when it stops
        double max = 0;
        long stop = -1, last = t[t.length - 1];
        for (long m = start; m <= last; m += 5 * 60_000L) {
            double x = at(t, a, m);
            if (Double.isNaN(x) || x < WET) {
                stop = m;
                break;
            }
            max = Math.max(max, x * 4);
        }
        long mins = (start - now) / 60_000;
        if (mins <= 2) v.put("rhead", "RAINING NOW");
        else if (mins < 60) v.put("rhead", "RAIN IN " + (Math.round(mins / 5.0) * 5) + " MIN");
        else v.put("rhead", "RAIN AT " + hm(start));
        v.put("rlvl", max < 2.5 ? "LIGHT" : max < 7.6 ? "MODERATE" : "HEAVY");
        v.put("rmax", String.format(Locale.US, "UP TO %.1f mm/h", max));
        v.put("rend", stop > 0 ? "DRY BY " + hm(stop) : "WET AT " + hm(last));
    }

    /** Next 2 hours as 8 bars (428 x 64, same plot area as the tide curve), now on the left. */
    static Bitmap graph(SharedPreferences p, long now) {
        final int w = 428, h = 64;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float x0 = 8, x1 = 420, y0 = 7, y1 = 57;
        long[] t;
        double[] a;
        try {
            t = times(p);
            a = amounts(p, t.length);
        } catch (Exception e) {
            return bmp;
        }
        Paint flat = new Paint(Paint.ANTI_ALIAS_FLAG);
        flat.setColor(Color.argb(200, 90, 110, 140));
        flat.setStrokeWidth(2);
        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        float bw = (x1 - x0) / 8f;
        RectF r = new RectF();
        for (int i = 0; i < 8; i++) {
            double x = at(t, a, now + i * Q + Q / 2);
            float l = x0 + i * bw + 4, rr = x0 + (i + 1) * bw - 4;
            if (Double.isNaN(x) || x < WET) {
                c.drawLine(l, y1, rr, y1, flat);
                continue;
            }
            double rate = x * 4;
            float top = y1 - (y1 - y0) * (float) Math.max(0.12, Math.min(1, rate / 4));
            bar.setColor(rate < 2.5 ? Color.rgb(91, 168, 242) : rate < 7.6 ? Color.rgb(60, 120, 255) : Color.rgb(150, 90, 255));
            r.set(l, top, rr, y1);
            c.drawRoundRect(r, 4, 4, bar);
        }
        return bmp;
    }
}
