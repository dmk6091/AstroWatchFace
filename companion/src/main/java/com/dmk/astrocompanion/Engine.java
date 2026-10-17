package com.dmk.astrocompanion;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.util.Log;

import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Fetches weather + tide (every ~30 min) and builds the values for the face. */
final class Engine {
    static final String TAG = "AstroCompanion";

    private Engine() {}

    // ------------------------------------------------------------ location

    /** Pune, used until the watch reports a real position (marked as approximate). */
    static final double FALLBACK_LAT = 18.62, FALLBACK_LON = 73.80;

    /**
     * Best location of the watch, saved so later runs (even without permission) have one.
     * Order: Google's fused location (comes from the phone on Wear OS), then the watch's own
     * providers. Blocking (up to ~20 s): never call on the main thread.
     */
    static void updateLocation(Context ctx) {
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

    // ------------------------------------------------------------ network

    /** Weather details and tide from Open-Meteo. Returns a short status line. */
    static String refresh(Context ctx, boolean force) {
        updateLocation(ctx);
        SharedPreferences p = Store.p(ctx);
        if (!Store.hasLocation(p)) return "no location yet: open Astro once and allow location";
        long now = System.currentTimeMillis();
        if (!force && now - p.getLong("wTime", 0) < 25 * 60_000L) return "fresh";
        double lat = Store.d(p, "lat", 0), lon = Store.d(p, "lon", 0);
        StringBuilder st = new StringBuilder();
        SharedPreferences.Editor e = p.edit();
        try {
            String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                    + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,"
                    + "wind_direction_10m,pressure_msl,surface_pressure&daily=sunrise,sunset"
                    + "&timezone=auto&timeformat=unixtime&forecast_days=2" + Rain.PARAMS, lat, lon);
            JSONObject j = new JSONObject(Net.get(url));
            try {
                Rain.save(j, e);
            } catch (Throwable t) {
                Log.w(TAG, "rain: " + t);
            }
            JSONObject cur = j.getJSONObject("current");
            Store.putD(e, "feels", cur.optDouble("apparent_temperature", Double.NaN));
            Store.putD(e, "hum", cur.optDouble("relative_humidity_2m", Double.NaN));
            Store.putD(e, "wind", cur.optDouble("wind_speed_10m", Double.NaN));
            Store.putD(e, "windDir", cur.optDouble("wind_direction_10m", Double.NaN));
            Store.putD(e, "msl", cur.optDouble("pressure_msl", Double.NaN));
            Store.putD(e, "ground", j.optDouble("elevation", Double.NaN));
            JSONObject daily = j.getJSONObject("daily");
            JSONArray rise = daily.getJSONArray("sunrise"), set = daily.getJSONArray("sunset");
            StringBuilder r = new StringBuilder(), s = new StringBuilder();
            for (int i = 0; i < rise.length(); i++) {
                if (i > 0) {
                    r.append(',');
                    s.append(',');
                }
                r.append(rise.getLong(i));
                s.append(set.getLong(i));
            }
            e.putString("sunrise", r.toString());
            e.putString("sunset", s.toString());
            e.putLong("wTime", now);
            st.append("weather ok");
        } catch (Throwable t) {
            Log.w(TAG, "weather: " + t);
            st.append("weather failed: ").append(t.getMessage());
        }
        try {
            String url = String.format(Locale.US, "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.4f"
                    + "&longitude=%.4f&current=us_aqi", lat, lon);
            JSONObject cur = new JSONObject(Net.get(url)).getJSONObject("current");
            Store.putD(e, "aqi", cur.optDouble("us_aqi", Double.NaN));
            st.append(", air ok");
        } catch (Throwable t) {
            Log.w(TAG, "air: " + t);
            st.append(", air failed");
        }
        try {
            int idx = Tide.place(p, lat, lon);
            String url = String.format(Locale.US, "https://marine-api.open-meteo.com/v1/marine?latitude=%.3f&longitude=%.3f"
                    + "&hourly=sea_level_height_msl&timezone=auto&timeformat=unixtime&past_days=1&forecast_days=3",
                    Tide.POS[idx][0], Tide.POS[idx][1]);
            JSONObject hourly = new JSONObject(Net.get(url)).getJSONObject("hourly");
            JSONArray tt = hourly.getJSONArray("time"), hh = hourly.getJSONArray("sea_level_height_msl");
            StringBuilder a = new StringBuilder(), b = new StringBuilder();
            for (int i = 0; i < tt.length(); i++) {
                if (hh.isNull(i)) continue;
                if (a.length() > 0) {
                    a.append(',');
                    b.append(',');
                }
                a.append(tt.getLong(i));
                b.append(String.format(Locale.US, "%.3f", hh.getDouble(i)));
            }
            e.putString("tideT", a.toString());
            e.putString("tideH", b.toString());
            e.putInt("tidePlace", idx);
            st.append(", tide ok (").append(Tide.NAMES[idx]).append(')');
        } catch (Throwable t) {
            Log.w(TAG, "tide: " + t);
            st.append(", tide failed: ").append(t.getMessage());
        }
        st.append(", ").append(Iss.refresh(p, e));
        st.append(", ").append(Quakes.refresh(e));
        e.putString("status", new SimpleDateFormat("HH:mm", Locale.US).format(new Date(now)) + " " + st);
        e.apply();
        pushUpdates(ctx);
        return st.toString();
    }

    static void pushUpdates(Context ctx) {
        try {
            ComplicationDataSourceUpdateRequester.create(ctx, new ComponentName(ctx, DataService.class)).requestUpdateAll();
            ComplicationDataSourceUpdateRequester.create(ctx, new ComponentName(ctx, TideGraphService.class)).requestUpdateAll();
            ComplicationDataSourceUpdateRequester.create(ctx, new ComponentName(ctx, GlobeService.class)).requestUpdateAll();
        } catch (Throwable t) {
            Log.w(TAG, "update request: " + t);
        }
    }

    // ------------------------------------------------------------ values for the face

    private static String hm(long ms) {
        return ms <= 0 ? "--:--" : new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms));
    }

    private static long todayFrom(String csv, long day0, long day1) {
        if (csv == null || csv.isEmpty()) return -1;
        for (String s : csv.split(",")) {
            long ms = Long.parseLong(s.trim()) * 1000;
            if (ms >= day0 && ms < day1) return ms;
        }
        return -1;
    }

    /** Today's sunrise and sunset (epoch ms), -1 when unknown. */
    static long[] sunTimes(SharedPreferences p, long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long day0 = cal.getTimeInMillis(), day1 = day0 + 86_400_000L;
        return new long[]{todayFrom(p.getString("sunrise", ""), day0, day1), todayFrom(p.getString("sunset", ""), day0, day1)};
    }

    /** All values, read fresh (sensors) where they change by the minute. Blocks ~1-3 s. */
    static Map<String, String> values(Context ctx) {
        Map<String, String> v = new HashMap<>();
        SharedPreferences p = Store.p(ctx);
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long day0 = cal.getTimeInMillis(), day1 = day0 + 86_400_000L;
        boolean loc = Store.hasLocation(p);
        double lat = Store.d(p, "lat", 0), lon = Store.d(p, "lon", 0);

        // ISS: next pass you can see from here
        if (loc) {
            try {
                String iss = Iss.faceText(p, lat, lon, now);
                if (iss != null) v.put("iss", iss);
            } catch (Throwable t) {
                Log.w(TAG, "iss pass: " + t);
            }
        }

        // sun and moon
        if (loc) {
            long[] mt = Astro.moonTimes(day0, lat, lon);
            v.put("mrise", hm(mt[0]));
            v.put("mset", hm(mt[1]));
            double az = Astro.sunAzimuth(now, lat, lon);
            v.put("az", Math.round(az) + "° " + Astro.dir8(az));
        }
        long sr = todayFrom(p.getString("sunrise", ""), day0, day1);
        long ss = todayFrom(p.getString("sunset", ""), day0, day1);
        v.put("srise", hm(sr));
        v.put("sset", hm(ss));
        if (sr > 0 && ss > sr) {
            long min = (ss - sr) / 60_000;
            v.put("dlen", (min / 60) + "h " + String.format(Locale.US, "%02d", min % 60) + "m");
        }
        double feels = Store.d(p, "feels", Double.NaN);
        if (!Double.isNaN(feels)) v.put("feels", Math.round(feels) + "°");
        double hum = Store.d(p, "hum", Double.NaN);
        if (!Double.isNaN(hum)) v.put("hum", Math.round(hum) + "%");
        double aqi = Store.d(p, "aqi", Double.NaN);
        if (!Double.isNaN(aqi)) v.put("aqi", String.valueOf(Math.round(aqi)));
        v.put("mage", String.format(Locale.US, "%.1f d", Astro.moonAge(now)));

        // pressure, elevation (today's max / min / climb), trend over ~3 h
        double hpa = Sensors.pressure(ctx);
        double msl = Store.d(p, "msl", Double.NaN);
        double alt = Double.NaN;
        if (!Double.isNaN(hpa)) {
            double p0 = Double.isNaN(msl) || now - p.getLong("wTime", 0) > 6 * 3600_000L ? 1013.25 : msl;
            alt = 44330.0 * (1 - Math.pow(hpa / p0, 0.1903));
            v.put("baro", String.valueOf(Math.round(hpa)));
            String trend = trend(p, now, hpa);
            if (trend != null) v.put("btrend", trend);
        } else {
            alt = Store.d(p, "ground", Double.NaN);
        }
        if (!Double.isNaN(alt)) {
            SharedPreferences.Editor e = p.edit();
            long dayKey = day0;
            double mx = Store.d(p, "eMax", alt), mn = Store.d(p, "eMin", alt), gain = Store.d(p, "eGain", 0);
            double last = Store.d(p, "eLast", alt);
            if (p.getLong("eDay", 0) != dayKey) {
                mx = alt;
                mn = alt;
                gain = 0;
                last = alt;
            }
            mx = Math.max(mx, alt);
            mn = Math.min(mn, alt);
            if (alt - last >= 3) {      // ignore barometer noise below 3 m
                gain += alt - last;
                last = alt;
            } else if (last - alt >= 3) {
                last = alt;
            }
            e.putLong("eDay", dayKey);
            Store.putD(e, "eMax", mx);
            Store.putD(e, "eMin", mn);
            Store.putD(e, "eGain", gain);
            Store.putD(e, "eLast", last);
            e.apply();
            v.put("elev", String.valueOf(Math.round(alt)));
            v.put("alt", String.valueOf(Math.round(alt)));
            v.put("gain", "+" + Math.round(gain));
            v.put("emax", String.valueOf(Math.round(mx)));
            v.put("emin", String.valueOf(Math.round(mn)));
        }

        // compass
        double head = Sensors.heading(ctx);
        if (!Double.isNaN(head)) {
            v.put("head", Math.round(head) % 360 + "°");
            v.put("hdir", Astro.dir8(head));
        }

        // bottom panel: which screen, and the quake / rain screens' text
        v.put("panel", String.valueOf(Panel.checkAuto(ctx, p, now)));
        Quakes.values(p, v, now, loc ? lat : FALLBACK_LAT, loc ? lon : FALLBACK_LON);
        Rain.values(p, v, now);

        // tide
        Tide.Series s = Tide.Series.load(p);
        int idx = p.getInt("tidePlace", -1);
        if (idx >= 0 && idx < Tide.NAMES.length) v.put("tplace", Tide.NAMES[idx].toUpperCase(Locale.US));
        if (s != null) {
            long ns = now / 1000;
            double h = s.at(ns), h2 = s.at(ns + 1800);
            if (!Double.isNaN(h)) {
                v.put("tnow", Tide.fmt(h));
                if (!Double.isNaN(h2)) v.put("tstate", h2 >= h ? "RISING" : "FALLING");
            }
            double[] hi = s.next(ns, true), lo = s.next(ns, false);
            if (hi != null) {
                v.put("hit", hm((long) (hi[0] * 1000)));
                v.put("hih", Tide.fmt(hi[1]));
            }
            if (lo != null) {
                v.put("lot", hm((long) (lo[0] * 1000)));
                v.put("loh", Tide.fmt(lo[1]));
            }
        }
        return v;
    }

    /** Pressure change over about 3 hours as "+1.2" / "-0.8", from samples kept every 10 min. */
    private static String trend(SharedPreferences p, long now, double hpa) {
        String hist = p.getString("pHist", "");
        StringBuilder keep = new StringBuilder();
        double old = Double.NaN;
        long lastT = 0;
        for (String part : hist.split(";")) {
            if (part.isEmpty()) continue;
            String[] kv = part.split(":");
            if (kv.length != 2) continue;
            long t = Long.parseLong(kv[0]);
            if (now - t > 4 * 3600_000L) continue;
            if (keep.length() > 0) keep.append(';');
            keep.append(part);
            lastT = Math.max(lastT, t);
            if (now - t >= 2.5 * 3600_000L && Double.isNaN(old)) old = Double.parseDouble(kv[1]);
        }
        if (now - lastT >= 10 * 60_000L) {
            if (keep.length() > 0) keep.append(';');
            keep.append(now).append(':').append(String.format(Locale.US, "%.1f", hpa));
        }
        p.edit().putString("pHist", keep.toString()).apply();
        if (Double.isNaN(old)) return null;
        double d = hpa - old;
        return (d >= 0 ? "+" : "") + String.format(Locale.US, "%.1f", d);
    }

    /** Fixed-width text the face cuts apart (see FieldSpec, generated with the face). */
    static String pack(Map<String, String> v) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < FieldSpec.NAMES.length; i++) {
            String s = v.get(FieldSpec.NAMES[i]);
            if (s == null) s = "--";
            int w = FieldSpec.WIDTHS[i];
            if (s.length() > w) s = s.substring(0, w);
            int pad = w - s.length();
            char a = FieldSpec.ALIGN.charAt(i);
            int left = a == 'R' ? pad : a == 'C' ? pad / 2 : 0;
            for (int k = 0; k < left; k++) out.append(' ');
            out.append(s);
            for (int k = 0; k < pad - left; k++) out.append(' ');
        }
        // which bottom-panel screen: told by the text's length (1, 2 or 3 extra marks), since the
        // face can compare numbers but not words
        int panel = 0;
        try {
            panel = Integer.parseInt(v.get("panel"));
        } catch (Exception ignored) {
        }
        for (int k = 0; k <= panel; k++) out.append('#');
        return out.toString();
    }
}
