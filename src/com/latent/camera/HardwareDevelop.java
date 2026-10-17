package com.latent.camera;

import android.content.ContentResolver;
import android.content.Context;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;

/**
 * Develops a frame through the camera's own hardware, straight into the camera roll:
 *
 *   decode (Sony hardware) -> film look on the decoded pixels (our code) -> written into a SEPARATE
 *   image made by Sony's scaler -> Sony's JPEG encoder saves and catalogues it as a new photo ->
 *   its orientation is matched to the original with Sony's own rotate.
 *
 * The original is only ever read here. Before any image memory is touched, the private read/write
 * functions are checked against Sony's public buffer API on a scratch buffer. Every Sony call has a
 * time limit; if one never answers, its resources are left alone and no more hardware work happens
 * until Film Lab is restarted. Any failure throws, and the darkroom develops that frame in software.
 *
 * Once a developed frame is safely in the camera roll, keepNegative() moves its original into the
 * roll's NEG folder: copied, checked, and only then removed with Sony's own delete.
 */
final class HardwareDevelop {
    static final int RUN = Probe.RUN, OK = Probe.OK, FAIL = Probe.FAIL, INFO = Probe.INFO;
    /** The steps, as the hardware test lists them. */
    static final String[] STEPS = { "Photo", "Hardware decode", "Memory check", "Output image", "Develop",
            "Save to camera roll", "New photo" };
    static final int S_PHOTO = 0, S_DECODE = 1, S_MEMORY = 2, S_IMAGE = 3, S_DEVELOP = 4, S_SAVE = 5, S_NEW = 6;

    /** Milliseconds per phase of the last develop(). */
    static final int T_DECODE = 0, T_IMAGE = 1, T_DEVELOP = 2, T_SAVE = 3, T_FIND = 4, T_ORIENT = 5, T_TOTAL = 6, T_COUNT = 7;

    static final class Stuck extends Exception {
        Stuck() { super("no answer from the camera in time"); }
    }

    /** A step failed (and has already been reported as such). */
    static final class Failed extends IllegalStateException {
        Failed(String why) { super(why); }
    }

    /** A developed frame in the camera roll. */
    static final class Print {
        File file;
        boolean catalogued;     // in Sony's catalogue (shows in playback): only then may the original move
        String orientation;     // what happened to its orientation
    }

    /**
     * Sony's catalogue, decoder and encoder are used by one thread at a time: the darkroom's develop and
     * the negatives filed alongside it take turns (fairly). Our own film look runs outside it.
     */
    static final ReentrantLock SONY = new ReentrantLock(true);

    /** A Sony call never answered: what it holds may still be in use, so leave the hardware alone. */
    private static volatile boolean hung;
    /** The transfer size the darkroom develops with, once verified on this camera (0 = not yet). */
    private static int block;
    /** The darkroom's transfer size until the Develop test has chosen one (DevelopTest.P_BLOCK). */
    static final int DARKROOM_BLOCK = 2 * 1024 * 1024;
    /** Largest transfer verified on this camera so far, of those up to checkedUpTo (the Develop test checks up to 4 MB). */
    private static int verified, checkedUpTo;

    static int verified() { return verified; }
    /** The transfer size develop() uses. */
    static int block() { return block; }
    /** Save at the encoder's best quality, unless it has refused that once. */
    private static volatile boolean extraFine = true;

    static boolean extraFine() { return extraFine; }

    private final ContentResolver cr;
    private final File card;
    private final StringBuilder log;
    private final Probe.Listener steps;
    private String mediaId;
    private Uri uri;
    final long[] ms = new long[T_COUNT];
    /** Run just before Sony's encoder saves a develop (the darkroom: FilmCore.prewarm of the next frame's film). */
    volatile Runnable beforeSave;
    final int[] look = new int[FilmCore.T_COUNT];
    int current;

    HardwareDevelop(Context ctx, File card, StringBuilder log, Probe.Listener steps) {
        this.cr = ctx.getContentResolver(); this.card = card; this.log = log; this.steps = steps;
    }

    static boolean hung() { return hung; }

    private void step(int i, int status, String detail) {
        current = i;
        if (status != RUN) log.append(STEPS[i]).append(": ").append(status == OK ? "ok" : status == FAIL ? "FAILED" : "info")
                .append(" - ").append(detail).append("\r\n");
        if (steps != null) steps.step(i, status, detail);
    }

    // ------------------------------------------------------------------------------ setting up

    /**
     * Once per darkroom visit: the card's catalogue, and (once per app run, or again when asked) the
     * largest transfer size at which the private read/write agree with Sony's public buffer API.
     */
    void prepare(boolean verifyAgain) throws Exception { prepare(verifyAgain, DARKROOM_BLOCK); }

    /**
     * The same, with transfers verified up to upTo bytes; the darkroom then develops with the largest
     * verified size up to upTo (the Develop test checks up to 4 MB and times each size itself).
     */
    void prepare(boolean verifyAgain, int upTo) throws Exception {
        openCatalogue();
        if (verified != 0 && checkedUpTo >= upTo && !verifyAgain) { block = Math.min(verified, upTo); return; }
        block = 0;
        verified = 0;
        step(S_MEMORY, RUN, "checking…");
        Class<?> dsp = Class.forName("com.sony.scalar.hardware.DSP");
        Object proc = dsp.getMethod("createProcessor", String.class).invoke(null, "sony-di-dsp");
        int good = 0;
        StringBuilder speeds = new StringBuilder();
        try {
            for (int size : new int[] { 65536, 262144, 1048576, 2097152, 4194304 }) {
                if (size > upTo) break;
                String r = verifyBlock(proc, size);
                if (r == null) break;   // this size could not be verified: keep the last good one
                good = size;
                speeds.append(speeds.length() > 0 ? ", " : "").append(size / 1024).append(" KB ").append(r);
            }
        } finally {
            call(proc, "release");
        }
        if (good == 0) throw fail(S_MEMORY, "private read/write could not be verified; nothing else was touched");
        verified = good;
        checkedUpTo = upTo;
        block = Math.min(good, upTo);
        log.append("transfer speeds (read/write ms): ").append(speeds).append("\r\n");
        step(S_MEMORY, OK, "verified up to " + verified / 1024 + " KB per transfer (" + speeds + ")");
    }

    /** The card's catalogue (all that filing negatives needs). */
    void openCatalogue() throws Exception {
        if (hung) throw new IllegalStateException("a camera call is still busy; restart Latent to use the hardware again");
        Class<?> store = Class.forName("com.sony.scalar.provider.AvindexStore");
        Class<?> media = Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
        String[] ids = (String[]) store.getMethod("getExternalMediaIds").invoke(null);
        if (ids == null || ids.length != 1) throw new IllegalStateException("expected one memory card, found " + (ids == null ? 0 : ids.length));
        mediaId = ids[0];
        uri = (Uri) media.getMethod("getContentUri", String.class).invoke(null, mediaId);
    }

    // ------------------------------------------------------------------------------ developing

    /**
     * Develops 'original' (a photo in DCIM, in the catalogue) into a new photo in the camera roll.
     * progress runs 0-100 over the whole frame. Throws with the reason on any failure.
     */
    Print develop(File original, String lut, int[] params, final FilmCore.Progress progress) throws Exception {
        if (hung) throw new IllegalStateException("a camera call is still busy");
        if (block == 0 || uri == null) throw new IllegalStateException("not prepared");
        java.util.Arrays.fill(ms, 0);
        java.util.Arrays.fill(look, 0);
        long t0 = System.currentTimeMillis(), t;
        Held o = new Held();
        boolean stuck = false;
        SONY.lock();
        try {
            int[] sg = new int[8], dg = new int[8];
            int wanted = openImages(original, o, sg, dg, progress);
            SONY.unlock();   // the look is ours: filing may use Sony meanwhile

            // develop: the look, block by block, from the source into the output image
            step(S_DEVELOP, RUN, "developing…");
            t = System.currentTimeMillis();
            FilmCore.Progress scaled = progress == null ? null : new FilmCore.Progress() {
                public void onProgress(int p) { progress.onProgress(6 + p * 80 / 100); }
            };
            int rc;
            CpuGate.busy(true);   // background filing waits for this
            try {
                rc = SonyMemory.develop(SonyMemory.handle(o.src), sg, SonyMemory.handle(o.full), dg, block, lut, params, scaled);
            } finally {
                CpuGate.busy(false);
            }
            if (rc != 0) throw fail(S_DEVELOP, SonyMemory.lastError());
            FilmCore.timing(look);
            ms[T_DEVELOP] = System.currentTimeMillis() - t;
            step(S_DEVELOP, OK, ms[T_DEVELOP] + " ms: read " + look[FilmCore.T_READ] + ", look " + look[FilmCore.T_LOOK]
                    + ", write " + look[FilmCore.T_WRITE] + " (" + block / 1024 + " KB blocks)");
            SONY.lock();
            call(o.src, "release");
            o.src = null;

            // Sony's encoder saves and catalogues it; meanwhile the next frame's film is made ready
            if (beforeSave != null) try { beforeSave.run(); } catch (Throwable ignored) { }
            step(S_SAVE, RUN, "saving…");
            Set<String> before = new HashSet<String>();
            for (File f : Library.photos(card)) before.add(f.getPath());
            t = System.currentTimeMillis();
            int code = export(o.full, mediaId, extraFine ? "EXTRAFINE" : "FINE");
            if (code == -2 && extraFine) {   // this camera won't take Extra Fine here: Fine from now on
                extraFine = false;
                log.append("Extra Fine refused (invalid parameters); saving as Fine\r\n");
                code = export(o.full, mediaId, "FINE");
            }
            ms[T_SAVE] = System.currentTimeMillis() - t;
            if (code != 0) throw fail(S_SAVE, "Sony's encoder answered " + code + (code == -1 ? " (failed)" : code == -2 ? " (invalid parameters)" : ""));
            call(o.full, "release");
            o.full = null;
            step(S_SAVE, OK, "Sony's encoder saved it (" + (extraFine ? "Extra Fine" : "Fine") + ") in " + ms[T_SAVE] + " ms");
            pct(progress, 94);

            // the new photo: on the card, in the catalogue, the right way up
            t = System.currentTimeMillis();
            Print print = new Print();
            for (int i = 0; i < 40 && print.file == null; i++) {
                if (i > 0) Thread.sleep(250);
                for (File f : Library.photos(card)) if (!before.contains(f.getPath())) { print.file = f; break; }
            }
            if (print.file == null) throw fail(S_NEW, "Sony's encoder answered OK, but no new photo appeared");
            Probe.Row row = null;
            for (int i = 0; i < 8 && row == null; i++) {
                if (i > 0) Thread.sleep(250);
                row = Probe.find(Probe.query(cr, uri), print.file);
            }
            ms[T_FIND] = System.currentTimeMillis() - t;
            print.catalogued = row != null && row.data != null;
            t = System.currentTimeMillis();
            print.orientation = print.catalogued ? matchOrientation(print.file, row.data, wanted) : "not catalogued, not rotated";
            ms[T_ORIENT] = System.currentTimeMillis() - t;
            ms[T_TOTAL] = System.currentTimeMillis() - t0;
            step(S_NEW, print.catalogued ? OK : FAIL, print.file.getName() + (print.catalogued ? " is in the camera roll" : " exists but is not catalogued")
                    + ", " + print.file.length() / 1024 + " KB; " + print.orientation);
            pct(progress, 100);
            return print;
        } catch (Stuck e) {
            stuck = true;
            hung = true;
            step(current, FAIL, "no answer from the camera in time. If it doesn't recover, turn it off (or remove the battery).");
            throw e;
        } finally {
            if (!stuck) {   // a stuck call may still be using them: leave them for the camera to reclaim
                for (Object x : new Object[] { o.half, o.src, o.proc, o.full }) if (x != null) try { call(x, "release"); } catch (Throwable ignored) { }
            }
            if (SONY.isHeldByCurrentThread()) SONY.unlock();
        }
    }

    /** What the Develop test does with the two images: Sony handles and layouts of the source and the output. */
    interface Bench { void run(int src, int[] sg, int full, int[] dg) throws Exception; }

    /**
     * For the Develop test: 'original' decoded and given an output image exactly as develop() does, then
     * bench runs on them. Nothing is saved; both are released after (unless a Sony call never answered).
     */
    void bench(File original, Bench bench) throws Exception {
        if (hung) throw new IllegalStateException("a camera call is still busy");
        if (verified == 0 || uri == null) throw new IllegalStateException("not prepared");
        Held o = new Held();
        boolean stuck = false;
        SONY.lock();
        try {
            int[] sg = new int[8], dg = new int[8];
            openImages(original, o, sg, dg, null);
            bench.run(SonyMemory.handle(o.src), sg, SonyMemory.handle(o.full), dg);
        } catch (Stuck e) {
            stuck = true;
            hung = true;
            step(current, FAIL, "no answer from the camera in time. If it doesn't recover, turn it off (or remove the battery).");
            throw e;
        } finally {
            if (!stuck) for (Object x : new Object[] { o.half, o.src, o.proc, o.full }) if (x != null) try { call(x, "release"); } catch (Throwable ignored) { }
            if (SONY.isHeldByCurrentThread()) SONY.unlock();
        }
    }

    /** What a develop holds of Sony's: released when it ends, unless a call never answered. */
    private static final class Held { Object src, half, full, proc; }

    /**
     * The first half of a develop, under SONY: the photo's catalogue entry, its hardware decode (o.src)
     * and a separate output image from Sony's scaler (o.full), their layouts into sg and dg. Returns the
     * photo's EXIF orientation. What it holds is in o, for the caller to release.
     */
    private int openImages(File original, final Held o, int[] sg, int[] dg, FilmCore.Progress progress) throws Exception {
        long t;
        final Probe.Row entry = Probe.find(Probe.query(cr, uri), original);
        if (entry == null || entry.data == null) throw fail(S_PHOTO, original.getName() + " is not in the camera's catalogue");
        int wanted = orientation(original);
        step(S_PHOTO, OK, original.getName() + " (orientation " + wanted + ")");

        // hardware decode, asking for the photo's metadata too (that keeps its EXIF in the new photo)
        step(S_DECODE, RUN, "decoding…");
        final Class<?> opts = Class.forName("com.sony.scalar.graphics.OptimizedImageFactory$Options");
        final Class<?> factory = Class.forName("com.sony.scalar.graphics.OptimizedImageFactory");
        t = System.currentTimeMillis();
        o.src = guarded(new Call() {
            public Object run() throws Exception {
                Object opt = opts.newInstance();
                opts.getField("imageType").setInt(opt, opts.getField("MAIN").getInt(null));
                for (String f : new String[] { "bBasicInfo", "bCamInfo", "bExtCamInfo", "bGpsInfo" }) opts.getField(f).setBoolean(opt, true);
                return factory.getMethod("decodeImage", String.class, opts).invoke(null, entry.data, opt);
            }
        }, 30000);
        if (o.src == null) throw fail(S_DECODE, "the decoder returned nothing");
        int w = (Integer) call(o.src, "getWidth"), h = (Integer) call(o.src, "getHeight");
        ms[T_DECODE] = System.currentTimeMillis() - t;
        step(S_DECODE, OK, w + "x" + h + " in " + ms[T_DECODE] + " ms");
        pct(progress, 3);

        // a separate output image from Sony's scaler (via half size, so it is never the source)
        step(S_IMAGE, RUN, "making the output image…");
        t = System.currentTimeMillis();
        o.half = scale(o.src, (w / 2) & ~1, (h / 2) & ~1);
        if (o.half == null) throw fail(S_IMAGE, "the scaler made no half-size image");
        o.full = scale(o.half, w, h);
        call(o.half, "release");
        o.half = null;
        if (o.full == null) throw fail(S_IMAGE, "the scaler made no full-size image");
        Class<?> dsp = Class.forName("com.sony.scalar.hardware.DSP");
        Class<?> memory = Class.forName("com.sony.scalar.hardware.DeviceMemory");
        o.proc = dsp.getMethod("createProcessor", String.class).invoke(null, "sony-di-dsp");
        Method prop = dsp.getMethod("getPropertyAsInt", memory, String.class), sprop = dsp.getMethod("getProperty", memory, String.class);
        for (int i = 0; i < 8; i++) { sg[i] = (Integer) prop.invoke(o.proc, o.src, SonyMemory.KEYS[i]); dg[i] = (Integer) prop.invoke(o.proc, o.full, SonyMemory.KEYS[i]); }
        long sa = Long.parseLong((String) sprop.invoke(o.proc, o.src, "memory-address")) & 0x3fffffffL;
        long da = Long.parseLong((String) sprop.invoke(o.proc, o.full, "memory-address")) & 0x3fffffffL;
        call(o.proc, "release");
        o.proc = null;
        boolean separate = sa + sg[7] <= da || da + dg[7] <= sa;
        log.append("geometry src ").append(java.util.Arrays.toString(sg)).append(" dst ").append(java.util.Arrays.toString(dg)).append("\r\n");
        if (!separate) throw fail(S_IMAGE, "output image overlaps the source in memory; refused");
        if (dg[0] != w || dg[1] != h) throw fail(S_IMAGE, "output image has the wrong size");
        ms[T_IMAGE] = System.currentTimeMillis() - t;
        step(S_IMAGE, OK, dg[0] + "x" + dg[1] + ", separate from the source, " + ms[T_IMAGE] + " ms");
        pct(progress, 6);
        return wanted;
    }

    private Failed fail(int s, String why) {
        step(s, FAIL, why);
        return new Failed(why);
    }

    private static void pct(FilmCore.Progress p, int v) { if (p != null) p.onProgress(v); }

    private Object scale(final Object from, final int w, final int h) throws Exception {
        final Class<?> scaler = Class.forName("com.sony.scalar.graphics.imagefilter.ScaleImageFilter");
        final Class<?> optimized = Class.forName("com.sony.scalar.graphics.OptimizedImage");
        return guarded(new Call() {
            public Object run() throws Exception {
                Object s = scaler.newInstance();
                try {
                    scaler.getMethod("setSource", optimized).invoke(s, from);
                    scaler.getMethod("setDestSize", int.class, int.class).invoke(s, w, h);
                    return (Boolean) scaler.getMethod("execute").invoke(s) ? scaler.getMethod("getOutput").invoke(s) : null;
                } finally {
                    scaler.getMethod("release").invoke(s);
                }
            }
        }, 30000);
    }

    // ------------------------------------------------------------------------------ orientation

    static int orientation(File f) {
        try { return new ExifInterface(f.getPath()).getAttributeInt("Orientation", 1); } catch (Exception e) { return 1; }
    }

    private static int degrees(int tag) {
        switch (tag) { case 1: return 0; case 6: return 90; case 3: return 180; case 8: return 270; default: return -1; }
    }

    /** Sony's relative angle (+90 = 0, +180 = 1, -90 = 3) from one EXIF orientation to another; -1 none needed, -2 unsupported. */
    static int relativeAngle(int from, int to) {
        int a = degrees(from), b = degrees(to);
        if (a < 0 || b < 0) return -2;
        switch ((b - a + 360) % 360) { case 0: return -1; case 90: return 0; case 180: return 1; default: return 3; }
    }

    /**
     * Sony's encoder doesn't keep the orientation tag. Turn the new photo with Sony's own rotate (so
     * playback, its thumbnail and EXIF all agree), then read the tag back. Never touches the original.
     */
    private String matchOrientation(File print, final String id, int wanted) {
        int now = orientation(print);
        final int angle = relativeAngle(now, wanted);
        if (angle == -1) return "orientation " + now + " as the original";
        if (angle == -2) return "orientation left at " + now + " (original " + wanted + " is mirrored)";
        try {
            final Class<?> wrapper = Class.forName("com.sony.scalar.database.avindex.wrapper.InfraScalarMprWrapper");
            Object ok = guarded(new Call() {
                public Object run() throws Exception {
                    return wrapper.getMethod("rotateImage", String.class, int.class).invoke(null, id, angle);
                }
            }, 15000);
            int after = orientation(print);
            return after == wanted ? "turned " + now + " -> " + after + " to match the original"
                    : "Sony's rotate answered " + ok + " but the photo reads " + after + " (wanted " + wanted + ")";
        } catch (Stuck e) {
            hung = true;
            return "Sony's rotate gave no answer in time";
        } catch (Throwable e) {
            return "not turned: " + Probe.why(e);
        }
    }

    // ------------------------------------------------------------------------------ negatives

    /**
     * Copies the original into the roll's NEG folder, reading it once and noting its CRC on the way.
     * Returns that CRC, or throws. The copy is read back and checked against it later, once it has
     * settled on the card and before the original goes (removeOriginal). The original is not touched.
     */
    static long copyNegative(File original, File neg) throws IOException {
        File dir = neg.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot make " + dir);
        if (neg.exists() && neg.length() == original.length()) {   // filed before: keep it if it matches
            long crc = crc(original);
            if (crc(neg) == crc) return crc;
        }
        String stem = neg.getName().substring(0, neg.getName().lastIndexOf('.'));
        File tmp = new File(dir, stem + ".TMP");
        long copied;
        InputStream in = new FileInputStream(original);
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                CRC32 c = new CRC32();
                byte[] buf = new byte[256 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) { CpuGate.await(); out.write(buf, 0, n); c.update(buf, 0, n); }
                copied = c.getValue();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        long crc = copied;   // read once, while copying
        if (tmp.length() != original.length()) {
            tmp.delete();
            throw new IOException("the copy of " + original.getName() + " did not match");
        }
        if (neg.exists() && !neg.delete()) { tmp.delete(); throw new IOException("cannot replace " + neg); }
        if (!tmp.renameTo(neg)) { tmp.delete(); throw new IOException("cannot name " + neg); }
        return crc;
    }

    /**
     * The original leaves the camera roll, once its print is in the catalogue and its negative (written
     * long enough ago to be on the card, see MainActivity.SETTLE_MS) is read back and checked again. Removed with Sony's own delete, so the catalogue stays right. Returns
     * null when done, or why it stayed (it is never lost either way: it is still in DCIM, or in NEG).
     */
    static final String NEG_MISMATCH = "its negative did not match; original kept, copying it again";

    String removeOriginal(File original, File print, File neg, long crc) {
        try {
            if (!original.exists()) return null;
            if (uri == null) return "catalogue not open; original kept";
            if (!print.exists()) return "its print is gone; original kept";
            if (crc == 0) crc = crc(original);
            if (!neg.exists() || neg.length() != original.length() || crc(neg) != crc) return NEG_MISMATCH;
            SONY.lock();
            try {
                return deleteOriginal(original, print);
            } finally {
                SONY.unlock();
            }
        } catch (Stuck e) {
            hung = true;
            return "Sony's delete gave no answer in time";
        } catch (Throwable e) {
            return Probe.why(e);
        }
    }

    /** Sony's delete of the original, its print checked in the catalogue again first. Under SONY. */
    private String deleteOriginal(File original, File print) throws Exception {
        List<Probe.Row> rows = Probe.query(cr, uri);
        if (Probe.find(rows, print) == null) return "its print is not in the camera roll; original kept";
        final Probe.Row row = Probe.find(rows, original);
        if (row == null) return "not in the catalogue; original kept";
        final Class<?> media = Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
        Object ok = guarded(new Call() {
            public Object run() throws Exception {
                return media.getMethod("deleteImage", ContentResolver.class, Uri.class, long.class).invoke(null, cr, uri, row.id);
            }
        }, 15000);
        for (int i = 0; i < 12 && original.exists(); i++) Thread.sleep(250);
        return original.exists() ? "Sony's delete answered " + ok + " and kept the original" : null;
    }

    static long crc(File f) throws IOException {
        CRC32 c = new CRC32();
        byte[] buf = new byte[256 * 1024];
        InputStream in = new FileInputStream(f);
        try {
            for (int n; (n = in.read(buf)) > 0; ) { CpuGate.await(); c.update(buf, 0, n); }
        } finally {
            in.close();
        }
        return c.getValue();
    }

    // ------------------------------------------------------------------------------ Sony calls

    interface Call { Object run() throws Exception; }

    /** Runs a Sony call on its own thread with a time limit; a hang is reported, never waited out. */
    static Object guarded(final Call call, long limitMs) throws Exception {
        final Object[] r = new Object[2];
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { r[0] = call.run(); } catch (Throwable e) { r[1] = e; }
            }
        }, "sony-call");
        t.start();
        t.join(limitMs);
        if (t.isAlive()) throw new Stuck();
        if (r[1] instanceof Exception) throw (Exception) r[1];
        if (r[1] != null) throw new RuntimeException(Probe.why((Throwable) r[1]));
        return r[0];
    }

    static Object call(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) continue;
            return m.invoke(target, args);
        }
        throw new NoSuchMethodException(name);
    }

    /**
     * Writes a pattern of 'size' bytes into a fresh scratch buffer with Sony's public API and reads it
     * back with the private one, then the reverse with a second pattern. Returns "r/w ms" timings,
     * or null if the buffer could not be made or anything disagreed. Never touches an image.
     */
    private String verifyBlock(Object proc, int size) {
        Object scratch = null;
        try {
            scratch = call(proc, "createBuffer", size);
            if (scratch == null) { log.append("block ").append(size).append(": no scratch buffer\r\n"); return null; }
            int hs = SonyMemory.handle(scratch);
            byte[] a = new byte[size], back = new byte[size];
            for (int i = 0; i < size; i++) a[i] = (byte) (i * 7 + 3 + (i >> 9));
            scratch.getClass().getMethod("write", byte[].class, int.class, int.class, int.class).invoke(scratch, a, 0, size, 0);
            long t0 = System.currentTimeMillis();
            if (SonyMemory.read(hs, back, size, 0) != size) { log.append("block ").append(size).append(": read failed (").append(SonyMemory.lastError()).append(")\r\n"); return null; }
            if (!java.util.Arrays.equals(a, back)) { log.append("block ").append(size).append(": read back different bytes\r\n"); return null; }
            long t1 = System.currentTimeMillis();
            for (int i = 0; i < size; i++) a[i] = (byte) (255 - i - (i >> 11));
            if (SonyMemory.write(hs, a, size, 0) != size) { log.append("block ").append(size).append(": write failed (").append(SonyMemory.lastError()).append(")\r\n"); return null; }
            long t2 = System.currentTimeMillis();
            java.util.Arrays.fill(back, (byte) 0);
            scratch.getClass().getMethod("read", byte[].class, int.class, int.class, int.class).invoke(scratch, back, 0, size, 0);
            if (!java.util.Arrays.equals(a, back)) { log.append("block ").append(size).append(": written bytes read back different\r\n"); return null; }
            return (t1 - t0) + "/" + (t2 - t1);
        } catch (Throwable e) {
            log.append("block ").append(size).append(": ").append(Probe.why(e)).append("\r\n");
            return null;
        } finally {
            if (scratch != null) try { call(scratch, "release"); } catch (Throwable ignored) { }
        }
    }

    /** JpegExporter.encode(image, mediaId, quality, listener) on a Looper thread; waits for the result code. */
    private int export(final Object image, final String mediaId, final String quality) throws Exception {
        final Class<?> exporterClass = Class.forName("com.sony.scalar.graphics.JpegExporter");
        final Class<?> optionsClass = Class.forName("com.sony.scalar.graphics.JpegExporter$Options");
        final Class<?> listenerClass = Class.forName("com.sony.scalar.graphics.JpegExporter$onExportEventListener");
        final Class<?> optimized = Class.forName("com.sony.scalar.graphics.OptimizedImage");
        final int[] code = { Integer.MIN_VALUE };
        final Object lock = new Object();
        final Object[] exporter = new Object[1];
        final Throwable[] error = new Throwable[1];
        final Object listener = Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class<?>[] { listenerClass }, new InvocationHandler() {
            public Object invoke(Object proxy, Method m, Object[] args) {
                if (m.getName().equals("onExported")) {
                    synchronized (lock) { code[0] = (Integer) args[0]; lock.notifyAll(); }
                    return null;
                }
                if (m.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (m.getName().equals("equals")) return proxy == args[0];
                return "listener";
            }
        });
        HandlerThread looper = new HandlerThread("sony-export");
        looper.start();
        Handler h = new Handler(looper.getLooper());
        h.post(new Runnable() {
            public void run() {
                try {
                    Object o = optionsClass.newInstance();
                    int q;
                    try {
                        q = optionsClass.getField(quality).getInt(null);
                    } catch (NoSuchFieldException e) {   // an older camera API without Extra Fine
                        extraFine = false;
                        q = optionsClass.getField("FINE").getInt(null);
                    }
                    optionsClass.getField("quality").setInt(o, q);
                    optionsClass.getField("rotateDegree").setInt(o, 0);
                    exporter[0] = exporterClass.newInstance();
                    exporterClass.getMethod("encode", optimized, String.class, optionsClass, listenerClass).invoke(exporter[0], image, mediaId, o, listener);
                } catch (Throwable e) {
                    synchronized (lock) { error[0] = e; lock.notifyAll(); }
                }
            }
        });
        long until = System.currentTimeMillis() + 90000;
        synchronized (lock) {
            while (code[0] == Integer.MIN_VALUE && error[0] == null && System.currentTimeMillis() < until) lock.wait(1000);
        }
        if (error[0] != null) { looper.quit(); throw new RuntimeException(Probe.why(error[0])); }
        if (code[0] == Integer.MIN_VALUE) throw new Stuck();   // keep everything alive: the encoder may still answer
        h.post(new Runnable() {
            public void run() { try { if (exporter[0] != null) exporterClass.getMethod("release").invoke(exporter[0]); } catch (Throwable ignored) { } }
        });
        Thread.sleep(300);
        looper.quit();
        return code[0];
    }
}
