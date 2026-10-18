package com.latent.camera;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Sony's photo catalogue, read only: its rows, its photo numbers, and the entry for a file on the card.
 * What is left of the camera roll test (v0.1x), which found out how the catalogue answers; the darkroom,
 * the viewfinder, the card read and the camera settings all ask it this way. why() puts a failure in a line.
 */
final class Probe {
    /** A step's status, in the logs (HardwareDevelop). */
    static final int RUN = 0, OK = 1, FAIL = 2, INFO = 4;

    private Probe() {}

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

    static String why(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getClass().getSimpleName() + (c.getMessage() != null ? ": " + c.getMessage() : "");
    }
}
