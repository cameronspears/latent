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
    private final Typeface[] fonts;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint bitmap = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint safelight = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF rf = new RectF();
    private final Rect src = new Rect();
    private Canvas c;

    private static final Typeface[] FONTS = new Typeface[4];
    private static boolean fontsLoaded;

    /**
     * The four faces, loaded once for every AndroidGfx (the screen, the presenter, the overlay thread): Skia then
     * keeps one glyph cache per face and size, and the presenter and the overlay find the screen's glyphs made.
     * Typefaces don't change, so the threads can share them.
     */
    private static synchronized Typeface[] fonts(AssetManager assets) {
        if (fontsLoaded) return FONTS;
        String[] files = { "inter-display-light.ttf", "inter-regular.ttf", "inter-medium.ttf", "plex-mono-medium.ttf" };
        for (int i = 0; i < 4; i++) {
            try {
                FONTS[i] = Typeface.createFromAsset(assets, "fonts/" + files[i]);
            } catch (RuntimeException e) {
                FONTS[i] = i == MONO ? Typeface.MONOSPACE : Typeface.DEFAULT;
            }
        }
        fontsLoaded = true;
        return FONTS;
    }

    AndroidGfx(AssetManager assets) {
        fonts = fonts(assets);
        bitmap.setDither(true);
        // dithered fills and strokes: translucent ones band less on the 16-bit surface (and where Skia dithers into the 16-bit plane)
        fill.setDither(true);
        stroke.setDither(true);
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

    private int fontNow = -1;
    private float sizeNow;

    private void font(int font, float size) {
        if (font == fontNow && size == sizeNow) return;
        text.setTypeface(fonts[font]);
        text.setTextSize(size);
        fontNow = font;
        sizeNow = size;
    }

    // tracked text: each character's own advance, measured once for the string (getTextWidths), and the whole
    // string drawn in one call at those places (drawPosText): the places the per-character loop used, without
    // a String, a measure and a draw per character
    private float[] adv = new float[32], pos = new float[64];
    private char[] chars = new char[32];
    /** The two ways measure alike on this camera (checked per face and size before use; any difference: the old way). */
    private static volatile boolean posTextOk = true;
    private static final java.util.Set<Integer> posTextChecked = java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());

    /** The string's characters' advances into adv; their number. */
    private int advances(String s) {
        int n = s.length();
        if (adv.length < n) { adv = new float[n * 2]; chars = new char[n * 2]; pos = new float[n * 4]; }
        text.getTextWidths(s, 0, n, adv);
        return n;
    }

    /** Every character the fonts have (tools/make_fonts.py), for the check below. */
    private static final String CHARSET;
    static {
        StringBuilder b = new StringBuilder();
        for (char ch = 0x20; ch < 0x7F; ch++) b.append(ch);
        CHARSET = b.append("\u00b7\u2013\u2014\u2026\u2039\u203a\u00b0\u00d7\u2019\u201c\u201d").toString();
    }

    /**
     * Once per face and size (font() set): getTextWidths gives every character the advance that measuring it alone
     * gave, so drawPosText puts each where the per-character loop did. Any difference: that loop, for good.
     */
    private boolean posText(int font, float size) {
        if (!posTextOk) return false;
        int key = font * 100000 + Math.round(size * 100);
        if (posTextChecked.contains(key)) return true;
        int n = advances(CHARSET);
        for (int i = 0; i < n; i++) {
            if (Math.abs(adv[i] - text.measureText(CHARSET, i, i + 1)) > 0.001f) { posTextOk = false; return false; }
        }
        posTextChecked.add(key);
        return true;
    }

    // Android 2.3 has no letter spacing, so tracked text is laid out one character at a time
    public void text(String s, float x, float y, int font, float size, int color, int align, int tracking) {
        font(font, size);
        text.setColor(color);
        if (tracking == 0) {   // measured only when it isn't left-aligned
            if (align != LEFT) { float w = text.measureText(s); x -= align == CENTER ? w / 2 : w; }
            c.drawText(s, x, y, text);
            return;
        }
        float extra = size * tracking / 1000f;
        if (posText(font, size)) {
            int n = advances(s);
            float w = extra * Math.max(0, n - 1);
            for (int i = 0; i < n; i++) w += adv[i];
            if (align == CENTER) x -= w / 2; else if (align == RIGHT) x -= w;
            for (int i = 0; i < n; i++) { pos[2 * i] = x; pos[2 * i + 1] = y; x += adv[i] + extra; }
            s.getChars(0, n, chars, 0);
            c.drawPosText(chars, 0, n, pos, text);
            return;
        }
        float w = textWidth(s, font, size, tracking);
        if (align == CENTER) x -= w / 2; else if (align == RIGHT) x -= w;
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
        if (posText(font, size)) {
            int n = advances(s);
            for (int i = 0; i < n; i++) w += adv[i];
        } else {
            for (int i = 0; i < s.length(); i++) w += text.measureText(s, i, i + 1);
        }
        return w + size * tracking / 1000f * Math.max(0, s.length() - 1);
    }

    public void image(Object img, float x, float y, float w, float h, boolean red) {
        Bitmap b = (Bitmap) img;
        if (b == null || b.isRecycled()) {   // black, not what the window held: the screen isn't filled under pictures
            fill.setColor(0xFF000000);
            c.drawRect(x, y, x + w, y + h, fill);
            return;
        }
        float iw = b.getWidth(), ih = b.getHeight(), sc = Math.max(w / iw, h / ih);
        float sw = w / sc, sh = h / sc, sx = (iw - sw) / 2, sy = (ih - sh) / 2;
        src.set(Math.round(sx), Math.round(sy), Math.round(sx + sw), Math.round(sy + sh));
        rf.set(x, y, x + w, y + h);
        c.drawBitmap(b, src, rf, red ? safelight : bitmap);
    }

    private final Paint exact = new Paint();

    private final android.graphics.Matrix m = new android.graphics.Matrix(), at = new android.graphics.Matrix();
    private final float[] pt = new float[2], v = new float[9];

    /**
     * A picture made at the screen's own pixels, drawn 1:1. Placed by an exact device-pixel translation, not
     * through the layout's scale (which happens to come to 1:1 but isn't exactly 1.0 in floats): Skia then
     * copies it row by row instead of sampling it (the viewfinder does this every frame).
     */
    private long exactMs;

    /** Time spent drawing 1:1 pictures since last asked (the viewfinder's frame, for its stats). */
    long takeExactMs() { long ms = exactMs; exactMs = 0; return ms; }

    public void imageExact(Object img, float x, float y, float w, float h) {
        long t0 = android.os.SystemClock.uptimeMillis();
        try { drawExact(img, x, y, w, h); } finally { exactMs += android.os.SystemClock.uptimeMillis() - t0; }
    }

    private void drawExact(Object img, float x, float y, float w, float h) {
        Bitmap b = (Bitmap) img;
        if (b == null || b.isRecycled()) return;
        c.getMatrix(m);
        pt[0] = x; pt[1] = y;
        m.mapPoints(pt);
        m.getValues(v);
        if (v[android.graphics.Matrix.MSKEW_X] != 0 || v[android.graphics.Matrix.MSKEW_Y] != 0
                || Math.abs(w * v[android.graphics.Matrix.MSCALE_X] - b.getWidth()) > 0.5f || Math.abs(h * v[android.graphics.Matrix.MSCALE_Y] - b.getHeight()) > 0.5f) {
            rf.set(x, y, x + w, y + h);   // not 1:1 after all: as asked
            c.drawBitmap(b, null, rf, exact);
            return;
        }
        c.save();
        at.setTranslate(Math.round(pt[0]), Math.round(pt[1]));
        c.setMatrix(at);
        c.drawBitmap(b, 0, 0, exact);
        c.restore();
    }

    public int imageWidth(Object img) { return ((Bitmap) img).getWidth(); }
    public int imageHeight(Object img) { return ((Bitmap) img).getHeight(); }
}
