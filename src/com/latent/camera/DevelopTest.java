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
 * v0.38.0's idea, transfer size, gave nothing (4 MB 3% faster, 1 MB slower; v0.38.1 on the camera). It
 * showed instead that a develop which first has to read its film file took ~950 ms longer, outside the
 * block loop, which is what the darkroom pays whenever a frame's film differs from the last one's.
 * This version's idea (v0.39.0): read the film file (.cube) with Latent's own exact decimal reader
 * instead of sscanf. Every film file on the card is read both ways first and must give the same floats
 * in every row; then each develop starts cold (the film read from the card), as in the darkroom.
 */
final class DevelopTest {
    static final String[] STEPS = { "Test picture", "Memory check", "Film files", "Today's (sscanf)", "Fast film read",
            "Same picture", "Darkroom" };
    static final int S_PICTURE = 0, S_MEMORY = 1, S_FILES = 2, S_FIRST = 3, S_SAME = 5, S_RESULT = 6;
    static final int OK = Probe.OK, FAIL = Probe.FAIL, RUN = Probe.RUN, INFO = Probe.INFO;
    /** The ways timed, today's first; each has its step from S_FIRST on. */
    static final boolean[] FAST = { false, true };
    static final int ROUNDS = 3;
    /** A way is only kept when its median develop is this much faster than today's (ms, and per cent). */
    static final int MIN_GAIN_MS = 120, MIN_GAIN_PCT = 3;
    /** The darkroom's transfer size, as v0.38's test chose it (HardwareDevelop.DARKROOM_BLOCK until then). */
    static final String P_BLOCK = "developBlock";
    /** Film files are read with the fast reader (FilmCore.setFastCube), once this test has passed it. */
    static final String P_FAST_CUBE = "fastCube";

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
            final int block = prefs.getInt(P_BLOCK, HardwareDevelop.DARKROOM_BLOCK);
            note("photo " + photo.getName() + ", film " + stock.name + ", threads " + threads + ", loop " + FilmCore.VARIANTS[Math.max(0, Math.min(FilmCore.VARIANTS.length - 1, variant))]
                    + ", block " + block / 1024 + " KB, film files read now with " + (prefs.getBoolean(P_FAST_CUBE, false) ? "the fast reader" : "sscanf"));

            // every film file on the card, read both ways: the same floats in every row, or the idea stops here
            begin(S_FILES, "reading…");
            File[] cubes = stock.lut.getParentFile().listFiles();
            int files = 0, slow = 0, fast = 0, left = 0;
            boolean filesSame = true;
            int[] ms = new int[4];
            if (cubes != null) for (File f : cubes) {
                if (!f.getName().toUpperCase(Locale.US).endsWith(".CUB")) continue;
                note("reading " + f.getName() + " both ways");
                save();
                int rc = FilmCore.cubeCheck(f.getPath(), ms);
                note("  " + (rc == 0 ? "identical" : rc > 0 ? "DIFFERENT: " + FilmCore.lastError() : "failed: " + FilmCore.lastError())
                        + "; sscanf " + ms[0] + " ms, fast " + ms[1] + " ms, " + ms[2] + " rows fast, " + ms[3] + " left to sscanf");
                if (rc != 0) filesSame = false;
                files++; slow += ms[0]; fast += ms[1]; left += ms[3];
            }
            if (files == 0) { report(S_FILES, FAIL, "no film files found"); return finish(); }
            report(S_FILES, filesSame ? OK : FAIL, files + (filesSame ? " identical" : ", SOME DIFFER") + ", " + slow / files + " -> " + fast / files + " ms each"
                    + (left > 0 ? ", " + left + " rows by sscanf" : ""));

            begin(S_MEMORY, "checking…");
            save();
            hw.prepare(true, block);

            begin(S_PICTURE, "decoding " + photo.getName() + "…");
            note("decoding " + photo.getName() + " and making the output image");
            save();
            final boolean filesOk = filesSame;
            hw.bench(photo, new HardwareDevelop.Bench() {
                public void run(int src, int[] sg, int full, int[] dg) throws Exception {
                    measure(src, sg, full, dg, block, stock.lut.getPath(), p, filesOk);
                }
            });
        } catch (Throwable e) {
            if (!(e instanceof HardwareDevelop.Stuck) && !(e instanceof HardwareDevelop.Failed)) {
                note("stopped: " + Probe.why(e));
                out.step(current, FAIL, Probe.why(e));
            }
        } finally {
            FilmCore.setFastCube(prefs.getBoolean(P_FAST_CUBE, false));   // as the darkroom will read film files from now on
        }
        return finish();
    }

    /** Everything timed on the two images, while HardwareDevelop holds them. */
    private void measure(int src, int[] sg, int full, int[] dg, int block, String lut, int[] p, boolean filesSame) throws Exception {
        report(S_PICTURE, OK, sg[0] + "x" + sg[1] + ", canvas " + sg[2] + " -> " + dg[2]);
        note("geometry src " + Arrays.toString(sg) + " dst " + Arrays.toString(dg));

        // the source, hashed before and after: the develops only ever read it
        long[] h = new long[2];
        note("hashing the source");
        save();
        if (SonyMemory.hash(src, sg, block, h) != 0) throw new IllegalStateException("hash: " + SonyMemory.lastError());
        long srcHash = h[0];
        note("  source " + Long.toHexString(srcHash) + " in " + h[1] / 1000 + " ms");
        if (SonyMemory.hash(full, dg, block, h) != 0) throw new IllegalStateException("hash: " + SonyMemory.lastError());
        long blank = h[0];
        note("  output before developing " + Long.toHexString(blank) + " in " + h[1] / 1000 + " ms");

        // the develops: each way in turn, ROUNDS times, each starting cold (the film read from the card), each hashed
        int ways = FAST.length;
        long[][] ms = new long[ways][ROUNDS];
        int[][] setup = new int[ways][ROUNDS];
        long[] hash = new long[ways];
        boolean[] same = new boolean[ways];
        int[] t = new int[FilmCore.T_COUNT];
        Arrays.fill(same, true);
        if (!filesSame) { same[1] = false; report(S_FIRST + 1, FAIL, "not tried: a film file read differently"); }
        out.step(S_SAME, RUN, "checking each develop…");
        for (int r = 0; r < ROUNDS; r++) {
            for (int b = 0; b < ways; b++) {
                if (FAST[b] && !filesSame) continue;
                begin(S_FIRST + b, "round " + (r + 1) + " of " + ROUNDS + "…");
                note("round " + (r + 1) + ": developing, film read " + (FAST[b] ? "with the fast reader" : "with sscanf"));
                save();
                FilmCore.forgetLut();
                FilmCore.setFastCube(FAST[b]);
                long t0 = System.currentTimeMillis();
                int rc;
                CpuGate.busy(true);
                try {
                    rc = SonyMemory.develop(src, sg, full, dg, block, lut, p, null);
                } finally {
                    CpuGate.busy(false);
                }
                ms[b][r] = System.currentTimeMillis() - t0;
                if (rc != 0) throw new IllegalStateException("develop: " + SonyMemory.lastError());
                FilmCore.timing(t);
                setup[b][r] = t[FilmCore.T_SETUP];
                String note = FilmCore.helperNote();
                if (SonyMemory.hash(full, dg, block, h) != 0) throw new IllegalStateException("hash: " + SonyMemory.lastError());
                note("  " + ms[b][r] + " ms: setup " + t[FilmCore.T_SETUP] + ", read " + t[FilmCore.T_READ] + ", look " + t[FilmCore.T_LOOK]
                        + " (this thread waited " + t[FilmCore.T_WAIT] + " for the helpers, looked " + t[FilmCore.T_ROWS] + " of " + sg[1] + " rows), write "
                        + t[FilmCore.T_WRITE] + ", cpu " + t[FilmCore.T_CPU] + " + helpers " + t[FilmCore.T_HCPU] + ", threads " + t[FilmCore.T_THREADS]
                        + (note.length() > 0 ? " (" + note + ")" : "") + "; output " + Long.toHexString(h[0]));
                if (r == 0) hash[b] = h[0];   // today's way, round 1, is the picture every other develop must match
                if (h[0] != hash[0] || h[0] == blank || h[0] != hash[b]) same[b] = false;
                out.step(S_FIRST + b, same[b] ? RUN : FAIL, ms[b][r] + " ms, setup " + setup[b][r] + (same[b] ? "" : ", PICTURE DIFFERS"));
            }
        }
        current = S_SAME;
        note("hashing the source again");
        save();
        if (SonyMemory.hash(src, sg, block, h) != 0) throw new IllegalStateException("hash: " + SonyMemory.lastError());
        boolean untouched = h[0] == srcHash;
        note("  source " + Long.toHexString(h[0]) + (untouched ? " (unchanged)" : " (CHANGED)"));

        // medians, and the verdict
        long[] median = new long[ways];
        for (int b = 0; b < ways; b++) {
            if (FAST[b] && !filesSame) continue;
            long[] sorted = ms[b].clone();
            Arrays.sort(sorted);
            median[b] = sorted[ROUNDS / 2];
            int[] su = setup[b].clone();
            Arrays.sort(su);
            String vs = b == 0 ? "" : ", " + signed(median[b] - median[0]) + " ms";
            report(S_FIRST + b, same[b] ? OK : FAIL, median[b] + " ms, setup " + su[ROUNDS / 2] + vs + (same[b] ? "" : ", PICTURE DIFFERS"));
            note("  " + (FAST[b] ? "fast reader" : "sscanf") + ": " + Arrays.toString(ms[b]) + " ms, median " + median[b] + "; setup " + Arrays.toString(setup[b]));
        }
        boolean allSame = untouched && same[0] && same[1];
        report(S_SAME, allSame ? OK : FAIL, allSame ? "every develop identical, source untouched"
                : !untouched ? "THE SOURCE CHANGED" : !same[0] ? "today's way disagreed with itself"
                : !filesSame ? "a film file read differently; fast read not tried" : "the fast read gave another picture");

        long gain = median[0] - median[1];
        boolean keep = allSame && filesSame && gain >= MIN_GAIN_MS && gain * 100 >= median[0] * MIN_GAIN_PCT;
        prefs.edit().putBoolean(P_FAST_CUBE, keep).commit();
        report(S_RESULT, OK, keep ? "reads film files the fast way, " + gain + " ms faster a new film" : "keeps sscanf (nothing clearly faster)");
    }

    private static String signed(long v) { return v > 0 ? "+" + v : "" + v; }

    private String finish() {
        save();
        return log.toString();
    }
}
