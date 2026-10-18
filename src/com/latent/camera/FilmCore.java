package com.latent.camera;

/**
 * The native image core (jni/filmcore.c). Its caches (films, grain, decoded previews) are shared and locked
 * (v0.45.0), so any thread may call it; a frame composer (frameNew) belongs to one thread at a time.
 */
final class FilmCore {
    static final int HIGHLIGHT = 0, SHADOW = 1, GRAIN = 2, GRAIN_SIZE = 3, QUALITY = 4, SEED = 5, THREADS = 6, COUNT = 7;
    static final int PREVIEW_FIT = 0, PREVIEW_CROP = 1;
    /** indexes into timing(): milliseconds spent decoding, applying the look, encoding, finishing, in total */
    static final int T_READ = 0, T_LOOK = 1, T_WRITE = 2, T_FINISH = 3, T_TOTAL = 4, T_CPU = 5, T_THREADS = 6;
    /** block develops only: ms Latent's thread waited for the helpers, rows it looked itself, the helpers' CPU ms */
    static final int T_WAIT = 7, T_ROWS = 8, T_HCPU = 9, T_SETUP = 10, T_WARM = 11, T_COUNT = 12;   /* T_SETUP: ms before the first block; T_WARM: 1 if its film was ready */

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

    /** live()'s info: the size produced, the level (1/100 degree, confidence 0..100, blocks agreeing), a 64-bin histogram. */
    static final int L_W = 0, L_H = 1, L_ANGLE = 2, L_CONFIDENCE = 3, L_SUPPORT = 4, L_HIST = 5, L_HIST_BINS = 64, L_COUNT = 69;

    /**
     * The viewfinder: one live frame (the camera's small JPEG, jpeg[0..len)) decoded in memory, fitted
     * inside w x h, with the look but no grain, into pixels (ARGB); info[L_COUNT] gets its size, the level
     * read from its straight edges, and its brightness histogram. 0 = ok.
     */
    static native int live(byte[] jpeg, int len, String lutPath, int[] params, int[] pixels, int w, int h, int[] info);

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

    /**
     * Film files (.cube) read with Latent's own exact decimal reader (true, the default since v0.42.0: the
     * camera's check found every row of every film identical) or sscanf (false).
     */
    static native void setFastCube(boolean on);

    /**
     * The next develop's film (lutPath, highlight, shadow, grain, grain size from params) made ready on a
     * thread of its own while this thread waits on something else: Sony's encoder. Returns at once (once an
     * earlier one is done); the next develop waits for it if it isn't done. The picture is the same either way.
     */
    static native void prewarm(String lutPath, int[] params);

    /**
     * A film file read and kept in the shared film cache (several films, least recently used dropped), so the
     * next look on it, on any thread, needs no card read. Safe from any thread. 0, or -1 (lastError).
     */
    static native int cacheLut(String lutPath);

    /**
     * Block develops remember recent per-pair lookups, 2^bits entries per thread (8..16), or don't (0, the
     * default). The picture is the same either way; the Develop test chooses whether it is faster.
     */
    static native void setPairMemo(int bits);

    /** Every cached film forgotten (and the decoded previews): the next develop reads its file from the card again. */
    static native void forgetLut();

    /**
     * A film file read both ways: ms[0] ms with sscanf, ms[1] with the fast reader, ms[2] rows the fast
     * reader took, ms[3] rows it left to sscanf. 0 identical, 1 different (lastError says where), -1 error.
     */
    static native int cubeCheck(String path, int[] ms);

    /** Which inner loop developing uses (index into VARIANTS). */
    static native void setVariant(int v);

    /** Why the last call failed. */
    static native String lastError();

    /**
     * A live frame (argb, w x h) for the 16-bit screen: cropped from the centre to aspect1000/1000 (0: ow:oh),
     * stretched to ow x oh (the panel's pixels aren't square) and ordered-dithered to 4 bits a channel, into
     * out as 16-bit pixels, each channel's nibble at shifts[0..3] (red, green, blue, alpha). 0, or -1.
     */
    static native int to4444(int[] argb, int w, int h, short[] out, int ow, int oh, int aspect1000, int[] shifts);

    /** The calling thread onto one core of the camera's four (Latent starts on core 3 only). 0, or -1 (lastError). */
    static native int pinCore(int core);

    // ---- the viewfinder's frame composer (v0.38.0): one live JPEG to the screen's 16-bit picture in one go
    static final int F_LEVEL = 1, F_HIST = 2, F_TEMPORAL = 4;
    /**
     * F_STILL: after decoding, compare the frame's signature (info[FI_SIG..], FI_SIG_N cells of mean
     * brightness, 8 across x 6 down, 0..255) with the one setStillRef gave; when no cell differs by more than
     * its threshold, stop there and return 1 (nothing written to the screen bitmap: the scene is still).
     * Without F_STILL the signature is still written to info, and the frame is composed as usual.
     */
    static final int F_STILL = 8;
    static final int FMT_565 = 0, FMT_4444 = 1;
    static final int NO_READING = -999999, FI_OVERLAY = 69, FI_SIG = 70, FI_SIG_N = 48, FI_COUNT = 118;
    static final int TM_DECODE = 0, TM_READ = 1, TM_LOOK = 2, TM_COMPOSE = 3, TM_BLEND = 4, TM_DITHER = 5, TM_COUNT = 6;

    /** A composer for one thread (its buffers and its film); 0 if there's no memory. */
    static native long frameNew();
    static native void frameFree(long ctx);

    /**
     * One frame: jpeg decoded (fitted inside maxW x maxH), the level and histogram read when flags ask,
     * the look, then for each of ow x oh screen pixels the 3:2 (aspect1000) crop stretched, the overlay
     * laid in, dithered (blue noise, moving with seq under F_TEMPORAL) to 4 bits a channel in format
     * (FMT_565 opaque, or FMT_4444 with shifts). Written into bitmap (an android.graphics.Bitmap, ow x oh, that format) when given,
     * else into out. jpeg null: the last frame again, with the overlay as it is now. info[FI_COUNT]: frame
     * size, level (NO_READING when not read), histogram, FI_OVERLAY the overlay generation laid in; tm
     * [TM_COUNT] ms per stage. Returns 0, 1 (F_STILL and the scene is still: nothing written), -1 (lastError),
     * or -2 when bitmaps can't be written here.
     */
    static native int frame(long ctx, byte[] jpeg, int len, String lutPath, int[] params, int flags, int seq,
                            short[] out, Object bitmap, int ow, int oh, int aspect1000, int format,
                            int[] shifts, int maxW, int maxH, int[] info, int[] tm);

    /** This core's and its memory's speed, as a line for the log; bitmap (an android.graphics.Bitmap) is filled too, or null. */
    static native String probe(Object bitmap);

    /**
     * The still scene F_STILL compares against (FI_SIG_N cells, as frame() wrote them to info), and how far a
     * cell may move and still count as still (0..255); sig null forgets it, and F_STILL then always composes.
     */
    static native void setStillRef(int[] sig, int threshold);

    /** Latent's type and fades laid over every frame from now on (argb as getPixels gives it, ow x oh); null clears. Its generation. */
    static native int setOverlay(int[] argb, int w, int h);
}
