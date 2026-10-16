package com.latent.camera;

/**
 * Colour depth of the camera's screen. Sony gives apps a 16-bit RGBA4444 frame buffer by default
 * (4,096 colours: photos band badly); Gpelibrary can switch it to full ABGR8888 colour, as the
 * OpenMemories framework does. Film Lab asks for full colour while it is in front and gives the
 * default back when it leaves. By reflection: a no-op anywhere this Sony API is missing.
 */
final class Display {
    private Display() {}

    /**
     * The panel's real width/height (16:9 on the A5100), which apps can't see from the 640x480
     * frame buffer Sony stretches onto it. 0 when unknown (then the buffer's own shape is used).
     */
    static float aspect() {
        try {
            Class<?> dm = Class.forName("com.sony.scalar.hardware.avio.DisplayManager");
            Object m = dm.newInstance();
            try {
                Object device = dm.getMethod("getActiveDevice").invoke(m);
                Object info = dm.getMethod("getDeviceInfo", String.class).invoke(m, device);
                int a = info.getClass().getField("aspect").getInt(info);
                switch (a) {
                    case 1: return 3f / 2;     // ASPECT_RATIO_3_2
                    case 2: return 16f / 9;    // ASPECT_RATIO_16_9
                    case 3: return 4f / 3;     // ASPECT_RATIO_4_3
                    case 4: return 5f / 3;     // ASPECT_RATIO_5_3
                    case 5: return 11f / 9;    // ASPECT_RATIO_11_9
                    default: return 0;
                }
            } finally {
                dm.getMethod("finish").invoke(m);   // must be released before the app pauses
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    static boolean fullColour(boolean on) {
        try {
            Class<?> lib = Class.forName("com.sony.scalar.sysutil.didep.Gpelibrary");
            Class<?> type = Class.forName("com.sony.scalar.sysutil.didep.Gpelibrary$GS_FRAMEBUFFER_TYPE");
            Object value = type.getMethod("valueOf", String.class).invoke(null, on ? "ABGR8888" : "RGBA4444");
            lib.getMethod("changeFrameBufferPixel", type).invoke(null, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
