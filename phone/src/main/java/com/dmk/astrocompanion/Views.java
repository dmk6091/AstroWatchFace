package com.dmk.astrocompanion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** The small drawings on the phone: rain bars, tide curve, pressure line, moon disc. */
final class Views {
    private Views() {}

    private static float dp(View v, float x) {
        return x * v.getResources().getDisplayMetrics().density;
    }

    /** Next 2 hours of rain, 8 bars of 15 minutes, clock times under them. */
    static final class RainBars extends View {
        double[] rates = new double[8];
        long now;
        Typeface tf;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        RainBars(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), (int) dp(this, 92));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), top = dp(this, 6), base = getHeight() - dp(this, 24);
            float bw = w / 8f;
            for (int i = 0; i < 8; i++) {
                double v = rates[i];
                float l = i * bw + dp(this, 4), rr = (i + 1) * bw - dp(this, 4);
                if (!Rain.wet(v)) {
                    p.setColor(0xFF3A4A66);
                    p.setStrokeWidth(dp(this, 3));
                    c.drawLine(l, base, rr, base, p);
                    continue;
                }
                float hgt = (base - top) * (float) Math.max(0.12, Math.min(1, v / 4));
                p.setColor(v < 2.5 ? Color.rgb(91, 168, 242) : v < 7.6 ? Color.rgb(60, 120, 255) : Color.rgb(150, 90, 255));
                r.set(l, base - hgt, rr, base);
                c.drawRoundRect(r, dp(this, 5), dp(this, 5), p);
            }
            p.setColor(Ui.DIM);
            p.setTextSize(dp(this, 12));
            if (tf != null) p.setTypeface(tf);
            SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
            for (int i = 0; i <= 4; i++) {
                String s = f.format(new Date(now + i * 30 * 60_000L));
                float x = i * 2 * bw;
                p.setTextAlign(i == 0 ? Paint.Align.LEFT : i == 4 ? Paint.Align.RIGHT : Paint.Align.CENTER);
                c.drawText(s, x, getHeight() - dp(this, 4), p);
            }
        }
    }

    /** Today's tide (00 to 24 h), filled under the line, a dot at now. */
    static final class TideCurve extends View {
        Tide.Series s;
        long day0, now;
        Typeface tf;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        TideCurve(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), (int) dp(this, 130));
        }

        @Override
        protected void onDraw(Canvas c) {
            if (s == null) return;
            float w = getWidth(), top = dp(this, 8), base = getHeight() - dp(this, 26);
            int n = 96;
            double[] v = new double[n + 1];
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int i = 0; i <= n; i++) {
                v[i] = s.at(day0 / 1000 + i * 900L);
                if (!Double.isNaN(v[i])) {
                    lo = Math.min(lo, v[i]);
                    hi = Math.max(hi, v[i]);
                }
            }
            if (lo == Double.MAX_VALUE) return;
            if (hi - lo < 0.5) hi = lo + 0.5;
            Path line = new Path(), fill = new Path();
            boolean started = false;
            float lastX = 0;
            for (int i = 0; i <= n; i++) {
                if (Double.isNaN(v[i])) continue;
                float x = w * i / n, y = (float) (base - (v[i] - lo) / (hi - lo) * (base - top));
                if (!started) {
                    line.moveTo(x, y);
                    fill.moveTo(x, base);
                    fill.lineTo(x, y);
                    started = true;
                } else {
                    line.lineTo(x, y);
                    fill.lineTo(x, y);
                }
                lastX = x;
            }
            fill.lineTo(lastX, base);
            fill.close();
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, top, 0, base, Color.argb(90, 70, 140, 230), Color.argb(15, 70, 140, 230),
                    Shader.TileMode.CLAMP));
            c.drawPath(fill, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(this, 2.5f));
            p.setColor(Ui.TIDE);
            c.drawPath(line, p);
            double hn = s.at(now / 1000);
            if (!Double.isNaN(hn)) {
                float x = w * (float) ((now - day0) / 86_400_000.0), y = (float) (base - (hn - lo) / (hi - lo) * (base - top));
                p.setStyle(Paint.Style.FILL);
                p.setColor(Ui.WHITE);
                c.drawCircle(x, y, dp(this, 5), p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.DIM);
            p.setTextSize(dp(this, 12));
            if (tf != null) p.setTypeface(tf);
            String[] hrs = {"00", "06", "12", "18", "24"};
            for (int i = 0; i < 5; i++) {
                p.setTextAlign(i == 0 ? Paint.Align.LEFT : i == 4 ? Paint.Align.RIGHT : Paint.Align.CENTER);
                c.drawText(hrs[i], w * i / 4, getHeight() - dp(this, 6), p);
            }
        }
    }

    /** A thin line of recent values (pressure). */
    static final class Spark extends View {
        double[] v = new double[0];
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Spark(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), (int) dp(this, 40));
        }

        @Override
        protected void onDraw(Canvas c) {
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            int n = 0;
            for (double x : v) {
                if (Double.isNaN(x)) continue;
                lo = Math.min(lo, x);
                hi = Math.max(hi, x);
                n++;
            }
            if (n < 2) return;
            if (hi - lo < 1) {
                double m = (hi + lo) / 2;
                lo = m - 0.5;
                hi = m + 0.5;
            }
            float w = getWidth(), top = dp(this, 4), base = getHeight() - dp(this, 4);
            Path path = new Path();
            boolean started = false;
            for (int i = 0; i < v.length; i++) {
                if (Double.isNaN(v[i])) continue;
                float x = w * i / (v.length - 1), y = (float) (base - (v[i] - lo) / (hi - lo) * (base - top));
                if (!started) {
                    path.moveTo(x, y);
                    started = true;
                } else {
                    path.lineTo(x, y);
                }
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(this, 2.5f));
            p.setColor(Ui.BLUE);
            c.drawPath(path, p);
        }
    }

    /** The moon with its phase (same drawing as on the globe). */
    static final class Moon extends View {
        double age;
        int sizeDp = 46;

        Moon(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int s = (int) dp(this, sizeDp);
            setMeasuredDimension(s, s);
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = getWidth() / 2.6f;
            Globe.drawMoon(c, getWidth() / 2f, getHeight() / 2f, r, age);
        }
    }
}
