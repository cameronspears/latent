package com.latent.camera;

/**
 * When the viewfinder asks the camera for its next live frame, and what for (v0.45.0). Plain logic with no
 * camera in it, tested on a Mac (tests/ViewfinderTest). One thread (the UI's).
 *
 * - A frame goes to the first free look thread in core order (2, then 1, then 3: the third, at background
 *   priority, only when the other two are busy), as soon as one is free: a look thread that finishes is
 *   given the next frame at once instead of waiting for a poll. Never sooner than the spacing after the last
 *   frame asked for (MIN_FRAME_MS, or the Pacer's when the scene is still), so the camera is never asked
 *   more often than before.
 * - While a picture is being stored (the quiet), nothing: the camera's processor is for the picture.
 * - Hidden behind MENU, or with the look off (the camera's own view shows), a frame is only taken and
 *   dropped now and then, so the camera's frames are drained as they always have been and nothing is made.
 */
final class FrameScheduler {
    static final int WAIT = 0, DRAIN = 1, LOOK = 2;
    /**
     * Hidden or the look off: one frame taken and dropped this often. The look-off path took one every 300 ms
     * and the store quiet none for up to 3 s; the camera has never been left with frames unread for minutes.
     */
    static final long DRAIN_MS = 400;

    int looker = -1;    // LOOK: which look thread (its place in the core order) gets the frame
    long wakeAt;        // WAIT: when to ask again; 0 when only a look thread finishing (or the quiet ending) can change it

    /**
     * What to do now. busy: each look thread, in core order. lastGrabAt: when the last frame was taken.
     * drainOnly: hidden, or the look off. spacing: the least time between frames asked for.
     */
    int next(long now, long lastGrabAt, boolean[] busy, boolean quiet, boolean drainOnly, long spacing) {
        looker = -1;
        wakeAt = 0;
        if (quiet) return WAIT;
        if (drainOnly) {
            if (now - lastGrabAt >= DRAIN_MS) return DRAIN;
            wakeAt = lastGrabAt + DRAIN_MS;
            return WAIT;
        }
        int free = -1;
        for (int i = 0; i < busy.length; i++) if (!busy[i]) { free = i; break; }
        if (free < 0) return WAIT;   // the next frame goes to whichever finishes first
        if (now - lastGrabAt < spacing) { wakeAt = lastGrabAt + spacing; return WAIT; }
        looker = free;
        return LOOK;
    }

    /**
     * The quiet while a picture is written: until quietUntil (the floor after the shutter's answer), and on
     * until the picture is in the catalogue, but never past capAt.
     */
    static boolean quiet(long now, long quietUntil, boolean listed, long capAt) {
        return now < quietUntil || (!listed && now < capAt);
    }
}
