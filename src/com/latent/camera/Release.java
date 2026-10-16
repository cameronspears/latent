package com.latent.camera;

/**
 * What the shutter button does in Latent's viewfinder. A half-press focuses, and if focus won't lock
 * (a dark room, a plain wall) it tries again for as long as the button is held. A full press takes
 * the picture: at once, or with "focus first" on, only once focus has locked (as the camera's own
 * AF-S does). After a picture, a button still held focuses again for the next one.
 *
 * Plain logic with no camera in it, so it can be tested on a Mac (tests/ViewfinderTest); Viewfinder
 * carries out what it asks for and reports back through focused() and shot(). One thread (the UI's).
 */
final class Release {
    static final int READY = 0, SEARCHING = 1, LOCKED = 2, NO_LOCK = 3;   // UiState.afState
    /** Tries per half-press before it rests on "can't focus" (a new half-press starts again). */
    static final int MAX_TRIES = 6;
    /** How long "can't focus" shows between tries. */
    static final long RETRY_MS = 400;

    /** The camera, as Release needs it. */
    interface Camera {
        void focus();
        void cancelFocus();
        void shoot();
        /** Runs r on this thread after ms, unless a newer call replaces it. */
        void later(Runnable r, long ms);
    }

    private final Camera camera;
    boolean focusFirst;
    int state = READY;
    int tries;                 // focus tries since the half-press
    boolean half;              // the button is half-pressed (or further)
    boolean waiting;           // pressed fully, waiting for focus to lock (focus first)
    boolean shooting;          // the picture is being taken
    boolean refused;           // the last full press took no picture: focus first, and focus never locked
    private int gen;           // retries scheduled before a newer press don't run

    Release(Camera camera, boolean focusFirst) { this.camera = camera; this.focusFirst = focusFirst; }

    void halfDown() {
        if (half) return;
        half = true;
        refused = false;
        tries = 0;
        if (!shooting) focus();
    }

    void halfUp() {
        half = false;
        waiting = false;
        gen++;
        if (shooting) return;   // the picture finishes; shot() sets the brackets back
        if (state == SEARCHING || state == LOCKED) camera.cancelFocus();
        state = READY;
        tries = 0;
    }

    void fullDown() {
        if (shooting) return;
        if (!half) halfDown();   // the full press always comes after a half one; just in case
        refused = false;
        if (state == LOCKED) { shoot(); return; }
        if (!focusFirst) {
            if (state == SEARCHING) camera.cancelFocus();
            shoot();
            return;
        }
        waiting = true;   // goes as soon as focus locks
        if (state == NO_LOCK && tries >= MAX_TRIES) { tries = 0; focus(); }
    }

    /** The camera's answer to focus(): locked, or not. */
    void focused(boolean locked) {
        if (state != SEARCHING || shooting) return;
        if (locked) {
            state = LOCKED;
            if (waiting) shoot();
            return;
        }
        camera.cancelFocus();
        state = NO_LOCK;
        if (half && tries < MAX_TRIES) {
            final int g = ++gen;
            camera.later(new Runnable() { public void run() { if (g == gen && half && state == NO_LOCK && !shooting) focus(); } }, RETRY_MS);
        } else if (waiting) {
            waiting = false;
            refused = true;
        }
    }

    /** The picture is taken (ok) or the camera wouldn't take it. */
    void shot(boolean ok) {
        if (!shooting) return;
        shooting = false;
        waiting = false;
        camera.cancelFocus();
        state = READY;
        tries = 0;
        if (half) focus();
    }

    private void focus() {
        tries++;
        state = SEARCHING;
        camera.focus();
    }

    private void shoot() {
        waiting = false;
        shooting = true;
        gen++;
        camera.shoot();
    }
}
