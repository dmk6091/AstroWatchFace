package com.dmk.astrocompanion;

import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * International Space Station: position from its orbit (TLE from CelesTrak, fetched once a day)
 * and the next pass you can actually see from where you are. Worked out on the watch:
 * Kepler orbit with the Earth's flattening (J2) and the TLE's decay term, plenty for a dot on
 * the globe and pass times to well under a minute over two days.
 *
 * A pass is "visible" when the station is at least 10 degrees up, the sky is dark for you
 * (sun 6 degrees or more below the horizon) and the station itself is still in sunlight.
 */
final class Iss {
    private static final double MU = 398600.4418, RE = 6378.137, J2 = 1.08262668e-3;
    private static final double RAD = Math.PI / 180;
    static final String TLE_URL = "https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE";

    private Iss() {}

    // ------------------------------------------------------------ orbit

    static final class Orbit {
        long epochMs;
        double inc, raan0, ecc, argp0, m0, nRad, ndot2, a, raanDot, argpDot, mDot;

        static Orbit parse(String l1, String l2) {
            Orbit o = new Orbit();
            int yy = Integer.parseInt(l1.substring(18, 20).trim());
            double doy = Double.parseDouble(l1.substring(20, 32).trim());
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.clear();
            c.set(yy < 57 ? 2000 + yy : 1900 + yy, Calendar.JANUARY, 1, 0, 0, 0);
            o.epochMs = c.getTimeInMillis() + (long) ((doy - 1) * 86400000d);
            double ndot2RevDay2 = Double.parseDouble(l1.substring(33, 43).trim());
            o.inc = Double.parseDouble(l2.substring(8, 16).trim()) * RAD;
            o.raan0 = Double.parseDouble(l2.substring(17, 25).trim()) * RAD;
            o.ecc = Double.parseDouble("0." + l2.substring(26, 33).trim());
            o.argp0 = Double.parseDouble(l2.substring(34, 42).trim()) * RAD;
            o.m0 = Double.parseDouble(l2.substring(43, 51).trim()) * RAD;
            double nRevDay = Double.parseDouble(l2.substring(52, 63).trim());
            o.nRad = nRevDay * 2 * Math.PI / 86400.0;                     // rad/s
            o.ndot2 = ndot2RevDay2 * 2 * Math.PI / (86400.0 * 86400.0);   // rad/s^2
            o.a = Math.cbrt(MU / (o.nRad * o.nRad));
            double p = o.a * (1 - o.ecc * o.ecc);
            double k = 1.5 * J2 * (RE / p) * (RE / p) * o.nRad;
            double si = Math.sin(o.inc);
            o.raanDot = -k * Math.cos(o.inc);
            o.argpDot = k * (2 - 2.5 * si * si);
            o.mDot = o.nRad + k * Math.sqrt(1 - o.ecc * o.ecc) * (1 - 1.5 * si * si);
            return o;
        }

        /** Earth-fixed position (km) at time ms. */
        double[] ecef(long ms) {
            double dt = (ms - epochMs) / 1000.0;
            double m = m0 + mDot * dt + ndot2 * dt * dt;
            double e = ecc, ea = m;
            for (int i = 0; i < 8; i++) ea = ea - (ea - e * Math.sin(ea) - m) / (1 - e * Math.cos(ea));
            double nu = 2 * Math.atan2(Math.sqrt(1 + e) * Math.sin(ea / 2), Math.sqrt(1 - e) * Math.cos(ea / 2));
            double r = a * (1 - e * Math.cos(ea));
            double u = argp0 + argpDot * dt + nu, om = raan0 + raanDot * dt;
            double cu = Math.cos(u), su = Math.sin(u), co = Math.cos(om), so = Math.sin(om);
            double ci = Math.cos(inc), si = Math.sin(inc);
            double x = r * (co * cu - so * su * ci), y = r * (so * cu + co * su * ci), z = r * su * si;
            double th = gmst(ms);
            double ct = Math.cos(th), st = Math.sin(th);
            return new double[]{x * ct + y * st, -x * st + y * ct, z};
        }
    }

    /** Greenwich sidereal angle (radians), same formula as Astro. */
    static double gmst(long ms) {
        double d = ms / 86400000d - 0.5 + 2440588 - 2451545;
        return RAD * (280.16 + 360.9856235 * d);
    }

    static Orbit orbit(SharedPreferences p) {
        String l1 = p.getString("tle1", null), l2 = p.getString("tle2", null);
        if (l1 == null || l2 == null) return null;
        try {
            return Orbit.parse(l1, l2);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Sub-satellite point {lat, lon} in degrees. */
    static double[] subPoint(Orbit o, long ms) {
        double[] r = o.ecef(ms);
        return new double[]{Math.toDegrees(Math.atan2(r[2], Math.hypot(r[0], r[1]))),
                Math.toDegrees(Math.atan2(r[1], r[0]))};
    }

    // ------------------------------------------------------------ download (daily)

    /** Fetches a fresh orbit if the stored one is older than 12 h. Returns a status word. */
    static String refresh(SharedPreferences p, SharedPreferences.Editor e) {
        long now = System.currentTimeMillis();
        if (now - p.getLong("tleTime", 0) < 12 * 3600_000L && p.contains("tle1")) return "iss ok";
        try {
            String[] lines = Net.get(TLE_URL).trim().split("\\r?\\n");
            String l1 = null, l2 = null;
            for (String l : lines) {
                if (l.startsWith("1 ")) l1 = l;
                else if (l.startsWith("2 ")) l2 = l;
            }
            if (l1 == null || l2 == null) throw new Exception("no TLE in reply");
            Orbit.parse(l1, l2); // check it reads
            e.putString("tle1", l1);
            e.putString("tle2", l2);
            e.putLong("tleTime", now);
            return "iss ok";
        } catch (Throwable t) {
            Log.w(Engine.TAG, "iss: " + t);
            return "iss failed";
        }
    }

    // ------------------------------------------------------------ passes

    /** {start, end, maxElevationDeg, startAzDeg, endAzDeg, maxTime} of the next visible pass, or null. */
    static double[] nextVisiblePass(Orbit o, double lat, double lon, long now) {
        double phi = lat * RAD, lam = lon * RAD;
        double sp = Math.sin(phi), cp = Math.cos(phi), sl = Math.sin(lam), cl = Math.cos(lam);
        double[] obs = {RE * cp * cl, RE * cp * sl, RE * sp};
        final long step = 20_000L;
        long t = now - 15 * 60_000L, end = now + 48 * 3600_000L;
        boolean in = false, visible = false;
        double maxEl = -90, startAz = 0, lastAz = 0;
        long start = 0, maxT = 0;
        for (; t <= end; t += step) {
            double[] r = o.ecef(t);
            double rx = r[0] - obs[0], ry = r[1] - obs[1], rz = r[2] - obs[2];
            double s = sp * cl * rx + sp * sl * ry - cp * rz;
            double ee = -sl * rx + cl * ry;
            double z = cp * cl * rx + cp * sl * ry + sp * rz;
            double range = Math.sqrt(rx * rx + ry * ry + rz * rz);
            double el = Math.toDegrees(Math.asin(z / range));
            double az = (Math.toDegrees(Math.atan2(ee, -s)) + 360) % 360;
            if (el >= 10) {
                if (!in) {
                    in = true;
                    visible = false;
                    maxEl = -90;
                    start = t;
                    startAz = az;
                }
                if (el > maxEl) {
                    maxEl = el;
                    maxT = t;
                }
                lastAz = az;
                if (!visible && darkSky(t, sp, cp, sl, cl) && sunlit(r, t)) visible = true;
            } else if (in) {
                in = false;
                if (visible && t > now) return new double[]{start, t, maxEl, startAz, lastAz, maxT};
            }
        }
        return null;
    }

    /** Sun direction (Earth-fixed unit vector) at ms. */
    private static double[] sunDir(long ms) {
        double[] ss = Astro.subsolar(ms); // {dec, lon} radians
        double cd = Math.cos(ss[0]);
        return new double[]{cd * Math.cos(ss[1]), cd * Math.sin(ss[1]), Math.sin(ss[0])};
    }

    /** Sun 6 degrees or more below the observer's horizon. */
    private static boolean darkSky(long ms, double sp, double cp, double sl, double cl) {
        double[] s = sunDir(ms);
        double sinAlt = s[0] * cp * cl + s[1] * cp * sl + s[2] * sp;
        return sinAlt < Math.sin(-6 * RAD);
    }

    /** Station outside the Earth's shadow (cylinder model). */
    private static boolean sunlit(double[] r, long ms) {
        double[] s = sunDir(ms);
        double d = r[0] * s[0] + r[1] * s[1] + r[2] * s[2];
        if (d > 0) return true;
        double px = r[0] - d * s[0], py = r[1] - d * s[1], pz = r[2] - d * s[2];
        return Math.sqrt(px * px + py * py + pz * pz) > RE;
    }

    /** Text for the face: "19:42 58°", "NOW 58°", or null when no visible pass in 2 days. */
    static String faceText(SharedPreferences p, double lat, double lon, long now) {
        Orbit o = orbit(p);
        if (o == null) return null;
        double[] pass = nextVisiblePass(o, lat, lon, now);
        if (pass == null) return null;
        String el = Math.round(pass[2]) + "°";
        if (pass[0] <= now) return "NOW " + el;
        String hm = new SimpleDateFormat("HH:mm", Locale.US).format(new Date((long) pass[0]));
        // pass tomorrow or later: mark it so it isn't mistaken for today
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        b.setTimeInMillis((long) pass[0]);
        boolean today = a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR) && a.get(Calendar.YEAR) == b.get(Calendar.YEAR);
        return (today ? "" : "+") + hm + " " + el;
    }

    /** Longer description for the companion app screen. */
    static String describe(SharedPreferences p, double lat, double lon, long now) {
        Orbit o = orbit(p);
        if (o == null) return "ISS: orbit not downloaded yet";
        double[] sub = subPoint(o, now);
        StringBuilder b = new StringBuilder(String.format(Locale.US, "ISS now over %.1f°, %.1f°", sub[0], sub[1]));
        double[] pass = nextVisiblePass(o, lat, lon, now);
        if (pass == null) {
            b.append("\nNo visible pass in the next 2 days");
        } else {
            SimpleDateFormat f = new SimpleDateFormat("EEE HH:mm", Locale.US);
            long mins = Math.round((pass[1] - pass[0]) / 60000.0);
            b.append("\nNext visible pass: ").append(f.format(new Date((long) pass[0])))
                    .append(", ").append(mins).append(" min, up to ").append(Math.round(pass[2])).append("°, ")
                    .append(Astro.dir8(pass[3])).append(" → ").append(Astro.dir8(pass[4]));
        }
        return b.toString();
    }
}
