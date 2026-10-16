package com.latent.camera;

/**
 * The viewfinder's level, from the picture itself: the A5100's motion sensors give no readings, so
 * each live frame's straight edges say how the horizon lies (FilmCore.live, fl_level in filmcore.c).
 * This steadies those readings for the screen: it shows only when the frames are sure, eases toward
 * each new reading, ignores a lone odd one, and turns green once level, with a little give so it
 * doesn't flicker at the edge. Plain logic, tested on a Mac (tests/ViewfinderTest).
 */
final class Level {
    /** A frame counts when this share of its edges agree (0..100), from at least this many blocks. */
    static final int MIN_CONFIDENCE = 30, MIN_BLOCKS = 6;
    /** Green within this many degrees; it stays green until past the second. */
    static final float LEVEL_IN = 0.5f, LEVEL_OUT = 0.9f;
    /** A reading this far from the shown angle is an outlier, unless frames keep saying so. */
    static final float JUMP = 3f;
    static final int JUMP_FRAMES = 3;
    /** With no sure reading for this long, the level hides. */
    static final long HOLD_MS = 1500;

    boolean shown;             // drawn at all
    float angle;               // degrees, positive when the scene's lines rise to the right
    boolean level;             // within LEVEL_IN (then LEVEL_OUT)
    private long lastSure;
    private int odd;           // readings in a row far from the shown angle
    int frames, sure;          // for the log

    /** One frame's reading: angle in 1/100 degree, confidence 0..100, blocks agreeing. */
    void frame(int angle100, int confidence, int blocks, long now) {
        frames++;
        if (confidence < MIN_CONFIDENCE || blocks < MIN_BLOCKS) { tick(now); return; }
        sure++;
        float a = angle100 / 100f;
        lastSure = now;
        if (!shown) {
            angle = a;
            shown = true;
            odd = 0;
        } else if (Math.abs(a - angle) > JUMP) {
            if (++odd >= JUMP_FRAMES) { angle = a; odd = 0; }   // the camera really moved
        } else {
            angle += 0.45f * (a - angle);
            odd = 0;
        }
        float off = Math.abs(angle);
        if (level && off > LEVEL_OUT) level = false;
        else if (!level && off <= LEVEL_IN) level = true;
    }

    /** Time passing without a sure reading. */
    void tick(long now) {
        if (shown && now - lastSure > HOLD_MS) { shown = false; level = false; odd = 0; }
    }
}
