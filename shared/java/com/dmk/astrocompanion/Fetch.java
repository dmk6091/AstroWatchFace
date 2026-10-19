package com.dmk.astrocompanion;

import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * All downloads, for the watch and the phone alike: weather (now, UV, 15-min rain, pressure),
 * air quality, tide, the ISS orbit and earthquakes. Writes what it gets into the preferences.
 */
final class Fetch {
    private static final String TAG = Store.TAG;

    private Fetch() {}

    /** Returns a short status line ("weather ok, air ok, ..."). Blocking. */
    static String all(SharedPreferences p, double lat, double lon, long now) {
        StringBuilder st = new StringBuilder();
        SharedPreferences.Editor e = p.edit();
        try {
            String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                    + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,"
                    + "wind_direction_10m,pressure_msl,surface_pressure,weather_code,is_day"
                    + "&daily=sunrise,sunset,precipitation_probability_max,uv_index_max&hourly=uv_index,pressure_msl"
                    + "&timezone=auto&timeformat=unixtime&forecast_days=2" + Rain.PARAMS, lat, lon);
            JSONObject j = new JSONObject(Net.get(url));
            try {
                Rain.save(j, e);
            } catch (Throwable t) {
                Log.w(TAG, "rain: " + t);
            }
            try {
                JSONObject hr = j.getJSONObject("hourly");
                JSONArray ht = hr.getJSONArray("time"), hu = hr.getJSONArray("uv_index");
                JSONArray hp = hr.optJSONArray("pressure_msl");
                StringBuilder ut = new StringBuilder(), uv = new StringBuilder(), pv = new StringBuilder();
                for (int i = 0; i < ht.length(); i++) {
                    if (hu.isNull(i)) continue;
                    if (ut.length() > 0) {
                        ut.append(',');
                        uv.append(',');
                        pv.append(',');
                    }
                    ut.append(ht.getLong(i));
                    uv.append(String.format(Locale.US, "%.1f", hu.getDouble(i)));
                    pv.append(hp == null || hp.isNull(i) ? "NaN" : String.format(Locale.US, "%.1f", hp.getDouble(i)));
                }
                e.putString("uvT", ut.toString());
                e.putString("uvV", uv.toString());
                e.putString("pV", pv.toString());   // sea-level pressure, same hours as uvT
            } catch (Throwable t) {
                Log.w(TAG, "uv: " + t);
            }
            JSONObject cur = j.getJSONObject("current");
            Store.putD(e, "feels", cur.optDouble("apparent_temperature", Double.NaN));
            Store.putD(e, "hum", cur.optDouble("relative_humidity_2m", Double.NaN));
            Store.putD(e, "wind", cur.optDouble("wind_speed_10m", Double.NaN));
            Store.putD(e, "windDir", cur.optDouble("wind_direction_10m", Double.NaN));
            Store.putD(e, "msl", cur.optDouble("pressure_msl", Double.NaN));
            Store.putD(e, "ground", j.optDouble("elevation", Double.NaN));
            Store.putD(e, "temp", cur.optDouble("temperature_2m", Double.NaN));
            e.putInt("wcode", cur.optInt("weather_code", -1));
            e.putBoolean("isDay", cur.optInt("is_day", 1) == 1);
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
            JSONArray pp = daily.optJSONArray("precipitation_probability_max"), um = daily.optJSONArray("uv_index_max");
            Store.putD(e, "rainDay", pp == null || pp.isNull(0) ? Double.NaN : pp.getDouble(0));
            Store.putD(e, "uvMax", um == null || um.isNull(0) ? Double.NaN : um.getDouble(0));
            e.putLong("wTime", now);
            st.append("weather ok");
        } catch (Throwable t) {
            Log.w(TAG, "weather: " + t);
            st.append("weather failed: ").append(t.getMessage());
        }
        try {
            String url = String.format(Locale.US, "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.4f"
                    + "&longitude=%.4f&current=us_aqi&hourly=us_aqi,pm2_5,pm10&past_hours=6&forecast_hours=19"
                    + "&timeformat=unixtime", lat, lon);
            JSONObject aj = new JSONObject(Net.get(url));
            JSONObject cur = aj.getJSONObject("current");
            Store.putD(e, "aqi", cur.optDouble("us_aqi", Double.NaN));
            try {
                Topics.save(e, "aq", aj.getJSONObject("hourly"), "us_aqi", "pm2_5", "pm10");
            } catch (Throwable t) {
                Log.w(TAG, "air hourly: " + t);
            }
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
        try {
            String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                    + Topics.PARAMS_WEATHER, lat, lon);
            Topics.save(e, "tw", new JSONObject(Net.get(url)).getJSONObject("hourly"),
                    "wind_speed_10m", "wind_gusts_10m", "wind_direction_10m", "pressure_msl");
        } catch (Throwable t) {
            Log.w(TAG, "wind: " + t);
            st.append(", wind failed");
        }
        try {
            int idx = Tide.place(p, lat, lon);
            String url = String.format(Locale.US, "https://marine-api.open-meteo.com/v1/marine?latitude=%.3f&longitude=%.3f"
                    + "&hourly=wave_height,wave_period,wave_direction&timeformat=unixtime&past_days=1&forecast_days=2",
                    Tide.POS[idx][0], Tide.POS[idx][1]);
            Topics.save(e, "sw", new JSONObject(Net.get(url)).getJSONObject("hourly"),
                    "wave_height", "wave_period", "wave_direction");
        } catch (Throwable t) {
            Log.w(TAG, "waves: " + t);
            st.append(", waves failed");
        }
        st.append(", ").append(Iss.refresh(p, e));
        st.append(", ").append(Quakes.refresh(e));
        try {
            JSONArray kp = new JSONArray(Net.get("https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json"));
            double k = Double.NaN;
            for (int i = kp.length() - 1; i >= 0 && Double.isNaN(k); i--) {
                Object row = kp.get(i);
                if (row instanceof JSONObject) k = ((JSONObject) row).optDouble("Kp", Double.NaN);
                else if (row instanceof JSONArray) {
                    try {
                        k = Double.parseDouble(((JSONArray) row).getString(1));
                    } catch (Exception ignored) {
                    }
                }
            }
            Store.putD(e, "kp", k);
            e.putLong("kpTime", now);
            st.append(", kp ").append(Double.isNaN(k) ? "?" : String.format(Locale.US, "%.0f", k));
        } catch (Throwable t) {
            Log.w(TAG, "kp: " + t);
            st.append(", kp failed");
        }
        e.apply();
        return st.toString();
    }
}
