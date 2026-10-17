package com.dmk.astrocompanion;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;

/**
 * The watch's updates (face + Astro), straight from GitHub or from files the phone sent.
 * The first face update asks for one tap on the watch; after that updates go through silently,
 * in the background too (every few hours) when auto-update is on. See Installer.
 */
final class Updater {
    static final String FACE_PKG = Installer.FACE_PKG;
    static final long AUTO_EVERY = 3 * 3600_000L;
    /** One-time command for watches that hide the "install unknown apps" switch. */
    static final String GRANT_CMD = "appops set com.dmk.astrocompanion REQUEST_INSTALL_PACKAGES allow";

    private Updater() {}

    static long installed(Context c, String pkg) {
        return Installer.installed(c, pkg);
    }

    static boolean allowed(Context c) {
        return Installer.allowed(c);
    }

    static Installer.Release latest() throws Exception {
        return Installer.latest();
    }

    /** "Build 16" or "Build 16 (face 15)" for the app screen. */
    static String versions(Context c) {
        long me = installed(c, c.getPackageName()), face = installed(c, FACE_PKG);
        String s = "Build " + me;
        if (face < 0) s += " (face not installed)";
        else if (face != me) s += " (face " + face + ")";
        return s;
    }

    /**
     * Checks GitHub and installs what's newer. Blocking: never on the main thread.
     * foreground = started from the app screen (may show the installer's prompt).
     */
    static String run(Context c, boolean foreground, Installer.Progress pr) {
        return go(c, foreground, pr, null, null);
    }

    /** Installs the files the phone sent (face and / or Astro), whichever are newer. */
    static String installFiles(Context c, File face, File app, Installer.Progress pr) {
        return go(c, false, pr, face, app);
    }

    private static synchronized String go(Context c, boolean foreground, Installer.Progress pr, File faceFile, File appFile) {
        SharedPreferences p = Store.p(c);
        String res;
        boolean fromPhone = faceFile != null || appFile != null;
        try {
            if (!allowed(c)) throw new Exception("allow installing updates first (open Astro)");
            long me = installed(c, c.getPackageName()), face = installed(c, FACE_PKG);
            long target;
            boolean faceNew, meNew;
            if (fromPhone) {
                long fb = faceFile == null ? -1 : Installer.buildOf(c, faceFile);
                long ab = appFile == null ? -1 : Installer.buildOf(c, appFile);
                faceNew = fb > face;
                meNew = ab > me;
                target = Math.max(fb, ab);
            } else {
                pr.say("Checking…");
                Installer.Release r = latest();
                p.edit().putLong("updCheck", System.currentTimeMillis()).putLong("updLatest", r.build)
                        .putString("updNotes", r.notes).apply();
                faceNew = face >= 0 ? face < r.build : foreground;
                meNew = me < r.build;
                target = r.build;
            }
            if (!faceNew && !meNew) {
                res = "Up to date (build " + Math.max(me, face) + ")";
            } else {
                if (faceNew) {
                    File f = fromPhone ? faceFile : Installer.download(c, Installer.FACE_APK, "face", pr);
                    Installer.check(c, f, FACE_PKG, face);
                    pr.say(foreground ? "Installing face… (tap Install if asked)" : "Installing face…");
                    String out = Installer.install(c, f, FACE_PKG, foreground);
                    if (out != null) throw new Exception("face: " + out);
                    pr.say("Face updated to " + Installer.buildOf(c, f));
                }
                if (meNew) {
                    File f = fromPhone ? appFile : Installer.download(c, Installer.APP_APK, "Astro", pr);
                    long b = Installer.check(c, f, c.getPackageName(), me);
                    pr.say("Installing Astro… (it restarts)");
                    // installing ourselves ends this process; the result is written on restart
                    p.edit().putLong("updSelf", b).putString("updStatus", "Installing build " + b + "…").commit();
                    WatchLink.publish(c);
                    String out = Installer.install(c, f, c.getPackageName(), foreground);
                    if (out != null) throw new Exception("Astro: " + out);
                }
                res = "Updated to build " + target;
            }
        } catch (Throwable t) {
            Log.w(Store.TAG, "update: " + t);
            res = "Update: " + (t.getMessage() == null ? t.toString() : t.getMessage());
        }
        p.edit().putString("updStatus", res).putLong("updWhen", System.currentTimeMillis()).apply();
        Installer.clean(c);
        WatchLink.publish(c);
        return res;
    }

    /** From the background worker: every few hours when auto-update is on. Never asks anything. */
    static void auto(Context c) {
        SharedPreferences p = Store.p(c);
        if (!p.getBoolean("autoUpd", true) || !allowed(c)) return;
        if (System.currentTimeMillis() - p.getLong("updCheck", 0) < AUTO_EVERY) return;
        run(c, false, s -> {});
    }
}
