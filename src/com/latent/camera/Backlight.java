package com.latent.camera;

/**
 * The backlight, the largest share of the camera's power while a screen is up (the panel is a backlit
 * LCD: Latent's black saves nothing, only the light does). This works out the level the screen wants
 * now and which presses only wake it; MainActivity (and Relaunch) set the level through the window's
 * screenBrightness and ask again at next(). Pure logic, tested on the Mac (tests/RollTest).
 *
 *   working   the darkroom: the darkroom's light. A turn of the wheel or an arrow in the darkroom brightens
 *             it for PEEK_MS, as an always-on display wakes when it's touched, then it goes down again.
 *   resting   a roll that developed with nobody there: what it ends on stays at the darkroom's light until
 *             a press (the camera was set down; the screen needn't light up for nobody).
 *   still     a screen that waits for you (the camera back, Load film, the menu, a contact sheet): after
 *             REST_AFTER_MS without a press it rests at REST, as a phone dims before it locks.
 *   otherwise the camera's own brightness: the viewfinder, the viewer.
 *
 * The first press on a resting or rested screen only wakes it, so nothing is loaded or developed by a
 * press made to see the screen. The shutter button is the exception: it wakes the screen and acts, as
 * shooting is the one obvious action. The camera's own power-save timer is never touched here.
 */
final class Backlight {
    /** The camera's own brightness, as set in its menu (WindowManager's BRIGHTNESS_OVERRIDE_NONE). */
    static final float FULL = -1f;
    static final float DARK = 0.05f;   // the darkroom's light
    static final float REST = 0.3f;    // a still screen left alone: lower, still readable outdoors
    static final long REST_AFTER_MS = 30000, PEEK_MS = 5000;
    /** What a press is, for the light: any button, the shutter (half or full), or the wheel or an arrow in the darkroom. */
    static final int PRESS = 0, SHUTTER = 1, PEEK = 2;

    private boolean working, still, resting;
    private long quietSince, peekUntil;
    private long pressedAt = Long.MIN_VALUE;

    Backlight(long now) { quietSince = now; }

    /** Long work with the screen down starts or ends (on: the darkroom's light; off: lit again, a fresh rest timer). */
    void work(boolean on, long now) {
        if (on) { working = true; resting = false; return; }
        if (working) quietSince = now;
        working = false;
        peekUntil = 0;
    }

    /**
     * The darkroom's develop has ended (finished, or paused). If nobody pressed anything since it started
     * at startedAt, the screen rests at the darkroom's light until a press; otherwise it lights up.
     * Returns true when it rests.
     */
    boolean developEnded(long now, long startedAt) {
        work(false, now);
        resting = pressedAt < startedAt;
        return resting;
    }

    /** Whether the screen on show may rest. A change starts a fresh rest timer. */
    void still(boolean on, long now) {
        if (on == still) return;
        still = on;
        quietSince = now;
    }

    /** Latent came back on screen: lit, with a fresh rest timer. */
    void shown(long now) {
        resting = false;
        quietSince = now;
    }

    /** A press (its kind: PRESS, SHUTTER or PEEK). Returns true if it only wakes the screen and should do nothing else. */
    boolean press(long now, int kind) {
        boolean dimmed = !working && (resting || (still && now - quietSince >= REST_AFTER_MS));
        pressedAt = now;
        quietSince = now;
        resting = false;
        if (working && kind == PEEK) peekUntil = now + PEEK_MS;
        return dimmed && kind != SHUTTER;
    }

    /** The level wanted now: FULL, REST or DARK (a fraction of full brightness, or FULL for the camera's own). */
    float level(long now) {
        if (working) return now < peekUntil ? FULL : DARK;
        if (resting) return DARK;
        if (still && now - quietSince >= REST_AFTER_MS) return REST;
        return FULL;
    }

    /** When level() next changes by time alone (uptime ms), or -1 if only a press or a change of screen changes it. */
    long next(long now) {
        if (working) return now < peekUntil ? peekUntil : -1;
        if (resting || !still) return -1;
        long at = quietSince + REST_AFTER_MS;
        return at > now ? at : -1;
    }

    boolean resting() { return resting; }
}
