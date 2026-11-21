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
            P[0] = 0xFF25272B;
            P[1] = 0xFF111214;
            P[3] = 0xFFF1F2F4;
            P[4] = 0xFF9498A1;
            P[5] = 0x1FFFFFFF;
        } else {
            P[0] = 0xFFF7F7F9;
            P[1] = 0xFFDADCE1;
            P[3] = 0xFF17181B;
            P[4] = 0xFF5C6068;
            P[5] = 0x99FFFFFF;
        }
        int a = accentFor(accentIdx, d);
        P[2] = a;
        double lum = (0.299 * Color.red(a) + 0.587 * Color.green(a) + 0.114 * Color.blue(a)) / 255.0;
        P[6] = lum > 0.48 ? 0xFF111215 : 0xFFFFFFFF;
    }
}
