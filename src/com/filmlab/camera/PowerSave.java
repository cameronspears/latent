package com.filmlab.camera;

import android.content.SharedPreferences;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Keeps the camera from powering off while a photo is processed. Sony cameras ignore Android's
 * keep-screen-on flag and sleep after the menu's "Power Saving Start Time" (one minute by default),
 * so for the length of a save this raises that timer to the longest value the camera offers, then
 * puts the user's value back. The original is written to preferences first, so a crash mid-save is
 * repaired on the next launch.
 *
 * Uses com.sony.scalar.sysutil.didep.Settings by reflection (Sony API v7+). On a camera without it
 * every call is a no-op and the menu setting applies as usual.
 */
final class PowerSave {
    private static final String KEY = "apo_restore";
    private final SharedPreferences prefs;

    PowerSave(SharedPreferences prefs) { this.prefs = prefs; }

    private static Class<?> settings() throws Exception {
        return Class.forName("com.sony.scalar.sysutil.didep.Settings");
    }

    private static int get() throws Exception {
        return ((Integer) settings().getMethod("getAutoPowerOffTime").invoke(null)).intValue();
    }

    private static void set(int value) throws Exception {
        Method m = settings().getMethod("setAutoPowerOffTime", int.class);
        m.invoke(null, value);
    }

    /** Raise the timer for a long job. Returns a note for the on-screen log. */
    String hold() {
        try {
            int current = get();
            List<?> supported = (List<?>) settings().getMethod("getSupportedAutoPowerOffTimes").invoke(null);
            int longest = current;
            if (supported != null) for (Object o : supported) if (o instanceof Integer && (Integer) o > longest) longest = (Integer) o;
            if (longest == current) return "power-save " + current + " (already longest)";
            prefs.edit().putInt(KEY, current).commit();
            set(longest);
            return "power-save " + current + "->" + longest;
        } catch (Throwable t) {
            return "power-save unchanged (" + t.getClass().getSimpleName() + ")";
        }
    }

    /**
     * Experiment for long develops: re-apply the current timer value, which may restart the camera's
     * idle countdown. Harmless if it does not; the develop log records whether the camera stayed up.
     */
    void touch() {
        try { set(get()); } catch (Throwable t) { /* no Sony API: nothing to do */ }
    }

    /** Put the user's timer back, if hold() changed it (also after a crash). */
    void release() {
        if (!prefs.contains(KEY)) return;
        try {
            set(prefs.getInt(KEY, 0));
            prefs.edit().remove(KEY).commit();
        } catch (Throwable t) {
            // leave the key: the next launch tries again
        }
    }
}
