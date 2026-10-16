package com.latent.camera;

import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Files on the memory card. Sony's card file system takes DOS 8.3 names only (upper case, eight
 * characters, three-letter extension), so every name here is 8.3: LATENT, LUTS, OUT, *.CUB.
 *
 *   /LATENT/LUTS/*.CUB   looks (.cube files renamed)
 *   /LATENT/OUT/*.JPG    processed copies, named after the source: DSC04491.JPG -> FLA04491.JPG
 */
final class Library {
    private Library() {}

    /**
     * Latent's folder on the card. Before the app was renamed it was FILMLAB: that folder (rolls,
     * negatives, the note of your camera settings) is renamed the first time it's seen.
     */
    static File home(File card) {
        File home = new File(card, "LATENT");
        if (!home.exists()) {
            File old = new File(card, "FILMLAB");
            if (old.isDirectory() && !old.renameTo(home)) return old;   // couldn't rename: keep using it as it is
        }
        return home;
    }
    static File lutDir(File card) { return new File(home(card), "LUTS"); }
    static File outDir(File card) { return new File(home(card), "OUT"); }

    /** Every JPEG under DCIM, newest first. */
    static List<File> photos(File card) {
        List<File> out = new ArrayList<File>();
        walk(new File(card, "DCIM"), out, 0);
        Collections.sort(out, new Comparator<File>() {
            public int compare(File a, File b) {
                long d = b.lastModified() - a.lastModified();
                return d < 0 ? -1 : d > 0 ? 1 : a.getName().compareTo(b.getName());
            }
        });
        return out;
    }

    private static void walk(File dir, List<File> out, int depth) {
        File[] files = dir.listFiles();
        if (files == null || depth > 3) return;
        for (File f : files) {
            if (f.isDirectory()) walk(f, out, depth + 1);
            else if (isJpeg(f.getName()) && f.length() > 0) out.add(f);
        }
    }

    static boolean isJpeg(String name) {
        String n = name.toUpperCase(Locale.US);
        return n.endsWith(".JPG") || n.endsWith(".JPEG");
    }

    /** Looks on the card, sorted by name. */
    static List<File> luts(File card) {
        File[] files = lutDir(card).listFiles();
        List<File> out = new ArrayList<File>();
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                String n = f.getName().toUpperCase(Locale.US);
                if (n.endsWith(".CUB") || n.endsWith(".CUBE")) out.add(f);
            }
        }
        return out;
    }

    /** The looks bundled in the APK, copied to the card on first run so they can be edited or swapped. */
    /** The looks shipped inside the app (assets/luts). */
    static final String[] BUNDLED = { "CUBANNEG.CUB", "PORTRA4W.CUB" };

    static void installBundledLuts(AssetManager assets, File card) throws IOException {
        File dir = lutDir(card);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        boolean all = true;
        for (String name : BUNDLED) all &= new File(dir, name).exists();
        if (all) return;   // the usual case: no need to open the app's own file list (slow on the camera)
        String[] names = assets.list("luts");
        if (names == null) return;
        for (String name : names) {
            File target = new File(dir, name);
            if (target.exists()) continue;
            File tmp = new File(dir, "SAVING.TMP");
            InputStream in = assets.open("luts/" + name);
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                in.close();
                out.close();
            }
            if (!tmp.renameTo(target)) {
                tmp.delete();
                throw new IOException("cannot write " + target);
            }
        }
    }

    /** A display name for a look file: CUBANNEG.CUB -> CUBANNEG */
    static String lutName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** A free 8.3 output name for a source photo: DSC04491.JPG -> FLA04491.JPG, FLB04491.JPG ... */
    static File outputFor(File card, File source) {
        File dir = outDir(card);
        String stem = source.getName();
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        String digits = stem.replaceAll("[^0-9]", "");
        if (digits.length() > 5) digits = digits.substring(digits.length() - 5);
        while (digits.length() < 5) digits = "0" + digits;
        for (char c = 'A'; c <= 'Z'; c++) {
            File f = new File(dir, "FL" + c + digits + ".JPG");
            if (!f.exists()) return f;
        }
        return null;
    }
}
