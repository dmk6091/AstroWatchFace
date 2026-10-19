package com.dmk.astrocompanion;

import android.app.PendingIntent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.util.Log;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.NoDataComplicationData;
import androidx.wear.watchface.complications.data.PhotoImageComplicationData;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.data.SmallImage;
import androidx.wear.watchface.complications.data.SmallImageComplicationData;
import androidx.wear.watchface.complications.data.SmallImageType;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

/**
 * The picture in the face's bottom panel: today's tide curve, the last 24 h of earthquakes, or
 * the next 2 h of rain (see Panel). Tapping the panel moves to the next screen.
 */
public class TideGraphService extends ComplicationDataSourceService {

    /** As a small "photo" image (what Samsung lets other apps provide), or a full photo image. */
    static ComplicationData image(ComplicationType type, android.graphics.Bitmap b) {
        return image(type, b, "Tide graph");
    }

    static ComplicationData image(ComplicationType type, android.graphics.Bitmap b, String what) {
        return image(type, Icon.createWithBitmap(b), what, null);
    }

    static ComplicationData image(ComplicationType type, Icon icon, String what) {
        return image(type, icon, what, null);
    }

    /** From a picture (a big one goes compressed: a raw bitmap is too large to send), with a tap action. */
    static ComplicationData image(ComplicationType type, Icon icon, String what, PendingIntent tap) {
        PlainComplicationText desc = new PlainComplicationText.Builder(what).build();
        if (type == ComplicationType.PHOTO_IMAGE) {
            PhotoImageComplicationData.Builder b = new PhotoImageComplicationData.Builder(icon, desc);
            if (tap != null) b.setTapAction(tap);
            return b.build();
        }
        SmallImageComplicationData.Builder b = new SmallImageComplicationData.Builder(
                new SmallImage.Builder(icon, SmallImageType.PHOTO).build(), desc);
        if (tap != null) b.setTapAction(tap);
        return b.build();
    }

    static Icon jpeg(android.graphics.Bitmap b) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out);
        byte[] data = out.toByteArray();
        return Icon.createWithData(data, 0, data.length);
    }

    static boolean ours(ComplicationType t) {
        return t == ComplicationType.SMALL_IMAGE || t == ComplicationType.PHOTO_IMAGE;
    }

    @Override
    public void onComplicationRequest(ComplicationRequest request, ComplicationRequestListener listener) {
        ComplicationData data;
        try {
            if (!ours(request.getComplicationType())) {
                data = new NoDataComplicationData();
            } else {
                SharedPreferences p = Store.p(this);
                long now = System.currentTimeMillis();
                int panel = Panel.current(p, now);
                android.graphics.Bitmap b = panel == Panel.QUAKES ? Quakes.graph(p, now)
                        : panel == Panel.RAIN ? Rain.graph(p, now)
                        : panel >= Panel.WIND ? Topics.graph(p, now, panel)
                        : Tide.graph(Tide.Series.load(p), now);
                data = image(request.getComplicationType(), Icon.createWithBitmap(b), "Bottom panel", Panel.tap(this));
            }
        } catch (Throwable t) {
            Log.w(Engine.TAG, "graph: " + t);
            data = new NoDataComplicationData();
        }
        try {
            listener.onComplicationData(data);
        } catch (Throwable t) {
            Log.w(Engine.TAG, "graph reply: " + t);
        }
    }

    /** Sample curve for the complication picker (it hides sources that give no preview). */
    @Override
    public ComplicationData getPreviewData(ComplicationType type) {
        if (!ours(type)) return null;
        long now = System.currentTimeMillis();
        long[] t = new long[73];
        double[] h = new double[73];
        long start = now / 1000 - 36 * 3600;
        for (int i = 0; i < t.length; i++) {
            t[i] = start + i * 3600L;
            h[i] = 1.6 * Math.cos(2 * Math.PI * i / 12.42);  // two tides a day
        }
        return image(type, Tide.graph(new Tide.Series(t, h), now));
    }
}
