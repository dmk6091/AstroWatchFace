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
    private TextView status;

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
        build();
        Worker.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        askLocation();
        refreshStatus();
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
        button("Allow location all the time", () -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        text("Data: Open-Meteo (weather, marine). Tide is a forecast model, not a harbour table.", 10, Color.GRAY);
        refreshStatus();
    }

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
            sb.append("Heading ").append(v.get("head")).append(' ').append(v.get("hdir"));
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
