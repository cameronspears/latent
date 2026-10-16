package com.latent.camera;

/**
 * The few drawing operations the UI needs, so the same screen code runs on the camera (Android
 * Canvas, AndroidGfx) and on a Mac (Java2D, tools/HostGfx) for pixel-exact design previews.
 * Coordinates are in the 640x480 design space. Colours are 0xAARRGGBB.
 */
interface Gfx {
    int DISPLAY = 0, REGULAR = 1, MEDIUM = 2, MONO = 3;     // fonts
    int LEFT = 0, CENTER = 1, RIGHT = 2;                    // text alignment

    void rect(float x, float y, float w, float h, int color);
    void roundRect(float x, float y, float w, float h, float r, int color);
    void strokeRoundRect(float x, float y, float w, float h, float r, float width, int color);
    void circle(float cx, float cy, float r, int color);
    void strokeCircle(float cx, float cy, float r, float width, int color);
    void line(float x1, float y1, float x2, float y2, float width, int color);

    /** Draws text with its baseline at y. Letter spacing in 1/1000 em (tracking), 0 for none. */
    void text(String s, float x, float y, int font, float size, int color, int align, int tracking);
    float textWidth(String s, int font, float size, int tracking);

    /**
     * Draws an image (Bitmap / BufferedImage) scaled to cover the box, cropping the centre when the
     * aspect ratios differ (so letterboxed thumbnails lose their bars). red = safelight tint.
     */
    void image(Object img, float x, float y, float w, float h, boolean red);
    /** The whole image stretched into the rect, unfiltered: a picture already made at the screen's own pixels. */
    void imageExact(Object img, float x, float y, float w, float h);
    int imageWidth(Object img);
    int imageHeight(Object img);
}
