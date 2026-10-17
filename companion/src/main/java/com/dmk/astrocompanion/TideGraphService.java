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

    @Override
    public ComplicationData getPreviewData(ComplicationType type) {
        return null;
    }
}
