package com.dmk.astrocompanion;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.NoDataComplicationData;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The live Earth globe for the Terra face. */
public class GlobeService extends ComplicationDataSourceService {
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    static double sunFrac(SharedPreferences p, long now) {
        long[] t = Engine.sunTimes(p, now);
        if (t[0] <= 0 || t[1] <= t[0] || now < t[0] || now > t[1]) return Double.NaN;
        return (now - t[0]) / (double) (t[1] - t[0]);
    }

    @Override
    public void onComplicationRequest(ComplicationRequest request, ComplicationRequestListener listener) {
        EXEC.execute(() -> {
            ComplicationData data;
            try {
                ComplicationType type = request.getComplicationType();
                if (!TideGraphService.ours(type)) {
                    data = new NoDataComplicationData();
                } else {
                    SharedPreferences p = Store.p(this);
                    long now = System.currentTimeMillis();
                    double lat = Store.d(p, "lat", Engine.FALLBACK_LAT), lon = Store.d(p, "lon", Engine.FALLBACK_LON);
                    Globe.showQuakes = Panel.current(p, now) == Panel.QUAKES;
                    int turn = Panel.spinSteps(p, now);
                    double lon0 = ((lon + 60.0 * turn + 540) % 360 + 360) % 360 - 180;
                    data = TideGraphService.image(type, TideGraphService.jpeg(
                            Globe.render(this, lat, lon0, now, sunFrac(p, now), Iss.orbit(p), lat, lon, turn != 0)),
                            "Earth", Panel.spinTap(this));
                }
            } catch (Throwable t) {
                Log.w(Engine.TAG, "globe: " + t);
                data = new NoDataComplicationData();
            }
            try {
                listener.onComplicationData(data);
            } catch (Throwable t) {
                Log.w(Engine.TAG, "globe reply: " + t);
            }
        });
    }

    @Override
    public ComplicationData getPreviewData(ComplicationType type) {
        if (!TideGraphService.ours(type)) return null;
        long now = System.currentTimeMillis();
        return TideGraphService.image(type, TideGraphService.jpeg(
                Globe.render(this, Engine.FALLBACK_LAT, Engine.FALLBACK_LON, now, 0.4)), "Earth");
    }
}
