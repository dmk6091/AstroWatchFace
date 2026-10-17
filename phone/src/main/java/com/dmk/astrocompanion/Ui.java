package com.dmk.astrocompanion;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Small building blocks for the phone screens (Terra's colours and the Barlow fonts). */
final class Ui {
    static final int BG = 0xFF070B14, CARD = 0xFF101A2C, CARD2 = 0xFF16223A, LINE = 0xFF24324D, WHITE = 0xFFEEF2F7,
            SOFT = 0xFFB9C3D3, DIM = 0xFF7D8799, ICE = 0xFF8FB8FF, BLUE = 0xFF5FA2F5, GOLD = 0xFFF2B33D,
            TEAL = 0xFF5BC8D6, GREEN = 0xFF7CC24A, RED = 0xFFFF5440, ORANGE = 0xFFF28B3D, SEL = 0xFF2A4A80,
            TIDE = 0xFF5BA8F2;

    final Activity a;
    final float density;
    Typeface med = Typeface.DEFAULT, semi = Typeface.DEFAULT_BOLD, cond = Typeface.DEFAULT_BOLD;

    Ui(Activity a) {
        this.a = a;
        density = a.getResources().getDisplayMetrics().density;
        try {
            med = a.getResources().getFont(R.font.barlow_medium);
            semi = a.getResources().getFont(R.font.barlow_semibold);
            cond = a.getResources().getFont(R.font.barlow_sc_semibold);
        } catch (Throwable ignored) {
        }
    }

    int dp(float v) {
        return Math.round(v * density);
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable dot(int color, int sizePx, boolean ring) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (ring) g.setStroke(Math.max(2, sizePx / 6), color);
        else g.setColor(color);
        g.setSize(sizePx, sizePx);
        return g;
    }

    TextView text(ViewGroup parent, CharSequence s, float sp, int color, Typeface tf) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        if (parent != null) parent.addView(t);
        return t;
    }

    TextView label(ViewGroup parent, String s) {
        TextView t = text(parent, s.toUpperCase(java.util.Locale.US), 13, DIM, cond);
        t.setLetterSpacing(0.04f);
        return t;
    }

    /** A rounded card (vertical), added to parent with a gap below. */
    LinearLayout card(ViewGroup parent) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, dp(18)));
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        parent.addView(c, lp);
        return c;
    }

    LinearLayout row(ViewGroup parent) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    /** Text that takes the remaining width of a row. */
    TextView grow(LinearLayout row, CharSequence s, float sp, int color, Typeface tf) {
        TextView t = text(null, s, sp, color, tf);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return t;
    }

    View gap(ViewGroup parent, int dpH) {
        View v = new View(a);
        parent.addView(v, new LinearLayout.LayoutParams(1, dp(dpH)));
        return v;
    }

    View divider(ViewGroup parent) {
        View v = new View(a);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(12);
        parent.addView(v, lp);
        return v;
    }

    /** A horizontally scrolling row (chips). Returns the inner row. */
    LinearLayout scrollRow(ViewGroup parent) {
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(r);
        parent.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    TextView chip(ViewGroup parent, String s, boolean on, int color, Runnable click) {
        TextView t = text(null, s, 15, on ? WHITE : color, cond);
        t.setBackground(round(on ? SEL : CARD2, dp(18)));
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        parent.addView(t, lp);
        if (click != null) t.setOnClickListener(v -> click.run());
        return t;
    }

    /** Legend chip with a small dot or ring in front. */
    TextView legend(ViewGroup parent, String s, int color, boolean ring) {
        TextView t = chip(parent, s, false, color, null);
        t.setCompoundDrawablesRelative(dot(color, dp(10), ring), null, null, null);
        t.setCompoundDrawablePadding(dp(7));
        return t;
    }

    TextView button(ViewGroup parent, String s, int fill, int color, Runnable click) {
        TextView t = text(null, s, 18, color, semi);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(fill, dp(30)));
        t.setPadding(dp(16), dp(16), dp(16), dp(16));
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        parent.addView(t, lp);
        if (click != null) t.setOnClickListener(v -> click.run());
        return t;
    }

    /** Label + small line on the left, a switch on the right. */
    Switch toggle(ViewGroup parent, String title, String sub, boolean on, java.util.function.Consumer<Boolean> change) {
        LinearLayout r = row(parent);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        text(col, title, 17, WHITE, semi);
        gap(col, 4);
        text(col, sub, 13, DIM, med);
        Switch sw = new Switch(a);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((b, v) -> change.accept(v));
        r.addView(sw);
        return sw;
    }

    /** "Label ......... value" line. */
    void pair(ViewGroup parent, String k, String v, int color) {
        LinearLayout r = row(parent);
        grow(r, k, 15, DIM, med);
        text(r, v, 16, color, semi);
        gap(parent, 8);
    }
}
