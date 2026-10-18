package com.latent.camera;

/**
 * How often the viewfinder makes a frame (v0.45.0), the way a phone's screen slows when nothing on it moves.
 * Full rate while anything moves or you touch anything. Once nothing has been touched for IDLE_MS and the
 * scene has held still for STILL_MS, a frame is only checked (its brightness in 8 x 6 cells, read as it is
 * decoded, before any look: FilmCore.F_STILL) about four times a second, with a whole frame at least once
 * a second, so slow changes of light still show. The first frame that differs, or any key, and it's back
 * to full rate at once.
 *
 * Plain logic with no camera in it, tested on a Mac (tests/ViewfinderTest). One thread (the UI's).
 */
final class Pacer {
    static final long IDLE_MS = 9000, STILL_MS = 2000, SLOW_MS = 250, WHOLE_MS = 1000;
    /** How far a cell's mean brightness (0..255) may move and the scene still count as still. */
    static final int THRESHOLD = 4;

    private long lastInputAt, stillSince, lastWholeAt;
    private int[] spell;      // the frame this still spell is measured from
    private boolean refChanged;
    boolean slow;
    long slowSince;
    /** While slow: what each check compares with (FilmCore.setStillRef); null at full rate. */
    int[] ref;

    /** A key, the shutter half-pressed, the scale open, a film card or a picture storing: full rate. True if it was slow. */
    boolean input(long now) {
        lastInputAt = now;
        if (!slow) return false;
        slow = false;
        ref = null;
        refChanged = true;
        spell = null;   // stillness counts again from the next frame
        return true;
    }

    /**
     * A whole frame was made, with its signature (FilmCore.FI_SIG_N cells; null: none). True when it changed
     * the pace: slow from now (nothing touched, the scene still), or full rate again (the scene moved).
     */
    boolean whole(long now, int[] sig) {
        if (sig == null) return false;
        if (slow) {
            if (moved(sig, ref)) {
                slow = false;
                ref = null;
                refChanged = true;
                spell = sig;
                stillSince = now;
                return true;
            }
            ref = sig;   // follows slow changes of light, so they don't add up to a false move
            refChanged = true;
            return false;
        }
        if (spell == null || moved(sig, spell)) {
            spell = sig;
            stillSince = now;
            return false;
        }
        if (now - stillSince >= STILL_MS && now - lastInputAt >= IDLE_MS) {
            slow = true;
            slowSince = now;
            ref = sig;
            refChanged = true;
            lastWholeAt = now;
            return true;
        }
        return false;
    }

    /**
     * A frame is being asked for: a check (true: F_STILL, nothing made if the scene is still), or a whole
     * frame (false: at full rate, and once a second while slow).
     */
    boolean check(long now) {
        if (!slow) return false;
        if (now - lastWholeAt >= WHOLE_MS) { lastWholeAt = now; return false; }
        return true;
    }

    /** The least time between frames asked for. */
    long spacing(long full) { return slow ? Math.max(full, SLOW_MS) : full; }

    /** The still reference changed since the last ask (hand ref to FilmCore.setStillRef). */
    boolean takeRefChange() {
        boolean c = refChanged;
        refChanged = false;
        return c;
    }

    static boolean moved(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) return true;
        for (int i = 0; i < a.length; i++) if (Math.abs(a[i] - b[i]) > THRESHOLD) return true;
        return false;
    }
}
