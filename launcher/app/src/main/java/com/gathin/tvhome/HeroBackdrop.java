package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The glass home's full-screen backdrops, made once and then shown as a single bitmap: the projector (Android 9,
 * 1 GB) cannot blur live. A series backdrop keeps the art sharp at the top and turns into a blurred copy of itself
 * under the app row; an app without one gets a wash of its own colour. Both carry the scrim that keeps the clock
 * and the row readable.
 */
final class HeroBackdrop {
    static final int W = 1280, H = 720;

    private HeroBackdrop() {
    }

    /** Sharp art above, the same art blurred below, then the scrim. The source is not recycled. */
    static Bitmap compose(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        c.drawBitmap(src, centerCrop(src.getWidth(), src.getHeight(), W, H), p);

        // Blur by scaling down sixteen times, a few box passes, and back up with filtering: smooth and cheap.
        // Taken from the cropped art, so the blurred copy lines up with the sharp one.
        Bitmap small = Bitmap.createScaledBitmap(out, W / 16, H / 16, true);
        Bitmap soft = boxBlur(small, 2, 3);
        if (soft != small) small.recycle();
        Bitmap layer = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas lc = new Canvas(layer);
        lc.drawBitmap(soft, null, new RectF(-W * 0.04f, -H * 0.04f, W * 1.04f, H * 1.04f), p);
        soft.recycle();
        Paint mask = new Paint();
        mask.setShader(new LinearGradient(0, H * 0.36f, 0, H * 0.64f, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        lc.drawRect(0, 0, W, H, mask);
        c.drawBitmap(layer, 0, 0, null);
        layer.recycle();

        scrim(c, W, H);
        return out;
    }

    /** A dark field lit in the app's colour, for apps with no series art. Small: it is shown stretched. */
    static Bitmap wash(int color) {
        int w = 480, h = 270;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        int base = Color.HSVToColor(new float[]{hsv[0], Math.min(1f, hsv[1]), 0.05f});
        int deep = Color.HSVToColor(new float[]{hsv[0], Math.min(1f, hsv[1] * 1.05f), 0.42f});
        int lit = Color.HSVToColor(new float[]{hsv[0], Math.min(1f, hsv[1] * 1.08f), Math.max(0.62f, hsv[2] * 0.92f)});
        c.drawColor(base);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        // The main glow: wide and centred a little above the middle, like an app banner stretched to the screen.
        drawEllipseGlow(c, p, w * 0.52f, h * 0.40f, w * 0.62f, h * 0.46f,
                new int[]{withAlpha(lit, 0.92f), withAlpha(deep, 0.75f), withAlpha(deep, 0f)}, new float[]{0f, 0.38f, 0.78f});
        drawEllipseGlow(c, p, w * 0.18f, h * 0.78f, w * 0.40f, h * 0.34f,
                new int[]{withAlpha(deep, 0.55f), withAlpha(deep, 0f)}, null);
        drawEllipseGlow(c, p, w * 0.88f, h * 0.18f, w * 0.36f, h * 0.30f,
                new int[]{withAlpha(deep, 0.5f), withAlpha(deep, 0f)}, null);
        scrim(c, w, h);
        return out;
    }

    /** Darkens behind the clock (top left) and towards the bottom, where the row and the categories sit. */
    private static void scrim(Canvas c, int w, int h) {
        Paint p = new Paint(Paint.DITHER_FLAG);
        drawEllipseGlow(c, p, w * 0.10f, h * 0.08f, w * 0.38f, h * 0.30f,
                new int[]{0x6B000000, 0x00000000}, null);
        p.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x47000000, 0x00000000, 0x00000000, 0x61000000, 0x9E000000},
                new float[]{0f, 0.20f, 0.42f, 0.70f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
    }

    /** A radial gradient squashed into an ellipse with radii rx and ry around (cx, cy). */
    private static void drawEllipseGlow(Canvas c, Paint p, float cx, float cy, float rx, float ry, int[] colors, float[] stops) {
        RadialGradient g = new RadialGradient(0, 0, 1f, colors, stops, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(rx, ry);
        m.postTranslate(cx, cy);
        g.setLocalMatrix(m);
        p.setShader(g);
        c.drawRect(0, 0, c.getWidth(), c.getHeight(), p);
        p.setShader(null);
    }

    private static int withAlpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0xFFFFFF);
    }

    /** Matrix that scales (sw, sh) to cover (dw, dh), centred. */
    private static Matrix centerCrop(int sw, int sh, int dw, int dh) {
        float scale = Math.max(dw / (float) sw, dh / (float) sh);
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((dw - sw * scale) / 2f, (dh - sh * scale) / 2f);
        return m;
    }

    /** Box blur on a small bitmap, passes times with the given radius; returns a new bitmap. */
    static Bitmap boxBlur(Bitmap src, int radius, int passes) {
        int w = src.getWidth(), h = src.getHeight();
        int[] a = new int[w * h], b = new int[w * h];
        src.getPixels(a, 0, w, 0, 0, w, h);
        for (int i = 0; i < passes; i++) {
            blurLine(a, b, w, h, radius, true);
            blurLine(b, a, w, h, radius, false);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(a, 0, w, 0, 0, w, h);
        return out;
    }

    private static void blurLine(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) {
                int sr = 0, sg = 0, sb = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int j = Math.max(0, Math.min(len - 1, i + k));
                    int px = horizontal ? in[l * w + j] : in[j * w + l];
                    sr += (px >> 16) & 0xFF;
                    sg += (px >> 8) & 0xFF;
                    sb += px & 0xFF;
                    n++;
                }
                int v = 0xFF000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
                if (horizontal) out[l * w + i] = v;
                else out[i * w + l] = v;
            }
        }
    }

    /** Largest power-of-two sample size that keeps the decoded image at least (w, h). */
    static int sampleSize(int srcW, int srcH, int w, int h) {
        int s = 1;
        while (srcW / (s * 2) >= w && srcH / (s * 2) >= h) s *= 2;
        return s;
    }
}
