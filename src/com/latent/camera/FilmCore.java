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

    /** Phase timings of the last successful process() call, into out[T_COUNT]. */
    static native void timing(int[] out);

    /** Why a helper core didn't join the last develop ("" when all did). */
    static native String helperNote();

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

    /** Every cached film forgotten (and the decoded previews): the next look reads its file again (the host tests start cold). */
    static native void forgetLut();

    /** Why the last call failed. */
    static native String lastError();

    /** The calling thread onto one core of the camera's four (Latent starts on core 3 only). 0, or -1 (lastError). */
    static native int pinCore(int core);

    // ---- the viewfinder's frame composer (v0.38.0): one live JPEG to the screen's 16-bit picture in one go
    /**
     * F_HIST: the histogram of the picture with the look, as it will develop (every other pixel of every other
     * row). F_TEMPORAL: the dither moves with seq. (A frame is decoded as Y'CbCr, through the film's Y'CbCr table;
     * 1, the level's flag until v0.45.0, is the host tests' RGB decode now.)
     */
    static final int F_HIST = 2, F_TEMPORAL = 4;
    /**
     * F_STILL: after decoding, compare the frame's signature (info[FI_SIG..], FI_SIG_N cells of mean
     * brightness, 8 across x 6 down, 0..255) with the one setStillRef gave; when no cell differs by more than
     * its threshold, stop there and return 1 (nothing written to the screen bitmap: the scene is still).
     * Without F_STILL the signature is still written to info, and the frame is composed as usual.
     */
    static final int F_STILL = 8;
    static final int FMT_565 = 0, FMT_4444 = 1;
    /** frame()'s info: the frame's size (0, 1), its histogram (FI_HIST, 64 bins), the overlay laid in, the signature. */
    static final int FI_HIST = 5, FI_HIST_BINS = 64, FI_OVERLAY = 69, FI_SIG = 70, FI_SIG_N = 48, FI_COUNT = 118;
    /**
     * frame()'s ms per stage: decode, read (the sharpening, when it's built in; the level until v0.45.0), look,
     * compose; of compose (from every eighth row), TM_BLEND the rows in one pass (blend, fades, dither, packing:
     * v0.45.0) and TM_DITHER the overlay's own pixels (the type); the rest of compose is stretching.
     */
    static final int TM_DECODE = 0, TM_READ = 1, TM_LOOK = 2, TM_COMPOSE = 3, TM_BLEND = 4, TM_DITHER = 5, TM_COUNT = 6;

    /** A composer for one thread (its buffers and its film); 0 if there's no memory. */
    static native long frameNew();
    static native void frameFree(long ctx);

    /**
     * One frame: jpeg decoded (fitted inside maxW x maxH), its signature written, the look, its histogram when asked, then for each of ow x oh screen pixels the 3:2 (aspect1000) crop
     * stretched, the overlay laid in (opaque type at the nearest 4-bit colour, solid), dithered (blue noise,
     * moving with seq under F_TEMPORAL) to 4 bits a channel in format (FMT_565 opaque, or FMT_4444 with shifts),
     * one pass a row. Written into bitmap (an android.graphics.Bitmap, ow x oh, that format) when given, else
     * into out. jpeg null: the last frame again, with the overlay as it is now (F_STILL ignored). info[FI_COUNT]:
     * frame size, histogram (2..4 are spare: the level's until v0.45.0), FI_OVERLAY the overlay generation laid in,
     * the signature at FI_SIG; tm[TM_COUNT] ms per stage. Returns 0, 1 (F_STILL and the scene is still: nothing
     * written; size, signature and TM_DECODE given), -1 (lastError), or -2 when bitmaps can't be written here.
     */
    static native int frame(long ctx, byte[] jpeg, int len, String lutPath, int[] params, int flags, int seq,
                            short[] out, Object bitmap, int ow, int oh, int aspect1000, int format,
                            int[] shifts, int maxW, int maxH, int[] info, int[] tm);

    /**
     * This core's and its memory's speed, as a line for the log, ending with a load chased through 8..256 KB (the
     * caches: where it slows); bitmap (an android.graphics.Bitmap) is filled too, or null.
     */
    static native String probe(Object bitmap);

    /**
     * The still scene F_STILL compares against (FI_SIG_N cells, as frame() wrote them to info), and how far a
     * cell may move and still count as still (0..255); sig null forgets it, and F_STILL then always composes.
     */
    static native void setStillRef(int[] sig, int threshold);

    /** Latent's type and fades laid over every frame from now on (argb as getPixels gives it, ow x oh); null clears. Its generation. */
    static native int setOverlay(int[] argb, int w, int h);
}
