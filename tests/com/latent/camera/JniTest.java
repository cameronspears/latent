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
        // without the level the frame is decoded as Y'CbCr and looked up in the film's Y'CbCr table (v0.45.0)
        rc = FilmCore.frame(c, jpeg, jpeg.length, lut, p, 0, 1, b, null, ow, oh, 1500, FilmCore.FMT_4444, sh, 320, 240, info, tm);
        int changed = 0;
        for (int i = 0; i < ow * oh; i++) { int[] la = levels(a[i], FilmCore.FMT_4444), lb = levels(b[i], FilmCore.FMT_4444); for (int k = 0; k < 3; k++) if (la[k] != lb[k]) changed++; }
        check(rc == 0 && info[2] == FilmCore.NO_READING && changed < ow * oh * 3 * 0.06, "no level asked: none read, decoded as Y'CbCr, "
                + String.format("%.1f%%", changed * 100.0 / (ow * oh * 3)) + " of 4-bit values changed (RGB's within 6%)");
        System.arraycopy(b, 0, a, 0, a.length);
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
        if (!untouched) for (int y = 20; y < oh; y++) { boolean done = false; for (int x = 0; x < ow; x++) if (!(y >= 100 && y < 140 && x >= 200 && x < 300) && a[y * ow + x] != b[y * ow + x]) { System.out.println("  first difference at " + x + "," + y + ": " + Integer.toHexString(a[y * ow + x] & 0xFFFF) + " vs " + Integer.toHexString(b[y * ow + x] & 0xFFFF)); done = true; break; } if (done) break; }
        check(untouched, "no overlay: the picture exactly");
        check(darker, "half-black overlay: about half as bright (" + db + " of " + da + ")");
        {   // a fade row (one colour across: laid in by arithmetic) with a clear gap and opaque type in it
            int[] fv = new int[ow * oh];
            for (int x = 0; x < ow; x++) fv[300 * ow + x] = 0x60000000;
            for (int x = 100; x < 110; x++) fv[300 * ow + x] = 0;
            for (int x = 200; x < 210; x++) fv[300 * ow + x] = 0xFFFFFFFF;
            FilmCore.setOverlay(fv, ow, oh);
            short[] f = new short[ow * oh];
            FilmCore.frame(c, jpeg, jpeg.length, lut, p, FilmCore.F_TEMPORAL, 2, f, null, ow, oh, 1500, FilmCore.FMT_4444, sh, 320, 240, info, tm);
            boolean gap = true, type = true, faded = true, rest = true;
            for (int x = 100; x < 110; x++) if (f[300 * ow + x] != a[300 * ow + x]) gap = false;
            for (int x = 200; x < 210; x++) if ((f[300 * ow + x] & 0xFFFF) != 0xFFFF) type = false;
            long fa = 0, ff = 0;
            for (int x = 300; x < ow; x++) { fa += a[300 * ow + x] >> 12 & 15; ff += f[300 * ow + x] >> 12 & 15; }
            faded = ff < fa * 0.8;
            for (int y = 0; y < oh; y++) if (y != 300) for (int x = 0; x < ow; x++) if (f[y * ow + x] != a[y * ow + x]) rest = false;
            check(gap && type && faded && rest, "a fade row: a clear gap shows the picture exactly, type exactly its colour, the rest faded ("
                    + gap + type + faded + rest + ")");
        }
        {   // type in colours off the 4-bit grid (v0.44's ink, an amber) is solid: one 4-bit colour across, on a fade and off it
            int[] tv = new int[ow * oh];
            for (int y = 0; y < 64; y++) for (int x = 0; x < ow; x++) tv[y * ow + x] = 0x99000000;
            for (int y = 20; y < 44; y++) for (int x = 40; x < 140; x++) tv[y * ow + x] = 0xFFF4F1EA;
            for (int y = 200; y < 230; y++) for (int x = 300; x < 400; x++) tv[y * ow + x] = 0xFFFFAA33;
            FilmCore.setOverlay(tv, ow, oh);
            short[] t1 = new short[ow * oh];
            FilmCore.frame(c, jpeg, jpeg.length, lut, p, FilmCore.F_TEMPORAL, 5, t1, null, ow, oh, 1500, FilmCore.FMT_4444, sh, 320, 240, info, tm);
            boolean inkSolid = true, amberSolid = true;
            for (int y = 20; y < 44; y++) for (int x = 40; x < 140; x++) if (t1[y * ow + x] != t1[20 * ow + 40]) inkSolid = false;
            for (int y = 200; y < 230; y++) for (int x = 300; x < 400; x++) if (t1[y * ow + x] != t1[200 * ow + 300]) amberSolid = false;
            int ink = t1[20 * ow + 40] & 0xFFFF, amber = t1[200 * ow + 300] & 0xFFFF;
            check(inkSolid && amberSolid && ink == 0xEEEF && amber == 0xFA3F, "opaque type off the 4-bit grid: solid, the nearest 4-bit colour (ink "
                    + Integer.toHexString(ink) + ", amber " + Integer.toHexString(amber) + ")");
        }
        {   // against v0.44.1's composer (built into the host library, tools/frame_ref.h): the same frame, the same dither
            byte[] small = cameraSize(jpeg);
            int[][] ovls = { null, ov, fadeRow(ow, oh) };
            String[] what = { "no overlay", "type and a half-black band", "a fade row with a gap and type" };
            for (int k = 0; k < ovls.length; k++) {
                FilmCore.setOverlay(ovls[k], ow, oh);
                for (byte[] j : new byte[][] { jpeg, small }) for (int fmt = 0; fmt < 2; fmt++) {
                    short[] now = new short[ow * oh], was = new short[ow * oh];
                    int[] s1 = fmt == FilmCore.FMT_4444 ? sh : null;
                    int r1 = FilmCore.frame(c, j, j.length, lut, p, FilmCore.F_TEMPORAL | FilmCore.F_LEVEL, 2, now, null, ow, oh, 1500, fmt, s1, 320, 240, info, tm);
                    int r2 = FilmCore.frame(c, j, j.length, lut, p, FilmCore.F_TEMPORAL | REFERENCE, 2, was, null, ow, oh, 1500, fmt, s1, 320, 240, info, tm);
                    double[] v = versus(now, was, fmt);
                    check(r1 == 0 && r2 == 0 && v[0] >= 0.90 && v[1] <= 1 && Math.abs(v[2]) <= 0.02,
                            String.format("as v0.44.1 composed it (%s, %s, %s): %.1f%% identical, at most %d level apart, mean %+.4f levels %s",
                                    what[k], j == small ? "320 px frame" : "preview", fmt == FilmCore.FMT_4444 ? "4444" : "565", v[0] * 100, (int) v[1], v[2], FilmCore.lastError()));
                }
            }
            FilmCore.setOverlay(ov, ow, oh);
        }
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
        String pr = FilmCore.probe(null);
        check(pr.contains("multiply-adds") && pr.contains("MB/s"), "the speed probe: " + pr);
        System.out.println("  composer times (Mac): decode " + tm[0] + ", read " + tm[1] + ", look " + tm[2] + ", compose " + tm[3] + " ms");
        FilmCore.setOverlay(null, 0, 0);
        FilmCore.frameFree(c);
    }

    /** FL_F_REFERENCE: v0.44.1's composer instead (host builds only, tools/frame_ref.h). */
    static final int REFERENCE = 1 << 30;

    /** now against was, 4-bit levels: the share of pixels identical, the largest channel difference, the mean difference. */
    static double[] versus(short[] now, short[] was, int fmt) {
        int same = 0, max = 0;
        long sum = 0;
        for (int i = 0; i < now.length; i++) {
            int[] a = levels(now[i], fmt), b = levels(was[i], fmt);
            boolean eq = true;
            for (int k = 0; k < 3; k++) { int d = a[k] - b[k]; sum += d; if (d != 0) eq = false; max = Math.max(max, Math.abs(d)); }
            if (eq) same++;
        }
        return new double[] { (double) same / now.length, max, (double) sum / (3.0 * now.length) };
    }

    static int[] levels(short v, int fmt) {
        int u = v & 0xFFFF;
        return fmt == FilmCore.FMT_4444 ? new int[] { u >> 12 & 15, u >> 8 & 15, u >> 4 & 15 } : new int[] { u >> 12, u >> 7 & 15, u >> 1 & 15 };
    }

    /** Fades as the viewfinder's are (one colour across a row), a row with a clear gap and opaque type, and ink type. */
    static int[] fadeRow(int ow, int oh) {
        int[] fv = new int[ow * oh];
        for (int y = 0; y < 64; y++) for (int x = 0; x < ow; x++) fv[y * ow + x] = (0xB0 - y * 2) << 24 | 0x0C0C08;
        for (int x = 0; x < ow; x++) fv[300 * ow + x] = 0x60000000;
        for (int x = 100; x < 110; x++) fv[300 * ow + x] = 0;
        for (int x = 200; x < 210; x++) fv[300 * ow + x] = 0xFFFFFFFF;
        for (int y = 30; y < 50; y++) for (int x = 380; x < 420; x++) fv[y * ow + x] = 0xFFF4F1EA;
        for (int y = 30; y < 50; y++) fv[y * ow + 379] = 0x80F4F1EA;
        return fv;
    }

    /** The frame re-encoded as the camera sends them: 320 px wide, small and fairly compressed. */
    static byte[] cameraSize(byte[] jpeg) throws java.io.IOException {
        java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
        java.awt.image.BufferedImage dst = new java.awt.image.BufferedImage(320, 216, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = dst.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, 320, 216, null);
        g.dispose();
        javax.imageio.ImageWriter w = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next();
        javax.imageio.ImageWriteParam wp = w.getDefaultWriteParam();
        wp.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        wp.setCompressionQuality(0.6f);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        javax.imageio.stream.ImageOutputStream io = javax.imageio.ImageIO.createImageOutputStream(bo);
        w.setOutput(io);
        w.write(null, new javax.imageio.IIOImage(dst, null, null), wp);
        io.close();
        w.dispose();
        return bo.toByteArray();
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
