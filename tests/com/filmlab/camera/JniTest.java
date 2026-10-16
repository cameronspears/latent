package com.filmlab.camera;

/** Host test: drives the real JNI bridge (built as a macOS library) exactly as the app does. */
public class JniTest {
    public static void main(String[] a) {
        String in = a[0], outDir = a[1], lut = a[2];
        int[] p = new int[FilmCore.COUNT];
        p[FilmCore.HIGHLIGHT] = -2; p[FilmCore.SHADOW] = 1; p[FilmCore.GRAIN] = 5;
        p[FilmCore.GRAIN_SIZE] = 2; p[FilmCore.QUALITY] = 95; p[FilmCore.SEED] = 42; p[FilmCore.THREADS] = 4;
        final int[] calls = new int[2];
        int rc = FilmCore.process(in, outDir + "/FLA04491.JPG", lut, outDir + "/PRV04491.JPG", p, new FilmCore.Progress() {
            public void onProgress(int pct) { calls[0]++; calls[1] = pct; }
        });
        check(rc == 0, "process: " + FilmCore.lastError());
        check(calls[0] > 10 && calls[1] == 100, "progress callbacks " + calls[0] + " last " + calls[1]);
        check(new java.io.File(outDir, "FLA04491.JPG").length() > 1000000, "output written");
        check(!new java.io.File(outDir, "FLA04491.TMP").exists(), "no temp file left");
        check(new java.io.File(outDir, "PRV04491.JPG").length() > 20000, "screen preview written while developing");
        check(FilmCore.makePreview(outDir + "/FLA04491.JPG", outDir + "/PRV2.JPG") == 0
                && new java.io.File(outDir, "PRV2.JPG").length() > 20000, "preview made for an existing frame");

        int[] px = new int[432 * 288], size = new int[2];
        rc = FilmCore.preview(in, lut, p, FilmCore.PREVIEW_FIT, px, 432, 288, size);
        check(rc == 0 && size[0] <= 432 && size[1] <= 288 && (size[0] >= 428 || size[1] == 288),
                "fit preview fills the box " + size[0] + "x" + size[1] + " " + FilmCore.lastError());
        check((px[0] >>> 24) == 0xFF && px[size[0] * size[1] - 1] != 0, "fit pixels opaque and filled");
        rc = FilmCore.preview(in, null, p, FilmCore.PREVIEW_CROP, px, 432, 288, size);
        check(rc == 0 && size[0] == 432 && size[1] == 288, "crop preview without LUT");

        rc = FilmCore.process(in, outDir + "/FLB04491.JPG", outDir + "/MISSING.CUB", null, p, null);
        check(rc != 0 && FilmCore.lastError().contains("cannot open look"), "missing LUT reported: " + FilmCore.lastError());
        rc = FilmCore.preview(in, lut, p, 0, new int[10], 432, 288, size);
        check(rc != 0 && FilmCore.lastError().contains("buffer"), "small buffer rejected");
        rc = FilmCore.process(outDir + "/NOPE.JPG", outDir + "/FLC04491.JPG", null, null, p, null);
        check(rc != 0, "missing source reported: " + FilmCore.lastError());
        int[] bp = { -2, 1, 5, 2, 95, 1, 1 };
        String bench = FilmCore.bench(in, lut, bp, 4);
        check(bench != null && bench.split("\n").length == 2 + FilmCore.VARIANTS.length && bench.contains("colour reuse"),
                "speed test reports every inner loop: " + (bench == null ? FilmCore.lastError() : bench.replace("\n", " | ")));
        check(FilmCore.bench(in + ".missing", lut, bp, 4) == null && FilmCore.lastError().length() > 0, "speed test reports a missing photo");
        String cores = FilmCore.cores();
        check(cores != null && cores.contains("busy while idle"), "core report reads (on a Mac: unreadable)");
        String two = FilmCore.benchCores(in, lut, bp, 4);
        check(two != null && two.contains("pictures identical") && two.contains("widen:"), "two-thread run matches one core: " + (two == null ? FilmCore.lastError() : two.replace("\n", " | ")));
        check(FilmCore.benchCores(in + ".missing", lut, bp, 4) == null, "two-thread run reports a missing photo");
        FilmCore.setVariant(1);
        FilmCore.setVariant(0);
        System.out.println("JNI OK");
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  pass  " : "  FAIL  ") + what);
        if (!ok) System.exit(1);
    }
}
