package com.dmk.astrocompanion;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Map;

/** Small settings / status screen on the watch. */
public class MainActivity extends Activity {
    private LinearLayout list;
    private TextView status, updLine, updBtn;
    private volatile boolean updBusy;
    private volatile String updMsg = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.BLACK);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(22);
        list.setPadding(pad, dp(30), pad, dp(40));
        sv.addView(list);
        setContentView(sv);
        Worker.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        askLocation();
        build(); // (also after coming back from the install permission screen)
        peekUpdate();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        list.addView(t);
        return t;
    }

    private TextView button(String s, Runnable r) {
        TextView t = text(s, 14, Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(Color.rgb(32, 52, 82));
        t.setBackground(bg);
        t.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) t.getLayoutParams();
        lp.width = LinearLayout.LayoutParams.MATCH_PARENT;
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private void build() {
        list.removeAllViews();
        text("Astro", 20, Color.rgb(95, 162, 245));
        text("Feeds the Terra watch face", 12, Color.GRAY);
        status = text("", 12, Color.rgb(200, 205, 214));
        SharedPreferences p = Store.p(this);
        int idx = p.getInt("tideIdx", -1);
        button("Tide: " + (idx < 0 ? "nearest coast" : Tide.NAMES[idx]), () -> {
            int n = p.getInt("tideIdx", -1) + 1;
            if (n >= Tide.NAMES.length) n = -1;
            p.edit().putInt("tideIdx", n).putLong("wTime", 0).apply();
            build();
            refreshNow();
        });
        button("Refresh now", this::refreshNow);

        // updates straight from GitHub (no phone / adb)
        updLine = text("", 12, Color.rgb(200, 205, 214));
        updBtn = button("Check for update", this::updateNow);
        updBtn.setEnabled(!updBusy);
        boolean auto = p.getBoolean("autoUpd", true);
        button("Auto-update: " + (auto ? "on" : "off"), () -> {
            p.edit().putBoolean("autoUpd", !auto).apply();
            build();
        });
        if (!Updater.allowed(this)) {
            button("Allow installing updates", this::allowInstalls);
            text("No switch on that screen? Run once with GeminiMan / adb shell:\n" + Updater.GRANT_CMD, 11, Color.GRAY);
        }
        showUpdate();

        button("Allow location all the time", () -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        text("Data: Open-Meteo (weather, marine). Tide is a forecast model, not a harbour table.", 10, Color.GRAY);
        refreshStatus();
    }

    // ------------------------------------------------------------ updates

    private void showUpdate() {
        if (updLine == null) return;
        if (updBusy) {
            updLine.setText(updMsg);
            return;
        }
        SharedPreferences p = Store.p(this);
        long latest = p.getLong("updLatest", 0);
        long me = Updater.installed(this, getPackageName()), face = Updater.installed(this, Updater.FACE_PKG);
        boolean newer = latest > me || (face >= 0 && latest > face);
        StringBuilder s = new StringBuilder(Updater.versions(this));
        if (newer) s.append("\nBuild ").append(latest).append(" available");
        String st = p.getString("updStatus", "");
        if (!st.isEmpty() && !newer) s.append('\n').append(st);
        updLine.setText(s);
        updLine.setTextColor(newer ? Color.rgb(120, 220, 140) : Color.rgb(200, 205, 214));
        updBtn.setText(newer ? "Install build " + latest : "Check for update");
    }

    /** Quick look at GitHub when the screen opens (at most every 10 min), so a new build shows. */
    private void peekUpdate() {
        SharedPreferences p = Store.p(this);
        if (updBusy || System.currentTimeMillis() - p.getLong("updSeen", 0) < 10 * 60_000L) return;
        new Thread(() -> {
            try {
                Updater.Release r = Updater.latest();
                p.edit().putLong("updSeen", System.currentTimeMillis()).putLong("updLatest", r.build)
                        .putString("updNotes", r.notes).apply();
                runOnUiThread(this::showUpdate);
            } catch (Throwable ignored) {
            }
        }).start();
    }

    private void updateNow() {
        if (updBusy) return;
        if (!Updater.allowed(this)) {
            allowInstalls();
            return;
        }
        updBusy = true;
        updBtn.setEnabled(false);
        updLine.setTextColor(Color.rgb(200, 205, 214));
        new Thread(() -> {
            Updater.run(this, true, s -> runOnUiThread(() -> {
                updMsg = s;
                if (updLine != null) updLine.setText(s);
            }));
            runOnUiThread(() -> {
                updBusy = false;
                if (updBtn != null) updBtn.setEnabled(true);
                showUpdate();
            });
        }).start();
    }

    private void allowInstalls() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            updLine.setText("This watch hides that switch. Run once with GeminiMan / adb shell:\n" + Updater.GRANT_CMD);
        }
    }

    // ------------------------------------------------------------ data

    private void refreshNow() {
        status.setText("Refreshing…");
        new Thread(() -> {
            String r = Engine.refresh(this, true);
            runOnUiThread(() -> {
                status.setText(r);
                refreshStatus();
            });
        }).start();
    }

    private void refreshStatus() {
        if (status == null) return;
        new Thread(() -> {
            SharedPreferences p = Store.p(this);
            Map<String, String> v = new java.util.HashMap<String, String>() {
                @Override
                public String get(Object k) {
                    String s = super.get(k);
                    return s == null ? "--" : s;
                }
            };
            v.putAll(Engine.values(this));
            StringBuilder sb = new StringBuilder();
            sb.append(p.getString("status", "not updated yet")).append('\n');
            if (Store.hasLocation(p)) {
                sb.append(String.format(java.util.Locale.US, "Location %.3f, %.3f%s\n",
                        Store.d(p, "lat", 0), Store.d(p, "lon", 0),
                        p.getBoolean("locApprox", false) ? " (approx: Pune)" : ""));
            } else {
                sb.append("No location yet\n");
            }
            String note = p.getString("locNote", "");
            if (!note.isEmpty()) sb.append(note).append('\n');
            sb.append("Sun ").append(v.get("srise")).append(" - ").append(v.get("sset"))
                    .append("  Moon ").append(v.get("mrise")).append(" - ").append(v.get("mset")).append('\n');
            sb.append("Alt ").append(v.get("alt")).append(" m  Baro ").append(v.get("baro")).append(" hPa\n");
            sb.append("Humidity ").append(v.get("hum")).append("  AQI ").append(v.get("aqi"))
                    .append("  Moon ").append(v.get("mage")).append('\n');
            sb.append("Tide ").append(v.get("tplace")).append(' ').append(v.get("tnow")).append(" m ")
                    .append(v.get("tstate")).append('\n');
            sb.append("Heading ").append(v.get("head")).append(' ').append(v.get("hdir")).append('\n');
            if (Store.hasLocation(p)) sb.append(Iss.describe(p, Store.d(p, "lat", 0), Store.d(p, "lon", 0),
                    System.currentTimeMillis()));
            String s = sb.toString();
            runOnUiThread(() -> status.setText(s));
            Engine.pushUpdates(this);
        }).start();
    }

    private void askLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
            return;
        }
        // a fresh fix (from the phone), saved for the background runs
        new Thread(() -> {
            boolean had = Store.hasLocation(Store.p(this)) && !Store.p(this).getBoolean("locApprox", false);
            Engine.updateLocation(this);
            boolean has = Store.hasLocation(Store.p(this)) && !Store.p(this).getBoolean("locApprox", false);
            if (has && !had) Engine.refresh(this, true);
            runOnUiThread(this::refreshStatus);
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        askLocation();
        refreshNow();
    }
}
