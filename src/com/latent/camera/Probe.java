package com.latent.camera;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Camera roll test (phase 1): finds out, as safely as possible, whether this camera can take Film
 * Lab's photos into its own playback. Runs on a worker thread; reports each step as it goes.
 *
 * What it never does: load or call Sony's private native functions (it only checks they exist by
 * reading the library file), write into image memory, export a photo, change a setting, or touch
 * an original photo. The one thing it writes is a temporary copy of the newest photo, which it
 * always removes again: through Sony's own delete if the camera catalogued it, so the camera's
 * photo database stays consistent.
 */
final class Probe {
    static final String[] STEPS = { "Camera", "Sony functions", "Native functions", "Photo catalog",
            "Hardware decode", "Camera roll import", "Clean up" };
    static final int RUN = 0, OK = 1, FAIL = 2, SKIP = 3, INFO = 4;

    interface Listener { void step(int index, int status, String detail); }

    private final Context ctx;
    private final File card;
    private final Listener out;
    private final Handler main;
    private final StringBuilder log = new StringBuilder();

    Probe(Context ctx, File card, Handler main, Listener out) {
        this.ctx = ctx; this.card = card; this.main = main; this.out = out;
    }

    private void report(int i, int status, String detail) {
        log.append(STEPS[i]).append(": ").append(status == OK ? "ok" : status == FAIL ? "FAILED" : status == SKIP ? "skipped" : status == INFO ? "info" : "running")
           .append(" - ").append(detail).append("\r\n");
        out.step(i, status, detail);
    }

    private static Class<?> cls(String name) {
        try { return Class.forName(name); } catch (Throwable t) { return null; }
    }

    private static Method method(Class<?> c, String name, Class<?>... args) {
        try { return c == null ? null : c.getMethod(name, args); } catch (Throwable t) { return null; }
    }

    /** Runs every step; returns the log, also written to LATENT/PROBE.TXT. */
    String run() {
        log.append("Latent camera roll test\r\n");

        // 1. the camera
        report(0, INFO, Build.MODEL + ", Android " + Build.VERSION.RELEASE + ", build " + Build.DISPLAY);

        // 2. Sony's Java functions: looked up, not called
        Class<?> store = cls("com.sony.scalar.provider.AvindexStore");
        Class<?> media = cls("com.sony.scalar.provider.AvindexStore$Images$Media");
        Class<?> factory = cls("com.sony.scalar.graphics.OptimizedImageFactory");
        Class<?> options = cls("com.sony.scalar.graphics.OptimizedImageFactory$Options");
        Class<?> image = cls("com.sony.scalar.graphics.OptimizedImage");
        Class<?> exporter = cls("com.sony.scalar.graphics.JpegExporter");
        Class<?> scaler = cls("com.sony.scalar.graphics.imagefilter.ScaleImageFilter");
        Class<?> rotate = cls("com.sony.scalar.database.avindex.wrapper.InfraScalarMprWrapper");
        String[][] wanted = {
                { "catalog", store == null ? null : "ok" },
                { "catalog query", method(media, "getContentUri", String.class) == null ? null : "ok" },
                { "catalog delete", method(media, "deleteImage", ContentResolver.class, Uri.class, long.class) == null ? null : "ok" },
                { "hardware decoder", factory == null || options == null || image == null ? null : "ok" },
                { "hardware encoder", exporter == null ? null : "ok" },
                { "scaler", scaler == null ? null : "ok" },
                { "rotation", rotate == null ? null : "ok" },
        };
        List<String> have = new ArrayList<String>(), missing = new ArrayList<String>();
        for (String[] w : wanted) (w[1] != null ? have : missing).add(w[0]);
        report(1, missing.isEmpty() ? OK : FAIL, missing.isEmpty() ? "all present: " + join(have) : "missing: " + join(missing));

        // 3. the native functions the hardware route needs: their names in the library file, read as plain bytes
        String[] symbols = { "_ZN6scalar18DeviceMemoryDiadem11writeBufferEPKvjj", "_ZN6scalar18DeviceMemoryDiadem10readBufferEPvjj",
                "_ZN6scalar18DeviceMemoryDiadem17getPropertyDiademEiPi", "_ZN6scalar18DeviceMemoryDiadem7getTypeEv" };
        int found = countNames(new File("/system/lib/libcameraseq.so"), symbols);
        report(2, found == symbols.length ? OK : FAIL, found < 0 ? "library not readable" : found + " of " + symbols.length + " found (not loaded, not called)");

        // 4. the photo catalog, read only
        ContentResolver cr = ctx.getContentResolver();
        String mediaId = null;
        Uri uri = null;
        List<Row> rows = null;
        try {
            String[] ids = (String[]) store.getMethod("getExternalMediaIds").invoke(null);
            mediaId = ids != null && ids.length > 0 ? ids[0] : null;
            uri = (Uri) media.getMethod("getContentUri", String.class).invoke(null, mediaId);
            rows = query(cr, uri);
            report(3, OK, rows.size() + " photos catalogued on card " + mediaId);
        } catch (Throwable t) {
            report(3, FAIL, why(t));
        }

        // the newest photo on the card, and its catalog entry
        List<File> photos = Library.photos(card);
        File newest = photos.isEmpty() ? null : photos.get(0);
        Row entry = newest == null || rows == null ? null : find(rows, newest);

        // 5. hardware decode of that photo, as playback does: timed, size read, released
        if (entry == null || factory == null || options == null) {
            report(4, SKIP, newest == null ? "no photo on the card" : "photo not found in the catalog");
        } else {
            report(4, RUN, "decoding " + newest.getName() + "…");
            final String id = entry.data;
            final Object[] result = new Object[2];
            final Class<?> fFactory = factory, fOptions = options;
            Thread t = new Thread(new Runnable() {
                public void run() {
                    long t0 = System.currentTimeMillis();
                    try {
                        Object opts = fOptions.newInstance();
                        fOptions.getField("imageType").setInt(opts, fOptions.getField("MAIN").getInt(null));
                        Object img = fFactory.getMethod("decodeImage", String.class, fOptions).invoke(null, id, opts);
                        if (img == null) { result[1] = "decoder returned nothing"; return; }
                        int w = (Integer) img.getClass().getMethod("getWidth").invoke(img);
                        int h = (Integer) img.getClass().getMethod("getHeight").invoke(img);
                        long ms = System.currentTimeMillis() - t0;
                        img.getClass().getMethod("release").invoke(img);
                        result[0] = w + "x" + h + " in " + ms + " ms";
                    } catch (Throwable e) {
                        result[1] = why(e);
                    }
                }
            });
            t.start();
            try { t.join(30000); } catch (InterruptedException e) { /* fall through */ }
            if (t.isAlive()) report(4, FAIL, "no answer after 30 s (left running; the rest is skipped)");
            else if (result[0] != null) report(4, OK, result[0] + " (our software decode: about 10,000 ms)");
            else report(4, FAIL, String.valueOf(result[1]));
            if (t.isAlive()) return finish();
        }

        // 6. does a re-scan take a file Film Lab wrote into the camera roll? (a copy of the newest photo)
        if (newest == null || entry == null || rows == null) {
            report(5, SKIP, "needs a catalogued photo");
            report(6, SKIP, "nothing written");
            return finish();
        }
        File folder = newest.getParentFile();
        int number = freeNumberBelow(folder, rows, entry.folder);
        if (number < 1) {
            report(5, SKIP, "no unused file number below your photos in " + folder.getName());
            report(6, SKIP, "nothing written");
            return finish();
        }
        File test = new File(folder, "FLAB" + pad4(number) + ".JPG");
        int before = rows.size();
        final List<String> events = new ArrayList<String>();
        BroadcastReceiver rx = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) { synchronized (events) { events.add(i.getAction().replaceAll(".*\\.", "")); } }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.sony.scalar.database.avindex.action.AVINDEX_DATABASE_UPDATED");
        filter.addAction("com.sony.scalar.providers.avindex.action.AVINDEX_LOAD_STARTED");
        filter.addAction("com.sony.scalar.providers.avindex.action.AVINDEX_LOAD_FINISHED");
        ctx.registerReceiver(rx, filter, null, main);
        Row imported = null;
        try {
            report(5, RUN, "copying " + newest.getName() + " to " + test.getName() + "…");
            copy(newest, test);
            ctx.sendBroadcast(new Intent("com.sony.scalar.providers.avindex.action.MEDIA_SCANNER_SCAN_EXTERNAL_VOLUME"));
            for (int s = 0; s < 30 && imported == null; s++) {
                Thread.sleep(1000);
                report(5, RUN, "asked the camera to re-scan; waiting " + (s + 1) + " s");
                imported = findNumber(query(cr, uri), entry.folder, number);
            }
            String ev;
            synchronized (events) { ev = events.isEmpty() ? "no catalog events" : "events: " + events; }
            int after = query(cr, uri).size();
            report(5, imported != null ? OK : FAIL, (imported != null ? "the camera catalogued our file" : "the camera did not catalogue it")
                    + " (" + before + " -> " + after + " photos; " + ev + ")");
        } catch (Throwable t) {
            report(5, FAIL, why(t));
        } finally {
            try { ctx.unregisterReceiver(rx); } catch (Throwable t) { /* already gone */ }
        }

        // 7. remove the test copy, the way that keeps the camera's catalog consistent
        try {
            Row still = findNumber(query(cr, uri), entry.folder, number);
            if (still != null) {
                boolean ok = (Boolean) media.getMethod("deleteImage", ContentResolver.class, Uri.class, long.class).invoke(null, cr, uri, still.id);
                Thread.sleep(1500);
                boolean gone = !test.exists() && findNumber(query(cr, uri), entry.folder, number) == null;
                report(6, gone ? OK : FAIL, gone ? "removed with Sony's delete; catalog back to normal"
                        : "Sony's delete returned " + ok + ". Please delete " + test.getName() + " in playback with the trash button.");
            } else if (test.exists()) {
                boolean ok = test.delete();
                Thread.sleep(3000);   // a re-scan that finished late may still have catalogued it: clear that too
                Row late = findNumber(query(cr, uri), entry.folder, number);
                if (late != null) media.getMethod("deleteImage", ContentResolver.class, Uri.class, long.class).invoke(null, cr, uri, late.id);
                report(6, ok ? OK : FAIL, ok ? "removed (it was never catalogued)" + (late != null ? "; a late catalog entry was cleared too" : "")
                        : "could not remove " + test.getName() + " from " + folder.getName());
            } else {
                report(6, OK, "nothing left to remove");
            }
        } catch (Throwable t) {
            report(6, FAIL, why(t) + ". Please delete " + test.getName() + " in playback.");
        }
        return finish();
    }

    private String finish() {
        try {
            FileOutputStream o = new FileOutputStream(new File(Library.home(card), "PROBE.TXT"));
            try { o.write(log.toString().getBytes("UTF-8")); } finally { o.close(); }
        } catch (Exception e) {
            // the screen shows the same
        }
        return log.toString();
    }

    // ------------------------------------------------------------------------------ helpers

    static final class Row {
        long id;
        String data;
        int folder, number;
        boolean jpeg;
    }

    static List<Row> query(ContentResolver cr, Uri uri) {
        List<Row> out = new ArrayList<Row>();
        Cursor c = cr.query(uri, new String[] { "_id", "_data", "dcf_folder_number", "dcf_file_number", "exist_jpeg" }, null, null, null);
        if (c == null) return out;
        try {
            int i = c.getColumnIndex("_id"), d = c.getColumnIndex("_data"), f = c.getColumnIndex("dcf_folder_number"),
                    n = c.getColumnIndex("dcf_file_number"), j = c.getColumnIndex("exist_jpeg");
            while (c.moveToNext()) {
                Row r = new Row();
                r.id = c.getLong(i);
                r.data = c.getString(d);
                r.folder = c.getInt(f);
                r.number = c.getInt(n);
                r.jpeg = j >= 0 && c.getInt(j) != 0;
                out.add(r);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * Every photo number in the catalogue at uri (folder 100, file 4696: 1004696), asked for those two columns
     * only: no paths, no rows kept. Throws if the catalogue won't answer that (the caller then asks as query does).
     */
    static int[] numbers(ContentResolver cr, Uri uri) {
        Cursor c = cr.query(uri, new String[] { "dcf_folder_number", "dcf_file_number" }, null, null, null);
        if (c == null) throw new IllegalStateException("no answer for folder and number alone");
        try {
            int f = c.getColumnIndexOrThrow("dcf_folder_number"), n = c.getColumnIndexOrThrow("dcf_file_number");
            int[] out = new int[64];
            int k = 0;
            while (c.moveToNext()) {
                if (k == out.length) { int[] more = new int[k * 2]; System.arraycopy(out, 0, more, 0, k); out = more; }
                out[k++] = c.getInt(f) * 10000 + c.getInt(n);
            }
            int[] exact = new int[k];
            System.arraycopy(out, 0, exact, 0, k);
            return exact;
        } finally {
            c.close();
        }
    }

    /** Every photo number in the card's catalogue (folder 100, file 4696: 1004696), or null if it can't be read. */
    static java.util.Set<Integer> catalogued(ContentResolver cr) {
        try {
            String[] ids = (String[]) Class.forName("com.sony.scalar.provider.AvindexStore").getMethod("getExternalMediaIds").invoke(null);
            if (ids == null || ids.length != 1) return null;
            Uri uri = (Uri) Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media").getMethod("getContentUri", String.class).invoke(null, ids[0]);
            java.util.Set<Integer> out = new java.util.HashSet<Integer>();
            for (Row r : query(cr, uri)) out.add(r.folder * 10000 + r.number);
            return out;
        } catch (Throwable e) {
            return null;
        }
    }

    /** The catalog entry for a DCIM file: folder NNNxxxxx and file xxxxNNNN. */
    static Row find(List<Row> rows, File f) {
        try {
            int folder = Integer.parseInt(f.getParentFile().getName().substring(0, 3));
            String stem = f.getName().substring(0, f.getName().lastIndexOf('.'));
            int number = Integer.parseInt(stem.substring(stem.length() - 4));
            return findNumber(rows, folder, number);
        } catch (Exception e) {
            return null;
        }
    }

    static Row findNumber(List<Row> rows, int folder, int number) {
        for (Row r : rows) if (r.folder == folder && r.number == number && r.jpeg) return r;
        for (Row r : rows) if (r.folder == folder && r.number == number) return r;
        return null;
    }

    /**
     * A file number below every photo in the folder and used by no file or catalog entry, so the
     * camera's own numbering (which continues from the highest number) is never affected.
     */
    private static int freeNumberBelow(File folder, List<Row> rows, int folderNumber) {
        Set<Integer> used = new HashSet<Integer>();
        int lowest = 10000;
        File[] files = folder.listFiles();
        if (files != null) for (File f : files) {
            String n = f.getName();
            int dot = n.lastIndexOf('.');
            if (dot < 4) continue;
            try {
                int v = Integer.parseInt(n.substring(dot - 4, dot));
                used.add(v);
                lowest = Math.min(lowest, v);
            } catch (NumberFormatException e) { /* not a DCF name */ }
        }
        for (Row r : rows) if (r.folder == folderNumber) { used.add(r.number); lowest = Math.min(lowest, r.number); }
        for (int v = lowest - 1; v >= 1; v--) if (!used.contains(v)) return v;
        return -1;
    }

    /**
     * How many of the names appear in the file (a library's symbol table is plain text). Scans in
     * 64 KB chunks with an overlap, so it never holds the whole library in the 24 MB Java heap.
     */
    static int countNames(File f, String[] names) {
        boolean[] seen = new boolean[names.length];
        int longest = 0;
        for (String n : names) longest = Math.max(longest, n.length());
        try {
            InputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[65536 + longest];
                int len = 0, n;
                while ((n = in.read(buf, len, buf.length - len)) > 0) {
                    len += n;
                    String text = new String(buf, 0, len, "ISO-8859-1");
                    for (int i = 0; i < names.length; i++) if (!seen[i] && text.indexOf(names[i]) >= 0) seen[i] = true;
                    int keep = Math.min(longest - 1, len);   // a name may straddle two chunks
                    System.arraycopy(buf, len - keep, buf, 0, keep);
                    len = keep;
                }
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return -1;
        }
        int count = 0;
        for (boolean b : seen) if (b) count++;
        return count;
    }

    private static String join(List<String> items) {
        StringBuilder b = new StringBuilder();
        for (String s : items) { if (b.length() > 0) b.append(", "); b.append(s); }
        return b.toString();
    }

    private static void copy(File src, File dst) throws Exception {
        InputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            try { out.getFD().sync(); } catch (Exception e) { /* Sony's FUSE has no fsync */ }
        } finally {
            in.close();
            out.close();
        }
    }

    private static String pad4(int n) { String s = "000" + n; return s.substring(s.length() - 4); }

    static String why(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getClass().getSimpleName() + (c.getMessage() != null ? ": " + c.getMessage() : "");
    }
}
