package com.example.myapp;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Programmatic 3D-style icons (no emoji, no image files).
 * Each icon is built from paths in a 24x24 space, then drawn in 3 layers:
 * soft shadow, light bevel edge, and a vertical-gradient body.
 */
public class Icon3D extends Drawable {

    public static final int LIBRARY = 0, PLAYER = 1, TRASH = 2, SETTINGS = 3, PLAY = 4, PAUSE = 5,
            NEXT = 6, PREV = 7, SHUFFLE = 8, REPEAT = 9, REPEAT_ONE = 10, MUSIC = 11, CLOSE = 12,
            CHECK = 13, MORE = 14, IMAGE = 15, EDIT = 16, RESTORE = 17, HOME = 18, MOON = 19,
            SUN = 20, TIMER = 21;

    private final Path fillPath = new Path();
    private final Path strokePath = new Path();
    private float strokeW = 2f;      // width of strokePath lines
    private float roundW = 1.4f;     // outline used to round the corners of filled shapes
    private int color = 0xFFFFFFFF;
    private Shader shader;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public Icon3D(int kind) {
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        build(kind);
        rebuildShader();
    }

    public void setColor(int c) {
        if (c == color) return;
        color = c;
        rebuildShader();
        invalidateSelf();
    }

    // ------------------------------------------------------------ colors

    private static int mix(int a, int b, float t) {
        int ar = Color.alpha(a), rr = Color.red(a), gg = Color.green(a), bb = Color.blue(a);
        int r2 = Color.red(b), g2 = Color.green(b), b2 = Color.blue(b);
        return Color.argb(ar,
                (int) (rr + (r2 - rr) * t), (int) (gg + (g2 - gg) * t), (int) (bb + (b2 - bb) * t));
    }

    private void rebuildShader() {
        int top = mix(color, 0xFFFFFFFF, 0.38f);
        int bottom = mix(color, 0xFF000000, 0.38f);
        shader = new LinearGradient(0, 2, 0, 22, top, bottom, Shader.TileMode.CLAMP);
    }

    // ------------------------------------------------------------ drawing

    private void layer(Canvas c, float dx, float dy, boolean useShader, int flat) {
        c.save();
        c.translate(dx, dy);
        if (useShader) {
            fillPaint.setShader(shader);
            strokePaint.setShader(shader);
        } else {
            fillPaint.setShader(null);
            strokePaint.setShader(null);
            fillPaint.setColor(flat);
            strokePaint.setColor(flat);
        }
        if (!fillPath.isEmpty()) {
            c.drawPath(fillPath, fillPaint);
            if (roundW > 0f) {
                strokePaint.setStrokeWidth(roundW);
                c.drawPath(fillPath, strokePaint);
            }
        }
        if (!strokePath.isEmpty()) {
            strokePaint.setStrokeWidth(strokeW);
            c.drawPath(strokePath, strokePaint);
        }
        c.restore();
    }

    @Override
    public void draw(Canvas canvas) {
        android.graphics.Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        int a = Color.alpha(color);
        canvas.save();
        canvas.translate(b.left, b.top);
        canvas.scale(b.width() / 24f, b.height() / 24f);
        // soft drop shadow (two passes for a blurred feel)
        layer(canvas, 0.7f, 1.2f, false, Color.argb(a * 40 / 255, 0, 0, 0));
        layer(canvas, 0.45f, 0.8f, false, Color.argb(a * 70 / 255, 0, 0, 0));
        // bevel highlight on the top-left edge
        layer(canvas, -0.45f, -0.55f, false, Color.argb(a * 190 / 255, 255, 255, 255));
        // body
        layer(canvas, 0f, 0f, true, 0);
        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        // colour is driven by setColor(); a platform tint would flatten the 3D shading
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return 96;
    }

    @Override
    public int getIntrinsicHeight() {
        return 96;
    }

    // ------------------------------------------------------------ shapes

    private void tri(Path p, float x1, float y1, float x2, float y2, float x3, float y3) {
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        p.lineTo(x3, y3);
        p.close();
    }

    private void arrow(Path p, float cx, float cy, float r, float angleDeg) {
        double th = Math.toRadians(angleDeg);
        float px = (float) (cx + r * Math.cos(th));
        float py = (float) (cy + r * Math.sin(th));
        float tx = (float) -Math.sin(th);
        float ty = (float) Math.cos(th);
        float nx = -ty, ny = tx;
        float bx = px - 0.4f * tx, by = py - 0.4f * ty;
        tri(p, px + 3f * tx, py + 3f * ty, bx + 2.7f * nx, by + 2.7f * ny, bx - 2.7f * nx, by - 2.7f * ny);
    }

    private void build(int kind) {
        Path f = fillPath, s = strokePath;
        switch (kind) {
            case PLAY:
                tri(f, 7f, 4.5f, 20f, 12f, 7f, 19.5f);
                roundW = 1.8f;
                break;
            case PAUSE:
                f.addRoundRect(new RectF(6f, 4.8f, 10.6f, 19.2f), 1.5f, 1.5f, Path.Direction.CW);
                f.addRoundRect(new RectF(13.4f, 4.8f, 18f, 19.2f), 1.5f, 1.5f, Path.Direction.CW);
                roundW = 0.6f;
                break;
            case NEXT:
                tri(f, 5.5f, 5f, 16f, 12f, 5.5f, 19f);
                f.addRoundRect(new RectF(16.6f, 5f, 19.2f, 19f), 1.1f, 1.1f, Path.Direction.CW);
                roundW = 1.6f;
                break;
            case PREV:
                tri(f, 18.5f, 5f, 8f, 12f, 18.5f, 19f);
                f.addRoundRect(new RectF(4.8f, 5f, 7.4f, 19f), 1.1f, 1.1f, Path.Direction.CW);
                roundW = 1.6f;
                break;
            case SHUFFLE:
                s.moveTo(3f, 7f);
                s.lineTo(6.5f, 7f);
                s.cubicTo(11f, 7f, 12.5f, 17f, 17f, 17f);
                s.moveTo(3f, 17f);
                s.lineTo(6.5f, 17f);
                s.cubicTo(11f, 17f, 12.5f, 7f, 17f, 7f);
                tri(f, 16.2f, 13.8f, 21.2f, 17f, 16.2f, 20.2f);
                tri(f, 16.2f, 3.8f, 21.2f, 7f, 16.2f, 10.2f);
                strokeW = 2.1f;
                roundW = 1f;
                break;
            case REPEAT:
            case REPEAT_ONE:
                s.moveTo(6f, 12.5f);
                s.lineTo(6f, 10.5f);
                s.quadTo(6f, 7f, 9.5f, 7f);
                s.lineTo(16.5f, 7f);
                s.moveTo(18f, 11.5f);
                s.lineTo(18f, 13.5f);
                s.quadTo(18f, 17f, 14.5f, 17f);
                s.lineTo(7.5f, 17f);
                tri(f, 16.5f, 3.8f, 21f, 7f, 16.5f, 10.2f);
                tri(f, 7.5f, 13.8f, 3f, 17f, 7.5f, 20.2f);
                if (kind == REPEAT_ONE) {
                    s.moveTo(10.8f, 10.8f);
                    s.lineTo(12.4f, 9.8f);
                    s.lineTo(12.4f, 14.4f);
                }
                strokeW = 2.1f;
                roundW = 1f;
                break;
            case MUSIC:
                f.addCircle(8.6f, 17.4f, 3.4f, Path.Direction.CW);
                f.addRoundRect(new RectF(10.9f, 4.5f, 13.2f, 17.4f), 1f, 1f, Path.Direction.CW);
                tri(f, 13f, 4.5f, 19.2f, 7.4f, 13f, 10.6f);
                roundW = 1.2f;
                break;
            case LIBRARY:
                s.moveTo(3.5f, 6.5f);
                s.lineTo(12.5f, 6.5f);
                s.moveTo(3.5f, 11.5f);
                s.lineTo(12.5f, 11.5f);
                s.moveTo(3.5f, 16.5f);
                s.lineTo(9f, 16.5f);
                f.addCircle(16f, 17f, 2.8f, Path.Direction.CW);
                f.addRoundRect(new RectF(17.4f, 5.5f, 19.6f, 17f), 1f, 1f, Path.Direction.CW);
                strokeW = 2.2f;
                roundW = 1f;
                break;
            case PLAYER:
                s.addCircle(12f, 12f, 8.6f, Path.Direction.CW);
                tri(f, 10f, 8.2f, 16.2f, 12f, 10f, 15.8f);
                strokeW = 2.2f;
                roundW = 1.4f;
                break;
            case TRASH:
                f.setFillType(Path.FillType.EVEN_ODD);
                f.addRoundRect(new RectF(6.4f, 9.2f, 17.6f, 20.4f), 2f, 2f, Path.Direction.CW);
                f.addRoundRect(new RectF(9.6f, 11.4f, 10.7f, 18f), 0.5f, 0.5f, Path.Direction.CW);
                f.addRoundRect(new RectF(13.3f, 11.4f, 14.4f, 18f), 0.5f, 0.5f, Path.Direction.CW);
                f.addRoundRect(new RectF(4.6f, 5.8f, 19.4f, 8f), 1.1f, 1.1f, Path.Direction.CW);
                s.moveTo(9.4f, 5.4f);
                s.lineTo(9.4f, 4.2f);
                s.lineTo(14.6f, 4.2f);
                s.lineTo(14.6f, 5.4f);
                strokeW = 1.7f;
                roundW = 0.5f;
                break;
            case SETTINGS: {
                Matrix m = new Matrix();
                Path tooth = new Path();
                tooth.addRoundRect(new RectF(10.3f, 2f, 13.7f, 7f), 0.9f, 0.9f, Path.Direction.CW);
                f.addCircle(12f, 12f, 7f, Path.Direction.CW);
                for (int k = 0; k < 8; k++) {
                    Path t = new Path(tooth);
                    m.setRotate(k * 45f, 12f, 12f);
                    t.transform(m);
                    f.addPath(t);
                }
                f.addCircle(12f, 12f, 2.9f, Path.Direction.CCW);
                roundW = 0.5f;
                break;
            }
            case CLOSE:
                s.moveTo(6f, 6f);
                s.lineTo(18f, 18f);
                s.moveTo(18f, 6f);
                s.lineTo(6f, 18f);
                strokeW = 2.6f;
                break;
            case CHECK:
                s.moveTo(5.5f, 12.5f);
                s.lineTo(10f, 17f);
                s.lineTo(18.5f, 7.5f);
                strokeW = 2.8f;
                break;
            case MORE:
                f.addCircle(12f, 5.5f, 2.1f, Path.Direction.CW);
                f.addCircle(12f, 12f, 2.1f, Path.Direction.CW);
                f.addCircle(12f, 18.5f, 2.1f, Path.Direction.CW);
                roundW = 0f;
                break;
            case IMAGE:
                s.addRoundRect(new RectF(3.8f, 4.8f, 20.2f, 19.2f), 2.6f, 2.6f, Path.Direction.CW);
                f.moveTo(6.8f, 16.6f);
                f.lineTo(10.4f, 11.4f);
                f.lineTo(13.2f, 14.8f);
                f.lineTo(15.2f, 12.4f);
                f.lineTo(17.6f, 16.6f);
                f.close();
                f.addCircle(16.2f, 8.6f, 1.6f, Path.Direction.CW);
                strokeW = 1.9f;
                roundW = 1f;
                break;
            case EDIT:
                f.moveTo(4f, 20f);
                f.lineTo(4.8f, 15.6f);
                f.lineTo(15.4f, 5f);
                f.quadTo(16.2f, 4.2f, 17f, 5f);
                f.lineTo(19f, 7f);
                f.quadTo(19.8f, 7.8f, 19f, 8.6f);
                f.lineTo(8.4f, 19.2f);
                f.close();
                roundW = 0.8f;
                break;
            case RESTORE:
                s.arcTo(new RectF(5f, 5f, 19f, 19f), 300f, 290f);
                arrow(f, 12f, 12f, 7f, 230f);
                strokeW = 2.2f;
                roundW = 1f;
                break;
            case HOME:
                f.moveTo(12f, 3.6f);
                f.lineTo(21f, 11.6f);
                f.lineTo(18.4f, 11.6f);
                f.lineTo(18.4f, 20f);
                f.lineTo(14.2f, 20f);
                f.lineTo(14.2f, 14.6f);
                f.lineTo(9.8f, 14.6f);
                f.lineTo(9.8f, 20f);
                f.lineTo(5.6f, 20f);
                f.lineTo(5.6f, 11.6f);
                f.lineTo(3f, 11.6f);
                f.close();
                roundW = 1.4f;
                break;
            case MOON: {
                Path a = new Path();
                a.addCircle(12f, 12f, 8.4f, Path.Direction.CW);
                Path b = new Path();
                b.addCircle(16.8f, 8.2f, 6.6f, Path.Direction.CW);
                a.op(b, Path.Op.DIFFERENCE);
                f.addPath(a);
                roundW = 0.8f;
                break;
            }
            case SUN:
                f.addCircle(12f, 12f, 4.4f, Path.Direction.CW);
                for (int k = 0; k < 8; k++) {
                    double th = Math.toRadians(k * 45);
                    s.moveTo((float) (12 + 7.3 * Math.cos(th)), (float) (12 + 7.3 * Math.sin(th)));
                    s.lineTo((float) (12 + 9.8 * Math.cos(th)), (float) (12 + 9.8 * Math.sin(th)));
                }
                strokeW = 2f;
                roundW = 0.5f;
                break;
            case TIMER:
                s.addCircle(12f, 13.5f, 7.6f, Path.Direction.CW);
                s.moveTo(12f, 13.5f);
                s.lineTo(12f, 9.2f);
                s.moveTo(18.4f, 6.8f);
                s.lineTo(19.8f, 5.4f);
                f.addRoundRect(new RectF(9.6f, 2.2f, 14.4f, 4.2f), 1f, 1f, Path.Direction.CW);
                strokeW = 2.2f;
                roundW = 0.5f;
                break;
            default:
                f.addCircle(12f, 12f, 6f, Path.Direction.CW);
        }
    }
}
