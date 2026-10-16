package com.latent.camera;

/** The native image core (jni/filmcore.c). Not thread-safe: call from one worker thread only. */
final class FilmCore {
    static final int HIGHLIGHT = 0, SHADOW = 1, GRAIN = 2, GRAIN_SIZE = 3, QUALITY = 4, SEED = 5, THREADS = 6, COUNT = 7;
    static final int PREVIEW_FIT = 0, PREVIEW_CROP = 1;
    /** indexes into timing(): milliseconds spent decoding, applying the look, encoding, finishing, in total */
    static final int T_READ = 0, T_LOOK = 1, T_WRITE = 2, T_FINISH = 3, T_TOTAL = 4, T_CPU = 5, T_THREADS = 6, T_COUNT = 7;

    interface Progress { void onProgress(int percent); }

    static { System.loadLibrary("latent"); }

    private FilmCore() {}

    /**
     * Process inPath into outPath (written as .TMP, then renamed). lutPath may be null; previewPath,
     * if not null, also gets a screen-size JPEG of the result. 0 = ok.
     */
    static native int process(String inPath, String outPath, String lutPath, String previewPath, int[] params, Progress progress);

    /** A screen-size JPEG of an existing photo (frames developed before previews existed). 0 = ok. */
    static native int makePreview(String inPath, String outPath);

    /** Fill pixels (ARGB) with a preview; size[0], size[1] receive the produced size. 0 = ok. */
    static native int preview(String inPath, String lutPath, int[] params, int mode, int[] pixels, int w, int h, int[] size);

    /** Phase timings of the last successful process() call, into out[T_COUNT]. */
    static native void timing(int[] out);

    /** The look's inner loops, named as fl_bench reports them; setVariant picks one for developing. */
    static final String[] VARIANTS = { "per pair", "colour reuse", "packed table", "smaller table" };

    /**
     * Speed test: decodes inPath at 1/scale and times every inner loop on the same pixels, with each
     * one's difference from the full-precision reference. One line per loop, or null (lastError).
     */
    static native String bench(String inPath, String lutPath, int[] params, int scale);

    /**
     * Read only: cores present and online, those Latent may use, its CPU group, and each core's load
     * over one second ("key: value" lines). Takes a second.
     */
    static native String cores();

    /**
     * The look on inPath (at 1/scale) on one core, then on two threads with the helper allowed onto
     * every core: what that request did, where each ran, both times, and whether the pictures match.
     */
    static native String benchCores(String inPath, String lutPath, int[] params, int scale);

    /** Why a helper core didn't join the last develop ("" when all did). */
    static native String helperNote();

    /** Which inner loop developing uses (index into VARIANTS). */
    static native void setVariant(int v);

    /** Why the last call failed. */
    static native String lastError();
}
