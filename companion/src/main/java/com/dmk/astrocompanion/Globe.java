package com.dmk.astrocompanion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.InputStream;

/**
 * Live Earth: orthographic globe centred on the user, real day / night line (city lights on the
 * night side), and an arc over it with the sun moving from sunrise (left, east) to sunset.
 * Same maths as tools/globe.py, which draws the face's fallback globe.
 */
final class Globe {
    private static final double RAD = Math.PI / 180;
    private static int[] sDay, sNight;
    private static int sW, sH;
    private static Bitmap sCache;
    private static long sCacheAt;

    private Globe() {}

    private static synchronized boolean load(Context ctx) {
        if (sDay != null) return true;
        try (InputStream a = ctx.getAssets().open("earth_day.jpg");
             InputStream b = ctx.getAssets().open("earth_night.jpg")) {
            Bitmap d = BitmapFactory.decodeStream(a), n = BitmapFactory.decodeStream(b);
            sW = d.getWidth();
            sH = d.getHeight();
            sDay = new int[sW * sH];
            sNight = new int[sW * sH];
            d.getPixels(sDay, 0, sW, 0, 0, sW, sH);
            Bitmap ns = n.getWidth() == sW ? n : Bitmap.createScaledBitmap(n, sW, sH, true);
            ns.getPixels(sNight, 0, sW, 0, 0, sW, sH);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** sunFrac: 0 at sunrise .. 1 at sunset, or NaN at night. Cached for 5 minutes. */
    static synchronized Bitmap render(Context ctx, double lat0, double lon0, long now, double sunFrac) {
        return render(ctx, lat0, lon0, now, sunFrac, null);
    }

    /** Project lat/lon (deg) onto the globe: {x, y} or null on the far side. */
    private static float[] project(double lat, double lon, double lat0, double lon0, double cx, double cy, double r) {
        double p = lat * RAD, l = lon * RAD, p0 = lat0 * RAD, l0 = lon0 * RAD;
        double cosc = Math.sin(p0) * Math.sin(p) + Math.cos(p0) * Math.cos(p) * Math.cos(l - l0);
        if (cosc < 0) return null;
        double x = Math.cos(p) * Math.sin(l - l0);
        double y = Math.cos(p0) * Math.sin(p) - Math.sin(p0) * Math.cos(p) * Math.cos(l - l0);
        return new float[]{(float) (cx + x * r), (float) (cy - y * r)};
    }

    /** With the ISS (dot + 20 min trail + 10 min ahead) when its orbit is known. Cached 2 min. */
    static synchronized Bitmap render(Context ctx, double lat0, double lon0, long now, double sunFrac, Iss.Orbit iss) {
        if (sCache != null && now - sCacheAt < 2 * 60_000L) return sCache;
        // The globe fills the whole round face; the day side is dimmed so the text on top reads well.
        final int size = 450;
        final double dim = 0.55;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        if (!load(ctx)) return bmp;
        int[] px = new int[size * size];
        double cx = size / 2.0, cy = size / 2.0, r = size / 2.0;
        double[] ss = Astro.subsolar(now);
        double sd = ss[0], sl = ss[1];
        double p0 = lat0 * RAD, l0 = lon0 * RAD;
        double sinP0 = Math.sin(p0), cosP0 = Math.cos(p0), sinSd = Math.sin(sd), cosSd = Math.cos(sd);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double xn = (x + 0.5 - cx) / r, yn = (cy - y - 0.5) / r, rr = xn * xn + yn * yn;
                if (rr > 1) {
                    px[y * size + x] = Color.BLACK;   // outside the round screen
                    continue;
                }
                double rho = Math.sqrt(rr), c = Math.asin(Math.min(1, rho));
                double lat, lon;
                if (rho == 0) {
                    lat = p0;
                    lon = l0;
                } else {
                    double sc = Math.sin(c), cc = Math.cos(c);
                    lat = Math.asin(cc * sinP0 + yn * sc * cosP0 / rho);
                    lon = l0 + Math.atan2(xn * sc, rho * cc * cosP0 - yn * sc * sinP0);
                }
                double lonDeg = ((lon / RAD + 180) % 360 + 360) % 360;
                int u = Math.min(sW - 1, (int) (lonDeg / 360 * sW));
                int v = Math.min(sH - 1, Math.max(0, (int) ((90 - lat / RAD) / 180 * sH)));
                int dc = sDay[v * sW + u];
                int li = Color.red(sNight[v * sW + u]);
                double cz = Math.sin(lat) * sinSd + Math.cos(lat) * cosSd * Math.cos(lon - sl);
                double dayf = Math.max(0, Math.min(1, (cz + 0.08) / 0.16));
                double shade = 0.35 + 0.65 * Math.pow(Math.max(0, cz), 0.6);
                double limb = 0.7 + 0.3 * Math.sqrt(Math.max(0, 1 - rr));
                double dr = Color.red(dc), dg = Color.green(dc), db = Color.blue(dc);
                double nr = dr * 0.07 + li * 0.9, ng = dg * 0.07 + li * 0.75, nb = db * 0.09 + li * 0.4;
                double k = dim * dayf + (1 - dayf);
                int cr = (int) Math.min(255, (dr * shade * dayf + nr * (1 - dayf)) * limb * k);
                int cg = (int) Math.min(255, (dg * shade * dayf + ng * (1 - dayf)) * limb * k);
                int cb = (int) Math.min(255, (db * shade * dayf + nb * (1 - dayf)) * limb * k);
                px[y * size + x] = Color.rgb(cr, cg, cb);
            }
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size);
        Canvas cv = new Canvas(bmp);
        // the sun: a soft glow over the place where it is straight overhead (when on our side)
        float[] sp = project(sd / RAD, sl / RAD, lat0, lon0, cx, cy, r);
        if (sp != null) {
            Paint sun = new Paint(Paint.ANTI_ALIAS_FLAG);
            sun.setColor(Color.argb(35, 255, 200, 80));
            cv.drawCircle(sp[0], sp[1], 18, sun);
            sun.setColor(Color.argb(80, 255, 200, 80));
            cv.drawCircle(sp[0], sp[1], 11, sun);
            sun.setColor(Color.rgb(255, 200, 80));
            cv.drawCircle(sp[0], sp[1], 6, sun);
        }
        if (iss != null) drawIss(cv, iss, now, lat0, lon0, cx, cy, r);
        Paint me = new Paint(Paint.ANTI_ALIAS_FLAG);
        me.setStyle(Paint.Style.STROKE);
        me.setStrokeWidth(2.5f);
        me.setColor(Color.rgb(255, 140, 60));
        cv.drawCircle((float) cx, (float) cy, 5, me);
        sCache = bmp;
        sCacheAt = now;
        return bmp;
    }

    private static void drawIss(Canvas cv, Iss.Orbit o, long now, double lat0, double lon0, double cx, double cy, double r) {
        Paint trail = new Paint(Paint.ANTI_ALIAS_FLAG);
        trail.setStyle(Paint.Style.FILL);
        // past 20 min: fading dots; next 10 min: faint small dots (where it's heading)
        for (int m = -20; m <= 10; m++) {
            if (m == 0) continue;
            double[] sp = Iss.subPoint(o, now + m * 60_000L);
            float[] q = project(sp[0], sp[1], lat0, lon0, cx, cy, r);
            if (q == null) continue;
            int a = m < 0 ? (int) (40 + 150 * (1 + m / 20.0)) : 70;
            trail.setColor(Color.argb(a, 230, 240, 255));
            cv.drawCircle(q[0], q[1], m < 0 ? 1.8f : 1.3f, trail);
        }
        double[] sp = Iss.subPoint(o, now);
        float[] q = project(sp[0], sp[1], lat0, lon0, cx, cy, r);
        if (q == null) return;   // on the far side of the Earth right now
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.argb(70, 255, 255, 255));
        cv.drawCircle(q[0], q[1], 9, p);
        p.setColor(Color.WHITE);
        cv.drawCircle(q[0], q[1], 4.5f, p);
        p.setColor(Color.rgb(90, 160, 255));
        cv.drawCircle(q[0], q[1], 2.2f, p);
        p.setColor(Color.WHITE);
        p.setTextSize(15);
        p.setFakeBoldText(true);
        p.setShadowLayer(3, 0, 0, Color.BLACK);
        cv.drawText("ISS", q[0] + 7, q[1] - 6, p);
    }
}
