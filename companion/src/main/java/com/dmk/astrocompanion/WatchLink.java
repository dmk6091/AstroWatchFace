package com.dmk.astrocompanion;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * The watch end of the phone link: settings, "refresh now", "update now", APK files from the
 * phone, and the status the phone shows on its Watch tab (see Link).
 */
public class WatchLink extends WearableListenerService {
    private static long sLastProgress;

    @Override
    public void onMessageReceived(MessageEvent ev) {
        Context c = getApplicationContext();
        String path = ev.getPath();
        try {
            if (Link.SET.equals(path)) {
                apply(c, new String(ev.getData(), StandardCharsets.UTF_8));
            } else if (Link.REFRESH.equals(path)) {
                LinkJob.start(c, LinkJob.REFRESH);
            } else if (Link.UPDATE.equals(path)) {
                LinkJob.start(c, LinkJob.UPDATE);
            } else if (Link.STATUS_REQ.equals(path)) {
                publish(c);
            }
        } catch (Throwable t) {
            Log.w(Store.TAG, "link: " + t);
        }
    }

    /** Settings from the phone: one "key=value" per line. */
    static void apply(Context c, String payload) {
        SharedPreferences p = Store.p(c);
        SharedPreferences.Editor e = p.edit();
        boolean refetch = false;
        for (String line : payload.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
            switch (k) {
                case Link.K_TIDE:
                    e.putInt(Link.K_TIDE, Integer.parseInt(v)).putLong("wTime", 0);
                    refetch = true;
                    break;
                case Link.K_PANEL:
                    int pn = Integer.parseInt(v);
                    e.putInt("panel", pn).putLong("panelTap", System.currentTimeMillis()).putInt("panelShown", pn);
                    break;
                case Link.K_AUTO:
                case Link.K_RAIN:
                case Link.K_COMPASS:
                    e.putBoolean(k, Boolean.parseBoolean(v));
                    break;
                default:
                    break;
            }
        }
        e.commit();
        if (refetch) LinkJob.start(c, LinkJob.REFRESH);
        Panel.refresh(c);
        try {
            Engine.pushUpdates(c);
        } catch (Throwable ignored) {
        }
        publish(c);
    }

    @Override
    public void onChannelOpened(ChannelClient.Channel ch) {
        if (!ch.getPath().startsWith(Link.APKS)) return;
        Context c = getApplicationContext();
        File zip = zipFile(c);
        zip.delete();
        Wearable.getChannelClient(c).receiveFile(ch, Uri.fromFile(zip), false);
        status(c, "Receiving files from the phone…");
    }

    @Override
    public void onInputClosed(ChannelClient.Channel ch, int reason, int appCode) {
        if (!ch.getPath().startsWith(Link.APKS)) return;
        Context c = getApplicationContext();
        if (reason == ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL) {
            status(c, "Files received, installing…");
            LinkJob.start(c, LinkJob.FILES);
        } else {
            status(c, "Update: the transfer from the phone broke off (" + reason + ")");
        }
    }

    static File zipFile(Context c) {
        return new File(Installer.dir(c), "fromphone.zip");
    }

    static void status(Context c, String s) {
        Store.p(c).edit().putString("updStatus", s).putLong("updWhen", System.currentTimeMillis()).apply();
        publish(c);
    }

    /** Update progress, sent to the phone at most every 3 s. */
    static void progress(Context c, String s) {
        Store.p(c).edit().putString("updStatus", s).apply();
        long now = System.currentTimeMillis();
        if (now - sLastProgress > 3000) {
            sLastProgress = now;
            publish(c);
        }
    }

    /** What the phone's Watch tab shows. Safe to call anywhere (no-op without Google Play services). */
    static void publish(Context c) {
        try {
            SharedPreferences p = Store.p(c);
            long now = System.currentTimeMillis();
            PutDataMapRequest r = PutDataMapRequest.create(Link.STATUS);
            r.getDataMap().putLong(Link.K_APP, Installer.installed(c, c.getPackageName()));
            r.getDataMap().putLong(Link.K_FACE, Installer.installed(c, Installer.FACE_PKG));
            r.getDataMap().putString(Link.K_STATUS, p.getString("status", "not refreshed yet"));
            r.getDataMap().putString(Link.K_UPD, p.getString("updStatus", ""));
            r.getDataMap().putInt(Link.K_TIDE, p.getInt(Link.K_TIDE, -1));
            int tp = p.getInt("tidePlace", -1);
            r.getDataMap().putString(Link.K_TIDE_NAME, tp >= 0 && tp < Tide.NAMES.length ? Tide.NAMES[tp] : "");
            r.getDataMap().putInt(Link.K_PANEL, Panel.current(p, now));
            r.getDataMap().putBoolean(Link.K_AUTO, p.getBoolean(Link.K_AUTO, true));
            r.getDataMap().putBoolean(Link.K_RAIN, p.getBoolean(Link.K_RAIN, true));
            r.getDataMap().putBoolean(Link.K_COMPASS, p.getBoolean(Link.K_COMPASS, true));
            r.getDataMap().putBoolean(Link.K_ALLOWED, Installer.allowed(c));
            r.getDataMap().putLong(Link.K_REFRESHED, p.getLong("wTime", 0));
            r.getDataMap().putString(Link.K_MODEL, Build.MODEL);
            r.getDataMap().putLong(Link.K_TIME, now);
            android.content.Intent bi = c.registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (bi != null) {
                int lv = bi.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int sc = bi.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
                r.getDataMap().putInt(Link.K_BATT, lv < 0 ? -1 : Math.round(lv * 100f / Math.max(1, sc)));
                r.getDataMap().putBoolean(Link.K_CHG, bi.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0);
            }
            Wearable.getDataClient(c).putDataItem(r.asPutDataRequest().setUrgent());
        } catch (Throwable t) {
            Log.w(Store.TAG, "publish: " + t);
        }
    }
}
