package com.dmk.astrocompanion;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.Calendar;

/**
 * Tide from Open-Meteo's marine forecast (sea level height relative to mean sea level, hourly).
 * Places are points just offshore, because the marine model has no data over land.
 */
final class Tide {
    static final String[] NAMES = {"Mumbai", "Alibaug", "Murud", "Daman", "Ratnagiri", "Ganpatipule",
            "Malvan", "Goa", "Karwar", "Mangalore", "Kochi", "Chennai", "Puri", "Digha"};
    static final double[][] POS = {{18.90, 72.78}, {18.63, 72.83}, {18.32, 72.90}, {20.40, 72.78},
            {16.99, 73.24}, {17.14, 73.24}, {16.05, 73.43}, {15.48, 73.74}, {14.80, 74.06},
            {12.87, 74.78}, {9.95, 76.19}, {13.08, 80.32}, {19.78, 85.85}, {21.58, 87.55}};

    private Tide() {}

    /** Index of the chosen place: the setting, or the nearest one to (lat, lon). */
    static int place(SharedPreferences p, double lat, double lon) {
        int fixed = p.getInt("tideIdx", -1);
        if (fixed >= 0 && fixed < NAMES.length) return fixed;
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < POS.length; i++) {
            double dl = POS[i][0] - lat, dn = (POS[i][1] - lon) * Math.cos(Math.toRadians(lat));
            double dd = dl * dl + dn * dn;
            if (dd < bd) {
                bd = dd;
                best = i;
            }
        }
        return best;
    }

    /** Hourly series: times in epoch seconds and heights in metres. */
    static final class Series {
        final long[] t;
        final double[] h;

        Series(long[] t, double[] h) {
            this.t = t;
            this.h = h;
        }

        static Series load(SharedPreferences p) {
            String ts = p.getString("tideT", ""), hs = p.getString("tideH", "");
            if (ts.isEmpty() || hs.isEmpty()) return null;
            String[] a = ts.split(","), b = hs.split(",");
            int n = Math.min(a.length, b.length);
            if (n < 3) return null;
            long[] t = new long[n];
            double[] h = new double[n];
            for (int i = 0; i < n; i++) {
                t[i] = Long.parseLong(a[i]);
                h[i] = Double.parseDouble(b[i]);
            }
            return new Series(t, h);
        }

        /** Height at a time (seconds), linear between hours; NaN outside the data. */
        double at(long s) {
            if (s < t[0] || s > t[t.length - 1]) return Double.NaN;
            for (int i = 1; i < t.length; i++) {
                if (s <= t[i]) {
                    double f = (s - t[i - 1]) / (double) (t[i] - t[i - 1]);
                    return h[i - 1] + f * (h[i] - h[i - 1]);
                }
            }
            return h[h.length - 1];
        }

        /** Next turning point after now: {time s, height} or null. high = true for high water. */
        double[] next(long now, boolean high) {
            for (int i = 1; i < t.length - 1; i++) {
                boolean peak = high ? (h[i] >= h[i - 1] && h[i] > h[i + 1]) : (h[i] <= h[i - 1] && h[i] < h[i + 1]);
                if (!peak) continue;
                // parabola through the three hours for the exact time and height
                double a = h[i - 1], b = h[i], c = h[i + 1];
                double den = a - 2 * b + c;
                double off = den == 0 ? 0 : 0.5 * (a - c) / den; // in hours, -0.5..0.5
                long ts = t[i] + (long) (off * (t[i + 1] - t[i]));
                double hv = b - 0.25 * (a - c) * off;
                if (ts > now) return new double[]{ts, hv};
            }
            return null;
        }
    }

    /** The curve for today (00-24 h) as a picture sized for the face's tide panel. */
    static Bitmap graph(Series s, long nowMs) {
        final int w = 428, hgt = 64;
        Bitmap bmp = Bitmap.createBitmap(w, hgt, Bitmap.Config.ARGB_8888);
        if (s == null) return bmp;
        Canvas c = new Canvas(bmp);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long day0 = cal.getTimeInMillis() / 1000;
        // plot area (matches the face's grid)
        float x0 = 8, x1 = 420, y0 = 7, y1 = 57;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        int steps = 96;
        double[] v = new double[steps + 1];
        for (int i = 0; i <= steps; i++) {
            v[i] = s.at(day0 + i * 900L);
            if (!Double.isNaN(v[i])) {
                lo = Math.min(lo, v[i]);
                hi = Math.max(hi, v[i]);
            }
        }
        if (lo == Double.MAX_VALUE) return bmp;
        lo = Math.floor(lo * 2) / 2.0;
        hi = Math.ceil(hi * 2) / 2.0;
        if (hi - lo < 0.5) hi = lo + 0.5;
        Path line = new Path(), fill = new Path();
        boolean started = false;
        float lastX = x0;
        for (int i = 0; i <= steps; i++) {
            if (Double.isNaN(v[i])) continue;
            float x = x0 + (x1 - x0) * i / steps;
            float y = (float) (y1 - (v[i] - lo) / (hi - lo) * (y1 - y0));
            if (!started) {
                line.moveTo(x, y);
                fill.moveTo(x, y1);
                fill.lineTo(x, y);
                started = true;
            } else {
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
            lastX = x;
        }
        fill.lineTo(lastX, y1);
        fill.close();
        Paint pf = new Paint(Paint.ANTI_ALIAS_FLAG);
        pf.setShader(new LinearGradient(0, y0, 0, y1, Color.argb(110, 70, 140, 230), Color.argb(25, 70, 140, 230),
                Shader.TileMode.CLAMP));
        c.drawPath(fill, pf);
        Paint pl = new Paint(Paint.ANTI_ALIAS_FLAG);
        pl.setStyle(Paint.Style.STROKE);
        pl.setStrokeWidth(3f);
        pl.setColor(Color.rgb(91, 168, 242));
        c.drawPath(line, pl);
        // now marker
        double nowH = s.at(nowMs / 1000);
        if (!Double.isNaN(nowH)) {
            float x = x0 + (x1 - x0) * (float) ((nowMs / 1000 - day0) / 86400.0);
            float y = (float) (y1 - (nowH - lo) / (hi - lo) * (y1 - y0));
            Paint pv = new Paint(Paint.ANTI_ALIAS_FLAG);
            pv.setColor(Color.argb(120, 160, 190, 230));
            pv.setStrokeWidth(1.5f);
            c.drawLine(x, y0, x, y1, pv);
            Paint pd = new Paint(Paint.ANTI_ALIAS_FLAG);
            pd.setColor(Color.WHITE);
            c.drawCircle(x, y, 5.5f, pd);
        }
        return bmp;
    }

    static String fmt(double m) {
        return String.format(java.util.Locale.US, "%.1f", m);
    }
}
