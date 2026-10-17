package com.dmk.astrocompanion;

import android.util.Log;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.LongTextComplicationData;
import androidx.wear.watchface.complications.data.NoDataComplicationData;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** All numbers for the Astro face, packed into one long text (see FieldSpec). */
public class DataService extends ComplicationDataSourceService {
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    static ComplicationData text(String s, android.app.PendingIntent tap) {
        LongTextComplicationData.Builder b = new LongTextComplicationData.Builder(
                new PlainComplicationText.Builder(s).build(),
                new PlainComplicationText.Builder("Astro data").build());
        if (tap != null) b.setTapAction(tap);   // tap on the ISS line: open Astro
        return b.build();
    }

    @Override
    public void onComplicationRequest(ComplicationRequest request, ComplicationRequestListener listener) {
        Worker.schedule(this);
        EXEC.execute(() -> {
            ComplicationData data;
            try {
                if (request.getComplicationType() != ComplicationType.LONG_TEXT) {
                    data = new NoDataComplicationData();
                } else {
                    data = text(Engine.pack(Engine.values(this)), Panel.openApp(this));
                }
            } catch (Throwable t) {
                Log.w(Engine.TAG, "data: " + t);
                data = new NoDataComplicationData();
            }
            try {
                listener.onComplicationData(data);
            } catch (Throwable t) {
                Log.w(Engine.TAG, "reply: " + t);
            }
        });
    }

    @Override
    public ComplicationData getPreviewData(ComplicationType type) {
        if (type != ComplicationType.LONG_TEXT) return null;
        Map<String, String> v = new HashMap<>();
        v.put("srise", "06:18");
        v.put("sset", "18:24");
        v.put("elev", "560");
        v.put("baro", "1008");
        v.put("tnow", "1.8");
        return text(Engine.pack(v), null);
    }
}
