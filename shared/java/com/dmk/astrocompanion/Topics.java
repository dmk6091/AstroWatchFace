package com.dmk.astrocompanion;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * The bottom panel's four "topic" screens: wind, pressure trend, sea state and air quality.
 * Each is hourly data from Open-Meteo kept in the preferences: 6 h back to 18 h ahead, which is
 * also what the graph shows (now at a quarter of the width). All four use the same text fields
 * on the face (xh, xl, xa, xb), filled for whichever screen is showing.
 */
final class Topics {
    static final int WIND = 3, PRESSURE = 4, SEA = 5, AIR = 6;
    static final String PARAMS_WEATHER = "&hourly=wind_speed_10m,wind_gusts_10m,wind_direction_10m,pressure_msl"
            + "&past_hours=6&forecast_hours=19&timeformat=unixtime";
    private static final long H = 3600_000L;
    private static final int BACK = 6, AHEAD = 18;

    private Topics() {}

    // ------------------------------------------------------------ storage

    /** Keeps the hourly arrays named in `names` under the prefix: pre+"T" for the times, pre+name for each. */
    static void save(SharedPreferences.Editor e, String pre, JSONObject hourly, String... names) throws Exception {
        JSONArray t = hourly.getJSONArray("time");
        StringBuilder tb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            if (i > 0) tb.append(',');
            tb.append(t.getLong(i));
        }
        e.putString(pre + "T", tb.toString());
        for (String n : names) {
            JSONArray a = hourly.optJSONArray(n);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < t.length(); i++) {
                if (i > 0) sb.append(',');
                sb.append(a == null || i >= a.length() || a.isNull(i) ? "NaN" : String.format(Locale.US, "%.2f", a.getDouble(i)));
            }
            e.putString(pre + n, sb.toString());
        }
    }

    private static double[] nums(SharedPreferences p, String key) {
        String s = p.getString(key, "");
        if (s.isEmpty()) return new double[0];
        String[] a = s.split(",");
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            try {
                out[i] = Double.parseDouble(a[i].trim());
            } catch (Exception ex) {
                out[i] = Double.NaN;
            }
        }
        return out;
    }

    /** One hourly series: times in seconds and values. */
    private static final class S {
        final double[] t, v;

        S(SharedPreferences p, String pre, String name) {
            t = nums(p, pre + "T");
            double[] x = nums(p, pre + name);
            v = x.length == t.length ? x : new double[0];
        }

        boolean ok() {
            return v.length > 1;
        }

        /** Value at ms, linear between the hours; NaN outside the data or where a value is missing. */
        double at(long ms) {
            double s = ms / 1000.0;
            for (int i = 0; i + 1 < t.length && i + 1 < v.length; i++) {
                if (s >= t[i] && s <= t[i + 1]) {
                    double a = v[i], b = v[i + 1];
                    if (Double.isNaN(a)) return b;
                    if (Double.isNaN(b)) return a;
                    return a + (b - a) * (s - t[i]) / (t[i + 1] - t[i]);
                }
            }
            return Double.NaN;
        }
    }

    // ------------------------------------------------------------ words

    private static String hm(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms));
    }

    private static final double[] BF = {1, 6, 12, 20, 29, 39, 50, 62, 75, 89, 103, 118};
    private static final String[] BF_NAME = {"CALM", "LIGHT AIR", "LIGHT BREEZE", "GENTLE BREEZE", "MODERATE BREEZE",
            "FRESH BREEZE", "STRONG BREEZE", "NEAR GALE", "GALE", "STRONG GALE", "STORM", "VIOLENT STORM", "HURRICANE"};

    static int beaufort(double kmh) {
        int b = 0;
        while (b < BF.length && kmh >= BF[b]) b++;
        return b;
    }

    private static String sea(double h) {
        return h < 0.1 ? "GLASSY" : h < 0.5 ? "CALM" : h < 1.25 ? "SMOOTH" : h < 2.5 ? "SLIGHT" : h < 4 ? "MODERATE"
                : h < 6 ? "ROUGH" : h < 9 ? "VERY ROUGH" : "HIGH SEAS";
    }

    private static String aqiWord(double a) {
        return a <= 50 ? "GOOD" : a <= 100 ? "MODERATE" : a <= 150 ? "SENSITIVE" : a <= 200 ? "UNHEALTHY"
                : a <= 300 ? "VERY POOR" : "HAZARDOUS";
    }

    private static String signed(double d) {
        return (d >= 0 ? "+" : "-") + String.format(Locale.US, "%.1f", Math.abs(d));
    }

    // ------------------------------------------------------------ text

    /** Fills xh / xl / xa / xb for the topic screen showing (nothing for the other screens). */
    static void values(SharedPreferences p, Map<String, String> v, long now, int panel) {
        v.put("xh", "");
        v.put("xl", "");
        v.put("xa", "");
        v.put("xb", "");
        if (panel == WIND) {
            S sp = new S(p, "tw", "wind_speed_10m"), g = new S(p, "tw", "wind_gusts_10m"), d = new S(p, "tw", "wind_direction_10m");
            double now_ = sp.at(now);
            if (!sp.ok() || Double.isNaN(now_)) {
                v.put("xh", "WIND: NO DATA");
                return;
            }
            double dir = d.at(now), gust = g.at(now);
            v.put("xh", Math.round(now_) + " km/h" + (Double.isNaN(dir) ? "" : " FROM " + Astro.dir8(dir)));
            if (!Double.isNaN(gust)) v.put("xl", "GUSTS " + Math.round(gust));
            v.put("xa", BF_NAME[beaufort(now_)]);
            double max = -1;
            long at = now;
            for (long m = now; m <= now + AHEAD * H; m += H) {
                double x = sp.at(m);
                if (!Double.isNaN(x) && x > max) {
                    max = x;
                    at = m;
                }
            }
            if (max >= 0) v.put("xb", "MAX " + Math.round(max) + " AT " + hm(at));
        } else if (panel == PRESSURE) {
            S s = new S(p, "tw", "pressure_msl");
            double n = s.at(now);
            if (!s.ok() || Double.isNaN(n)) {
                v.put("xh", "PRESSURE: NO DATA");
                return;
            }
            double d3 = n - s.at(now - 3 * H), d12 = s.at(now + 12 * H) - n;
            if (Double.isNaN(d3)) d3 = 0;
            String word = d3 <= -1 ? "FALLING" : d3 >= 1 ? "RISING" : "STEADY";
            v.put("xh", Math.round(n) + " hPa " + word);
            v.put("xl", signed(d3) + " IN 3H");
            double f = Double.isNaN(d12) ? d3 : d12;
            v.put("xa", f <= -6 ? "STORMY SPELL" : f <= -2 ? "UNSETTLED SOON" : f >= 3 ? "FAIR AND DRY" : f >= 1 ? "CLEARING UP" : "NO BIG CHANGE");
            if (!Double.isNaN(d12)) v.put("xb", "IN 12 H " + signed(d12));
        } else if (panel == SEA) {
            S h = new S(p, "sw", "wave_height"), per = new S(p, "sw", "wave_period"), dr = new S(p, "sw", "wave_direction");
            double n = h.at(now);
            if (!h.ok() || Double.isNaN(n)) {
                v.put("xh", "SEA: NO DATA");
                return;
            }
            v.put("xh", String.format(Locale.US, "WAVES %.1f m", n));
            double pe = per.at(now), di = dr.at(now);
            if (!Double.isNaN(pe)) v.put("xl", "PERIOD " + Math.round(pe) + " s");
            v.put("xa", sea(n));
            int idx = p.getInt("tidePlace", -1);
            String place = idx >= 0 && idx < Tide.NAMES.length ? Tide.NAMES[idx].toUpperCase(Locale.US) : "SEA";
            if (place.length() > 8) place = place.substring(0, 8);
            v.put("xb", place + (Double.isNaN(di) ? "" : " · " + Astro.dir8(di)));
        } else if (panel == AIR) {
            S a = new S(p, "aq", "us_aqi"), p25 = new S(p, "aq", "pm2_5"), p10 = new S(p, "aq", "pm10");
            double n = a.at(now);
            if (!a.ok() || Double.isNaN(n)) {
                v.put("xh", "AIR: NO DATA");
                return;
            }
            v.put("xh", "AQI " + Math.round(n));
            v.put("xl", aqiWord(n));
            double x = p25.at(now), y = p10.at(now);
            if (!Double.isNaN(x)) v.put("xa", "PM2.5 " + Math.round(x));
            if (!Double.isNaN(y)) v.put("xb", "PM10 " + Math.round(y));
        }
    }

    // ------------------------------------------------------------ picture

    /** The graph for a topic screen (428 x 64, same plot area as the other screens). */
    static Bitmap graph(SharedPreferences p, long now, int panel) {
        final int w = 428, h = 64;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float x0 = 8, x1 = 420, y0 = 7, y1 = 57;
        S s;
        if (panel == WIND) s = new S(p, "tw", "wind_speed_10m");
        else if (panel == PRESSURE) s = new S(p, "tw", "pressure_msl");
        else if (panel == SEA) s = new S(p, "sw", "wave_height");
        else s = new S(p, "aq", "us_aqi");
        if (!s.ok()) return bmp;
        int n = BACK + AHEAD + 1;
        double[] val = new double[n];
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            val[i] = s.at(now + (i - BACK) * H);
            if (!Double.isNaN(val[i])) {
                lo = Math.min(lo, val[i]);
                hi = Math.max(hi, val[i]);
            }
        }
        if (lo > hi) return bmp;
        boolean line = panel == PRESSURE || panel == SEA;
        if (panel == PRESSURE) {
            double mid = (lo + hi) / 2, half = Math.max(2.5, (hi - lo) / 2 * 1.1);
            lo = mid - half;
            hi = mid + half;
        } else {
            lo = 0;
            hi = panel == WIND ? Math.max(20, hi * 1.1) : panel == SEA ? Math.max(1, hi * 1.15) : Math.max(150, hi * 1.1);
        }
        float step = (x1 - x0) / (n - 1);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (line) {
            int col = panel == PRESSURE ? Color.rgb(255, 184, 107) : Color.rgb(89, 168, 255);
            Path ln = new Path(), area = new Path();
            boolean started = false;
            float lastX = x0;
            for (int i = 0; i < n; i++) {
                if (Double.isNaN(val[i])) continue;
                float x = x0 + i * step, y = (float) (y1 - (y1 - y0) * (val[i] - lo) / (hi - lo));
                if (!started) {
                    ln.moveTo(x, y);
                    area.moveTo(x, y1);
                    area.lineTo(x, y);
                    started = true;
                } else {
                    ln.lineTo(x, y);
                    area.lineTo(x, y);
                }
                lastX = x;
            }
            area.lineTo(lastX, y1);
            area.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(60, Color.red(col), Color.green(col), Color.blue(col)));
            c.drawPath(area, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3.5f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(col);
            c.drawPath(ln, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            RectF r = new RectF();
            float bw = (x1 - x0) / n;
            for (int i = 0; i < n; i++) {
                if (Double.isNaN(val[i])) continue;
                float l = x0 + i * bw + 1.5f, rr = x0 + (i + 1) * bw - 1.5f;
                float top = y1 - (y1 - y0) * (float) Math.max(0.06, Math.min(1, (val[i] - lo) / (hi - lo)));
                if (panel == WIND) paint.setColor(Color.rgb(94, 224, 200));
                else paint.setColor(val[i] <= 100 ? Color.rgb(124, 194, 74) : val[i] <= 150 ? Color.rgb(255, 210, 60)
                        : val[i] <= 200 ? Color.rgb(255, 138, 60) : Color.rgb(255, 84, 64));
                r.set(l, top, rr, y1);
                c.drawRoundRect(r, 2.5f, 2.5f, paint);
            }
        }
        // now
        float nx = x0 + BACK * step;
        Paint m = new Paint(Paint.ANTI_ALIAS_FLAG);
        m.setColor(Color.argb(210, 255, 255, 255));
        m.setStrokeWidth(2);
        m.setStyle(Paint.Style.STROKE);
        m.setPathEffect(new android.graphics.DashPathEffect(new float[]{4, 5}, 0));
        c.drawLine(nx, 2, nx, y1 + 3, m);
        return bmp;
    }
}
