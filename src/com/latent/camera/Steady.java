package com.latent.camera;

/**
 * The steady shutter (v0.47.0; docs/AUTOFOCUS.md): slower than 1/30 s, a full press waits for a frame that shows the
 * camera holding still, then the picture is taken; at most MAX_MS. The live frames are 320 px across, so what they
 * can tell is the jolt of the press settling, not a hand's finest tremor: the wait aims under STEADY_PX of blur in
 * the photo, or as steady as a frame can show (FLOOR_PX), whichever is more.
 *
 * Plain logic with no camera in it, tested on a Mac (tests/ViewfinderTest). One thread (the UI's).
 */
final class Steady {
    /** At most this long after the press: then it's taken anyway. */
    static final long MAX_MS = 600;
    /** No frame to judge by (too dark or plain to match): the press's own jolt is given this long. */
    static final long BLIND_MS = 250;
    /** The blur aimed under, in the photo's own pixels. */
    static final double STEADY_PX = 4;
    /** The least movement the frames can tell apart from still, in frame pixels a 70 ms frame. */
    static final double FLOOR_PX = 0.25;
    /** FilmCore.motion's detail (the mean step between neighbours x 16) under this: too plain to match. */
    static final int DETAIL_MIN = 32;

    boolean waiting;
    long since;                // the press (or when the wait began)
    private double exposure;   // seconds
    private double scale;      // photo pixels per frame pixel
    int frames, judged;        // pairs seen, and of them, with detail enough to judge
    double lastBlur = -1;      // the last judged pair's blur, in photo pixels (-1: none)

    /** A shot this long (seconds; 0: unknown) waits for a steady frame. */
    static boolean wanted(double exposure) { return exposure > Focus.SLOW_S; }

    void begin(long now, double exposure, double scale) {
        waiting = true;
        since = now;
        this.exposure = exposure;
        this.scale = scale;
        frames = judged = 0;
        lastBlur = -1;
    }

    /** The blur a frame pair's movement would make (photo pixels): dx, dy frame pixels in dtMs. */
    double blur(double dx, double dy, long dtMs) {
        return Math.sqrt(dx * dx + dy * dy) / dtMs * exposure * 1000 * scale;
    }

    /** What the wait aims under, in photo pixels: STEADY_PX, or the least a frame can tell at this exposure. */
    double aim() {
        return Math.max(STEADY_PX, FLOOR_PX / 70.0 * exposure * 1000 * scale);
    }

    /** Two frames, dtMs apart, moved dx, dy frame pixels (detail as FilmCore.motion's). True: take it now. */
    boolean frame(double dx, double dy, long dtMs, int detail) {
        if (!waiting || dtMs <= 0) return false;
        frames++;
        if (detail < DETAIL_MIN) return false;
        judged++;
        lastBlur = blur(dx, dy, dtMs);
        if (lastBlur > aim()) return false;
        waiting = false;
        return true;
    }

    /** Time's up (MAX_MS, or BLIND_MS with nothing judged yet). True: take it now. */
    boolean due(long now) {
        if (!waiting) return false;
        if (now - since >= MAX_MS || (judged == 0 && now - since >= BLIND_MS)) {
            waiting = false;
            return true;
        }
        return false;
    }

    void cancel() { waiting = false; }
}
