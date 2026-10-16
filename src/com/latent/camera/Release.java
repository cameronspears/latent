package com.latent.camera;

/**
 * What the shutter button does in Latent's viewfinder.
 *
 * The button is the camera's own (Sony's "direct shutter"): a half-press focuses and a full press
 * takes and stores the picture exactly as in the camera's shooting mode. That is the one way shown to
 * store pictures with live frames running (sony-remote-reboot on the A5000; v0.31.0 and v0.31.1 took
 * pictures themselves without it, and the camera hung storing them). Latent follows along from the
 * camera's focus reports (cameraFocus) to draw the brackets.
 *
 * When the camera can't lock and the button is still held (a dark room, a plain wall), Latent takes
 * the button back for a while (app mode): direct shutter off, its own focus, tried again and again
 * while held. A full press then takes the picture through Latent, at once, or with focus first only
 * once focus has locked. Letting go hands the button back to the camera. This is the sequence
 * sony-remote-reboot uses for a phone's half-press and shutter.
 *
 * With 'always' (v0.32.0 on: no direct shutter, the Shooting test's proven way) the button is
 * Latent's from the start: a half-press is Latent's focus, retried while held, and a full press
 * Latent's picture.
 *
 * Plain logic with no camera in it, tested on a Mac (tests/ViewfinderTest). One thread (the UI's).
 */
final class Release {
    static final int READY = 0, SEARCHING = 1, LOCKED = 2, NO_LOCK = 3;   // UiState.afState
    /** The camera's focus reports (CameraEx.AutoFocusDoneListener). */
    static final int AF_CLEAR = 0, AF_LOCK = 1, AF_WARN = 2, AF_WORKING = 3, AF_CONTINUOUS = 4;
    /** Tries per half-press before it rests on "can't focus" (a new half-press starts again). */
    static final int MAX_TRIES = 6;
    /** How long "can't focus" shows between tries. */
    static final long RETRY_MS = 400;

    /** The camera, as Release needs it. */
    interface Camera {
        /** Takes the button from the camera (direct shutter off), if it isn't Latent's already. */
        void takeButton();
        /** Takes the button (as takeButton) and focuses. */
        void appFocus();
        void cancelFocus();
        /** Takes the picture (in app mode: direct shutter is off). */
        void shoot();
        /** Gives the button back to the camera: focus cancelled, direct shutter on. */
        void handBack();
        /** Runs r on this thread after ms. */
        void later(Runnable r, long ms);
    }

    private final Camera camera;
    /** The button is always Latent's (no direct shutter): Latent focuses and takes every picture. */
    final boolean always;
    boolean focusFirst;
    int state = READY;
    int tries;                 // focus tries since the half-press (the camera's own is the first)
    boolean half;              // the button is half-pressed (or further)
    boolean app;               // Latent has the button (retrying focus); else the camera has it
    boolean waiting;           // app mode: pressed fully, waiting for focus to lock (focus first)
    boolean shooting;          // app mode: the picture is being taken
    boolean refused;           // the last full press took no picture: focus first, and focus never locked
    boolean locked;            // the roll is full: the button is Latent's for good, and takes nothing
    private int gen;           // retries scheduled before a newer press don't run

    Release(Camera camera, boolean focusFirst, boolean always) {
        this.camera = camera;
        this.focusFirst = focusFirst;
        this.always = always;
        this.app = always;
    }

    void halfDown() {
        if (half) return;
        half = true;
        refused = false;
        tries = 0;
        if (app) appFocus();
        else { tries = 1; state = SEARCHING; }   // the camera focuses by itself
    }

    void halfUp() {
        half = false;
        waiting = false;
        gen++;
        if (shooting) return;   // the picture finishes; shot() hands the button back
        if (app && !locked && !always) { camera.handBack(); app = false; }
        else if (app) camera.cancelFocus();
        state = READY;
        tries = 0;
    }

    /** A report from the camera's own focus (only heeded while the camera has the button). */
    void cameraFocus(int status) {
        if (app || shooting) return;
        if (status == AF_WORKING) state = SEARCHING;
        else if (status == AF_LOCK || status == AF_CONTINUOUS) state = LOCKED;
        else if (status == AF_WARN) { if (half) failed(); else state = NO_LOCK; }   // it couldn't lock: Latent tries again while it's held
        else if (status == AF_CLEAR && !half) state = READY;   // let go (while held: after a picture, no change)
    }

    /** A full press. In app mode Latent takes the picture; otherwise the camera has already taken it. */
    void fullDown() {
        if (!app || shooting) return;
        if (!half) halfDown();
        refused = false;
        if (locked) { refused = true; return; }
        if (state == LOCKED) { shoot(); return; }
        if (!focusFirst) {
            if (state == SEARCHING) camera.cancelFocus();
            shoot();
            return;
        }
        waiting = true;   // goes as soon as focus locks
        if (state == NO_LOCK && tries >= MAX_TRIES) { tries = 0; appFocus(); }
    }

    /** Latent's own focus answered (app mode): locked, or not. */
    void focused(boolean ok) {
        if (!app || state != SEARCHING || shooting) return;
        if (ok) {
            state = LOCKED;
            if (waiting) shoot();
            return;
        }
        camera.cancelFocus();
        failed();
    }

    private void failed() {
        state = NO_LOCK;
        if (half && tries < MAX_TRIES) {
            final int g = ++gen;
            camera.later(new Runnable() { public void run() { if (g == gen && half && state == NO_LOCK && !shooting) appFocus(); } }, RETRY_MS);
        } else if (waiting) {
            waiting = false;
            refused = true;
        }
    }

    /** Latent's picture is taken (ok) or the camera wouldn't take it: the button goes back to the camera. */
    void shot(boolean ok) {
        if (!shooting) return;
        shooting = false;
        waiting = false;
        if (!locked && !always) { camera.handBack(); app = false; }
        else camera.cancelFocus();
        state = READY;
        tries = 0;
        if (always && half && !locked) appFocus();   // still held: focus again for the next one
    }

    /** The roll is full: no more pictures. The button stays Latent's, and a full press takes nothing. */
    void lock() {
        if (locked) return;
        locked = true;
        if (!app) { app = true; camera.takeButton(); }
        state = READY;
    }

    private void appFocus() {
        tries++;
        state = SEARCHING;
        app = true;
        camera.appFocus();
    }

    private void shoot() {
        waiting = false;
        shooting = true;
        gen++;
        camera.shoot();
    }
}
