package com.dmk.astrocompanion;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Astro on the phone. Tab 1 "Sky & Earth": the globe and everything the watch face shows, big.
 * Tab 2 "Watch": the watch's status and its settings, refresh, and updates (sent over Wear OS).
 */
public class MainActivity extends Activity {
    private static final double RAD = Math.PI / 180;

    private Ui ui;
    private ScrollView scroll;
    private LinearLayout content, tabs;
    private int tab = 0;
    private volatile boolean busy, again;
    private volatile Bitmap globe;
    private volatile long globeAt;

    // watch link
    private volatile List<Node> nodes = new ArrayList<>();
    private volatile DataMap watch;
    private volatile String linkNote = "";
    private volatile String sendNote = "";
    private volatile String phoneNote = "";
    private volatile long latest = -1;
    private int padTop, padBottom;

    private final DataClient.OnDataChangedListener dataListener = events -> {
        for (DataEvent ev : events) {
            DataItem it = ev.getDataItem();
            if (ev.getType() == DataEvent.TYPE_CHANGED && Link.STATUS.equals(it.getUri().getPath())) {
                watch = DataMapItem.fromDataItem(it).getDataMap();
            }
        }
        runOnUiThread(() -> {
            if (tab == 1) render();
        });
    };

    // ------------------------------------------------------------ life cycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
        getWindow().getDecorView().setBackgroundColor(Ui.BG);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(0xF20A1120);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(tabs, tl);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars());
            padTop = bars.top;
            padBottom = bars.bottom;
            buildTabs();
            render();
            return in;
        });
        buildTabs();
        render();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            Wearable.getDataClient(this).addListener(dataListener);
        } catch (Throwable ignored) {
        }
        refresh(false);
        loadWatch();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            Wearable.getDataClient(this).removeListener(dataListener);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        refresh(true);
    }

    // ------------------------------------------------------------ data

    private SharedPreferences prefs() {
        return Store.p(this);
    }

    /** Location, then downloads when older than 15 min (or forced), then the globe. */
    private void refresh(boolean force) {
        if (busy) {
            again |= force;      // e.g. location was just allowed: run once more after this one
            return;
        }
        busy = true;
        render();
        new Thread(() -> {
            SharedPreferences p = prefs();
            try {
                Where.update(this);
                double lat = Store.d(p, "lat", Where.FALLBACK_LAT), lon = Store.d(p, "lon", Where.FALLBACK_LON);
                place(p, lat, lon);
                long now = System.currentTimeMillis();
                if (force || now - p.getLong("wTime", 0) > 15 * 60_000L || !p.contains("quakes")) {
                    String st = Fetch.all(p, lat, lon, now);
                    p.edit().putString("status", st).apply();
                }
                drawGlobe(true);
            } catch (Throwable t) {
                p.edit().putString("status", "error: " + t.getMessage()).apply();
            }
            busy = false;
            runOnUiThread(this::render);
            try {
                latest = Installer.latest().build;
            } catch (Throwable ignored) {
            }
            runOnUiThread(() -> {
                render();
                if (again) {
                    again = false;
                    refresh(true);
                }
            });
        }).start();
    }

    private void place(SharedPreferences p, double lat, double lon) {
        if (Math.abs(Store.d(p, "placeLat", 999) - lat) < 0.02 && Math.abs(Store.d(p, "placeLon", 999) - lon) < 0.02
                && p.contains("place")) return;
        try {
            @SuppressWarnings("deprecation")
            List<Address> a = new Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1);
            if (a != null && !a.isEmpty()) {
                Address ad = a.get(0);
                String s = ad.getSubLocality() != null ? ad.getSubLocality() : ad.getLocality();
                if (ad.getLocality() != null && s != null && !s.equals(ad.getLocality())) s += ", " + ad.getLocality();
                if (s == null) s = ad.getAdminArea();
                SharedPreferences.Editor e = p.edit().putString("place", s == null ? "" : s);
                Store.putD(e, "placeLat", lat);
                Store.putD(e, "placeLon", lon);
                e.apply();
            }
        } catch (Throwable ignored) {
        }
    }

    private void drawGlobe(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && globe != null && now - globeAt < 2 * 60_000L) return;
        SharedPreferences p = prefs();
        int size = Math.min(1080, getResources().getDisplayMetrics().widthPixels - ui.dp(32));
        globe = Globe.draw(this, size, 0.92, Store.d(p, "lat", Where.FALLBACK_LAT), Store.d(p, "lon", Where.FALLBACK_LON),
                now, Iss.orbit(p));
        globeAt = now;
    }

    // ------------------------------------------------------------ screen

    private void buildTabs() {
        tabs.removeAllViews();
        tabs.setPadding(0, ui.dp(6), 0, padBottom + ui.dp(6));
        String[] names = {"Sky & Earth", "Watch"};
        for (int i = 0; i < 2; i++) {
            final int k = i;
            TextView t = ui.text(null, names[i], 16, i == tab ? Ui.BLUE : Ui.DIM, ui.semi);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, ui.dp(12), 0, ui.dp(12));
            if (i == tab) t.setBackground(Ui.round(0xFF1B2C4A, ui.dp(22)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.leftMargin = ui.dp(24);
            lp.rightMargin = ui.dp(24);
            tabs.addView(t, lp);
            t.setOnClickListener(v -> {
                tab = k;
                buildTabs();
                scroll.scrollTo(0, 0);
                render();
                if (k == 1) loadWatch();
            });
        }
    }

    private void render() {
        int y = scroll.getScrollY();
        content.removeAllViews();
        content.setPadding(ui.dp(16), padTop + ui.dp(10), ui.dp(16), padBottom + ui.dp(96));
        if (tab == 0) sky();
        else watchTab();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private static String hm(long ms) {
        return ms <= 0 ? "--:--" : new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms));
    }

    private static long day0(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long today(String csv, long d0) {
        if (csv == null) return -1;
        for (String s : csv.split(",")) {
            try {
                long ms = Long.parseLong(s.trim()) * 1000;
                if (ms >= d0 && ms < d0 + 86_400_000L) return ms;
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    private static String sentence(String s) {
        if (s == null || s.isEmpty()) return "";
        String low = s.toLowerCase(Locale.US).replace("mm/h", "mm/h");
        return Character.toUpperCase(low.charAt(0)) + low.substring(1);
    }

    private static String title(String s) {
        StringBuilder b = new StringBuilder();
        boolean up = true;
        for (char ch : s.toLowerCase(Locale.US).toCharArray()) {
            b.append(up ? Character.toUpperCase(ch) : ch);
            up = ch == ' ' || ch == ',' || ch == '-' || ch == '(';
        }
        return b.toString();
    }

    /** WMO weather code -> {icon index (as on the face), words}. */
    private static Object[] weather(int code) {
        if (code == 0) return new Object[]{1, "Clear"};
        if (code == 1 || code == 2) return new Object[]{14, "Partly cloudy"};
        if (code == 3) return new Object[]{2, "Cloudy"};
        if (code == 45 || code == 48) return new Object[]{3, "Fog"};
        if (code >= 51 && code <= 57) return new Object[]{12, "Drizzle"};
        if (code == 61 || code == 80) return new Object[]{12, "Light rain"};
        if (code == 63 || code == 81) return new Object[]{6, "Rain"};
        if (code == 65 || code == 82) return new Object[]{4, "Heavy rain"};
        if (code == 66 || code == 67) return new Object[]{10, "Freezing rain"};
        if (code == 71 || code == 85) return new Object[]{11, "Light snow"};
        if (code >= 73 && code <= 86) return new Object[]{7, "Snow"};
        if (code >= 95) return new Object[]{9, "Thunderstorm"};
        return new Object[]{0, "--"};
    }

    private static String aqiWord(double a) {
        if (a <= 50) return "good";
        if (a <= 100) return "moderate";
        if (a <= 150) return "poor";
        if (a <= 200) return "unhealthy";
        return "very bad";
    }

    private static int aqiColor(double a) {
        if (a <= 50) return Ui.GREEN;
        if (a <= 100) return Ui.GOLD;
        return Ui.ORANGE;
    }

    private static String deg(double d) {
        return Math.round(((d % 360) + 360) % 360) + "°";
    }

    /** Hourly series value at time s (seconds), linear between hours; NaN if outside. */
    private static double hourly(SharedPreferences p, String key, long s) {
        try {
            String[] t = p.getString("uvT", "").split(","), v = p.getString(key, "").split(",");
            for (int i = 0; i + 1 < t.length && i + 1 < v.length; i++) {
                long a = Long.parseLong(t[i].trim()), b = Long.parseLong(t[i + 1].trim());
                if (s >= a && s <= b) {
                    double va = Double.parseDouble(v[i]), vb = Double.parseDouble(v[i + 1]);
                    return va + (vb - va) * (s - a) / (double) (b - a);
                }
            }
        } catch (Exception ignored) {
        }
        return Double.NaN;
    }

    // ------------------------------------------------------------ tab 1: Sky & Earth

    private void sky() {
        SharedPreferences p = prefs();
        long now = System.currentTimeMillis();
        double lat = Store.d(p, "lat", Where.FALLBACK_LAT), lon = Store.d(p, "lon", Where.FALLBACK_LON);

        // header
        LinearLayout h = ui.row(content);
        ui.grow(h, "Astro", 32, Ui.WHITE, ui.semi);
        TextView upd = ui.text(h, busy ? "Updating…" : "Updated " + hm(p.getLong("wTime", 0)), 14, Ui.DIM, ui.med);
        upd.setOnClickListener(v -> refresh(true));
        ui.gap(content, 6);
        String place = p.getString("place", "");
        double ground = Store.d(p, "ground", Double.NaN);
        String coords = String.format(Locale.US, "%.2f° %s  %.2f° %s", Math.abs(lat), lat >= 0 ? "N" : "S", Math.abs(lon),
                lon >= 0 ? "E" : "W");
        ui.text(content, (place.isEmpty() ? "" : place + "  ·  ") + coords
                + (Double.isNaN(ground) ? "" : "  ·  " + Math.round(ground) + " m"), 14, Ui.SOFT, ui.med);
        if (p.getBoolean("locApprox", false)) ui.text(content, "Approximate place: allow location for yours", 13, Ui.ORANGE, ui.med);
        ui.gap(content, 14);

        // globe
        if (globe != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(globe);
            iv.setAdjustViewBounds(true);
            content.addView(iv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            TextView t = ui.text(content, "Drawing the Earth…", 15, Ui.DIM, ui.med);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, ui.dp(120), 0, ui.dp(120));
        }
        ui.gap(content, 10);
        LinearLayout lg = ui.scrollRow(content);
        ui.legend(lg, "you", Ui.ORANGE, true);
        ui.legend(lg, "quakes 24 h", Ui.RED, true);
        ui.legend(lg, "ISS", Ui.WHITE, false);
        ui.legend(lg, "moon overhead", Ui.SOFT, false);
        ui.legend(lg, "sun overhead", Ui.GOLD, false);
        ui.gap(content, 14);

        weatherCard(p, now);
        rainCard(p, now);
        tideCard(p, now);
        skyCard(p, now, lat, lon);
        pressureCard(p, now);
        quakeCard(p, now, lat, lon);
        TextView foot = ui.text(content, "Data: Open-Meteo (weather, air, marine), USGS (earthquakes), CelesTrak (ISS orbit). "
                + "Sun, moon and passes are worked out on the phone.\n" + p.getString("status", ""), 12, Ui.DIM, ui.med);
        foot.setLineSpacing(0, 1.2f);
    }

    private void weatherCard(SharedPreferences p, long now) {
        LinearLayout c = ui.card(content);
        LinearLayout r = ui.row(c);
        Object[] w = weather(p.getInt("wcode", -1));
        boolean day = p.getBoolean("isDay", true);
        ImageView icon = new ImageView(this);
        int id = getResources().getIdentifier(String.format(Locale.US, "wx_%s_%02d", day ? "d" : "n", (Integer) w[0]),
                "drawable", getPackageName());
        if (id != 0) icon.setImageResource(id);
        r.addView(icon, new LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(ui.dp(12), 0, 0, 0);
        r.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        double t = Store.d(p, "temp", Double.NaN), feels = Store.d(p, "feels", Double.NaN);
        ui.text(mid, Double.isNaN(t) ? "--°" : Math.round(t) + "°", 44, Ui.WHITE, ui.med);
        ui.gap(mid, 4);
        ui.text(mid, w[1] + (Double.isNaN(feels) ? "" : " · feels " + Math.round(feels) + "°"), 14, Ui.SOFT, ui.med);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        r.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f));
        double hum = Store.d(p, "hum", Double.NaN), aqi = Store.d(p, "aqi", Double.NaN);
        double wind = Store.d(p, "wind", Double.NaN), wdir = Store.d(p, "windDir", Double.NaN);
        double uv = hourly(p, "uvV", now / 1000), uvMax = Store.d(p, "uvMax", Double.NaN);
        ui.pair(right, "Humidity", Double.isNaN(hum) ? "--" : Math.round(hum) + "%", Ui.TEAL);
        ui.pair(right, "AQI", Double.isNaN(aqi) ? "--" : Math.round(aqi) + " " + aqiWord(aqi), Double.isNaN(aqi) ? Ui.SOFT : aqiColor(aqi));
        ui.pair(right, "Wind", Double.isNaN(wind) ? "--" : Math.round(wind) + " km/h " + (Double.isNaN(wdir) ? "" : Astro.dir8(wdir)), Ui.SOFT);
        ui.pair(right, "UV", (Double.isNaN(uv) ? "--" : String.valueOf(Math.round(uv)))
                + (Double.isNaN(uvMax) ? "" : " (peak " + Math.round(uvMax) + ")"), Ui.GOLD);
    }

    private void rainCard(SharedPreferences p, long now) {
        LinearLayout c = ui.card(content);
        ui.label(c, "Rain · next 2 hours");
        ui.gap(c, 8);
        Map<String, String> v = new HashMap<>();
        Rain.values(p, v, now);
        LinearLayout r = ui.row(c);
        ui.grow(r, sentence(v.get("rhead")), 22, Ui.TEAL, ui.semi);
        double chance = Store.d(p, "rainDay", Double.NaN);
        if (!Double.isNaN(chance)) ui.text(r, "chance today " + Math.round(chance) + "%", 14, Ui.DIM, ui.med);
        String more = (v.get("rlvl").isEmpty() ? "" : sentence(v.get("rlvl"))) + (v.get("rmax").isEmpty() ? "" : " · " + v.get("rmax").toLowerCase(Locale.US).replace("up to", "up to"))
                + (v.get("rend").isEmpty() ? "" : " · " + sentence(v.get("rend")));
        if (!more.isEmpty()) {
            ui.gap(c, 6);
            ui.text(c, more, 14, Ui.SOFT, ui.med);
        }
        ui.gap(c, 12);
        Views.RainBars bars = new Views.RainBars(this);
        bars.rates = Rain.next2h(p, now);
        bars.now = now;
        bars.tf = ui.cond;
        c.addView(bars);
    }

    private void tideCard(SharedPreferences p, long now) {
        LinearLayout c = ui.card(content);
        int idx = p.getInt("tidePlace", -1);
        ui.label(c, "Tide · " + (idx >= 0 && idx < Tide.NAMES.length ? Tide.NAMES[idx] : "--"));
        ui.gap(c, 8);
        Tide.Series s = Tide.Series.load(p);
        LinearLayout r = ui.row(c);
        String head = "--", next = "";
        if (s != null) {
            long ns = now / 1000;
            double hn = s.at(ns), h2 = s.at(ns + 1800);
            if (!Double.isNaN(hn)) head = Tide.fmt(hn) + " m  " + (Double.isNaN(h2) ? "" : h2 >= hn ? "rising" : "falling");
            double[] hi = s.next(ns, true), lo = s.next(ns, false);
            if (lo != null) next += "low " + hm((long) (lo[0] * 1000));
            if (hi != null) next += (next.isEmpty() ? "" : "  ·  ") + "high " + hm((long) (hi[0] * 1000));
        }
        ui.grow(r, head, 22, Ui.BLUE, ui.semi);
        ui.text(r, next, 14, Ui.DIM, ui.med);
        ui.gap(c, 10);
        Views.TideCurve tc = new Views.TideCurve(this);
        tc.s = s;
        tc.day0 = day0(now);
        tc.now = now;
        tc.tf = ui.cond;
        c.addView(tc);
        ui.gap(c, 4);
        ui.text(c, "Change the place on the Watch tab (the watch and phone share it).", 12, Ui.DIM, ui.med);
    }

    private void skyCard(SharedPreferences p, long now, double lat, double lon) {
        LinearLayout c = ui.card(content);
        ui.label(c, "Sky");
        ui.gap(c, 10);
        long d0 = day0(now);
        long sr = today(p.getString("sunrise", ""), d0), ss = today(p.getString("sunset", ""), d0);
        // sun
        LinearLayout r = ui.row(c);
        TextView sunL = ui.text(r, "Sun", 18, Ui.GOLD, ui.semi);
        sunL.setMinWidth(ui.dp(70));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        String len = "";
        if (sr > 0 && ss > sr) {
            long m = (ss - sr) / 60_000;
            len = "   day " + (m / 60) + "h " + String.format(Locale.US, "%02d", m % 60) + "m";
        }
        ui.text(col, "rise " + hm(sr) + "   set " + hm(ss) + len, 15, Ui.SOFT, ui.med);
        ui.gap(col, 6);
        double saz = Astro.sunAzimuth(now, lat, lon), salt = Astro.sunAltitude(now, lat, lon);
        ui.text(col, "now " + deg(saz) + " " + Astro.dir8(saz) + ", " + Math.abs(Math.round(salt)) + "° "
                + (salt >= 0 ? "up" : "below the horizon"), 14, Ui.DIM, ui.med);
        ui.divider(c);
        // moon
        r = ui.row(c);
        Views.Moon mv = new Views.Moon(this);
        double age = Astro.moonAge(now);
        mv.age = age;
        r.addView(mv);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(14), 0, 0, 0);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ui.text(col, String.format(Locale.US, "Moon · %d%% lit · %.1f days", Math.round(Astro.moonLit(now) * 100), age), 17,
                Ui.WHITE, ui.semi);
        ui.gap(col, 6);
        long[] mt = Astro.moonTimes(d0, lat, lon);
        double maz = Astro.moonAzimuth(now, lat, lon), malt = Astro.moonAltitude(now, lat, lon);
        ui.text(col, "rise " + hm(mt[0]) + "   set " + hm(mt[1]) + "   now " + deg(maz) + " " + Astro.dir8(maz) + ", "
                + Math.abs(Math.round(malt)) + "° " + (malt >= 0 ? "up" : "down"), 14, Ui.SOFT, ui.med);
        ui.divider(c);
        // ISS
        r = ui.row(c);
        TextView issL = ui.text(r, "ISS", 18, Ui.ICE, ui.semi);
        issL.setMinWidth(ui.dp(70));
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Iss.Orbit o = Iss.orbit(p);
        if (o == null) {
            ui.text(col, "Orbit not downloaded yet", 15, Ui.SOFT, ui.med);
        } else {
            double[] pass = Iss.nextVisiblePass(o, lat, lon, now);
            if (pass == null) {
                ui.text(col, "No visible pass in the next 2 days", 16, Ui.WHITE, ui.semi);
            } else {
                long mins = Math.round((pass[1] - pass[0]) / 60000.0);
                String when = pass[0] <= now ? "now" : new SimpleDateFormat("EEE HH:mm", Locale.US).format(new Date((long) pass[0]));
                ui.text(col, "Next visible: " + when + " · " + mins + " min · " + Math.round(pass[2]) + "° up", 16,
                        Ui.WHITE, ui.semi);
                ui.gap(col, 6);
                ui.text(col, "from " + Astro.dir8(pass[3]) + " to " + Astro.dir8(pass[4]), 14, Ui.SOFT, ui.med);
            }
            ui.gap(col, 6);
            double[] sub = Iss.subPoint(o, now);
            ui.text(col, String.format(Locale.US, "now over %.1f° %s, %.1f° %s", Math.abs(sub[0]), sub[0] >= 0 ? "N" : "S",
                    Math.abs(sub[1]), sub[1] >= 0 ? "E" : "W"), 14, Ui.DIM, ui.med);
        }
    }

    private void pressureCard(SharedPreferences p, long now) {
        LinearLayout c = ui.card(content);
        ui.label(c, "Air pressure (sea level)");
        ui.gap(c, 8);
        double msl = Store.d(p, "msl", Double.NaN);
        long s = now / 1000;
        double a = hourly(p, "pV", s), b = hourly(p, "pV", s - 3 * 3600);
        if (Double.isNaN(msl)) msl = a;
        LinearLayout r = ui.row(c);
        ui.text(r, Double.isNaN(msl) ? "-- hPa" : Math.round(msl) + " hPa", 28, Ui.WHITE, ui.semi);
        if (!Double.isNaN(a) && !Double.isNaN(b)) {
            double d = a - b;
            String word = Math.abs(d) < 1 ? "steady" : d > 0 ? "rising" : "falling (weather may turn)";
            TextView t = ui.text(r, String.format(Locale.US, "   %+.1f in 3 h · %s", d, word), 15,
                    Math.abs(d) < 1 ? Ui.GREEN : d > 0 ? Ui.BLUE : Ui.ORANGE, ui.med);
        }
        ui.gap(c, 10);
        Views.Spark sp = new Views.Spark(this);
        double[] v = new double[13];
        for (int i = 0; i <= 12; i++) v[i] = hourly(p, "pV", s - (12 - i) * 3600L);
        sp.v = v;
        c.addView(sp);
        ui.text(c, "last 12 hours", 12, Ui.DIM, ui.med);
    }

    private void quakeCard(SharedPreferences p, long now, double lat, double lon) {
        LinearLayout c = ui.card(content);
        List<Quakes.Q> qs = Quakes.load(p, now);
        LinearLayout r = ui.row(c);
        TextView l = ui.label(null, "Earthquakes · last 24 h (M4.5+)");
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ui.text(r, String.valueOf(qs.size()), 18, Ui.RED, ui.semi);
        ui.gap(c, 10);
        qs.sort((x, y) -> Double.compare(y.mag, x.mag));
        if (qs.isEmpty()) ui.text(c, p.contains("quakes") ? "None in the last 24 hours" : "Not downloaded yet", 15, Ui.SOFT, ui.med);
        for (int i = 0; i < Math.min(8, qs.size()); i++) {
            Quakes.Q q = qs.get(i);
            LinearLayout row = ui.row(c);
            int col = Quakes.color(q.mag, 255);
            TextView m = ui.text(row, String.format(Locale.US, "M%.1f", q.mag), 16, col, ui.semi);
            m.setGravity(Gravity.CENTER);
            m.setMinWidth(ui.dp(62));
            m.setPadding(0, ui.dp(8), 0, ui.dp(8));
            m.setBackground(Ui.round(Color.argb(45, Color.red(col), Color.green(col), Color.blue(col)), ui.dp(10)));
            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(ui.dp(14), 0, 0, 0);
            row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            ui.text(mid, title(Quakes.shortPlace(q.place, 30)), 16, Ui.WHITE, ui.semi);
            ui.gap(mid, 4);
            ui.text(mid, String.format(Locale.US, "%,d km away", Math.round(Quakes.km(q.lat, q.lon, lat, lon) / 10.0) * 10), 13,
                    Ui.DIM, ui.med);
            ui.text(row, Quakes.ago(now - q.time), 14, Ui.DIM, ui.med);
            ui.gap(c, 10);
        }
    }

    // ------------------------------------------------------------ tab 2: Watch

    /** Connected watches and the latest status they published. Off the main thread. */
    private void loadWatch() {
        new Thread(() -> {
            try {
                nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes(), 10, TimeUnit.SECONDS);
                DataItemBuffer buf = Tasks.await(Wearable.getDataClient(this).getDataItems(Uri.parse("wear://*" + Link.STATUS)),
                        10, TimeUnit.SECONDS);
                try {
                    for (DataItem it : buf) {
                        DataMap m = DataMapItem.fromDataItem(it).getDataMap();
                        if (watch == null || m.getLong(Link.K_TIME) >= watch.getLong(Link.K_TIME)) watch = m;
                    }
                } finally {
                    buf.release();
                }
                linkNote = "";
                send(Link.STATUS_REQ, "");
            } catch (Throwable t) {
                linkNote = "Can't reach Wear OS on this phone (" + t.getMessage() + ")";
            }
            runOnUiThread(() -> {
                if (tab == 1) render();
            });
        }).start();
    }

    /** Sends a message to every connected watch. Blocking. */
    private boolean send(String path, String payload) {
        boolean any = false;
        for (Node n : nodes) {
            try {
                Tasks.await(Wearable.getMessageClient(this).sendMessage(n.getId(), path, payload.getBytes(StandardCharsets.UTF_8)),
                        10, TimeUnit.SECONDS);
                any = true;
            } catch (Throwable ignored) {
            }
        }
        return any;
    }

    private void sendAsync(String path, String payload, String note) {
        sendNote = note;
        render();
        new Thread(() -> {
            if (nodes.isEmpty()) loadNodes();
            boolean ok = send(path, payload);
            if (!ok) sendNote = "The watch didn't get it: is it connected?";
            runOnUiThread(this::render);
        }).start();
    }

    private void loadNodes() {
        try {
            nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes(), 10, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
    }

    private void set(String key, Object value) {
        sendAsync(Link.SET, key + "=" + value, "Sent to the watch");
    }

    private boolean wb(String k, boolean def) {
        DataMap w = watch;
        return w == null || !w.containsKey(k) ? def : w.getBoolean(k);
    }

    private void watchTab() {
        DataMap w = watch;
        long now = System.currentTimeMillis();
        boolean connected = !nodes.isEmpty();
        ui.text(content, "Watch", 32, Ui.WHITE, ui.semi);
        ui.gap(content, 16);

        // status
        LinearLayout c = ui.card(content);
        LinearLayout r = ui.row(c);
        ImageView pv = new ImageView(this);
        pv.setImageResource(R.drawable.watch_preview);
        pv.setClipToOutline(true);
        pv.setBackground(Ui.dot(Color.BLACK, ui.dp(110), false));
        r.addView(pv, new LinearLayout.LayoutParams(ui.dp(110), ui.dp(110)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(ui.dp(16), 0, 0, 0);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        String name = connected ? nodes.get(0).getDisplayName() : (w != null ? w.getString(Link.K_MODEL, "Watch") : "Watch");
        ui.text(col, name, 18, Ui.WHITE, ui.semi);
        ui.gap(col, 6);
        TextView st = ui.text(col, connected ? "connected" : "not connected", 14, connected ? Ui.GREEN : Ui.ORANGE, ui.med);
        st.setCompoundDrawablesRelative(Ui.dot(connected ? Ui.GREEN : Ui.ORANGE, ui.dp(9), false), null, null, null);
        st.setCompoundDrawablePadding(ui.dp(6));
        ui.gap(col, 6);
        if (w != null) {
            long app = w.getLong(Link.K_APP), face = w.getLong(Link.K_FACE);
            ui.text(col, "Astro " + app + "  ·  Terra face " + (face < 0 ? "not installed" : String.valueOf(face)), 14, Ui.SOFT, ui.med);
            ui.gap(col, 4);
            ui.text(col, "refreshed " + hm(w.getLong(Link.K_REFRESHED)) + " · status " + hm(w.getLong(Link.K_TIME)), 13, Ui.DIM, ui.med);
        } else {
            ui.text(col, connected ? "Waiting for the watch… (Astro build 23 or newer needed)" : "Open the Galaxy Wearable app / check Bluetooth",
                    13, Ui.DIM, ui.med);
        }
        if (w != null) {
            ui.gap(c, 10);
            String status = w.getString(Link.K_STATUS, "");
            if (!status.isEmpty()) ui.text(c, status, 13, Ui.DIM, ui.med);
            String upd = w.getString(Link.K_UPD, "");
            if (!upd.isEmpty()) {
                ui.gap(c, 6);
                ui.text(c, "Update: " + upd.replaceFirst("^Update: ", ""), 14, Ui.ICE, ui.med);
            }
            if (!w.getBoolean(Link.K_ALLOWED, true)) {
                ui.gap(c, 6);
                ui.text(c, "Astro on the watch isn't allowed to install updates yet: open Astro on the watch → Allow installing updates",
                        13, Ui.ORANGE, ui.med);
            }
        }
        if (!linkNote.isEmpty()) {
            ui.gap(c, 6);
            ui.text(c, linkNote, 13, Ui.ORANGE, ui.med);
        }

        // tide place
        c = ui.card(content);
        ui.label(c, "Tide place");
        ui.gap(c, 10);
        int ti = w == null ? -1 : w.getInt(Link.K_TIDE, -1);
        LinearLayout chips = ui.scrollRow(c);
        String near = "Nearest coast";
        if (w != null && ti < 0 && !w.getString(Link.K_TIDE_NAME, "").isEmpty()) near += " (" + w.getString(Link.K_TIDE_NAME) + ")";
        ui.chip(chips, near, ti < 0, Ui.SOFT, () -> set(Link.K_TIDE, -1));
        for (int i = 0; i < Tide.NAMES.length; i++) {
            final int k = i;
            ui.chip(chips, Tide.NAMES[i], ti == i, Ui.SOFT, () -> set(Link.K_TIDE, k));
        }
        // the phone uses the same place
        int myTide = prefs().getInt("tideIdx", -1);
        if (w != null && myTide != ti) {
            prefs().edit().putInt("tideIdx", ti).putLong("wTime", 0).apply();
        }

        // bottom panel
        c = ui.card(content);
        ui.label(c, "Bottom panel on the face");
        ui.gap(c, 10);
        LinearLayout seg = ui.row(c);
        String[] ps = {"Tide", "Quakes", "Rain"};
        int pn = w == null ? 0 : w.getInt(Link.K_PANEL, 0);
        for (int i = 0; i < 3; i++) {
            final int k = i;
            TextView t = ui.text(null, ps[i], 16, pn == i ? Ui.WHITE : Ui.SOFT, ui.semi);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, ui.dp(12), 0, ui.dp(12));
            t.setBackground(Ui.round(pn == i ? Ui.SEL : Ui.CARD2, ui.dp(16)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.rightMargin = i < 2 ? ui.dp(6) : 0;
            seg.addView(t, lp);
            t.setOnClickListener(v -> set(Link.K_PANEL, k));
        }

        // switches
        c = ui.card(content);
        ui.toggle(c, "Rain screen by itself", "when rain is under 30 min away", wb(Link.K_RAIN, true), v -> set(Link.K_RAIN, v));
        ui.divider(c);
        ui.toggle(c, "Auto-update the watch", "checks GitHub every 3 hours", wb(Link.K_AUTO, true), v -> set(Link.K_AUTO, v));
        ui.divider(c);
        ui.toggle(c, "Compass on the face", "reading at each refresh", wb(Link.K_COMPASS, true), v -> set(Link.K_COMPASS, v));

        // actions
        ui.gap(content, 4);
        ui.button(content, "Refresh watch data now", 0xFF20314F, Ui.WHITE, () -> sendAsync(Link.REFRESH, "", "Asked the watch to refresh"));
        long watchBuild = w == null ? -1 : Math.min(w.getLong(Link.K_APP), w.getLong(Link.K_FACE) < 0 ? Long.MAX_VALUE : w.getLong(Link.K_FACE));
        boolean newer = latest > 0 && watchBuild >= 0 && latest > watchBuild;
        String label = newer ? "Update watch to build " + latest : latest > 0 && w != null ? "Watch is up to date (build " + latest + ")" : "Update watch";
        ui.button(content, label, newer ? Ui.BLUE : 0xFF20314F, Ui.WHITE,
                () -> sendAsync(Link.UPDATE, "", "Asked the watch to update itself from GitHub"));
        TextView alt = ui.text(content, "Or send the update from the phone (if the watch can't get online)", 14, Ui.ICE, ui.med);
        alt.setGravity(Gravity.CENTER);
        alt.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(12));
        alt.setOnClickListener(v -> sendFiles());
        if (!sendNote.isEmpty()) {
            TextView t = ui.text(content, sendNote, 14, Ui.SOFT, ui.med);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, 0, 0, ui.dp(12));
        }

        // the phone app itself
        c = ui.card(content);
        long me = Installer.installed(this, getPackageName());
        LinearLayout pr = ui.row(c);
        ui.grow(pr, "Phone app build " + me + (latest > me ? "  ·  " + latest + " available" : latest > 0 ? "  ·  up to date" : ""),
                15, latest > me ? Ui.GREEN : Ui.SOFT, ui.med);
        TextView go = ui.text(pr, latest > me ? "Install" : "Check", 15, Ui.BLUE, ui.semi);
        go.setPadding(ui.dp(12), ui.dp(6), 0, ui.dp(6));
        go.setOnClickListener(v -> updatePhone());
        if (!phoneNote.isEmpty()) {
            ui.gap(c, 6);
            ui.text(c, phoneNote, 13, Ui.DIM, ui.med);
        }
    }

    /** Downloads the watch's two APKs here and sends them to the watch in one zip. */
    private void sendFiles() {
        sendNote = "Downloading for the watch…";
        render();
        new Thread(() -> {
            try {
                if (nodes.isEmpty()) loadNodes();
                if (nodes.isEmpty()) throw new Exception("no watch connected");
                Installer.Progress pr = s -> {
                    sendNote = s;
                    runOnUiThread(this::render);
                };
                File face = Installer.download(this, Installer.FACE_APK, "watch face", pr);
                File app = Installer.download(this, Installer.APP_APK, "watch app", pr);
                File zip = new File(Installer.dir(this), "towatch.zip");
                try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
                    byte[] buf = new byte[64 * 1024];
                    for (File f : new File[]{face, app}) {
                        z.putNextEntry(new ZipEntry(f.getName()));
                        try (FileInputStream in = new FileInputStream(f)) {
                            int n;
                            while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
                        }
                        z.closeEntry();
                    }
                }
                sendNote = String.format(Locale.US, "Sending %.1f MB to the watch… (a minute or two over Bluetooth)", zip.length() / 1e6);
                runOnUiThread(this::render);
                ChannelClient cc = Wearable.getChannelClient(this);
                for (Node n : nodes) {
                    ChannelClient.Channel ch = Tasks.await(cc.openChannel(n.getId(), Link.APKS), 20, TimeUnit.SECONDS);
                    Tasks.await(cc.sendFile(ch, Uri.fromFile(zip)), 10, TimeUnit.MINUTES);
                }
                sendNote = "Sent. The watch installs it now: its status shows above.";
            } catch (Throwable t) {
                sendNote = "Couldn't send: " + t.getMessage();
            }
            runOnUiThread(this::render);
        }).start();
    }

    /** This phone app's own update from the GitHub release. */
    private void updatePhone() {
        if (!Installer.allowed(this)) {
            phoneNote = "Allow Astro to install updates, then tap again";
            render();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignored) {
            }
            return;
        }
        phoneNote = "Checking…";
        render();
        new Thread(() -> {
            try {
                Installer.Release r = Installer.latest();
                latest = r.build;
                long me = Installer.installed(this, getPackageName());
                if (r.build <= me) {
                    phoneNote = "Up to date";
                } else {
                    File f = Installer.download(this, Installer.PHONE_APK, "Astro", s -> {
                        phoneNote = s;
                        runOnUiThread(this::render);
                    });
                    Installer.check(this, f, getPackageName(), me);
                    phoneNote = "Installing… (tap Update if Android asks)";
                    runOnUiThread(this::render);
                    String out = Installer.install(this, f, getPackageName(), true);
                    phoneNote = out == null ? "Updated" : "Update: " + out;
                }
            } catch (Throwable t) {
                phoneNote = "Update: " + t.getMessage();
            }
            runOnUiThread(this::render);
        }).start();
    }
}
