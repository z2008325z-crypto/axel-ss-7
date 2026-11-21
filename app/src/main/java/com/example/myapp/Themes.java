package com.example.myapp;

import android.graphics.Color;

/** Modern graphite palette (dark / light) with a selectable accent. */
public class Themes {
    public static final String[] ACCENT_NAMES = {"رمادي", "أزرق", "أخضر", "وردي", "بنفسجي"};
    private static final int[] ACCENTS = {0, 0xFF3B8BFF, 0xFF22C55E, 0xFFFF4D7D, 0xFF8B5CF6};
    public static final int DEFAULT_ACCENT = 4;

    // 0 bg top, 1 bg bottom, 2 accent, 3 text, 4 subText, 5 card(glass), 6 text-on-accent
    public static final int[] P = new int[7];
    public static boolean dark = true;

    static {
        apply(true, DEFAULT_ACCENT);
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
            P[0] = 0xFF17181C;
            P[1] = 0xFF0A0A0C;
            P[3] = 0xFFF5F5F8;
            P[4] = 0xFF8F929C;
            P[5] = 0x24FFFFFF;
        } else {
            P[0] = 0xFFF7F7FB;
            P[1] = 0xFFE7E8EF;
            P[3] = 0xFF15161A;
            P[4] = 0xFF626672;
            P[5] = 0x14000000;
        }
        int a = accentFor(accentIdx, d);
        P[2] = a;
        double lum = (0.299 * Color.red(a) + 0.587 * Color.green(a) + 0.114 * Color.blue(a)) / 255.0;
        P[6] = lum > 0.55 ? 0xFF111215 : 0xFFFFFFFF;
    }
}
