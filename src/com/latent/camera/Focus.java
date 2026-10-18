package com.latent.camera;

import java.io.File;

/**
 * Focus as Latent shows and checks it (v0.46.0; docs/AUTOFOCUS.md): where the camera focused, where its focus area
 * is, and whether a photo just taken is sharp there.
 *
 * The camera names its AF areas on a 5 x 5 grid, row by row: its focus report numbers them 1 to 25, Sony's makernote
 * (tag 0x201F) holds them as a mask, bit 0 to 24. That the grid covers the whole picture is inferred from the A5100's
 * photos, not documented: GRID is the one place to change if the camera shows otherwise.
 *
 * Plain logic with no camera in it, tested on a Mac (tests/ViewfinderTest).
 */
final class Focus {
    private Focus() {}

    /** The AF grid over the picture: x, y, width, height as parts of it (inferred: the whole picture). */
    static final float[] GRID = { 0f, 0f, 1f, 1f };
    static final int CELLS = 5;
    /** A focused cell is drawn this much of its size, about its middle, so the subject still shows. */
    static final float BOX = 0.6f;

    /** The focus report's areas (1 to 25) as a mask (bit 0 to 24); others are left out. 0 for none. */
    static int mask(int[] areas) {
        int m = 0;
        if (areas == null) return 0;
        for (int a : areas) if (a >= 1 && a <= CELLS * CELLS) m |= 1 << (a - 1);
        return m;
    }

    /** The cells in mask as a line for the log: "7 8 12", or "none". */
    static String cells(int mask) {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < CELLS * CELLS; k++) if ((mask >> k & 1) != 0) b.append(b.length() > 0 ? " " : "").append(k);
        return b.length() > 0 ? b.toString() : "none";
    }

    /** The focused cells as boxes {x, y, w, h}, parts of the picture (UiState.afAreas); null for none. */
    static float[][] boxes(int mask) {
        int n = Integer.bitCount(mask & (1 << CELLS * CELLS) - 1);
        if (n == 0) return null;
        float[][] out = new float[n][];
        float cw = GRID[2] / CELLS, ch = GRID[3] / CELLS;
        for (int k = 0, i = 0; k < CELLS * CELLS; k++) {
            if ((mask >> k & 1) == 0) continue;
            float x = GRID[0] + (k % CELLS) * cw, y = GRID[1] + (k / CELLS) * ch;
            out[i++] = new float[] { x + cw * (1 - BOX) / 2, y + ch * (1 - BOX) / 2, cw * BOX, ch * BOX };
        }
        return out;
    }

    /**
     * Flexible Spot's size as part of the picture's width and height, small, medium, large. Guesses until the
     * camera shows them: medium as one AF cell (about the bracket Latent drew until v0.46.0).
     */
    static final float[][] SPOT = { { 0.14f, 0.14f }, { 0.2f, 0.2f }, { 0.28f, 0.28f } };

    /**
     * Where the camera's focus area is, for the bracket: {x, y, w, h} as parts of the picture, or null to draw the
     * usual bracket in the middle. Center is the middle 3 x 3 cells (in the A5100's photos it only ever used cells of
     * those); Flexible Spot is at focus-point-x / -y, read as -1000..1000 about the middle as Android's focus areas
     * are (unconfirmed: -752 / 361 on this camera). Wide, Zone and the rest: null.
     */
    static float[] areaBox(String type, String size, String px, String py) {
        if ("center".equals(type)) {
            float cw = GRID[2] / CELLS, ch = GRID[3] / CELLS;
            return new float[] { GRID[0] + cw, GRID[1] + ch, 3 * cw, 3 * ch };
        }
        if (!"flex-spot".equals(type)) return null;
        int x, y;
        try {
            x = Integer.parseInt(px.trim());
            y = Integer.parseInt(py.trim());
        } catch (Throwable e) {
            return null;
        }
        float[] s = SPOT["small".equals(size) ? 0 : "large".equals(size) ? 2 : 1];
        float cx = Math.max(0, Math.min(1, (x + 1000) / 2000f)), cy = Math.max(0, Math.min(1, (y + 1000) / 2000f));
        float l = Math.max(0, Math.min(1 - s[0], cx - s[0] / 2)), t = Math.max(0, Math.min(1 - s[1], cy - s[1] / 2));
        return new float[] { l, t, s[0], s[1] };
    }

    // ------------------------------------------------------------------------------ the check after a shot

    static final int UNKNOWN = -1, SHARP = 0, MISSED = 1, MOVED = 2, SOFT = 3;
    /**
     * Scores are 0..1000 (FilmCore.focusCheck). Soft when the focused cells score under LOCKED_MIN, or under RATIO of
     * the frame's best; missed when the frame is sharp elsewhere (SHARP_ELSEWHERE). Set from the 61 photos on the card
     * of 2026-10-16: every frame that is soft at 100% where the camera focused falls under them, every sharp one
     * checked is over (docs/AUTOFOCUS.md). First guesses for one lens; VIEWFIND.TXT logs every score.
     */
    static final int LOCKED_MIN = 520, SHARP_ELSEWHERE = 600;
    static final float RATIO = 0.70f;
    /** Exposures longer than this (1/30 s) can blur with the camera's own movement. */
    static final double SLOW_S = 1 / 30.0 + 1e-6;

    /** The best score among the cells in mask (-1: none of them has one). */
    static int locked(int[] scores, int mask) {
        int best = -1;
        for (int k = 0; k < CELLS * CELLS; k++) if ((mask >> k & 1) != 0 && scores[k] > best) best = scores[k];
        return best;
    }

    /** The best score of all. */
    static int best(int[] scores) {
        int best = -1;
        for (int k = 0; k < CELLS * CELLS; k++) best = Math.max(best, scores[k]);
        return best;
    }

    /** How a photo turned out where the camera focused (mask), its exposure in seconds (0: unknown). */
    static int verdict(int[] scores, int mask, double exposure) {
        int l = locked(scores, mask), b = best(scores);
        if (mask == 0 || l < 0) return UNKNOWN;
        if (l >= LOCKED_MIN && l >= RATIO * b) return SHARP;
        if (b >= SHARP_ELSEWHERE && b > l) return MISSED;   // sharp somewhere else: focus landed off
        if (exposure > SLOW_S) return MOVED;
        return SOFT;
    }

    /** What the viewfinder says ("" for nothing). */
    static String words(int verdict) {
        switch (verdict) {
            case MISSED: return "Missed focus";
            case MOVED: return "Blurred: the camera moved";
            case SOFT: return "Not sharp";
            default: return "";
        }
    }

    static String name(int verdict) {
        switch (verdict) {
            case SHARP: return "sharp";
            case MISSED: return "missed focus";
            case MOVED: return "blurred, the camera moved";
            case SOFT: return "not sharp";
            default: return "can't tell";
        }
    }

    /**
     * A check's verdict (out as FilmCore.focusCheck fills it): the cells the photo's own makernote names, else those
     * the camera's report named at the lock (reportMask); the photo's own exposure, else the one read at the lock.
     */
    static int verdictOf(int[] out, int reportMask, double exposure) {
        return verdict(out, cellsOf(out, reportMask), exposureOf(out, exposure));
    }

    static int cellsOf(int[] out, int reportMask) { return out[FilmCore.FC_MASK] > 0 ? out[FilmCore.FC_MASK] : reportMask; }

    static double exposureOf(int[] out, double exposure) { return out[FilmCore.FC_EXPOSURE] > 0 ? out[FilmCore.FC_EXPOSURE] / 1e6 : exposure; }

    /** A check as a line for the log. */
    static String line(String name, int[] out, int reportMask, double exposure, int verdict) {
        int cells = cellsOf(out, reportMask), file = out[FilmCore.FC_MASK];
        int l = locked(out, cells), b = best(out);
        double e = exposureOf(out, exposure);
        return "focus check " + name + ": " + name(verdict) + " (cells " + cells(cells) + (file > 0 ? " from the photo" : " from the lock")
                + (file > 0 && reportMask != 0 && reportMask != file ? ", the lock named " + cells(reportMask) : "")
                + String.format(java.util.Locale.US, "; there %.2f, best %.2f", l / 1000.0, b / 1000.0)
                + (e > 0 ? "; " + seconds(e) : "") + "); cells " + scores(out)
                + "; read " + out[FilmCore.FC_READ] + " + measure " + out[FilmCore.FC_MEASURE] + " ms at " + out[FilmCore.FC_W] + "x" + out[FilmCore.FC_H];
    }

    static String seconds(double e) {
        return e >= 1 ? String.format(java.util.Locale.US, "%.1f s", e) : "1/" + Math.round(1 / e) + " s";
    }

    // ------------------------------------------------------------------------------ checked after the restart

    /**
     * The viewfinder's photos it couldn't read (Linux sees a photo the camera has just written only once the card is
     * read afresh: v0.22.1), checked when Latent comes back after its restart: LATENT/TOCHECK.TXT, one
     * "path cells exposure-in-us" a line, appended as the viewfinder closes and taken (the file removed) after.
     */
    static final class Waiting {
        final String path;
        final int reportMask;
        final double exposure;
        Waiting(String path, int reportMask, double exposure) { this.path = path; this.reportMask = reportMask; this.exposure = exposure; }
    }

    static String pendingLine(String path, int reportMask, double exposure) {
        return path + " " + reportMask + " " + Math.round(exposure * 1e6);
    }

    static File pendingFile(File card) { return new File(Library.home(card), "TOCHECK.TXT"); }

    static void addPending(File card, java.util.List<String> lines) throws java.io.IOException {
        StringBuilder b = new StringBuilder();
        for (String l : lines) b.append(l).append("\r\n");
        java.io.FileOutputStream o = new java.io.FileOutputStream(pendingFile(card), true);
        try { o.write(b.toString().getBytes("UTF-8")); } finally { o.close(); }
    }

    /** The photos waiting for their check, the file removed (each is checked once). Lines it can't read are skipped. */
    static java.util.List<Waiting> takePending(File card) {
        java.util.List<Waiting> out = new java.util.ArrayList<Waiting>();
        File f = pendingFile(card);
        if (!f.exists()) return out;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            try {
                for (String l; (l = r.readLine()) != null; ) {
                    String[] p = l.trim().split(" ");
                    if (p.length < 3) continue;
                    try {
                        out.add(new Waiting(p[0], Integer.parseInt(p[1]), Long.parseLong(p[2]) / 1e6));
                    } catch (NumberFormatException ignored) { }
                }
            } finally {
                r.close();
            }
        } catch (java.io.IOException ignored) {
            // read what there was
        }
        f.delete();
        return out;
    }

    /** What the screen says of the photos that came out soft: {frame number (0: not known), verdict} each, in order. */
    static String summary(java.util.List<int[]> soft) {
        if (soft.isEmpty()) return "";
        boolean same = true, numbered = true;
        for (int[] f : soft) { same &= f[1] == soft.get(0)[1]; numbered &= f[0] > 0; }
        String why = same ? lower(words(soft.get(0)[1])) : "";
        if (!numbered) return soft.size() == 1 ? "A photo just taken: " + why : soft.size() + " photos just taken came out soft";
        StringBuilder b = new StringBuilder(soft.size() == 1 ? "Frame " : "Frames ");
        for (int i = 0; i < soft.size(); i++) {
            if (i > 0) b.append(i == soft.size() - 1 ? " and " : ", ");
            b.append(soft.get(i)[0]);
        }
        return same ? b.append(": ").append(why).toString() : b.append(" came out soft").toString();
    }

    /** "Missed focus" -> "missed focus"; "Blurred: the camera moved" -> "blurred, the camera moved". */
    private static String lower(String w) {
        return w.length() == 0 ? w : (Character.toLowerCase(w.charAt(0)) + w.substring(1)).replace(":", ",");
    }

    /** The scores for the log, in hundredths, row by row ("--": too few edges). */
    static String scores(int[] scores) {
        StringBuilder b = new StringBuilder(80);
        for (int k = 0; k < CELLS * CELLS; k++) {
            if (k > 0) b.append(k % CELLS == 0 ? " / " : " ");
            b.append(scores[k] < 0 ? "--" : String.valueOf(Math.min(99, (scores[k] + 5) / 10)));
        }
        return b.toString();
    }
}
