package com.latent.camera;

/**
 * The camera's stored settings (jni/settings_bridge.c): the values its menus change and that stay
 * after Latent closes. Any can be read; only the few NeutralSettings switches can be written, and
 * the native side refuses anything else. The setting numbers and how each is stored are Recipe
 * Lab's, which lists the A5100 as supported.
 */
final class CameraSettings {
    private CameraSettings() {}

    static { System.loadLibrary("latent"); }   // may be the first native call of a run

    static final int WB_MODE = 0x01070019, WB_KELVIN = 0x01070018,
            WB_AB = 0x01070017, WB_GM = 0x01070016,                 // the white balance menu's fine-tune grid
            WB_AB_AUTO = 0x0107067f, WB_GM_AUTO = 0x0107067e,       // the copies the camera applies in Auto
            WB_AB_KELVIN = 0x01070683, WB_GM_KELVIN = 0x01070682,   // ... and in colour temperature
            STYLE = 0x01070175, CONTRAST = 0x01070178, SATURATION = 0x01070187, SHARPNESS = 0x0107018a,
            EFFECT = 0x010706f1, DRO = 0x01070104, DRO_LEVEL = 0x01070775, EV = 0x010700b8;

    static final int[] ALL = { WB_MODE, WB_KELVIN, WB_AB, WB_GM, WB_AB_AUTO, WB_GM_AUTO, WB_AB_KELVIN, WB_GM_KELVIN,
            STYLE, CONTRAST, SATURATION, SHARPNESS, EFFECT, DRO, DRO_LEVEL, EV };

    static final String[] NAMES = { "white balance mode", "colour temperature", "WB fine-tune A-B", "WB fine-tune G-M",
            "WB fine-tune A-B (Auto)", "WB fine-tune G-M (Auto)", "WB fine-tune A-B (K)", "WB fine-tune G-M (K)",
            "Creative Style", "contrast", "saturation", "sharpness", "Picture Effect", "DRO", "DRO level", "exposure compensation" };

    static final int ATTR_READ_ONLY = 1;

    static native boolean available();
    /** The setting's stored bytes, or null (see lastError). */
    static native byte[] read(int id);
    /** The setting's attribute bits (ATTR_READ_ONLY), or negative on failure. */
    static native int attr(int id);
    /** Writes one byte to one of the switchable settings; 0 = stored. The native side refuses the rest. */
    static native int write(int id, int value);
    /** Asks the camera to keep what was written. */
    static native void sync();
    static native String lastError();

    // ------------------------------------------------------------------------------ as the menus show them

    private static final String[] STYLES = { null, "Standard", "Vivid", "Neutral", "Portrait", "Landscape", "B/W", "Clear", "Deep",
            "Light", "Sunset", "Night Scene", "Autumn Leaves", null, "Sepia" };
    private static final String[] EFFECTS = { "Off", "Toy Camera", "Pop Colour", "Posterisation", "Retro Photo", "Soft High-key",
            "Partial Colour", "High Contrast Mono", "Soft Focus", "HDR Painting", "Rich-tone Mono", "Miniature", "Illustration", "Watercolour" };

    static String wbMode(int v) {
        return v == 1 ? "Auto" : v == 14 ? "Colour temperature" : "mode " + v;
    }

    /** Amber is stored positive. */
    static String amberBlue(int v) { return v == 0 ? "0" : v > 0 ? "A" + v : "B" + (-v); }

    /** Magenta is stored positive; the menu counts green. */
    static String greenMagenta(int v) { return v == 0 ? "0" : v < 0 ? "G" + (-v) : "M" + v; }

    static String style(int v) { return v >= 0 && v < STYLES.length && STYLES[v] != null ? STYLES[v] : "style " + v; }

    static String effect(int v) { return v >= 0 && v < EFFECTS.length ? EFFECTS[v] : "effect " + v; }

    static String dro(int v) { return v == 0 ? "Off" : v == 1 ? "Auto" : v <= 6 ? "Lv" + (v - 1) : "dro " + v; }

    static String signed(int v) { return v > 0 ? "+" + v : String.valueOf(v); }
}
