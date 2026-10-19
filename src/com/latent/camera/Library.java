package com.latent.camera;

import android.content.res.AssetManager;

import java.io.File;
import java.io.FileInputStream;
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

    /**
     * Photos that are never exposures: the camera tests' own shots and prints (the tests went in v0.45.0; their
     * photos may still be on the card). LATENT/IGNORE.TXT, one "100MSDCF/DSC04680.JPG" per line (folder and
     * name, so it reads the same on a computer).
     */
    static java.util.Set<String> ignored(File card) {
        java.util.Set<String> out = new java.util.HashSet<String>();
        File f = new File(home(card), "IGNORE.TXT");
        if (!f.exists()) return out;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            try { for (String line; (line = r.readLine()) != null; ) if (line.trim().length() > 0) out.add(line.trim().toUpperCase(Locale.US)); }
            finally { r.close(); }
        } catch (IOException e) {
            // nothing ignored
        }
        return out;
    }

    /**
     * Exit Latent (v0.48.0): the highest photo number on the card as you leave, in LATENT/AWAY.TXT. Photos taken in the
     * camera's own mode after it are yours, not a roll's (settleAway).
     */
    static void markAway(File card, int highest) throws IOException {
        FileOutputStream o = new FileOutputStream(new File(home(card), "AWAY.TXT"));
        try { o.write((highest + "\r\n").getBytes("UTF-8")); } finally { o.close(); }
    }

    /** Exit Latent called off (MENU: staying in Latent): what's shot from now on is Latent's again. */
    static void clearAway(File card) {
        new File(home(card), "AWAY.TXT").delete();
    }

    /**
     * Back in Latent after Exit Latent: every photo numbered above AWAY.TXT's (shot in the camera's own mode, with your
     * settings) goes into IGNORE.TXT, so no roll takes it, then AWAY.TXT goes. Card read, before the rolls collect their
     * photos. Returns a line for DEVELOP.TXT, or null when there was nothing to do.
     */
    static String settleAway(File card, List<File> listed) {
        File away = new File(home(card), "AWAY.TXT");
        if (!away.exists()) return null;
        int mark;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(away));
            try { mark = Integer.parseInt(r.readLine().trim()); } finally { r.close(); }
        } catch (Exception e) {
            away.delete();   // unreadable: nothing kept off (they join the roll, as before v0.48.0)
            return "exit: AWAY.TXT unreadable (" + e.getMessage() + "); removed";
        }
        java.util.Set<String> skip = ignored(card);
        StringBuilder add = new StringBuilder();
        int n = 0;
        for (File p : listed) {
            if (Roll.number(p) <= mark || skip.contains(key(p))) continue;
            add.append(key(p)).append("\r\n");
            n++;
        }
        try {
            if (n > 0) {
                FileOutputStream o = new FileOutputStream(new File(home(card), "IGNORE.TXT"), true);
                try { o.write(add.toString().getBytes("UTF-8")); } finally { o.close(); }
            }
        } catch (IOException e) {
            return "exit: photos shot since Exit not kept off the rolls (" + e.getMessage() + "); AWAY.TXT kept";
        }
        away.delete();
        return "exit: " + n + " photo(s) shot in the camera's own mode since Exit Latent, kept off the rolls";
    }

    static String key(File photo) {
        return (photo.getParentFile().getName() + "/" + photo.getName()).toUpperCase(Locale.US);
    }

    /**
     * Pictures taken in Latent's viewfinder: LATENT/SHOTS.TXT, one "100MSDCF/DSC04696.JPG" per line.
     * The camera writes them past Linux's back, so the card's listing may not show them until the card
     * is read afresh; until it does, they still count as exposures.
     */
    static void recordShot(File card, File photo) throws IOException {
        FileOutputStream o = new FileOutputStream(new File(home(card), "SHOTS.TXT"), true);
        try { o.write((key(photo) + "\r\n").getBytes("UTF-8")); } finally { o.close(); }
    }

    /**
     * These photos (newest first) plus the viewfinder's pictures the listing doesn't show yet, at the
     * end (their file times aren't known). SHOTS.TXT keeps only those still missing. catalogued, when
     * known, holds the photo numbers in the camera's catalogue: a picture missing from it was never
     * stored (v0.31.0's hung camera catalogued one, then lost it) and is dropped.
     */
    static List<File> withShots(File card, List<File> photos, java.util.Set<Integer> catalogued) {
        File list = new File(home(card), "SHOTS.TXT");
        if (!list.exists()) return photos;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (File p : photos) seen.add(key(p));
        List<File> out = new ArrayList<File>(photos);
        StringBuilder keep = new StringBuilder();
        java.util.Set<String> added = new java.util.HashSet<String>();
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(list));
            try {
                for (String line; (line = r.readLine()) != null; ) {
                    String k = line.trim().toUpperCase(Locale.US);
                    if (k.length() == 0 || seen.contains(k) || !added.add(k)) continue;
                    File f = new File(new File(card, "DCIM"), k);
                    if (catalogued != null && !catalogued.contains(Roll.number(f))) continue;
                    out.add(f);
                    keep.append(k).append("\r\n");
                }
            } finally { r.close(); }
            if (keep.length() == 0) list.delete();
            else {
                FileOutputStream o = new FileOutputStream(list);
                try { o.write(keep.toString().getBytes("UTF-8")); } finally { o.close(); }
            }
        } catch (IOException e) {
            // the listing alone, then
        }
        return out;
    }

    /** These photos without the ignored ones. */
    static List<File> withoutIgnored(File card, List<File> photos) {
        java.util.Set<String> skip = ignored(card);
        if (skip.isEmpty()) return photos;
        List<File> out = new ArrayList<File>(photos.size());
        for (File p : photos) if (!skip.contains(key(p))) out.add(p);
        return out;
    }

    /** Every JPEG under DCIM, newest first. */
    static List<File> photos(File card) {
        List<File> out = new ArrayList<File>();
        walk(new File(card, "DCIM"), out, 0);
        return newestFirst(out);
    }

    /**
     * These files newest first (ties by name, then as listed): each file's time is read once, where the
     * sort used to ask the card twice per comparison (~2 N log N stats a listing, ~20,000 for 1,000 photos).
     * The order is exactly the old one: the same comparison on the same times, in the same stable sort.
     */
    static List<File> newestFirst(List<File> files) {
        int n = files.size();
        List<Timed> timed = new ArrayList<Timed>(n);
        for (File f : files) timed.add(new Timed(f, f.lastModified()));
        Collections.sort(timed, NEWEST_FIRST);
        List<File> out = new ArrayList<File>(n);
        for (Timed t : timed) out.add(t.file);
        return out;
    }

    private static final class Timed {
        final File file;
        final long time;
        Timed(File file, long time) { this.file = file; this.time = time; }
    }

    private static final Comparator<Timed> NEWEST_FIRST = new Comparator<Timed>() {
        public int compare(Timed a, Timed b) {
            long d = b.time - a.time;
            return d < 0 ? -1 : d > 0 ? 1 : a.file.getName().compareTo(b.file.getName());
        }
    };

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

    /**
     * The looks shipped inside the app (assets/luts), copied to the card so they can be read there. A look an earlier
     * Latent put on the card is replaced by this version's (see stale); your own looks are never touched.
     */
    static final String[] BUNDLED = { "NATURAL.CUB", "PORTRA4W.CUB", "CUBANNEG.CUB", "PAINTRLY.CUB", "TRIX400.CUB" };

    private static volatile boolean installed;   // checked once per run: five small reads on the card every resume otherwise

    static void installBundledLuts(AssetManager assets, File card) throws IOException {
        if (installed) return;
        installLuts(assets, card);
        installed = true;
    }

    private static void installLuts(AssetManager assets, File card) throws IOException {
        File dir = lutDir(card);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        for (String name : BUNDLED) {
            File target = new File(dir, name);
            String shipped = firstLine(assets.open("luts/" + name));
            if (target.exists() && !stale(firstLine(new FileInputStream(target)), shipped)) continue;
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
            // the old look goes only once the new one is whole: a power cut between the two leaves no look, and the
            // next run installs it again
            if ((target.exists() && !target.delete()) || !tmp.renameTo(target)) {
                tmp.delete();
                throw new IOException("cannot write " + target);
            }
        }
    }

    /**
     * Whether a look on the card (its first line) is one an earlier Latent wrote and this one ships anew (shipped: the
     * first line of this version's): make_luts.py starts every look with a line naming its version, and Latent's looks
     * before it began "# Latent look." or "# Film Lab look.". Anything else is yours, and stays.
     */
    static boolean stale(String onCard, String shipped) {
        if (onCard == null || onCard.equals(shipped)) return false;
        return onCard.startsWith("# Latent look") || onCard.startsWith("# Film Lab look");
    }

    /** The first line of a file, or null; the stream is closed. */
    static String firstLine(InputStream in) throws IOException {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"), 256);
            return r.readLine();
        } finally {
            in.close();
        }
    }

    /** A display name for a look file: CUBANNEG.CUB -> CUBANNEG */
    static String lutName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}
