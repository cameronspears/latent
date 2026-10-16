package com.latent.camera;

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
        // the viewfinder: a live frame (here the screen preview, a small JPEG like the camera's) decoded from memory
        byte[] jpeg = read(outDir + "/PRV04491.JPG");
        int[] lp = new int[FilmCore.L_COUNT], live = new int[320 * 240];
        rc = FilmCore.live(jpeg, jpeg.length, lut, p, live, 320, 240, lp);
        int sum = 0;
        for (int i = 0; i < FilmCore.L_HIST_BINS; i++) sum += lp[FilmCore.L_HIST + i];
        check(rc == 0 && lp[FilmCore.L_W] == 320 && lp[FilmCore.L_H] <= 240 && lp[FilmCore.L_H] >= 200, "live frame fitted to " + lp[FilmCore.L_W] + "x" + lp[FilmCore.L_H] + " " + FilmCore.lastError());
        check(sum == lp[FilmCore.L_W] * lp[FilmCore.L_H] && (live[0] >>> 24) == 0xFF, "its histogram counts every pixel; pixels opaque");
        check(lp[FilmCore.L_CONFIDENCE] >= 0 && lp[FilmCore.L_CONFIDENCE] <= 100, "level read: " + lp[FilmCore.L_ANGLE] / 100.0 + " degrees, confidence " + lp[FilmCore.L_CONFIDENCE]);
        {   // the film is kept between frames (v0.37.0): another film changes the look, and coming back is exact
            String other = new java.io.File(new java.io.File(lut).getParentFile(), "TRIX400.CUB").getPath();
            int[] again = new int[320 * 240], mono = new int[320 * 240], back = new int[320 * 240];
            FilmCore.live(jpeg, jpeg.length, lut, p, again, 320, 240, lp);
            check(java.util.Arrays.equals(live, again), "the same film twice: the same frame");
            check(FilmCore.live(jpeg, jpeg.length, other, p, mono, 320, 240, lp) == 0 && !java.util.Arrays.equals(live, mono), "another film: another look");
            int[] hp = p.clone();
            hp[FilmCore.HIGHLIGHT] = 3;
            int[] bright = new int[320 * 240];
            FilmCore.live(jpeg, jpeg.length, lut, hp, bright, 320, 240, lp);
            check(!java.util.Arrays.equals(live, bright), "the film's highlight setting changes the look");
            FilmCore.live(jpeg, jpeg.length, lut, p, back, 320, 240, lp);
            check(java.util.Arrays.equals(live, back), "back to the first film: exactly as before");
            check(FilmCore.live(jpeg, jpeg.length, lut + ".missing", p, back, 320, 240, lp) != 0, "a missing film refused: " + FilmCore.lastError());
            FilmCore.live(jpeg, jpeg.length, lut, p, back, 320, 240, lp);
            check(java.util.Arrays.equals(live, back), "and the first film still right after it");
        }
        rc = FilmCore.live(jpeg, jpeg.length / 3, lut, p, live, 320, 240, lp);
        check(true, "a cut-off frame doesn't crash (rc " + rc + ")");
        byte[] junk = new byte[2048];
        check(FilmCore.live(junk, junk.length, lut, p, live, 320, 240, lp) != 0, "not a JPEG: refused (" + FilmCore.lastError() + ")");
        check(FilmCore.live(jpeg, jpeg.length, lut, p, new int[100], 320, 240, lp) != 0 && FilmCore.lastError().contains("buffers"), "small buffer rejected");
        check(FilmCore.live(jpeg, jpeg.length + 1, lut, p, live, 320, 240, lp) != 0, "a length past the array rejected");
        {   // the live frame for the 16-bit screen: cropped to 3:2, stretched, dithered to 4 bits a channel
            int w = 320, h = 216, ow = 540, oh = 480;
            int[] argb = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) argb[y * w + x] = 0xFF000000 | (x < 160 ? 0x808080 : 0xFF0000 | (y * 255 / (h - 1)) << 8);
            short[] o = new short[ow * oh];
            int[] sh = { 12, 8, 4, 0 };
            check(FilmCore.to4444(argb, w, h, o, ow, oh, 1500, sh) == 0, "16-bit frame made: " + FilmCore.lastError());
            int sevens = 0, eights = 0, other = 0;
            for (int y = 100; y < 140; y++) for (int x = 20; x < 100; x++) {   // the grey half: 128 lies between 7 (119) and 8 (136)
                int v = o[y * ow + x] & 0xFFFF, r = v >> 12 & 15, g = v >> 8 & 15, bl = v >> 4 & 15, al = v & 15;
                if (al != 15 || r != g || g != bl) other++; else if (r == 7) sevens++; else if (r == 8) eights++; else other++;
            }
            check(other == 0 && sevens > 0 && eights > 0, "mid-grey dithers between the two nearest levels (" + sevens + " 7s, " + eights + " 8s, " + other + " other)");
            int right = o[240 * ow + 500] & 0xFFFF;
            check((right >> 12 & 15) == 15 && (right & 15) == 15, "red lands in the red nibble, opaque");
            check(FilmCore.to4444(argb, w, h, new short[100], ow, oh, 1500, sh) != 0, "a small output rejected");
            check(FilmCore.to4444(argb, w, h, o, ow, oh, 1500, new int[] { 13, 8, 4, 0 }) != 0, "a bad channel shift rejected");
            // 3:2 into the 540 x 480 the screen's 720 x 480 design space is: nothing of the frame's width lost
            int[] edge = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) edge[y * w + x] = x < 4 ? 0xFFFFFFFF : 0xFF000000;   // a white stripe at the left edge
            check(FilmCore.to4444(edge, w, h, o, ow, oh, 1500, sh) == 0 && (o[240 * ow] >> 12 & 15) == 15, "a 3:2 crop keeps the frame's left edge");
            check(FilmCore.to4444(edge, w, h, o, ow, oh, 0, sh) == 0 && (o[240 * ow] >> 12 & 15) == 0, "a crop to the pixels' own shape loses it (v0.36.1's bug)");
        }
        FilmCore.setVariant(1);
        FilmCore.setVariant(0);
        System.out.println("JNI OK");
    }

    static byte[] read(String path) {
        try {
            java.io.File f = new java.io.File(path);
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try { int n = 0; while (n < b.length) n += in.read(b, n, b.length - n); } finally { in.close(); }
            return b;
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  pass  " : "  FAIL  ") + what);
        if (!ok) System.exit(1);
    }
}
