package com.example.myapp;

/** Graphite-gray palettes (dark / light). */
public class Themes {
    // bg1 (top), bg2 (bottom), accent, text, subText, card
    public static final int[] DARK = {
        0xFF2C2E33, 0xFF141518, 0xFFDDE0E5, 0xFFF3F4F6, 0xFF9CA2AB, 0x1FFFFFFF
    };
    public static final int[] LIGHT = {
        0xFFEEF0F3, 0xFFCDD1D7, 0xFF3A3F47, 0xFF16181C, 0xFF59606A, 0x80FFFFFF
    };

    public static int[] P = DARK;
    public static boolean dark = true;

    public static void set(boolean isDark) {
        dark = isDark;
        P = isDark ? DARK : LIGHT;
    }
}
