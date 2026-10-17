package com.dmk.astrocompanion;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Updates from the repo's "latest" GitHub release (watch and phone): read the build number,
 * download an APK, check it's the right app signed with our key, install it with Android's
 * installer. An app may update itself, and apps it installed before, without asking again.
 */
final class Installer {
    static final String REPO = "dmk6091/AstroWatchFace";
    static final String API = "https://api.github.com/repos/" + REPO + "/releases/tags/latest";
    static final String DL = "https://github.com/" + REPO + "/releases/download/latest/";
    static final String FACE_PKG = "com.dmk.astrowatchface";
    static final String FACE_APK = "AstroWatchFace.apk", APP_APK = "AstroCompanion.apk", PHONE_APK = "AstroPhone.apk";

    private Installer() {}

    interface Progress {
        void say(String s);
    }

    static final class Release {
        long build;
        String notes;
    }

    static Release latest() throws Exception {
        String json;
        try {
            json = Net.get(API);
        } catch (Exception e) {
            if (String.valueOf(e.getMessage()).contains("HTTP 404")) throw new Exception("no public release");
            throw e;
        }
        String body = new JSONObject(json).optString("body", "");
        Matcher m = Pattern.compile("build=(\\d+)").matcher(body);
        if (!m.find()) throw new Exception("release has no build number");
        Release r = new Release();
        r.build = Long.parseLong(m.group(1));
        r.notes = body.substring(m.end()).trim();
        return r;
    }

    static long installed(Context c, String pkg) {
        try {
            return c.getPackageManager().getPackageInfo(pkg, 0).getLongVersionCode();
        } catch (Exception e) {
            return -1;
        }
    }

    static boolean allowed(Context c) {
        try {
            return c.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            return false;
        }
    }

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "update");
        d.mkdirs();
        return d;
    }

    static void clean(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    /** Downloads a release file into the update folder. */
    static File download(Context c, String name, String label, Progress pr) throws Exception {
        File out = new File(dir(c), name);
        URL url = new URL(DL + name);
        HttpURLConnection h;
        for (int i = 0; ; i++) {
            h = (HttpURLConnection) url.openConnection();
            h.setInstanceFollowRedirects(false); // GitHub hands the file over from another host
            h.setConnectTimeout(15_000);
            h.setReadTimeout(30_000);
            h.setRequestProperty("User-Agent", Net.UA);
            int code = h.getResponseCode();
            if (code >= 300 && code < 400 && i < 6) {
                url = new URL(url, h.getHeaderField("Location"));
                h.disconnect();
                continue;
            }
            if (code != 200) throw new Exception(name + ": HTTP " + code);
            break;
        }
        long total = h.getContentLengthLong(), got = 0, shown = 0;
        try (InputStream in = h.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                fo.write(buf, 0, n);
                got += n;
                if (total > 0 && got - shown > total / 10) {
                    shown = got;
                    pr.say(String.format(java.util.Locale.US, "Downloading %s %d%%", label, got * 100 / total));
                }
            }
        } finally {
            h.disconnect();
        }
        if (total > 0 && got != total) throw new Exception(name + ": download cut off");
        if (out.length() < 10_000) throw new Exception(name + ": file too small");
        return out;
    }

    /** Right app, newer than what's installed, and signed with the same key as this app. Returns its build. */
    static long check(Context c, File f, String pkg, long installedVersion) throws Exception {
        PackageManager pm = c.getPackageManager();
        PackageInfo a = pm.getPackageArchiveInfo(f.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (a == null) throw new Exception(f.getName() + " isn't a valid app file");
        if (!pkg.equals(a.packageName)) throw new Exception(f.getName() + " is " + a.packageName);
        if (a.getLongVersionCode() <= installedVersion) throw new Exception(f.getName() + " isn't newer");
        PackageInfo me = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        Signature[] want = signers(me.signingInfo), have = signers(a.signingInfo);
        // (Android refuses a differently signed update anyway; this also covers a fresh install)
        if (want != null && have != null && !Arrays.equals(want, have))
            throw new Exception(f.getName() + " is signed with another key");
        return a.getLongVersionCode();
    }

    /** Build number inside an APK file, -1 if unreadable. */
    static long buildOf(Context c, File f) {
        try {
            PackageInfo a = c.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
            return a == null ? -1 : a.getLongVersionCode();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Signature[] signers(SigningInfo s) {
        if (s == null) return null;
        return s.hasMultipleSigners() ? s.getApkContentsSigners() : s.getSigningCertificateHistory();
    }

    /** Waiting install sessions: id -> results posted by InstallStatus. */
    static final Map<Integer, BlockingQueue<Object[]>> WAIT = new ConcurrentHashMap<>();

    /**
     * Installs and waits for the outcome. Returns null when installed, else the reason.
     * foreground: started from a screen, so Android's Install / Cancel prompt may be shown.
     */
    static String install(Context c, File f, String pkg, boolean foreground) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(pkg);
        sp.setSize(f.length());
        if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(sp);
        BlockingQueue<Object[]> q = new ArrayBlockingQueue<>(8);
        WAIT.put(id, q);
        try {
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (OutputStream o = s.openWrite("base.apk", 0, f.length()); InputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    s.fsync(o);
                }
                Intent i = new Intent(c, InstallStatus.class).putExtra(InstallStatus.EXTRA_FG, foreground);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                s.commit(PendingIntent.getBroadcast(c, id, i, flags).getIntentSender());
            }
            long deadline = System.currentTimeMillis() + (foreground ? 180_000L : 90_000L);
            while (true) {
                long left = deadline - System.currentTimeMillis();
                Object[] r = left > 0 ? q.poll(left, TimeUnit.MILLISECONDS) : null;
                if (r == null) {
                    try { pi.abandonSession(id); } catch (Throwable ignored) {}
                    return "no answer from the installer";
                }
                int status = (Integer) r[0];
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    if (!foreground) {
                        try { pi.abandonSession(id); } catch (Throwable ignored) {}
                        return "needs one tap: open Astro → Update now";
                    }
                    continue; // the prompt is on screen; wait for the final answer
                }
                if (status == PackageInstaller.STATUS_SUCCESS) return null;
                String msg = (String) r[1];
                if (status == PackageInstaller.STATUS_FAILURE_ABORTED) return "cancelled";
                if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)
                    return "signed differently: reinstall once by hand (" + msg + ")";
                return msg == null ? "failed (" + status + ")" : msg;
            }
        } finally {
            WAIT.remove(id);
        }
    }
}
