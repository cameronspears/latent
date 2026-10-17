package com.latent.camera;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The darkroom's speed, tried on this camera one idea at a time (docs/DEVELOP-SPEED.md). Log:
 * LATENT/DEVTEST.TXT, written before every step that asks something of the camera's hardware, so a hang
 * still leaves a record.
 *
 * Ideas so far: v0.38 transfer size (no gain, 2 MB kept: P_BLOCK); v0.39 film files read without sscanf
 * (always on since v0.42.0); v0.41 a helper on core 0 (dropped untried: the camera's core); v0.43 a lookup
 * memory per look thread (2863 -> 2225 ms, identical: 1K kept, MEMO_DEFAULT / P_MEMO).
 *
 * This version's question (v0.44.0): can the camera save one photo while it decodes the next? Sony's
 * save is now the largest piece of a frame (~1.75 s of Latent waiting for the encoder). If the next
 * frame's decode and output image (~1 s) could run meanwhile, each frame would be ~1 s shorter. Nothing
 * says Sony's decoder, scaler and encoder may run at once, or that its memory holds a third full image,
 * so the check goes step by step and stops at the first refusal:
 *  1. one photo decoded, given an output image and developed, as the darkroom does;
 *  2. with that output still held, the next photo decoded and given its own: is there room for both?
 *     (one call after another, nothing at the same time);
 *  3. the first saved on its own, timed;
 *  4. the first developed again, and saved while the next is decoded on this thread: both must work;
 *  5. the two saved photos compared byte for byte.
 * It saves two photos (kept off every roll; delete them in playback if you like) and changes nothing in
 * the darkroom: the answer decides what to build next.
 */
final class DevelopTest {
    static final String[] STEPS = { "Photos", "Memory check", "First photo ready", "Room for two", "Save alone", "Save while decoding",
            "Same photo saved", "Answer" };
    static final int S_PHOTOS = 0, S_MEMORY = 1, S_READY = 2, S_ROOM = 3, S_ALONE = 4, S_OVERLAP = 5, S_SAME = 6, S_ANSWER = 7;
    static final int OK = Probe.OK, FAIL = Probe.FAIL, RUN = Probe.RUN, INFO = Probe.INFO;
    /** The darkroom's transfer size, as v0.38's test chose it (HardwareDevelop.DARKROOM_BLOCK until then). */
    static final String P_BLOCK = "developBlock";
    /** Lookup memory per look thread, 2^bits entries (0 = none), as v0.43's test chose. */
    static final String P_MEMO = "pairMemo";
    /**
     * Until a run of the test says otherwise: 1K entries, as v0.43.0's run chose (2863 -> 2225 ms, every
     * develop identical). A default in code, because a camera crash can lose a pref (v0.41.0 lost fastCube).
     */
    static final int MEMO_DEFAULT = 10;
    /** Longest the save and the decode beside it may take together before the check calls it stuck. */
    static final long OVERLAP_LIMIT_MS = 120000;

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

    /** The step under way: a failure, and HardwareDevelop's own progress, are shown there. */
    private volatile int current = S_PHOTOS;

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

    /** Stops the check at a step that didn't work (already reported). */
    private static final class Stop extends Exception {
        Stop() { super("stopped"); }
    }

    String run() {
        log.append("\r\n").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append(" Latent ").append(version).append(" develop test: can the camera save while it decodes?\r\n");
        // HardwareDevelop's own steps show under whichever of this check's steps is under way
        Probe.Listener hwSteps = new Probe.Listener() {
            public void step(int i, int status, String detail) {
                if (i == HardwareDevelop.S_MEMORY) out.step(S_MEMORY, status, detail);
                else if (status == FAIL) out.step(current, FAIL, detail);
                else out.step(current, RUN, detail);
            }
        };
        final HardwareDevelop hw = new HardwareDevelop(ctx, card, log, hwSteps);
        HardwareDevelop.Held a = new HardwareDevelop.Held(), again = new HardwareDevelop.Held(), b = new HardwareDevelop.Held();
        boolean stuck = false, locked = false;
        try {
            List<File> photos = Library.photos(card);
            if (photos.size() < 2) { report(S_PHOTOS, FAIL, "needs two photos on the card"); return finish(); }
            File first = photos.get(0), next = photos.get(1);
            int[] p = new int[FilmCore.COUNT];
            p[FilmCore.HIGHLIGHT] = stock.highlight; p[FilmCore.SHADOW] = stock.shadow;
            p[FilmCore.GRAIN] = stock.grain; p[FilmCore.GRAIN_SIZE] = stock.grainSize;
            p[FilmCore.QUALITY] = 95; p[FilmCore.SEED] = 1; p[FilmCore.THREADS] = threads;
            FilmCore.setVariant(prefs.getInt("lookVariant", 0));   // exactly as the darkroom develops
            FilmCore.setFastCube(true);
            FilmCore.setPairMemo(prefs.getInt(P_MEMO, MEMO_DEFAULT));
            int block = prefs.getInt(P_BLOCK, HardwareDevelop.DARKROOM_BLOCK);
            String lut = stock.lut.getPath();
            note("first " + first.getName() + ", next " + next.getName() + ", film " + stock.name + ", " + threads + " threads, block "
                    + block / 1024 + " KB, lookup memory " + prefs.getInt(P_MEMO, MEMO_DEFAULT) + " bits");
            report(S_PHOTOS, OK, first.getName() + ", then " + next.getName());

            begin(S_MEMORY, "checking…");
            save();
            hw.prepare(true, block);

            HardwareDevelop.SONY.lock();   // the darkroom's filing stays out of the camera's way meanwhile
            locked = true;

            // 1. the first photo, ready to save, exactly as in the darkroom
            begin(S_READY, "decoding " + first.getName() + "…");
            note("decoding " + first.getName() + ", making its output image, developing it");
            save();
            int[] sga = new int[8], dga = new int[8];
            hw.open(first, a, sga, dga);
            long decodeA = hw.ms[HardwareDevelop.T_DECODE], imageA = hw.ms[HardwareDevelop.T_IMAGE];
            long developA = develop(a, sga, dga, block, lut, p);
            report(S_READY, OK, "decode " + decodeA + ", image " + imageA + ", develop " + developA + " ms");

            // 2. room for two: the next photo decoded and given its output while the first's output is held
            begin(S_ROOM, "decoding " + next.getName() + "…");
            note("decoding " + next.getName() + " and making its output image while " + first.getName() + "'s output is held (one call at a time)");
            save();
            int[] sgb = new int[8], dgb = new int[8];
            try {
                hw.open(next, b, sgb, dgb);
            } catch (HardwareDevelop.Failed e) {
                report(S_ROOM, FAIL, "no: " + e.getMessage());
                report(S_ANSWER, INFO, "the camera can't hold a second photo beside a finished one: no overlap");
                throw new Stop();
            }
            long decodeB = hw.ms[HardwareDevelop.T_DECODE], imageB = hw.ms[HardwareDevelop.T_IMAGE];
            report(S_ROOM, OK, "yes: decode " + decodeB + ", image " + imageB + " ms");
            note("releasing " + next.getName() + "'s images");
            save();
            b.release();

            // 3. the first saved on its own: the baseline
            begin(S_ALONE, "saving…");
            note("saving " + first.getName() + "'s develop on its own");
            save();
            Set<String> before = hw.photoPaths();
            long t = System.currentTimeMillis();
            int code = hw.save(a.full);
            long saveAlone = System.currentTimeMillis() - t;
            if (code != 0) { report(S_ALONE, FAIL, "Sony's encoder answered " + code); throw new Stop(); }
            File print1 = hw.newPhoto(before);
            if (print1 != null) Library.ignore(card, print1);
            note("  saved in " + saveAlone + " ms as " + (print1 == null ? "(not found)" : print1.getName()));
            a.release();
            report(S_ALONE, print1 != null ? OK : FAIL, saveAlone + " ms" + (print1 != null ? ", " + print1.getName() : ", but no new photo appeared"));
            if (print1 == null) throw new Stop();

            // 4. the same develop again, saved while the next photo is decoded beside it
            begin(S_OVERLAP, "developing " + first.getName() + " again…");
            note("decoding and developing " + first.getName() + " again");
            save();
            int[] sg2 = new int[8], dg2 = new int[8];
            hw.open(first, again, sg2, dg2);
            develop(again, sg2, dg2, block, lut, p);
            begin(S_OVERLAP, "saving while decoding…");
            note("saving it while " + next.getName() + " is decoded and given its output image at the same time");
            save();
            before = hw.photoPaths();
            final Object image = again.full;
            final int[] saveCode = { Integer.MIN_VALUE };
            final long[] saveMs = { 0 };
            final Throwable[] saveError = { null };
            Thread saver = new Thread(new Runnable() {
                public void run() {
                    long t0 = System.currentTimeMillis();
                    try { saveCode[0] = hw.save(image); } catch (Throwable e) { saveError[0] = e; }
                    saveMs[0] = System.currentTimeMillis() - t0;
                }
            }, "save-check");
            long w0 = System.currentTimeMillis();
            saver.start();
            Throwable decodeError = null;
            long decodeMs = 0, imageMs = 0;
            try {
                hw.open(next, b, sgb, dgb);
                decodeMs = hw.ms[HardwareDevelop.T_DECODE];
                imageMs = hw.ms[HardwareDevelop.T_IMAGE];
            } catch (HardwareDevelop.Stuck e) {
                throw e;
            } catch (Throwable e) {
                decodeError = e;
            }
            saver.join(OVERLAP_LIMIT_MS);
            if (saver.isAlive() || saveError[0] instanceof HardwareDevelop.Stuck) throw new HardwareDevelop.Stuck();
            long wall = System.currentTimeMillis() - w0;
            File print2 = saveCode[0] == 0 ? hw.newPhoto(before) : null;
            if (print2 != null) Library.ignore(card, print2);
            boolean catalogued = print2 != null && hw.inCatalogue(print2);
            note("  save " + saveMs[0] + " ms (code " + saveCode[0] + (saveError[0] != null ? ", " + Probe.why(saveError[0]) : "") + ") as "
                    + (print2 == null ? "(not found)" : print2.getName() + (catalogued ? ", catalogued" : ", NOT catalogued"))
                    + "; decode " + decodeMs + " + image " + imageMs + " ms" + (decodeError != null ? " (" + Probe.why(decodeError) + ")" : "")
                    + "; " + wall + " ms in all");
            boolean overlapOk = saveError[0] == null && saveCode[0] == 0 && catalogued && decodeError == null;
            if (!overlapOk) {
                report(S_OVERLAP, FAIL, decodeError != null ? "decode refused while saving: " + Probe.why(decodeError)
                        : "save failed while decoding: " + (saveError[0] != null ? Probe.why(saveError[0]) : "code " + saveCode[0] + (print2 != null && !catalogued ? ", not catalogued" : "")));
                report(S_ANSWER, INFO, "the camera can't save and decode at once: no overlap");
                throw new Stop();
            }
            long sequential = saveAlone + decodeB + imageB;
            report(S_OVERLAP, OK, "both in " + wall + " ms, one after the other " + sequential);   // the parts are in the log

            // 5. the same photo saved both times?
            current = S_SAME;
            long c1 = HardwareDevelop.crc(print1), c2 = HardwareDevelop.crc(print2);
            boolean same = c1 == c2 && print1.length() == print2.length();
            note("  " + print1.getName() + " " + print1.length() + " bytes crc " + Long.toHexString(c1) + ", " + print2.getName() + " "
                    + print2.length() + " bytes crc " + Long.toHexString(c2));
            report(S_SAME, same ? OK : INFO, same ? "identical, byte for byte" : "they differ (" + print1.length() + " / " + print2.length() + " bytes)");

            long gain = sequential - wall;
            report(S_ANSWER, OK, "yes: about " + gain + " ms a frame to gain" + (same ? "" : "; check why the files differ"));
        } catch (Stop e) {
            // reported at the step that stopped it
        } catch (HardwareDevelop.Stuck e) {
            stuck = true;
            HardwareDevelop.markHung();
            note("no answer from the camera in time: everything left as it is");
            out.step(current, FAIL, "no answer from the camera. If it doesn't recover, turn it off (or remove the battery).");
        } catch (Throwable e) {
            if (!(e instanceof HardwareDevelop.Failed)) {
                note("stopped: " + Probe.why(e));
                out.step(current, FAIL, Probe.why(e));
            }
        } finally {
            if (!stuck) { a.release(); again.release(); b.release(); }   // a call that never answered: leave it all to the camera
            if (locked) HardwareDevelop.SONY.unlock();
        }
        return finish();
    }

    /** The look into the output image from its source, as the darkroom develops; the source released after. Returns ms. */
    private long develop(HardwareDevelop.Held o, int[] sg, int[] dg, int block, String lut, int[] p) throws Exception {
        long t = System.currentTimeMillis();
        int rc;
        CpuGate.busy(true);
        try {
            rc = SonyMemory.develop(SonyMemory.handle(o.src), sg, SonyMemory.handle(o.full), dg, block, lut, p, null);
        } finally {
            CpuGate.busy(false);
        }
        if (rc != 0) throw new IllegalStateException("develop: " + SonyMemory.lastError());
        HardwareDevelop.call(o.src, "release");
        o.src = null;
        return System.currentTimeMillis() - t;
    }

    private String finish() {
        save();
        return log.toString();
    }
}
