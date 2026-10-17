package com.dmk.astrocompanion;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One-shot readings: air pressure (hPa) and compass heading (degrees). Never on the main thread. */
final class Sensors {
    private static HandlerThread sThread;

    private Sensors() {}

    private static synchronized Handler handler() {
        if (sThread == null) {
            sThread = new HandlerThread("astro-sensors");
            sThread.start();
        }
        return new Handler(sThread.getLooper());
    }

    /** First value of the sensor, or NaN if missing / no reading within the timeout. */
    private static float[] read(Context ctx, int type, long timeoutMs) {
        SensorManager sm = ctx.getSystemService(SensorManager.class);
        if (sm == null) return null;
        Sensor s = sm.getDefaultSensor(type);
        if (s == null) return null;
        final float[][] out = new float[1][];
        final CountDownLatch latch = new CountDownLatch(1);
        SensorEventListener l = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                if (out[0] == null) {
                    out[0] = e.values.clone();
                    latch.countDown();
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI, handler());
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        sm.unregisterListener(l);
        return out[0];
    }

    static double pressure(Context ctx) {
        float[] v = read(ctx, Sensor.TYPE_PRESSURE, 1500);
        return v == null || v.length == 0 || v[0] <= 0 ? Double.NaN : v[0];
    }

    /** Where 12 o'clock on the watch points, degrees from north. */
    static double heading(Context ctx) {
        float[] v = read(ctx, Sensor.TYPE_ROTATION_VECTOR, 1500);
        if (v == null) v = read(ctx, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, 1500);
        if (v == null) return Double.NaN;
        float[] r = new float[9], o = new float[3];
        SensorManager.getRotationMatrixFromVector(r, v);
        SensorManager.getOrientation(r, o);
        double deg = Math.toDegrees(o[0]);
        return (deg + 360) % 360;
    }
}
