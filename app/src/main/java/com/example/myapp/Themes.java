package com.example.myapp;

import android.graphics.Color;

/** Graphite gray palette (dark / light) with a selectable accent. */
public class Themes {
    public static final String[] ACCENT_NAMES = {"رمادي", "أزرق", "أخضر", "وردي"};
    private static final int[] ACCENTS = {0, 0xFF0A84FF, 0xFF1DB954, 0xFFFF375F};

    // 0 bg top, 1 bg bottom, 2 accent, 3 text, 4 subText, 5 card(glass), 6 text-on-accent
    public static final int[] P = new int[7];
    public static boolean dark = true;

    static {
        apply(true, 0);
    }

    public static int accentCount() {
        return ACCENTS.length;
    }

    public static int accentFor(int idx, boolean d) {
        if (idx > 0 && idx < ACCENTS.length) return ACCENTS[idx];
        return d ? 0xFFE8E9ED : 0xFF34363C;
    }

    public static void apply(boolean d, int accentIdx) {
        dark = d;
        if (d) {
            P[0] = 0xFF2C2E33;
            P[1] = 0xFF141518;
            P[3] = 0xFFF4F5F7;
            P[4] = 0xFF9EA2AA;
            P[5] = 0x1FFFFFFF;
        } else {
            P[0] = 0xFFF6F6F8;
            P[1] = 0xFFD5D7DC;
            P[3] = 0xFF1B1C20;
            P[4] = 0xFF6C7078;
            P[5] = 0x99FFFFFF;
        }
        int a = accentFor(accentIdx, d);
        P[2] = a;
        double lum = (0.299 * Color.red(a) + 0.587 * Color.green(a) + 0.114 * Color.blue(a)) / 255.0;
        P[6] = lum > 0.48 ? 0xFF111215 : 0xFFFFFFFF;
    }
}
