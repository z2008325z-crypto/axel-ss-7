package com.example.myapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/**
 * التنين الأخضر (بذيل أصفر) متحرك: موجة بتمشي من الراس للذيل بتخليه "يسبح"،
 * مع حركة طفو خفيفة. الرسم بيتم بـ drawBitmapMesh فمفيش أي مكتبات خارجية.
 */
public class DragonView extends View {

    private static final int COLS = 8;
    private static final int ROWS = 48;

    private Bitmap bmp;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float[] verts = new float[(COLS + 1) * (ROWS + 1) * 2];
    private boolean running = true;
    private final long t0 = SystemClock.uptimeMillis();

    public DragonView(Context c) {
        super(c);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        bmp = BitmapFactory.decodeResource(c.getResources(), R.drawable.lord_dragons, o);
    }

    /** وقف الحركة لما التطبيق يتخبّى (توفير بطارية) وكمّلها لما يرجع. */
    public void setRunning(boolean on) {
        running = on;
        if (on) postInvalidateOnAnimation();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (bmp == null) return;
        int vw = getWidth(), vh = getHeight();
        if (vw == 0 || vh == 0) return;

        float bw = bmp.getWidth(), bh = bmp.getHeight();
        // center-crop: الصورة تملا الشاشة من غير تشويه
        float s = Math.max(vw / bw, vh / bh);
        float ox = (vw - bw * s) / 2f;
        float oy = (vh - bh * s) / 2f;

        float t = (SystemClock.uptimeMillis() - t0) / 1000f;
        float amp = bw * s * 0.022f;           // أقصى ازاحة جانبية
        float k = (float) (2 * Math.PI / (bh * s * 0.55f)); // طول الموجة
        float speed = 1.5f;                    // سرعة الموجة

        int idx = 0;
        for (int j = 0; j <= ROWS; j++) {
            float fy = (float) j / ROWS;       // 0 فوق (الراس) .. 1 تحت (الذيل)
            float y = oy + bh * s * fy;
            // الذيل بيتحرك أكتر من الراس
            float a = amp * (0.25f + 0.75f * fy);
            float phase = k * (y - oy) - speed * t;
            float dx = a * (float) Math.sin(phase);
            float dy = (amp * 0.35f) * (float) Math.sin(phase * 0.5f + 1.3f) * fy;
            float bob = (float) Math.sin(t * 0.7f) * amp * 0.25f;
            for (int i = 0; i <= COLS; i++) {
                float x = ox + bw * s * i / COLS;
                verts[idx++] = x + dx;
                verts[idx++] = y + dy + bob;
            }
        }
        canvas.drawBitmapMesh(bmp, COLS, ROWS, verts, 0, null, 0, paint);

        if (running) postInvalidateOnAnimation();
    }
}
