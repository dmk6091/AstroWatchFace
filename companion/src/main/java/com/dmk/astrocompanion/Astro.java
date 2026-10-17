package com.dmk.astrocompanion;

/**
 * Sun and moon positions (port of the "suncalc" formulas, accurate to a few minutes, which is
 * plenty for a watch face). Angles in, degrees out.
 */
final class Astro {
    private static final double RAD = Math.PI / 180, DAY_MS = 86400000d, J1970 = 2440588, J2000 = 2451545;
    private static final double E = RAD * 23.4397; // obliquity of the Earth

    private Astro() {}

    private static double toDays(long ms) {
        return ms / DAY_MS - 0.5 + J1970 - J2000;
    }

    private static double rightAscension(double l, double b) {
        return Math.atan2(Math.sin(l) * Math.cos(E) - Math.tan(b) * Math.sin(E), Math.cos(l));
    }

    private static double declination(double l, double b) {
        return Math.asin(Math.sin(b) * Math.cos(E) + Math.cos(b) * Math.sin(E) * Math.sin(l));
    }

    private static double azimuth(double h, double phi, double dec) {
        return Math.atan2(Math.sin(h), Math.cos(h) * Math.sin(phi) - Math.tan(dec) * Math.cos(phi));
    }

    private static double altitude(double h, double phi, double dec) {
        return Math.asin(Math.sin(phi) * Math.sin(dec) + Math.cos(phi) * Math.cos(dec) * Math.cos(h));
    }

    private static double siderealTime(double d, double lw) {
        return RAD * (280.16 + 360.9856235 * d) - lw;
    }

    /** {declination, right ascension} of the sun. */
    private static double[] sunCoords(double d) {
        double m = RAD * (357.5291 + 0.98560028 * d);
        double c = RAD * (1.9148 * Math.sin(m) + 0.02 * Math.sin(2 * m) + 0.0003 * Math.sin(3 * m));
        double l = m + c + RAD * 102.9372 + Math.PI;
        return new double[]{declination(l, 0), rightAscension(l, 0)};
    }

    /** Sun azimuth in degrees from north (0..360). */
    static double sunAzimuth(long ms, double lat, double lon) {
        double lw = RAD * -lon, phi = RAD * lat, d = toDays(ms);
        double[] c = sunCoords(d);
        double h = siderealTime(d, lw) - c[1];
        double az = Math.toDegrees(azimuth(h, phi, c[0]));
        return ((az + 180) % 360 + 360) % 360;
    }

    /** Geocentric altitude of the moon in degrees. */
    static double moonAltitude(long ms, double lat, double lon) {
        double lw = RAD * -lon, phi = RAD * lat, d = toDays(ms);
        double ml = RAD * (218.316 + 13.176396 * d);
        double mm = RAD * (134.963 + 13.064993 * d);
        double f = RAD * (93.272 + 13.229350 * d);
        double l = ml + RAD * 6.289 * Math.sin(mm);
        double b = RAD * 5.128 * Math.sin(f);
        double ra = rightAscension(l, b), dec = declination(l, b);
        double h = siderealTime(d, lw) - ra;
        return Math.toDegrees(altitude(h, phi, dec));
    }

    /** Moonrise and moonset (epoch ms) within 24 h from start, or -1 when there is none. */
    static long[] moonTimes(long start, double lat, double lon) {
        final double h0 = 0.133; // geocentric altitude at rise/set (parallax, refraction, radius)
        final long step = 10 * 60_000L;
        long rise = -1, set = -1;
        double prev = moonAltitude(start, lat, lon) - h0;
        for (long t = start + step; t <= start + 86_400_000L; t += step) {
            double cur = moonAltitude(t, lat, lon) - h0;
            if (prev < 0 && cur >= 0 && rise < 0) rise = t - step + (long) (step * (-prev / (cur - prev)));
            if (prev >= 0 && cur < 0 && set < 0) set = t - step + (long) (step * (prev / (prev - cur)));
            prev = cur;
        }
        return new long[]{rise, set};
    }

    /** 8-point compass name for a direction in degrees. */
    static String dir8(double deg) {
        String[] n = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return n[(int) Math.round(((deg % 360) + 360) % 360 / 45.0) % 8];
    }
}
