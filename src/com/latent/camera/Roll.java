package com.latent.camera;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * A roll of film: a stock, a length, and every photo taken after it was loaded (up to that
 * length). Lives in LATENT/ROLLnnn/ with its developed frames (01_04547.JPG ...) and ROLL.TXT.
 *
 * Exposures are found by the camera's own photo numbers (100MSDCF/DSC04592 is 1004592): a roll
 * remembers the highest number on the card when it was loaded, and every higher-numbered photo is an
 * exposure. File times can't be used: the camera reports the same file's time hours apart from one
 * power-on to the next. Rolls loaded before this keep using file times (after / until).
 *
 * Once developing starts the frame list is frozen in ROLL.TXT, so a paused or interrupted develop
 * resumes with exactly the same frames.
 *
 * A frame is developed one of two ways. Through the camera's hardware, it becomes a new photo in the
 * camera roll (its path is kept in 'prints'), and its original is filed in NEG/ as the negative.
 * In software, it is written here as 01_04547.JPG and its original stays in the camera roll.
 * A frame counts as developed when either file exists.
 *
 * An endless roll (length ENDLESS, shown as ∞) has no last frame: each time Latent is back from the
 * camera, the photos shot since are added to its frames and develop behind the camera back.
 *
 * On an endless roll the film can change from frame to frame (the wheel in the viewfinder, as on the
 * X Half): 'looks' keeps the film each picture was taken in, by photo number, and stockFor(i) is what
 * the darkroom develops frame i in. 'stock' is the film loaded now, for pictures without an entry
 * (taken in the camera's own shooting mode). A roll of 12, 24 or 36 keeps one film.
 */
final class Roll {
    static final String LOADED = "loaded", DEVELOPING = "developing", DEVELOPED = "developed";
    static final int ENDLESS = 9999;   // a roll with no end (∞)

    final File dir;
    final int number;
    String stock;
    int length;
    long after;               // exposures are photos newer than this (camera file time)
    long until;               // ... and no newer than this, once a newer roll replaced it (0 = open)
    int afterNo;              // exposures are photos numbered above this (0 = an older roll: use times)
    int untilNo;              // ... and at most this, once a newer roll replaced it (0 = open)
    String state = LOADED;
    List<String> frames = new ArrayList<String>();   // source paths, frozen when developing starts
    List<String> prints = new ArrayList<String>();   // per frame: its developed photo in the camera roll, or ""
    final java.util.Map<Integer, String> looks = new java.util.TreeMap<Integer, String>();   // photo number -> film it was shot in
    // pictures shot in the viewfinder whose names aren't known yet (the card shows them once the camera is
    // back): their films, in the order they were taken, after photo number shotsAfter
    int shotsAfter;
    List<String> shotFilms = new ArrayList<String>();

    private Roll(File dir, int number) { this.dir = dir; this.number = number; }

    String label() { return "Roll " + pad3(number); }

    boolean endless() { return length >= ENDLESS; }

    // ------------------------------------------------------------------------------ on the card

    static Roll create(File card, String stock, int length, long after) throws IOException {
        return create(card, stock, length, after, 0);
    }

    static Roll create(File card, String stock, int length, long after, int afterNo) throws IOException {
        int n = 1;
        for (Roll r : list(card)) n = Math.max(n, r.number + 1);
        Roll r = new Roll(new File(Library.home(card), "ROLL" + pad3(n)), n);
        if (!r.dir.isDirectory() && !r.dir.mkdirs()) throw new IOException("cannot create " + r.dir);
        r.stock = stock;
        r.length = length;
        r.after = after;
        r.afterNo = afterNo;
        r.save();
        return r;
    }

    static Roll open(File dir) {
        String name = dir.getName();
        if (!name.matches("ROLL[0-9]{3}")) return null;
        Roll r = new Roll(dir, Integer.parseInt(name.substring(4)));
        Properties p = new Properties();
        try {
            FileInputStream in = new FileInputStream(new File(dir, "ROLL.TXT"));
            try { p.load(in); } finally { in.close(); }
        } catch (IOException e) {
            return null;
        }
        r.stock = p.getProperty("stock", "");
        r.length = parseInt(p.getProperty("length"), 24);
        r.after = parseLong(p.getProperty("after"), 0);
        r.until = parseLong(p.getProperty("until"), 0);
        r.afterNo = parseInt(p.getProperty("afterNo"), 0);
        r.untilNo = parseInt(p.getProperty("untilNo"), 0);
        r.state = p.getProperty("state", LOADED);
        String f = p.getProperty("frames", "");
        if (f.length() > 0) r.frames = new ArrayList<String>(Arrays.asList(f.split("\\|")));
        String pr = p.getProperty("prints", "");
        if (pr.length() > 0) r.prints = new ArrayList<String>(Arrays.asList(pr.split("\\|", -1)));
        r.shotsAfter = parseInt(p.getProperty("shotsAfter"), 0);
        String sf = p.getProperty("shotFilms", "");
        if (sf.length() > 0) r.shotFilms = new ArrayList<String>(Arrays.asList(sf.split("\\|")));
        for (String e : p.getProperty("looks", "").split("\\|")) {   // 1004706:CUBANNEG|1004707:NATURAL
            int c = e.indexOf(':');
            if (c <= 0 || c == e.length() - 1) continue;
            try { r.looks.put(Integer.parseInt(e.substring(0, c)), e.substring(c + 1)); } catch (NumberFormatException ignored) { }
        }
        return r;
    }

    /** Every roll on the card, newest first. */
    static List<Roll> list(File card) {
        List<Roll> out = new ArrayList<Roll>();
        File[] dirs = Library.home(card).listFiles();
        if (dirs != null) for (File d : dirs) {
            if (!d.isDirectory()) continue;
            Roll r = open(d);
            if (r != null) out.add(r);
        }
        Collections.sort(out, new Comparator<Roll>() {
            public int compare(Roll a, Roll b) { return b.number - a.number; }
        });
        return out;
    }

    synchronized void save() throws IOException {
        Properties p = new Properties();
        p.setProperty("stock", stock);
        p.setProperty("length", String.valueOf(length));
        p.setProperty("after", String.valueOf(after));
        p.setProperty("until", String.valueOf(until));
        p.setProperty("afterNo", String.valueOf(afterNo));
        p.setProperty("untilNo", String.valueOf(untilNo));
        p.setProperty("state", state);
        StringBuilder f = new StringBuilder();
        for (String s : frames) { if (f.length() > 0) f.append('|'); f.append(s); }
        p.setProperty("frames", f.toString());
        StringBuilder pr = new StringBuilder();
        for (int i = 0; i < prints.size(); i++) { if (i > 0) pr.append('|'); pr.append(prints.get(i)); }
        p.setProperty("prints", pr.toString());
        if (!shotFilms.isEmpty()) {
            StringBuilder sf = new StringBuilder();
            for (String film : shotFilms) { if (sf.length() > 0) sf.append('|'); sf.append(film); }
            p.setProperty("shotsAfter", String.valueOf(shotsAfter));
            p.setProperty("shotFilms", sf.toString());
        }
        if (!looks.isEmpty()) {
            StringBuilder lk = new StringBuilder();
            for (java.util.Map.Entry<Integer, String> e : looks.entrySet()) { if (lk.length() > 0) lk.append('|'); lk.append(e.getKey()).append(':').append(e.getValue()); }
            p.setProperty("looks", lk.toString());
        }
        File tmp = new File(dir, "SAVING.TMP"), target = new File(dir, "ROLL.TXT");
        FileOutputStream out = new FileOutputStream(tmp);
        try { p.store(out, "Latent roll"); } finally { out.close(); }
        if (target.exists() && !target.delete()) throw new IOException("cannot replace " + target);
        if (!tmp.renameTo(target)) throw new IOException("cannot write " + target);
    }

    // ------------------------------------------------------------------------------ frames

    /** The camera's number for a photo: folder 100MSDCF + file DSC04592 = 1004592; -1 if it has none. */
    static int number(File f) {
        try {
            String folder = f.getParentFile().getName(), name = f.getName();
            int dot = name.lastIndexOf('.');
            if (dot < 4) return -1;
            int n = Integer.parseInt(name.substring(dot - 4, dot)), d = Integer.parseInt(folder.substring(0, 3));
            return d >= 100 && d <= 999 ? d * 10000 + n : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** The film frame i was shot in: its own on an endless roll, else the roll's. */
    String stockFor(int i) {
        if (i < 0 || i >= frames.size()) return stock;
        String s = looks.get(number(new File(frames.get(i))));
        return s != null ? s : stock;
    }

    /** A picture's film, by its photo number (an endless roll keeps one per picture; others ignore it). */
    void lookFor(File photo, String film) {
        int n = number(photo);
        if (endless() && n > 0 && film != null && film.length() > 0) looks.put(n, film);
    }

    /**
     * A picture just taken in the viewfinder, before its name is known: its film, after photo number
     * 'highestBefore' (the card's highest when the viewfinder opened). Endless rolls only.
     */
    void shotFilm(int highestBefore, String film) {
        if (!endless() || film == null || film.length() == 0) return;
        if (shotFilms.isEmpty()) shotsAfter = highestBefore;
        shotFilms.add(film);
    }

    /**
     * Gives the viewfinder's pictures their films, once the card shows them: the photos numbered above
     * shotsAfter, oldest first, take the films in the order they were shot. Returns true if anything
     * changed. Nothing shown yet (the camera still open): kept for later.
     */
    boolean resolveShots(List<File> photosNewestFirst) {
        if (shotFilms.isEmpty()) return false;
        List<Integer> nums = new ArrayList<Integer>();
        for (File f : photosNewestFirst) { int n = number(f); if (n > shotsAfter && !looks.containsKey(n)) nums.add(n); }   // named ones are known already
        if (nums.isEmpty()) return false;
        Collections.sort(nums);
        for (int i = 0; i < nums.size() && i < shotFilms.size(); i++) looks.put(nums.get(i), shotFilms.get(i));
        shotFilms.clear();
        shotsAfter = 0;
        return true;
    }

    /** The highest photo number among these photos (0 if none). */
    static int highest(List<File> photos) {
        int h = 0;
        for (File f : photos) h = Math.max(h, number(f));
        return h;
    }

    /** Photos taken while this roll was loaded, oldest first, at most 'length'. */
    List<File> exposures(List<File> photosNewestFirst) {
        List<File> out = new ArrayList<File>();
        if (afterNo > 0) {
            for (File f : photosNewestFirst) {
                int n = number(f);
                if (n > afterNo && (untilNo == 0 || n <= untilNo)) out.add(f);
            }
            Collections.sort(out, new Comparator<File>() {
                public int compare(File a, File b) { return number(a) - number(b); }
            });
            return out.size() > length ? new ArrayList<File>(out.subList(0, length)) : out;
        }
        for (int i = photosNewestFirst.size() - 1; i >= 0; i--) {
            File f = photosNewestFirst.get(i);
            long t = f.lastModified();
            if (t > after && (until == 0 || t <= until) && out.size() < length) out.add(f);
        }
        return out;
    }

    /**
     * Another roll is being loaded: this one stops collecting photos. Its frames are fixed now, so a
     * later photo can never land on two rolls. A roll with no exposures at all is simply removed.
     */
    void close(List<File> photosNewestFirst) throws IOException {
        if (endless() && isOpen() && LOADED.equals(state)) collect(photosNewestFirst);
        else if (!LOADED.equals(state) || !frames.isEmpty()) return;
        else for (File f : exposures(photosNewestFirst)) frames.add(f.getPath());
        if (frames.isEmpty()) {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();   // only ROLL.TXT: nothing developed
            dir.delete();
            return;
        }
        until = photosNewestFirst.isEmpty() ? after : photosNewestFirst.get(0).lastModified();
        if (afterNo > 0) untilNo = Math.max(afterNo, highest(photosNewestFirst));
        save();
    }

    boolean exists() { return new File(dir, "ROLL.TXT").exists(); }

    /**
     * An endless roll in the camera takes on the photos shot since it last looked (numbered above its
     * newest frame; frames already developed have left the camera roll, so the numbers decide).
     * Saved if any were added. Returns how many.
     */
    int collect(List<File> photosNewestFirst) throws IOException {
        boolean named = resolveShots(photosNewestFirst);
        int newest = afterNo;
        for (String f : frames) newest = Math.max(newest, number(new File(f)));
        int added = 0;
        for (File f : exposures(photosNewestFirst)) {
            if (number(f) > newest) { frames.add(f.getPath()); added++; }
        }
        if (added > 0 || named) save();
        return added;
    }

    // ------------------------------------------------------------------------------ the roll in the camera

    /**
     * In the camera: loaded and still collecting exposures (never replaced), or part-way through
     * developing. The card says so by itself, which matters: the camera's own app storage doesn't
     * survive a camera crash, but the card does.
     */
    boolean isOpen() {
        return DEVELOPING.equals(state) || (LOADED.equals(state) && (frames.isEmpty() || endless()) && until == 0 && untilNo == 0);
    }

    /** The roll in the camera: the one Latent's own note names, if it is open, otherwise the newest open roll on the card. */
    static Roll inCamera(List<Roll> rolls, String note) {
        Roll newest = null;
        for (Roll r : rolls) {
            if (!r.isOpen()) continue;
            if (r.dir.getName().equals(note)) return r;
            if (newest == null || r.number > newest.number) newest = r;
        }
        return newest;
    }

    /**
     * Rolls left loaded side by side (a load whose note of the previous roll was lost, e.g. in a
     * camera crash): each older one stops where the next one began, so a photo can never be on two
     * rolls, and one left with no exposures is removed. Returns what changed (for the log), or null.
     */
    static String tidy(List<Roll> rolls, List<File> photosNewestFirst) throws IOException {
        List<Roll> open = new ArrayList<Roll>();
        for (Roll r : rolls) if (LOADED.equals(r.state) && r.isOpen()) open.add(r);
        if (open.size() < 2) return null;
        Collections.sort(open, new Comparator<Roll>() {
            public int compare(Roll a, Roll b) { return a.number - b.number; }
        });
        StringBuilder done = new StringBuilder();
        for (int i = 0; i + 1 < open.size(); i++) {
            Roll a = open.get(i), next = open.get(i + 1);
            if (a.afterNo > 0 && next.afterNo > 0) a.untilNo = Math.max(a.afterNo, next.afterNo);
            else a.until = Math.max(a.after, next.after);
            if (a.untilNo == 0 && a.until == 0) a.until = 1;   // closed, even if both began at the very start
            if (a.endless()) a.collect(photosNewestFirst);
            else for (File f : a.exposures(photosNewestFirst)) a.frames.add(f.getPath());
            if (a.frames.isEmpty()) {
                File[] files = a.dir.listFiles();
                if (files != null) for (File f : files) f.delete();   // only ROLL.TXT: nothing on it
                a.dir.delete();
                rolls.remove(a);
                done.append(done.length() > 0 ? "; " : "").append(a.label()).append(" (empty, replaced by ").append(next.label()).append(") removed");
            } else {
                a.save();
                done.append(done.length() > 0 ? "; " : "").append(a.label()).append(" closed with ").append(a.frames.size()).append(" frame(s)");
            }
        }
        return done.toString();
    }

    /** Where frame i (0-based) of this roll is developed to: 01_04547.JPG */
    File output(int i) {
        String stem = new File(frames.get(i)).getName();
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        String digits = stem.replaceAll("[^0-9]", "");
        if (digits.length() > 5) digits = digits.substring(digits.length() - 5);
        while (digits.length() < 5) digits = "0" + digits;
        return new File(dir, pad2(i + 1) + "_" + digits + ".JPG");
    }

    /** Frame i's developed photo in the camera roll, or null. */
    synchronized File print(int i) {
        String p = i < prints.size() ? prints.get(i) : "";
        return p.length() == 0 ? null : new File(p);
    }

    synchronized void setPrint(int i, File f) {
        while (prints.size() < frames.size()) prints.add("");
        prints.set(i, f.getPath());
    }

    /** Where frame i's original is filed once its print is in the camera roll: ROLLnnn/NEG/DSC04547.JPG */
    File negative(int i) {
        return new File(new File(dir, "NEG"), new File(frames.get(i)).getName());
    }

    /** Frame i's original: still in the camera roll, or filed as its negative. */
    File source(int i) {
        File o = new File(frames.get(i));
        if (o.exists()) return o;
        File n = negative(i);
        return n.exists() ? n : o;
    }

    /** Frame i developed: its print in the camera roll, or its file here; null if neither exists. */
    File developed(int i) {
        File p = print(i);
        if (p != null && p.exists()) return p;
        File o = output(i);
        return o.exists() ? o : null;
    }

    /** The screen-size preview of a developed frame: ROLLnnn/PRV/01_04547.JPG (or PRV/DSC04580.JPG) */
    File preview(File frame) {
        return new File(new File(dir, "PRV"), frame.getName());
    }

    int developedCount() {
        int n = 0;
        for (int i = 0; i < frames.size(); i++) if (developed(i) != null) n++;
        return n;
    }

    /** Developed frames in frame order (also lists them for rolls whose frame list is missing). */
    List<File> developed() {
        List<File> out = new ArrayList<File>();
        if (!frames.isEmpty()) {
            for (int i = 0; i < frames.size(); i++) {
                File f = developed(i);
                if (f != null) out.add(f);
            }
            return out;
        }
        File[] files = dir.listFiles();
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) if (f.getName().matches("[0-9]{2}_[0-9]{5}\\.JPG")) out.add(f);
        return out;
    }

    /** A developed frame as Photos lists it: its roll, its number on the roll (from 1) of the roll's frames, and the file. */
    static final class Shot {
        final Roll roll;
        final int number, of;
        final File file;

        Shot(Roll roll, int number, int of, File file) { this.roll = roll; this.number = number; this.of = of; this.file = file; }
    }

    /**
     * Every developed frame on the card, for Photos: the rolls as listed (newest first), and in each roll its newest
     * frame first. 'cameraRoll' is the card's camera roll as last listed (each photo's path): a print found there
     * isn't asked about again, where developed(i) asked the card twice a frame (null: each one is asked). Each
     * roll's own folder is listed once, for the frames developed into it.
     */
    static List<Shot> developedAll(List<Roll> rolls, Set<String> cameraRoll) {
        List<Shot> out = new ArrayList<Shot>();
        for (Roll r : rolls) {
            String[] names = r.dir.list();
            Set<String> here = names == null ? null : new HashSet<String>(Arrays.asList(names));
            int n = r.frames.size();
            if (n == 0) {   // a roll whose frame list is missing: its developed files, by their numbers
                List<File> dev = r.developed();
                for (int k = dev.size() - 1; k >= 0; k--) {
                    File f = dev.get(k);
                    int no = parseInt(f.getName().substring(0, 2), k + 1);
                    out.add(new Shot(r, no, dev.size(), f));
                }
                continue;
            }
            for (int i = n - 1; i >= 0; i--) {
                File p = r.print(i), f = null;
                if (p != null && ((cameraRoll != null && cameraRoll.contains(p.getPath())) || p.exists())) f = p;
                else {
                    File o = r.output(i);
                    if (here != null ? here.contains(o.getName()) : o.exists()) f = o;
                }
                if (f != null) out.add(new Shot(r, i + 1, n, f));
            }
        }
        return out;
    }

    /** Every roll's prints: photos in the camera roll that are developed frames, never exposures. */
    static Set<String> printPaths(List<Roll> rolls) {
        Set<String> out = new HashSet<String>();
        for (Roll r : rolls) for (String p : r.prints) if (p.length() > 0) out.add(p);
        return out;
    }

    /**
     * Rolls loaded before photo numbers were used, and still collecting exposures: they start after
     * the highest photo any earlier roll used (its frames and prints). Saved, so it happens once.
     */
    static void upgrade(List<Roll> rolls) {
        for (Roll r : rolls) {
            if (!LOADED.equals(r.state) || !r.frames.isEmpty() || r.afterNo > 0) continue;
            int h = 0;
            for (Roll o : rolls) {
                if (o.number >= r.number) continue;
                for (String f : o.frames) h = Math.max(h, number(new File(f)));
                for (String f : o.prints) if (f.length() > 0) h = Math.max(h, number(new File(f)));
            }
            if (h == 0) continue;   // nothing to go on: file times it is
            r.afterNo = h;
            try { r.save(); } catch (IOException e) { /* tried again next time */ }
        }
    }

    /** The camera roll's photos minus every roll's prints: what can be an exposure. */
    static List<File> exposable(List<File> photos, List<Roll> rolls) {
        Set<String> prints = printPaths(rolls);
        if (prints.isEmpty()) return photos;
        List<File> out = new ArrayList<File>(photos.size());
        for (File f : photos) if (!prints.contains(f.getPath())) out.add(f);
        return out;
    }

    /**
     * Rolls with frames still to develop that aren't the film in the camera (that one's develop is the camera
     * back's: a finished roll, Develop now): endless rolls' new photos, in the camera or replaced, and rolls of
     * 12, 24 or 36 closed part-shot by Change film or tidy (v0.44.1 developed those only from their contact sheet,
     * which Photos replaced; Change film says it keeps their frames for developing). The roll in the camera is left
     * out by which roll it is, not by isOpen(): a closed roll left part-developed by v0.44.1's sheet is marked
     * developing, so "open", and still waits. A frame whose original is gone from the camera roll and from NEG can never
     * develop, so it doesn't count (a roll of only those would start and fail on every visit). Oldest first.
     */
    static List<Roll> undeveloped(List<Roll> rolls, Roll inCamera) {
        List<Roll> out = new ArrayList<Roll>();
        for (Roll r : rolls) {
            if (DEVELOPED.equals(r.state) || r.frames.isEmpty()) continue;
            if (!r.endless() && inCamera != null && r.dir.equals(inCamera.dir)) continue;
            if (r.toDevelop() > 0) out.add(0, r);
        }
        return out;
    }

    /** Frames not developed yet whose original is still on the card (in the camera roll, or filed in NEG). */
    int toDevelop() {
        int n = 0;
        for (int i = 0; i < frames.size(); i++) if (developed(i) == null && source(i).exists()) n++;
        return n;
    }

    /** Replaced by a newer roll (Change film, or tidy): its frames are fixed, and it is no longer the film in the camera. */
    boolean replaced() { return until != 0 || untilNo != 0; }

    /**
     * The newest original Latent shot neutral: a frame of any roll (in the camera roll or filed in
     * NEG), or an exposure on the film in the camera. Looks are previewed on it, so they start from the
     * same plain picture they are developed from, never from a photo with your own style or a look on it.
     */
    static File reference(List<Roll> rolls, Roll inCamera, List<File> exposable) {
        File best = null;
        int bestNo = -1;
        if (inCamera != null && inCamera.frames.isEmpty()) {
            List<File> ex = inCamera.exposures(exposable);
            if (!ex.isEmpty()) { best = ex.get(ex.size() - 1); bestNo = number(best); }
        }
        for (Roll r : rolls) {
            for (int i = r.frames.size() - 1; i >= 0; i--) {
                int n = number(new File(r.frames.get(i)));
                if (n <= bestNo) continue;
                File src = r.source(i);
                if (src.exists()) { best = src; bestNo = n; break; }
            }
        }
        return best;
    }

    /** Remove half-written files a power cut may have left (only our own .TMP names). */
    void cleanTemp() {
        for (File d : new File[] { dir, new File(dir, "NEG") }) {
            File[] files = d.listFiles();
            if (files != null) for (File f : files) if (f.getName().endsWith(".TMP")) f.delete();
        }
    }

    private static String pad2(int n) { return n < 10 ? "0" + n : String.valueOf(n); }
    private static String pad3(int n) { return n < 10 ? "00" + n : n < 100 ? "0" + n : String.valueOf(n); }
    private static int parseInt(String s, int d) { try { return Integer.parseInt(s); } catch (Exception e) { return d; } }
    private static long parseLong(String s, long d) { try { return Long.parseLong(s); } catch (Exception e) { return d; } }
}
