package com.latent.camera;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

/** Gfx on Android's Canvas (the camera). tools/preview/HostGfx is the same on a Mac. */
final class AndroidGfx implements Gfx {
    private final Typeface[] fonts = new Typeface[4];
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint bitmap = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint safelight = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF rf = new RectF();
    private final Rect src = new Rect();
    private Canvas c;

    AndroidGfx(AssetManager assets) {
        String[] files = { "inter-display-light.ttf", "inter-regular.ttf", "inter-medium.ttf", "plex-mono-medium.ttf" };
        for (int i = 0; i < 4; i++) {
            try {
                fonts[i] = Typeface.createFromAsset(assets, "fonts/" + files[i]);
            } catch (RuntimeException e) {
                fonts[i] = i == MONO ? Typeface.MONOSPACE : Typeface.DEFAULT;
            }
        }
        bitmap.setDither(true);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        // darkroom: images as red monochrome, like prints under a safelight
        safelight.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                0.30f, 0.59f, 0.11f, 0, 0,
                0.06f, 0.12f, 0.02f, 0, 0,
                0.04f, 0.08f, 0.01f, 0, 0,
                0, 0, 0, 1, 0 })));
    }

    void begin(Canvas canvas) { c = canvas; }

    public void rect(float x, float y, float w, float h, int color) {
        fill.setColor(color);
        c.drawRect(x, y, x + w, y + h, fill);
    }

    public void roundRect(float x, float y, float w, float h, float r, int color) {
        fill.setColor(color);
        rf.set(x, y, x + w, y + h);
        c.drawRoundRect(rf, r, r, fill);
    }

    public void strokeRoundRect(float x, float y, float w, float h, float r, float width, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(width);
        rf.set(x, y, x + w, y + h);
        c.drawRoundRect(rf, r, r, stroke);
    }

    public void circle(float cx, float cy, float r, int color) {
        fill.setColor(color);
        c.drawCircle(cx, cy, r, fill);
    }

    public void strokeCircle(float cx, float cy, float r, float width, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(width);
        c.drawCircle(cx, cy, r, stroke);
    }

    public void line(float x1, float y1, float x2, float y2, float width, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(width);
        c.drawLine(x1, y1, x2, y2, stroke);
    }

    private void font(int font, float size) {
        text.setTypeface(fonts[font]);
        text.setTextSize(size);
    }

    // Android 2.3 has no letter spacing, so tracked text is laid out one character at a time
    public void text(String s, float x, float y, int font, float size, int color, int align, int tracking) {
        font(font, size);
        text.setColor(color);
        float w = textWidth(s, font, size, tracking);
        if (align == CENTER) x -= w / 2; else if (align == RIGHT) x -= w;
        if (tracking == 0) { c.drawText(s, x, y, text); return; }
        float extra = size * tracking / 1000f;
        for (int i = 0; i < s.length(); i++) {
            String ch = s.substring(i, i + 1);
            c.drawText(ch, x, y, text);
            x += text.measureText(ch) + extra;
        }
    }

    public float textWidth(String s, int font, float size, int tracking) {
        font(font, size);
        if (tracking == 0) return text.measureText(s);
        float w = 0;
        for (int i = 0; i < s.length(); i++) w += text.measureText(s.substring(i, i + 1));
        return w + size * tracking / 1000f * Math.max(0, s.length() - 1);
    }

    public void image(Object img, float x, float y, float w, float h, boolean red) {
        Bitmap b = (Bitmap) img;
        if (b == null || b.isRecycled()) return;
        float iw = b.getWidth(), ih = b.getHeight(), sc = Math.max(w / iw, h / ih);
        float sw = w / sc, sh = h / sc, sx = (iw - sw) / 2, sy = (ih - sh) / 2;
        src.set(Math.round(sx), Math.round(sy), Math.round(sx + sw), Math.round(sy + sh));
        rf.set(x, y, x + w, y + h);
        c.drawBitmap(b, src, rf, red ? safelight : bitmap);
    }

    public int imageWidth(Object img) { return ((Bitmap) img).getWidth(); }
    public int imageHeight(Object img) { return ((Bitmap) img).getHeight(); }
}
