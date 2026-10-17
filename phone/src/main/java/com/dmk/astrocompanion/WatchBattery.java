package com.dmk.astrocompanion;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * content://com.dmk.astrocompanion.watchbattery : the watch's battery (pct, charging, model, time),
 * as last sent by the watch. Read-only; the status bar module (NetSpeedXposed) shows it on the watch line.
 */
public class WatchBattery extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        Context c = getContext();
        SharedPreferences sp = c.getSharedPreferences("watchbatt", Context.MODE_PRIVATE);
        MatrixCursor m = new MatrixCursor(new String[]{"pct", "charging", "model", "time"});
        m.addRow(new Object[]{sp.getInt("pct", -1), sp.getBoolean("chg", false) ? 1 : 0, sp.getString("model", ""),
                sp.getLong("t", 0)});
        return m;
    }

    @Override
    public String getType(Uri u) {
        return "vnd.android.cursor.item/vnd.dmk.watchbattery";
    }

    @Override
    public Uri insert(Uri u, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri u, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri u, ContentValues v, String s, String[] a) {
        return 0;
    }
}
