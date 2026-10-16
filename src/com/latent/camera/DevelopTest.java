package com.latent.camera;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Times the darkroom's develop on this camera, one idea at a time, and keeps a faster way only if it
 * gives exactly the same picture. Your newest photo is decoded and given an output image exactly as
 * the darkroom does; then it is developed into that image several times, today's way and the way
 * being tried taking turns, and after each develop every picture byte of the output is hashed. Nothing
 * is saved: no new photo, the original only read. Log: LATENT/DEVTEST.TXT, written before every step
 * that touches the camera's image memory, so a hang still leaves a record.
 *
 * This version's idea (v0.38.0): the size of each transfer to and from Sony's image memory (2 MB
 * today). Bigger blocks mean fewer calls; smaller ones a shorter wait before the first block and after
 * the last.
 */
final class DevelopTest {
    static final String[] STEPS = { "Test picture", "Memory check", "Transfers", "Today's (2 MB)", "4 MB blocks", "1 MB blocks",
            "Same picture", "Darkroom" };
    static final int S_PICTURE = 0, S_MEMORY = 1, S_TRANSFERS = 2, S_FIRST = 3, S_SAME = 6, S_RESULT = 7;
    static final int OK = Probe.OK, FAIL = Probe.FAIL, RUN = Probe.RUN, INFO = Probe.INFO;
    /** The block sizes timed, today's first; each has its step from S_FIRST on. */
    static final int[] BLOCKS = { HardwareDevelop.DARKROOM_BLOCK, 4 * 1024 * 1024, 1024 * 1024 };
    static final int ROUNDS = 3;
    /** A way is only kept when its median develop is this much faster than today's (ms, and per cent). */
    static final int MIN_GAIN_MS = 120, MIN_GAIN_PCT = 3;
    /** The darkroom's transfer size, as this test chose it (HardwareDevelop.DARKROOM_BLOCK until then). */
    static final String P_BLOCK = "developBlock";

    private final Context ctx;
    private final File card;
    private final Stock stock;
    private final SharedPreferences prefs;
    private final Probe.Listener out;
    private final int threads;
    private final String version;
    private final StringBuilder log = new StringBuilder();

    DevelopTest(Context ctx, File card, Stock stock, SharedPreferences prefs, int threads, String version, Probe.Listener out) {
        this.ctx = ctx; this.card = card; this.stock = stock; this.prefs = prefs; this.threads = threads; this.version = version; this.out = out;
    }

    /** The step under way: a failure is shown there. */
    private int current = S_PICTURE;

    private void begin(int i, String detail) {
        current = i;
        out.step(i, RUN, detail);
    }

    private void report(int i, int status, String detail) {
        log.append(STEPS[i]).append(": ").append(status == OK ? "ok" : status == FAIL ? "FAILED" : "info").append(" - ").append(detail).append("\r\n");
        out.step(i, status, detail);
    }

    /** The log so far, onto the card (appended to earlier runs'), before anything that could hang. */
    private int saved;
    private void save() {
        try {
            FileOutputStream o = new FileOutputStream(new File(Library.home(card), "DEVTEST.TXT"), true);
            try { o.write(log.substring(saved).getBytes("UTF-8")); } finally { o.close(); }
            saved = log.length();
        } catch (Exception e) {
            // the screen shows the same
        }
    }

    private void note(String line) {
        log.append(line).append("\r\n");
    }

    String run() {
        log.append("\r\n").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append(" Latent ").append(version).append(" develop test\r\n");
        // HardwareDevelop's own steps land on this test's first two
        Probe.Listener hwSteps = new Probe.Listener() {
            public void step(int i, int status, String detail) {
                if (i == HardwareDevelop.S_MEMORY) out.step(S_MEMORY, status, detail);
                else if (i == HardwareDevelop.S_PHOTO || i == HardwareDevelop.S_DECODE || i == HardwareDevelop.S_IMAGE)
                    out.step(S_PICTURE, status == OK ? RUN : status, detail);
            }
        };
        final HardwareDevelop hw = new HardwareDevelop(ctx, card, log, hwSteps);
        try {
            List<File> photos = Library.photos(card);
            if (photos.isEmpty()) { report(S_PICTURE, FAIL, "no photo on the card"); return finish(); }
            final File photo = photos.get(0);
            final int[] p = new int[FilmCore.COUNT];
            p[FilmCore.HIGHLIGHT] = stock.highlight; p[FilmCore.SHADOW] = stock.shadow;
            p[FilmCore.GRAIN] = stock.grain; p[FilmCore.GRAIN_SIZE] = stock.grainSize;
            p[FilmCore.QUALITY] = 95; p[FilmCore.SEED] = 1; p[FilmCore.THREADS] = threads;
            int variant = prefs.getInt("lookVariant", 0);
            FilmCore.setVariant(variant);   // the inner loop the darkroom uses
            note("photo " + photo.getName() + ", film " + stock.name + ", threads " + threads + ", loop " + FilmCore.VARIANTS[Math.max(0, Math.min(FilmCore.VARIANTS.length - 1, variant))]
                    + ", darkroom block now " + prefs.getInt(P_BLOCK, HardwareDevelop.DARKROOM_BLOCK) / 1024 + " KB");

            begin(S_MEMORY, "checking…");
            note("verifying transfers up to 4096 KB on scratch buffers");
            save();
            hw.prepare(true, 4 * 1024 * 1024);
            final int verified = HardwareDevelop.verified();
            note("verified up to " + verified / 1024 + " KB");

            begin(S_PICTURE, "decoding " + photo.getName() + "…");
            note("decoding " + photo.getName() + " and making the output image");
            save();
            hw.bench(photo, new HardwareDevelop.Bench() {
                public void run(int src, int[] sg, int full, int[] dg) throws Exception {
                    measure(src, sg, full, dg, verified, stock.lut.getPath(), p);
                }
            });
        } catch (Throwable e) {
            if (!(e instanceof HardwareDevelop.Stuck) && !(e instanceof HardwareDevelop.Failed)) {
                note("stopped: " + Probe.why(e));
                out.step(current, FAIL, Probe.why(e));
            }
        }
        return finish();
    }

    /** Everything timed on the two images, while HardwareDevelop holds them. */
    private void measure(int src, int[] sg, int full, int[] dg, int verified, String lut, int[] p) throws Exception {
        report(S_PICTURE, OK, sg[0] + "x" + sg[1] + ", canvas " + sg[2] + " -> " + dg[2]);
        note("geometry src " + Arrays.toString(sg) + " dst " + Arrays.toString(dg));

        // raw transfers: reads of the source, reads and unchanged write-backs on the output
        begin(S_TRANSFERS, "timing…");
        int[] sizes = { 256 * 1024, 1024 * 1024, 2 * 1024 * 1024, 4 * 1024 * 1024 };
        long[] us = new long[2];
        double[] srcCall = new double[sizes.length], dstRead = new double[sizes.length], dstWrite = new double[sizes.length];
        int last = -1;
        for (int k = 0; k < sizes.length; k++) {
            int size = sizes[k];
            if (size > verified) break;
            int count = Math.max(2, 16 * 1024 * 1024 / size);
            note("reading the source: " + count + " x " + size / 1024 + " KB");
            save();
            if (SonyMemory.speed(src, sg, size, count, false, us) != 0) throw new IllegalStateException("source reads: " + SonyMemory.lastError());
            srcCall[k] = us[0] / 1000.0 / count;
            note("  " + us[0] / 1000 + " ms, " + fmt(srcCall[k]) + " ms a call, " + mbps(size, srcCall[k]) + " MB/s");
            note("reading the output and writing it back unchanged: " + count + " x " + size / 1024 + " KB");
            save();
            if (SonyMemory.speed(full, dg, size, count, true, us) != 0) throw new IllegalStateException("output transfers: " + SonyMemory.lastError());
            dstRead[k] = us[0] / 1000.0 / count;
            dstWrite[k] = us[1] / 1000.0 / count;
            note("  read " + us[0] / 1000 + " ms (" + mbps(size, dstRead[k]) + " MB/s), write " + us[1] / 1000 + " ms (" + mbps(size, dstWrite[k]) + " MB/s)");
            last = k;
        }
        if (last < 1) throw new IllegalStateException("too few transfer sizes verified (" + verified / 1024 + " KB)");
        // a straight line through the smallest and largest: a fixed cost per call plus a cost per byte
        String fitRead = fit("source read", sizes[0], srcCall[0], sizes[last], srcCall[last]);
        String fitOut = fit("output read", sizes[0], dstRead[0], sizes[last], dstRead[last]);
        String fitWrite = fit("output write", sizes[0], dstWrite[0], sizes[last], dstWrite[last]);
        note(fitRead);
        note(fitOut);
        note(fitWrite);
        report(S_TRANSFERS, OK, "read " + mbps(sizes[last], srcCall[last]) + " MB/s, write " + mbps(sizes[last], dstWrite[last]) + " MB/s at "
                + sizes[last] / 1024 / 1024 + " MB");

        // the source, hashed before and after: the develops only ever read it
        long[] h = new long[2];
        note("hashing the source");
        save();
        if (SonyMemory.hash(src, sg, HardwareDevelop.DARKROOM_BLOCK, h) != 0) throw new IllegalStateException("hash: " + SonyMemory.lastError());
        long srcHash = h[0];
        note("  source " + Long.toHexString(srcHash) + " in " + h[1] / 1000 + " ms");
        if (SonyMemory.hash(full, dg, HardwareDevelop.DARKROOM_BLOCK, h) != 0) throw new IllegalStateException("hash: " + SonyMemory.lastError());
        long blank = h[0];
        note("  output before developing " + Long.toHexString(blank) + " in " + h[1] / 1000 + " ms");

        // the develops: every way in turn, ROUNDS times, each hashed
        long[][] ms = new long[BLOCKS.length][ROUNDS];
        long[] hash = new long[BLOCKS.length];
        boolean[] same = new boolean[BLOCKS.length];
        boolean[] usable = new boolean[BLOCKS.length];
        int[] t = new int[FilmCore.T_COUNT];
        for (int b = 0; b < BLOCKS.length; b++) {
            usable[b] = BLOCKS[b] <= verified;
            same[b] = usable[b];
            if (!usable[b]) report(S_FIRST + b, INFO, "not verified on this camera");
        }
        out.step(S_SAME, RUN, "checking each develop…");
        for (int r = 0; r < ROUNDS; r++) {
            for (int b = 0; b < BLOCKS.length; b++) {
                if (!usable[b]) continue;
                begin(S_FIRST + b, "round " + (r + 1) + " of " + ROUNDS + "…");
                note("round " + (r + 1) + ": developing with " + BLOCKS[b] / 1024 + " KB blocks");
                save();
                long t0 = System.currentTimeMillis();
                int rc;
                CpuGate.busy(true);
                try {
                    rc = SonyMemory.develop(src, sg, full, dg, BLOCKS[b], lut, p, null);
                } finally {
                    CpuGate.busy(false);
                }
                ms[b][r] = System.currentTimeMillis() - t0;
                if (rc != 0) throw new IllegalStateException(BLOCKS[b] / 1024 + " KB develop: " + SonyMemory.lastError());
                FilmCore.timing(t);
                String note = FilmCore.helperNote();
                if (SonyMemory.hash(full, dg, HardwareDevelop.DARKROOM_BLOCK, h) != 0) throw new IllegalStateException("hash: " + SonyMemory.lastError());
                note("  " + ms[b][r] + " ms: read " + t[FilmCore.T_READ] + ", look " + t[FilmCore.T_LOOK] + " (this thread waited "
                        + t[FilmCore.T_WAIT] + " for the helpers, looked " + t[FilmCore.T_ROWS] + " of " + sg[1] + " rows), write " + t[FilmCore.T_WRITE]
                        + ", cpu " + t[FilmCore.T_CPU] + " + helpers " + t[FilmCore.T_HCPU] + ", threads " + t[FilmCore.T_THREADS]
                        + (note.length() > 0 ? " (" + note + ")" : "") + ", rows " + (SonyMemory.lastDirect() ? "direct" : "gathered")
                        + "; output " + Long.toHexString(h[0]));
                if (r == 0) hash[b] = h[0];   // today's way, round 1, is the picture every other develop must match
                if (h[0] != hash[0] || h[0] == blank) same[b] = false;
                if (h[0] != hash[b]) same[b] = false;   // the same way twice must agree too
                out.step(S_FIRST + b, same[b] ? RUN : FAIL, ms[b][r] + " ms" + (same[b] ? "" : ", PICTURE DIFFERS"));
            }
        }
        current = S_SAME;
        note("hashing the source again");
        save();
        if (SonyMemory.hash(src, sg, HardwareDevelop.DARKROOM_BLOCK, h) != 0) throw new IllegalStateException("hash: " + SonyMemory.lastError());
        boolean untouched = h[0] == srcHash;
        note("  source " + Long.toHexString(h[0]) + (untouched ? " (unchanged)" : " (CHANGED)"));

        // medians, and the verdict
        long[] median = new long[BLOCKS.length];
        for (int b = 0; b < BLOCKS.length; b++) {
            if (!usable[b]) continue;
            long[] sorted = ms[b].clone();
            Arrays.sort(sorted);
            median[b] = sorted[ROUNDS / 2];
            String vs = b == 0 ? "" : ", " + signed(median[b] - median[0]) + " ms";
            report(S_FIRST + b, same[b] ? OK : FAIL, median[b] + " ms" + vs + (same[b] ? "" : ", PICTURE DIFFERS"));
            note("  " + BLOCKS[b] / 1024 + " KB: " + Arrays.toString(ms[b]) + " ms, median " + median[b]);
        }
        boolean allSame = untouched && same[0];
        for (int b = 1; b < BLOCKS.length; b++) if (usable[b] && !same[b]) allSame = false;
        report(S_SAME, allSame ? OK : FAIL, allSame ? "every develop identical, source untouched"
                : !untouched ? "THE SOURCE CHANGED" : !same[0] ? "today's way disagreed with itself" : "a way gave another picture");

        int best = 0;
        if (untouched && same[0]) {
            for (int b = 1; b < BLOCKS.length; b++) {
                if (!usable[b] || !same[b]) continue;
                long gain = median[0] - median[b];
                if (gain >= MIN_GAIN_MS && gain * 100 >= median[0] * MIN_GAIN_PCT && median[b] < median[best]) best = b;
            }
        }
        prefs.edit().putInt(P_BLOCK, BLOCKS[best]).commit();
        report(S_RESULT, OK, best == 0 ? "keeps 2 MB blocks (nothing clearly faster)"
                : BLOCKS[best] / 1024 / 1024 + " MB blocks, " + (median[0] - median[best]) + " ms faster a photo");
    }

    private static String fmt(double v) { return String.format(Locale.US, "%.1f", v); }

    private static String mbps(int size, double msPerCall) {
        return msPerCall <= 0 ? "?" : String.format(Locale.US, "%.0f", size / 1048576.0 / (msPerCall / 1000));
    }

    private static String signed(long v) { return v > 0 ? "+" + v : "" + v; }

    private static String fit(String what, int s1, double c1, int s2, double c2) {
        double perMb = (c2 - c1) / ((s2 - s1) / 1048576.0), fixed = c1 - perMb * s1 / 1048576.0;
        return what + ": " + fmt(fixed) + " ms a call + " + fmt(perMb) + " ms a MB (" + (perMb > 0 ? String.format(Locale.US, "%.0f", 1000 / perMb) : "?") + " MB/s)";
    }

    private String finish() {
        save();
        return log.toString();
    }
}
