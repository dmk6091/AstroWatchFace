package com.dmk.astrocompanion;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.util.Log;

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
 * Updates straight from the repo's "latest" GitHub release, on the watch itself: no phone, no
 * adb. Checks the build number, downloads AstroWatchFace.apk / AstroCompanion.apk, checks each
 * file is the right app signed with the same key, then installs it with Android's installer.
 *
 * Android lets an app update itself, and any app it installed before, without asking again. So
 * the first face update asks for one tap on the watch; after that updates go through silently,
 * and in the background too (every few hours) when auto-update is on.
 */
final class Updater {
    static final String REPO = "dmk6091/AstroWatchFace";
    static final String API = "https://api.github.com/repos/" + REPO + "/releases/tags/latest";
    static final String DL = "https://github.com/" + REPO + "/releases/download/latest/";
    static final String FACE_PKG = "com.dmk.astrowatchface";
    static final String FACE_APK = "AstroWatchFace.apk", APP_APK = "AstroCompanion.apk";
    static final long AUTO_EVERY = 3 * 3600_000L;
    /** One-time command for watches that hide the "install unknown apps" switch. */
    static final String GRANT_CMD = "appops set com.dmk.astrocompanion REQUEST_INSTALL_PACKAGES allow";

    private Updater() {}

    interface Progress {
        void say(String s);
    }

    static final class Release {
        long build;
        String notes;
    }

    // ------------------------------------------------------------ release info

    static Release latest() throws Exception {
        String json;
        try {
            json = Net.get(API);
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("HTTP 404")) throw new Exception("no public release (is the repo public?)");
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

    /** "Build 16" or "Build 16 (face 15)" for the app screen. */
    static String versions(Context c) {
        long me = installed(c, c.getPackageName()), face = installed(c, FACE_PKG);
        String s = "Build " + me;
        if (face < 0) s += " (face not installed)";
        else if (face != me) s += " (face " + face + ")";
        return s;
    }

    // ------------------------------------------------------------ the whole update

    /**
     * Checks and installs what's newer. Blocking: never call on the main thread.
     * foreground = started from the app screen (may show the installer's confirmation).
     * Returns a short result line (also saved as "updStatus").
     */
    static synchronized String run(Context c, boolean foreground, Progress pr) {
        SharedPreferences p = Store.p(c);
        String res;
        try {
            if (!allowed(c)) throw new Exception("allow installing updates first");
            pr.say("Checking…");
            Release r = latest();
            p.edit().putLong("updCheck", System.currentTimeMillis()).putLong("updLatest", r.build)
                    .putString("updNotes", r.notes).apply();
            long me = installed(c, c.getPackageName()), face = installed(c, FACE_PKG);
            boolean faceNew = face >= 0 ? face < r.build : foreground;
            boolean meNew = me < r.build;
            if (!faceNew && !meNew) {
                res = "Up to date (build " + Math.max(me, face) + ")";
            } else {
                if (faceNew) {
                    pr.say("Downloading face…");
                    File f = download(c, FACE_APK, pr);
                    check(c, f, FACE_PKG, face);
                    pr.say(foreground ? "Installing face… (tap Install if asked)" : "Installing face…");
                    String out = install(c, f, FACE_PKG, foreground);
                    if (out != null) throw new Exception("face: " + out);
                    pr.say("Face updated to " + r.build);
                }
                if (meNew) {
                    pr.say("Downloading Astro…");
                    File f = download(c, APP_APK, pr);
                    check(c, f, c.getPackageName(), me);
                    pr.say("Installing Astro… (it restarts)");
                    // installing ourselves ends this process; the result is written on restart
                    p.edit().putLong("updSelf", r.build).putString("updStatus", "Installing build " + r.build + "…").commit();
                    String out = install(c, f, c.getPackageName(), foreground);
                    if (out != null) throw new Exception("Astro: " + out);
                }
                res = "Updated to build " + r.build;
            }
        } catch (Throwable t) {
            Log.w(Engine.TAG, "update: " + t);
            res = "Update: " + (t.getMessage() == null ? t.toString() : t.getMessage());
        }
        p.edit().putString("updStatus", res).putLong("updWhen", System.currentTimeMillis()).apply();
        clean(c);
        return res;
    }

    /** From the background worker: every few hours when auto-update is on. Never asks anything. */
    static void auto(Context c) {
        SharedPreferences p = Store.p(c);
        if (!p.getBoolean("autoUpd", true) || !allowed(c)) return;
        if (System.currentTimeMillis() - p.getLong("updCheck", 0) < AUTO_EVERY) return;
        run(c, false, s -> {});
    }

    // ------------------------------------------------------------ download + checks

    private static File download(Context c, String name, Progress pr) throws Exception {
        File dir = new File(c.getCacheDir(), "update");
        dir.mkdirs();
        File out = new File(dir, name);
        URL url = new URL(DL + name);
        HttpURLConnection h = null;
        for (int i = 0; ; i++) {
            h = (HttpURLConnection) url.openConnection();
            h.setInstanceFollowRedirects(false); // GitHub hands the file over from another host
            h.setConnectTimeout(15_000);
            h.setReadTimeout(30_000);
            h.setRequestProperty("User-Agent", "AstroWatchFace/1.0 (Wear OS companion)");
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
                    pr.say(String.format(java.util.Locale.US, "Downloading %s %d%%",
                            name.startsWith("AstroWatch") ? "face" : "Astro", got * 100 / total));
                }
            }
        } finally {
            h.disconnect();
        }
        if (total > 0 && got != total) throw new Exception(name + ": download cut off");
        if (out.length() < 10_000) throw new Exception(name + ": file too small");
        return out;
    }

    /** Right app, newer than what's installed, and signed with the same key as this app. */
    private static void check(Context c, File f, String pkg, long installedVersion) throws Exception {
        PackageManager pm = c.getPackageManager();
        PackageInfo a = pm.getPackageArchiveInfo(f.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (a == null) throw new Exception(f.getName() + " isn't a valid app file");
        if (!pkg.equals(a.packageName)) throw new Exception(f.getName() + " is " + a.packageName);
        if (a.getLongVersionCode() <= installedVersion) throw new Exception(f.getName() + " isn't newer");
        PackageInfo me = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        Signature[] want = signers(me.signingInfo), have = signers(a.signingInfo);
        // (Android refuses a differently signed update anyway; this also covers a fresh face install)
        if (want != null && have != null && !Arrays.equals(want, have))
            throw new Exception(f.getName() + " is signed with another key");
    }

    private static Signature[] signers(SigningInfo s) {
        if (s == null) return null;
        return s.hasMultipleSigners() ? s.getApkContentsSigners() : s.getSigningCertificateHistory();
    }

    private static void clean(Context c) {
        File[] fs = new File(c.getCacheDir(), "update").listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    // ------------------------------------------------------------ install

    /** Waiting install sessions: id -> results posted by InstallReceiver. */
    static final Map<Integer, BlockingQueue<Object[]>> WAIT = new ConcurrentHashMap<>();

    /** Installs and waits for the outcome. Returns null when installed, else the reason. */
    private static String install(Context c, File f, String pkg, boolean foreground) throws Exception {
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
                Intent i = new Intent(c, InstallReceiver.class)
                        .putExtra(InstallReceiver.EXTRA_FG, foreground);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                s.commit(PendingIntent.getBroadcast(c, id, i, flags).getIntentSender());
            }
            // a confirmation on screen can take a while; otherwise it's seconds
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
                    continue; // the confirmation is on screen; wait for the final answer
                }
                if (status == PackageInstaller.STATUS_SUCCESS) return null;
                String msg = (String) r[1];
                if (status == PackageInstaller.STATUS_FAILURE_ABORTED) return "cancelled";
                if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)
                    return "signed differently: reinstall once with GeminiMan (" + msg + ")";
                return msg == null ? "failed (" + status + ")" : msg;
            }
        } finally {
            WAIT.remove(id);
        }
    }
}
