package com.dmk.astrocompanion;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Earthquakes of magnitude 4.5 and more in the last 24 hours, from the USGS feed (free, no key),
 * fetched with the weather every ~30 min. Drawn as rings on the globe and listed in the
 * bottom panel's "quakes" screen.
 */
final class Quakes {
    static final String URL = "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/4.5_day.geojson";
    private static final double RAD = Math.PI / 180;

    private Quakes() {}

    static final class Q {
        final double lat, lon, mag;
        final long time;
        final String place;

        Q(double lat, double lon, double mag, long time, String place) {
            this.lat = lat;
            this.lon = lon;
            this.mag = mag;
            this.time = time;
            this.place = place;
        }
    }

    /** Downloads the feed and keeps a compact copy. Returns a status word. */
    static String refresh(SharedPreferences.Editor e) {
        try {
            JSONArray fs = new JSONObject(Net.get(URL)).getJSONArray("features");
            StringBuilder b = new StringBuilder();
            int n = 0;
            for (int i = 0; i < fs.length() && n < 60; i++) {
                JSONObject f = fs.getJSONObject(i);
                JSONObject pr = f.getJSONObject("properties");
                JSONArray c = f.getJSONObject("geometry").getJSONArray("coordinates");
                if (pr.isNull("mag")) continue;
                String place = pr.optString("place", "").replace(';', ',').replace('|', ' ');
                if (b.length() > 0) b.append(';');
                b.append(String.format(Locale.US, "%.3f|%.3f|%.1f|%d|%s", c.getDouble(1), c.getDouble(0),
                        pr.getDouble("mag"), pr.getLong("time"), place));
                n++;
            }
            e.putString("quakes", b.toString());
            e.putLong("qTime", System.currentTimeMillis());
            return "quakes ok (" + n + ")";
        } catch (Throwable t) {
            Log.w(Store.TAG, "quakes: " + t);
            return "quakes failed";
        }
    }

    /** The last 24 hours' quakes (older ones dropped), newest first as in the feed. */
    static List<Q> load(SharedPreferences p, long now) {
        List<Q> out = new ArrayList<>();
        for (String s : p.getString("quakes", "").split(";")) {
            String[] a = s.split("\\|", 5);
            if (a.length < 5) continue;
            try {
                long t = Long.parseLong(a[3]);
                if (now - t > 24 * 3600_000L || t > now + 600_000L) continue;
                out.add(new Q(Double.parseDouble(a[0]), Double.parseDouble(a[1]), Double.parseDouble(a[2]), t, a[4]));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    static double km(double lat, double lon, double lat0, double lon0) {
        double p = lat * RAD, p0 = lat0 * RAD;
        double c = Math.sin(p0) * Math.sin(p) + Math.cos(p0) * Math.cos(p) * Math.cos((lon - lon0) * RAD);
        return 6371 * Math.acos(Math.max(-1, Math.min(1, c)));
    }

    /** "51 km E of Namie, Japan" -> "NAMIE, JAPAN", or just "JAPAN" when that is too long. */
    static String shortPlace(String place, int max) {
        String s = place.trim();
        int of = s.indexOf(" of ");
        if (of >= 0) s = s.substring(of + 4);           // "51 km E of ..." / "south of the ..."
        if (s.startsWith("the ")) s = s.substring(4);
        // plain letters only (the face finds its screen by the text's length: no combining accents)
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^\\x20-\\x7E]", "");
        s = s.toUpperCase(Locale.US);
        if (s.length() > max) {
            int comma = s.lastIndexOf(", ");
            if (comma >= 0) s = s.substring(comma + 2);
        }
        if (s.length() > max) s = s.substring(0, max);
        return s;
    }

    static String ago(long ms) {
        long m = Math.max(0, ms / 60_000);
        return m < 60 ? m + "m ago" : (m / 60) + "h ago";
    }

    /** Values for the quake screen of the bottom panel. */
    static void values(SharedPreferences p, Map<String, String> v, long now, double lat0, double lon0) {
        List<Q> qs = load(p, now);
        if (qs.isEmpty()) {
            v.put("qmag", "");
            v.put("qplace", p.contains("quakes") ? "NONE IN 24 H" : "NO DATA YET");
            v.put("qago", "");
            v.put("qcnt", "");
            v.put("qnear", "");
            return;
        }
        Q big = qs.get(0), near = qs.get(0);
        for (Q q : qs) {
            if (q.mag > big.mag || (q.mag == big.mag && q.time > big.time)) big = q;
            if (km(q.lat, q.lon, lat0, lon0) < km(near.lat, near.lon, lat0, lon0)) near = q;
        }
        v.put("qmag", String.format(Locale.US, "M%.1f", big.mag));
        v.put("qplace", shortPlace(big.place, 16));
        v.put("qago", ago(now - big.time));
        v.put("qcnt", qs.size() + " IN 24 H");
        long d = Math.round(km(near.lat, near.lon, lat0, lon0) / 10.0) * 10;
        v.put("qnear", String.format(Locale.US, "NEAR %,d km", d));
    }

    static int color(double mag, int alpha) {
        if (mag >= 5.5) return Color.argb(alpha, 255, 84, 64);
        if (mag >= 5.0) return Color.argb(alpha, 255, 150, 110);
        return Color.argb(alpha, 255, 190, 150);
    }

    /** Timeline for the panel (428 x 64, same plot area as the tide curve): one tick per quake. */
    static Bitmap graph(SharedPreferences p, long now) {
        final int w = 428, h = 64;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        float x0 = 8, x1 = 420, y0 = 7, y1 = 57;
        Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
        axis.setColor(Color.argb(180, 120, 140, 175));
        axis.setStrokeWidth(2);
        c.drawLine(x0, y1, x1, y1, axis);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setStrokeCap(Paint.Cap.ROUND);
        pt.setStrokeWidth(4.5f);
        for (Q q : load(p, now)) {
            float x = x1 - (x1 - x0) * (float) ((now - q.time) / (24 * 3600_000.0));
            float top = y1 - (y1 - y0) * (float) Math.max(0.12, Math.min(1, (q.mag - 4) / 3));
            pt.setColor(color(q.mag, 255));
            c.drawLine(x, y1, x, top, pt);
            c.drawCircle(x, top, 4, pt);
        }
        return bmp;
    }
}
