package com.latent.camera;

import java.lang.reflect.Field;

/** Bounded access to the camera's image memory (jni/sony_bridge.c), for HardwareDevelop. */
final class SonyMemory {
    private SonyMemory() {}

    static { System.loadLibrary("latent"); }

    /** Geometry indexes, as read through Sony's public DSP.getPropertyAsInt. */
    static final String[] KEYS = { "image-target-width", "image-target-height", "image-canvas-width", "image-canvas-height",
            "image-data-offset", "image-target-x-offset", "image-target-y-offset", "memory-size" };

    /** The native handle behind a Sony DeviceMemory object (its private mNativeMemory field). */
    static int handle(Object deviceMemory) throws Exception {
        Class<?> c = Class.forName("com.sony.scalar.hardware.DeviceMemory");
        if (!c.isInstance(deviceMemory)) throw new IllegalArgumentException("not Sony device memory");
        if (!((Boolean) c.getMethod("isValid").invoke(deviceMemory))) throw new IllegalStateException("Sony memory is not valid");
        Field f = c.getDeclaredField("mNativeMemory");
        f.setAccessible(true);
        int h = f.getInt(deviceMemory);
        if (h == 0) throw new IllegalStateException("Sony memory has no native handle");
        return h;
    }

    static native int read(int handle, byte[] dst, int size, int offset);
    static native int write(int handle, byte[] src, int size, int offset);
    /** block: bytes per Sony transfer, a size already verified on a scratch buffer. */
    static native int develop(int src, int[] srcGeometry, int dst, int[] dstGeometry, int block, String lut, int[] params, FilmCore.Progress progress);
    /** Whether the last develop could write its blocks straight back (output laid out like the source). */
    static native boolean lastDirect();
    static native String lastError();
}
