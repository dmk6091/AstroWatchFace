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

    /** Sun altitude in degrees above the horizon (negative below). */
    static double sunAltitude(long ms, double lat, double lon) {
        double lw = RAD * -lon, phi = RAD * lat, d = toDays(ms);
        double[] c = sunCoords(d);
        double h = siderealTime(d, lw) - c[1];
        return Math.toDegrees(altitude(h, phi, c[0]));
    }

    /** Moon azimuth in degrees from north (0..360). */
    static double moonAzimuth(long ms, double lat, double lon) {
        double lw = RAD * -lon, phi = RAD * lat, d = toDays(ms);
        double ml = RAD * (218.316 + 13.176396 * d);
        double mm = RAD * (134.963 + 13.064993 * d);
        double f = RAD * (93.272 + 13.229350 * d);
        double l = ml + RAD * 6.289 * Math.sin(mm);
        double b = RAD * 5.128 * Math.sin(f);
        double ra = rightAscension(l, b), dec = declination(l, b);
        double h = siderealTime(d, lw) - ra;
        double az = Math.toDegrees(azimuth(h, phi, dec));
        return ((az + 180) % 360 + 360) % 360;
    }

    /** Fraction of the moon's disc that is lit (0..1), from its age. */
    static double moonLit(long ms) {
        return (1 - Math.cos(2 * Math.PI * moonAge(ms) / 29.530589)) / 2;
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

    /**
     * Where the Sun is in its day, as a short name: SOLAR NOON / SOLAR MIDNIGHT (within 20 min of the
     * highest / lowest point), SUNRISE / SUNSET, GOLDEN HOUR, BLUE HOUR, CIVIL / NAUTICAL / ASTRO DAWN
     * and DUSK, MORNING, AFTERNOON, NIGHT.
     */
    static String sunPhase(long now, double lat, double lon) {
        double alt = sunAltitude(now, lat, lon);
        boolean rising = sunAltitude(now + 300_000L, lat, lon) > alt;
        // highest and lowest point within +-12 h
        long step = 5 * 60_000L, tHi = now, tLo = now;
        double hi = -999, lo = 999;
        for (long t = now - 12 * 3600_000L; t <= now + 12 * 3600_000L; t += step) {
            double a = sunAltitude(t, lat, lon);
            if (a > hi) {
                hi = a;
                tHi = t;
            }
            if (a < lo) {
                lo = a;
                tLo = t;
            }
        }
        long edge = 12 * 3600_000L - step;
        if (Math.abs(tHi - now) <= 20 * 60_000L && Math.abs(tHi - now) < edge && alt > 0) return "SOLAR NOON";
        if (Math.abs(tLo - now) <= 20 * 60_000L && Math.abs(tLo - now) < edge && alt < 0) return "SOLAR MIDNIGHT";
        if (alt > 6) return rising ? "MORNING" : "AFTERNOON";
        if (alt > 2) return "GOLDEN HOUR";
        if (alt > -0.833) return rising ? "SUNRISE" : "SUNSET";
        if (alt > -4) return rising ? "CIVIL DAWN" : "CIVIL DUSK";
        if (alt > -6) return "BLUE HOUR";
        if (alt > -12) return rising ? "NAUTICAL DAWN" : "NAUTICAL DUSK";
        if (alt > -18) return rising ? "ASTRO DAWN" : "ASTRO DUSK";
        return "NIGHT";
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

    /** Sun's declination and the longitude where it is overhead (radians): {dec, lon}. */
    static double[] subsolar(long ms) {
        double d = toDays(ms);
        double[] c = sunCoords(d);
        double gmst = RAD * (280.16 + 360.9856235 * d);
        double lon = c[1] - gmst;
        lon = ((lon + Math.PI) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI) - Math.PI;
        return new double[]{c[0], lon};
    }

    /** Where the Moon is straight overhead: {lat, lon} in degrees. */
    static double[] sublunar(long ms) {
        double d = toDays(ms);
        double ml = RAD * (218.316 + 13.176396 * d);
        double mm = RAD * (134.963 + 13.064993 * d);
        double f = RAD * (93.272 + 13.229350 * d);
        double l = ml + RAD * 6.289 * Math.sin(mm);
        double b = RAD * 5.128 * Math.sin(f);
        double ra = rightAscension(l, b), dec = declination(l, b);
        double lon = ra - RAD * (280.16 + 360.9856235 * d);
        lon = ((lon + Math.PI) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI) - Math.PI;
        return new double[]{Math.toDegrees(dec), Math.toDegrees(lon)};
    }

    /** Moon age in days since new moon (0..29.5), from the sun-moon elongation. */
    static double moonAge(long ms) {
        double d = toDays(ms);
        double[] s = sunCoords(d); // {dec, ra}
        double ml = RAD * (218.316 + 13.176396 * d);
        double mm = RAD * (134.963 + 13.064993 * d);
        double f = RAD * (93.272 + 13.229350 * d);
        double l = ml + RAD * 6.289 * Math.sin(mm);
        double b = RAD * 5.128 * Math.sin(f);
        double mdist = 385001 - 20905 * Math.cos(mm);
        double mra = rightAscension(l, b), mdec = declination(l, b);
        double sdist = 149598000;
        double phi = Math.acos(Math.sin(s[0]) * Math.sin(mdec) + Math.cos(s[0]) * Math.cos(mdec) * Math.cos(s[1] - mra));
        double inc = Math.atan2(sdist * Math.sin(phi), mdist - sdist * Math.cos(phi));
        double angle = Math.atan2(Math.cos(s[0]) * Math.sin(s[1] - mra),
                Math.sin(s[0]) * Math.cos(mdec) - Math.cos(s[0]) * Math.sin(mdec) * Math.cos(s[1] - mra));
        double phase = 0.5 + 0.5 * inc * (angle < 0 ? -1 : 1) / Math.PI;
        return phase * 29.530589;
    }

    /** Well-known deep-sky objects: name for the face, right ascension (hours) and declination (degrees), J2000. */
    private static final Object[][] DEEP_SKY = {
            {"M31 ANDROMEDA", 0.712, 41.27}, {"M33 TRIANGULUM", 1.564, 30.66}, {"DOUBLE CLUSTER", 2.33, 57.13},
            {"M45 PLEIADES", 3.79, 24.12}, {"M42 ORION", 5.588, -5.39}, {"M35", 6.15, 24.33}, {"M44 BEEHIVE", 8.667, 19.67},
            {"M81 BODE", 9.926, 69.07}, {"M104 SOMBRERO", 12.666, -11.62}, {"M87", 12.514, 12.39}, {"M3", 13.703, 28.38},
            {"OMEGA CENTAURI", 13.446, -47.48}, {"M51 WHIRLPOOL", 13.498, 47.20}, {"M5", 15.31, 2.08}, {"M4", 16.393, -26.53},
            {"M13 HERCULES", 16.695, 36.46}, {"M6 BUTTERFLY", 17.668, -32.25}, {"M7 PTOLEMY", 17.898, -34.79},
            {"M92", 17.285, 43.14}, {"M20 TRIFID", 18.04, -23.03}, {"M8 LAGOON", 18.06, -24.38}, {"M16 EAGLE", 18.313, -13.82},
            {"M17 OMEGA", 18.35, -16.17}, {"M22", 18.607, -23.90}, {"M11 WILD DUCK", 18.851, -6.27}, {"M57 RING", 18.893, 33.03},
            {"M27 DUMBBELL", 19.994, 22.72}, {"NORTH AMERICA", 20.98, 44.33}, {"M15", 21.5, 12.17}, {"M2", 21.558, -0.82},
            {"HELIX", 22.494, -20.84}};

    /**
     * The deep-sky object that gets highest in the coming dark sky (sun more than 12 degrees down), with
     * the time it's highest: "M31 ANDROMEDA 23:15". Empty when it never gets above 25 degrees.
     */
    static String deepSky(long now, double lat, double lon) {
        final long step = 10 * 60_000L, span = 30 * 3600_000L;
        long start = -1, end = -1;
        for (long t = now; t <= now + span; t += step) {
            boolean dark = sunAltitude(t, lat, lon) < -12;
            if (dark && start < 0) start = t;
            if (!dark && start >= 0) {
                end = t;
                break;
            }
        }
        if (start < 0) return "";
        if (end < 0) end = now + span;
        double lw = RAD * -lon, phi = RAD * lat, bestAlt = -90;
        String best = "";
        long bestAt = start;
        for (Object[] o : DEEP_SKY) {
            double ra = (Double) o[1] * 15 * RAD, dec = (Double) o[2] * RAD, peak = -90;
            long at = start;
            for (long t = start; t <= end; t += step) {
                double a = Math.toDegrees(altitude(siderealTime(toDays(t), lw) - ra, phi, dec));
                if (a > peak) {
                    peak = a;
                    at = t;
                }
            }
            if (peak > bestAlt) {
                bestAlt = peak;
                best = (String) o[0];
                bestAt = at;
            }
        }
        if (bestAlt < 25) return "";
        return best + " " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date(bestAt));
    }

    /** 8-point compass name for a direction in degrees. */
    static String dir8(double deg) {
        String[] n = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return n[(int) Math.round(((deg % 360) + 360) % 360 / 45.0) % 8];
    }
}
