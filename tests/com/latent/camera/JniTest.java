package com.latent.camera;

/** Host test: drives the real JNI bridge (built as a macOS library) exactly as the app does. */
public class JniTest {
    public static void main(String[] a) throws Exception {
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

        // film files read Latent's own way (v0.39.0): the same floats in every row, and the same developed file
        int[] cms = new int[4];
        check(FilmCore.cubeCheck(lut, cms) == 0 && cms[2] > 30000 && cms[3] == 0, "fast film read matches sscanf row for row (" + cms[0] + " -> " + cms[1] + " ms) " + FilmCore.lastError());
        check(FilmCore.cubeCheck(lut + ".missing", cms) < 0, "fast film read reports a missing file");
        FilmCore.forgetLut();
        FilmCore.setFastCube(true);
        rc = FilmCore.process(in, outDir + "/FLF04491.JPG", lut, null, p, null);
        FilmCore.setFastCube(false);
        FilmCore.forgetLut();
        check(rc == 0 && java.util.Arrays.equals(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(outDir, "FLA04491.JPG")),
                java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(outDir, "FLF04491.JPG"))), "developed with the fast film read: the same file, byte for byte");
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
        frameComposer(jpeg, lut, p);
        FilmCore.setVariant(1);
        FilmCore.setVariant(0);
        System.out.println("JNI OK");
    }

    /** The viewfinder's frame composer (v0.38.0). */
    static void frameComposer(final byte[] jpeg, final String lut, final int[] p) throws Exception {
        final int ow = 540, oh = 480;
        final int[] sh = { 12, 8, 4, 0 };
        long c = FilmCore.frameNew();
        check(c != 0, "a frame composer made");
        int[] info = new int[FilmCore.FI_COUNT], tm = new int[FilmCore.TM_COUNT];
        short[] a = new short[ow * oh], b = new short[ow * oh];
        FilmCore.setOverlay(null, 0, 0);
        int rc = FilmCore.frame(c, jpeg, jpeg.length, lut, p, FilmCore.F_LEVEL | FilmCore.F_HIST, 1, a, null, ow, oh, 1500, FilmCore.FMT_4444, sh, 320, 240, info, tm);
        int sum = 0;
        for (int i = 0; i < 64; i++) sum += info[5 + i];
        check(rc == 0 && info[0] == 320 && info[1] > 200 && sum == info[0] * info[1], "composed: " + info[0] + "x" + info[1] + " frame, histogram whole " + FilmCore.lastError());
        check(info[2] != FilmCore.NO_READING && info[FilmCore.FI_OVERLAY] == 0, "level read, no overlay laid in");
        boolean opaque = true;
        for (short v : a) if ((v & 15) != 15) { opaque = false; break; }
        check(opaque, "every 4444 pixel opaque");
        // as bright as the 32-bit look, cropped the same way (another dither, so on average)
        int[] live = new int[320 * 240], li = new int[FilmCore.L_COUNT];
        FilmCore.live(jpeg, jpeg.length, lut, p, live, 320, 240, li);
        short[] ref = new short[ow * oh];
        FilmCore.to4444(live, li[FilmCore.L_W], li[FilmCore.L_H], ref, ow, oh, 1500, sh);
        double ma = 0, mr = 0;
        for (int i = 0; i < ow * oh; i++) { ma += (a[i] >> 12 & 15) + (a[i] >> 8 & 15) + (a[i] >> 4 & 15); mr += (ref[i] >> 12 & 15) + (ref[i] >> 8 & 15) + (ref[i] >> 4 & 15); }
        check(Math.abs(ma - mr) / (ow * oh * 3) < 0.25, "as bright as the 32-bit look: " + String.format("%.2f vs %.2f", ma / (ow * oh * 3), mr / (ow * oh * 3)));
        rc = FilmCore.frame(c, jpeg, jpeg.length, lut, p, 0, 1, b, null, ow, oh, 1500, FilmCore.FMT_4444, sh, 320, 240, info, tm);
        check(rc == 0 && info[2] == FilmCore.NO_READING && java.util.Arrays.equals(a, b), "no level asked: none read, the same picture");
        FilmCore.frame(c, null, 0, lut, p, 0, 1, b, null, ow, oh, 1500, FilmCore.FMT_4444, sh, 320, 240, info, tm);
        check(java.util.Arrays.equals(a, b), "the last frame again (no JPEG): the same picture");
        FilmCore.frame(c, jpeg, jpeg.length, lut, p, FilmCore.F_TEMPORAL, 2, a, null, ow, oh, 1500, FilmCore.FMT_4444, sh, 320, 240, info, tm);
        FilmCore.frame(c, jpeg, jpeg.length, lut, p, FilmCore.F_TEMPORAL, 3, b, null, ow, oh, 1500, FilmCore.FMT_4444, sh, 320, 240, info, tm);
        check(!java.util.Arrays.equals(a, b), "moving grain: the next frame's dither differs");
        FilmCore.frame(c, jpeg, jpeg.length, lut, p, FilmCore.F_TEMPORAL, 2, b, null, ow, oh, 1500, FilmCore.FMT_4444, sh, 320, 240, info, tm);
        check(java.util.Arrays.equals(a, b), "the same frame number: the same grain");

        // 565: the 4-bit values carried exactly (Skia widens to 8 bits, the 4444 screen keeps the top nibble)
        boolean exact = true;
        for (int n = 0; n < 16; n++) {
            int r5 = (n << 1) | (n >> 3), g6 = (n << 2) | (n >> 2);
            int r8 = (r5 << 3) | (r5 >> 2), g8 = (g6 << 2) | (g6 >> 4);
            if (r8 >> 4 != n || g8 >> 4 != n || Math.round(r8 / 17f) != n || Math.round(g8 / 17f) != n) exact = false;
        }
        check(exact, "RGB565 holds every 4-bit level exactly, truncated or rounded");
        short[] s5 = new short[ow * oh];
        FilmCore.frame(c, jpeg, jpeg.length, lut, p, FilmCore.F_TEMPORAL, 2, s5, null, ow, oh, 1500, FilmCore.FMT_565, null, 320, 240, info, tm);
        boolean same = true;
        for (int i = 0; i < ow * oh; i++) {
            int v = s5[i] & 0xFFFF, r = (v >> 11) >> 1, g = ((v >> 5) & 63) >> 2, bl = (v & 31) >> 1;
            int back = (((r << 1) | (r >> 3)) << 11) | (((g << 2) | (g >> 2)) << 5) | ((bl << 1) | (bl >> 3));
            if (back != v || r != (a[i] >> 12 & 15) || g != (a[i] >> 8 & 15) || bl != (a[i] >> 4 & 15)) { same = false; break; }
        }
        check(same, "565 and 4444 carry the same picture");

        // the overlay: opaque white in a box, half-black over a band, nothing elsewhere
        int[] ov = new int[ow * oh];
        for (int y = 100; y < 140; y++) for (int x = 200; x < 300; x++) ov[y * ow + x] = 0xFFFFFFFF;
        for (int y = 0; y < 20; y++) for (int x = 0; x < ow; x++) ov[y * ow + x] = 0x80000000;
        int gen = FilmCore.setOverlay(ov, ow, oh);
        check(gen > 0, "overlay set (generation " + gen + ")");
        FilmCore.frame(c, jpeg, jpeg.length, lut, p, FilmCore.F_TEMPORAL, 2, b, null, ow, oh, 1500, FilmCore.FMT_4444, sh, 320, 240, info, tm);
        check(info[FilmCore.FI_OVERLAY] == gen, "the overlay laid in");
        boolean white = true, untouched = true, darker = true;
        for (int y = 100; y < 140; y++) for (int x = 200; x < 300; x++) if ((b[y * ow + x] & 0xFFFF) != 0xFFFF) white = false;
        for (int y = 20; y < oh; y++) for (int x = 0; x < ow; x++) if (!(y >= 100 && y < 140 && x >= 200 && x < 300) && a[y * ow + x] != b[y * ow + x]) untouched = false;
        long da = 0, db = 0;
        for (int i = 0; i < 20 * ow; i++) { da += a[i] >> 12 & 15; db += b[i] >> 12 & 15; }
        darker = db < da * 0.65 && db > da * 0.3;
        check(white, "opaque overlay: exactly its colour");
        check(untouched, "no overlay: the picture exactly");
        check(darker, "half-black overlay: about half as bright (" + db + " of " + da + ")");
        check(FilmCore.setOverlay(new int[10], 2, 5) > gen && FilmCore.frame(c, jpeg, jpeg.length, lut, p, 0, 2, b, null, ow, oh, 1500, FilmCore.FMT_4444, sh, 320, 240, info, tm) == 0
                && info[FilmCore.FI_OVERLAY] == 0, "an overlay of another size isn't laid in");
        check(FilmCore.frame(c, jpeg, jpeg.length, lut, p, 0, 2, null, new Object(), ow, oh, 1500, FilmCore.FMT_565, null, 320, 240, info, tm) == -2, "no Android bitmaps here: says so");
        check(FilmCore.frame(c, jpeg, jpeg.length, lut, p, 0, 2, new short[10], null, ow, oh, 1500, FilmCore.FMT_565, null, 320, 240, info, tm) != 0, "a short buffer refused");
        byte[] junk = new byte[1000];
        check(FilmCore.frame(c, junk, junk.length, lut, p, 0, 2, b, null, ow, oh, 1500, FilmCore.FMT_565, null, 320, 240, info, tm) != 0, "not a JPEG refused: " + FilmCore.lastError());

        // two look threads at once, each its own composer, the overlay swapped meanwhile: the same pictures
        FilmCore.setOverlay(ov, ow, oh);
        final short[] want = new short[ow * oh];
        FilmCore.frame(c, jpeg, jpeg.length, lut, p, FilmCore.F_TEMPORAL | FilmCore.F_LEVEL, 7, want, null, ow, oh, 1500, FilmCore.FMT_565, null, 320, 240, info, tm);
        final boolean[] ok = { true, true };
        Thread[] th = new Thread[2];
        for (int k = 0; k < 2; k++) {
            final int kk = k;
            th[k] = new Thread() {
                public void run() {
                    long cc = FilmCore.frameNew();
                    short[] o = new short[ow * oh];
                    int[] in = new int[FilmCore.FI_COUNT];
                    for (int n = 0; n < 12; n++) {
                        if (FilmCore.frame(cc, jpeg, jpeg.length, lut, p, FilmCore.F_TEMPORAL | FilmCore.F_LEVEL, 7, o, null, ow, oh, 1500, FilmCore.FMT_565, null, 320, 240, in, null) != 0) ok[kk] = false;
                        else if (!java.util.Arrays.equals(o, want) && in[FilmCore.FI_OVERLAY] > 0) {
                            // the overlay may have been swapped for an identical one: the picture must still match
                            ok[kk] = false;
                        }
                    }
                    FilmCore.frameFree(cc);
                }
            };
            th[k].start();
        }
        for (int n = 0; n < 20; n++) FilmCore.setOverlay(ov, ow, oh);
        for (Thread t : th) t.join();
        check(ok[0] && ok[1], "two composers on two threads, overlays swapped meanwhile: every picture right");
        System.out.println("  composer times (Mac): decode " + tm[0] + ", read " + tm[1] + ", look " + tm[2] + ", compose " + tm[3] + " ms");
        FilmCore.setOverlay(null, 0, 0);
        FilmCore.frameFree(c);
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
