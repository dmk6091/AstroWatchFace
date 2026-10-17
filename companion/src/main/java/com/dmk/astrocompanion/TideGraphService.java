package com.dmk.astrocompanion;

import android.graphics.drawable.Icon;
import android.util.Log;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.NoDataComplicationData;
import androidx.wear.watchface.complications.data.PhotoImageComplicationData;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

/** Today's tide curve as a picture for the face's tide panel. */
public class TideGraphService extends ComplicationDataSourceService {

    static ComplicationData photo(android.graphics.Bitmap b) {
        return new PhotoImageComplicationData.Builder(Icon.createWithBitmap(b),
                new PlainComplicationText.Builder("Tide graph").build()).build();
    }

    @Override
    public void onComplicationRequest(ComplicationRequest request, ComplicationRequestListener listener) {
        ComplicationData data;
        try {
            if (request.getComplicationType() != ComplicationType.PHOTO_IMAGE) {
                data = new NoDataComplicationData();
            } else {
                data = photo(Tide.graph(Tide.Series.load(Store.p(this)), System.currentTimeMillis()));
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
        if (type != ComplicationType.PHOTO_IMAGE) return null;
        long now = System.currentTimeMillis();
        long[] t = new long[73];
        double[] h = new double[73];
        long start = now / 1000 - 36 * 3600;
        for (int i = 0; i < t.length; i++) {
            t[i] = start + i * 3600L;
            h[i] = 1.6 * Math.cos(2 * Math.PI * i / 12.42);  // two tides a day
        }
        return photo(Tide.graph(new Tide.Series(t, h), now));
    }
}
