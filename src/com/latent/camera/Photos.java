package com.latent.camera;

import java.util.ArrayList;
import java.util.List;

/**
 * Photos' rules (v0.45.0), kept apart so the Mac can test them (tests/LogicTest): how every developed frame lies
 * on the contact sheet, grouped by roll, and where the keys take the choice; where the viewer's 100% view may go;
 * and what a photo's EXIF says, in the caption's words. Plain arithmetic and strings: nothing here touches the card.
 */
final class Photos {
    private Photos() {}

    /**
     * The contact sheet: every developed frame in one list, newest first, grouped by roll as a Fuji contact sheet
     * is, each roll starting a row of its own, COLS frames a row, ROWS rows a sheet (a page). The wheel, LEFT and
     * RIGHT take the choice a frame along the list (out of one roll into the next); UP and DOWN a row, to the
     * frame in the same column, or the row's last when it is shorter.
     */
    static final class Sheet {
        static final int COLS = 5, ROWS = 3;
        private final int[] rowStart;   // each row's first frame, and after the last row the count

        /** sizes: each roll's frames, in the order shown (a roll of none takes no row). */
        Sheet(int[] sizes) {
            int rows = 0;
            for (int n : sizes) rows += (Math.max(0, n) + COLS - 1) / COLS;
            rowStart = new int[rows + 1];
            int r = 0, at = 0;
            for (int n : sizes) {
                for (int k = 0; k < n; k += COLS) rowStart[r++] = at + k;
                at += Math.max(0, n);
            }
            rowStart[rows] = at;
        }

        int count() { return rowStart[rowStart.length - 1]; }
        int rows() { return rowStart.length - 1; }
        int pages() { return (rows() + ROWS - 1) / ROWS; }

        /** The row frame i is on. */
        int row(int i) {
            int lo = 0, hi = rows() - 1;
            while (lo < hi) {   // the last row starting at or before i
                int mid = (lo + hi + 1) >>> 1;
                if (rowStart[mid] <= i) lo = mid; else hi = mid - 1;
            }
            return lo;
        }

        int col(int i) { return i - rowStart[row(i)]; }
        int page(int i) { return rows() == 0 ? 0 : row(i) / ROWS; }

        /** The page's first frame, and one past its last. */
        int pageStart(int p) { return rowStart[Math.max(0, Math.min(rows(), p * ROWS))]; }
        int pageEnd(int p) { return rowStart[Math.max(0, Math.min(rows(), (p + 1) * ROWS))]; }

        /** A frame along the list (d: -1 or 1), staying at either end. */
        int step(int i, int d) { return count() == 0 ? 0 : Math.max(0, Math.min(count() - 1, i + d)); }

        /** A row up (d -1) or down (1), to the same column or that row's last frame; at the top or the foot, where it is. */
        int vertical(int i, int d) {
            if (count() == 0) return 0;
            int r = row(i) + d;
            if (r < 0 || r >= rows()) return i;
            return Math.min(rowStart[r] + col(i), rowStart[r + 1] - 1);
        }
    }

    /** What the sheet needs to know of frame i of the list (MainActivity's frames, or the previews' made-up ones). */
    interface Frames {
        Object thumb(int i);           // its thumbnail, or null while it loads
        int number(int i);             // its number on its roll
        int roll(int i);               // its roll's number: frames of one roll lie together
        UiState.Film film(int i);      // its film
    }

    /**
     * The page frame 'sel' is on, into s as Ui draws it: each frame's cell (its row and column on the page, its
     * number and thumbnail), and on the first row of each roll there, the roll's film and number (its canister
     * marks where the roll starts, or where it carries on from the page before).
     */
    static void page(UiState s, Sheet sheet, int sel, Frames f) {
        int p = sheet.page(sel), from = sheet.pageStart(p), to = sheet.pageEnd(p), top = p * Sheet.ROWS;
        List<UiState.Cell> cells = new ArrayList<UiState.Cell>(to - from);
        for (int i = from; i < to; i++) {
            UiState.Cell c = new UiState.Cell();
            c.thumb = f.thumb(i);
            c.number = f.number(i);
            c.row = sheet.row(i) - top;
            c.col = sheet.col(i);
            if (i == from || f.roll(i - 1) != f.roll(i)) { c.roll = f.film(i); c.rollNumber = f.roll(i); }
            cells.add(c);
        }
        s.cells = cells;
        s.cellSel = sel >= from && sel < to ? sel - from : -1;
    }

    // ------------------------------------------------------------------------------ the viewer at 100%

    /**
     * The 100% view moved a step (dir -1 or 1) of 'step' photo pixels from 'at', its centre's offset from the
     * photo's centre along one side: never past the photo's edge, so a view of 'view' pixels stays inside a photo
     * of 'size' (one smaller than the view stays centred).
     */
    static int pan(int at, int dir, int step, int view, int size) {
        int max = Math.max(0, (size - view) / 2);
        return Math.max(-max, Math.min(max, at + dir * step));
    }

    // ------------------------------------------------------------------------------ the caption

    private static final String[] MONTHS = { "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };

    /** "12 Oct 2026" from EXIF's "2026:10:12 14:03:22"; "" when it doesn't read as a date (a camera never set: "0000:00:00"). */
    static String date(String exif) {
        if (exif == null) return "";
        String t = exif.trim();
        if (t.length() < 10 || t.charAt(4) != t.charAt(7) || (t.charAt(4) != ':' && t.charAt(4) != '-')) return "";
        try {
            int y = Integer.parseInt(t.substring(0, 4)), m = Integer.parseInt(t.substring(5, 7)), d = Integer.parseInt(t.substring(8, 10));
            if (y < 1990 || y > 2200 || m < 1 || m > 12 || d < 1 || d > 31) return "";
            return d + " " + MONTHS[m - 1] + " " + y;
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /**
     * The exposure in the viewfinder's words, "1/250  F5.6  ISO 400", from EXIF's ExposureTime (seconds), FNumber
     * and ISOSpeedRatings as ExifInterface gives them ("0.004" or "1/250", "5.6" or "56/10", "400"). Each part only
     * when it reads as something a camera could have used; "" when none does.
     */
    static String exposure(String time, String fNumber, String iso) {
        StringBuilder b = new StringBuilder();
        String t = shutter(number(time));
        if (t.length() > 0) b.append(t);
        double f = number(fNumber);
        if (f >= 0.7 && f < 100) {
            if (b.length() > 0) b.append("  ");
            double r = Math.round(f * 10) / 10.0;
            b.append('F').append(r >= 10 || r == Math.floor(r) ? String.valueOf(Math.round(r)) : String.valueOf(r));
        }
        double i = number(iso);
        if (i >= 25 && i <= 1000000) {
            if (b.length() > 0) b.append("  ");
            b.append("ISO ").append(Math.round(i));
        }
        return b.toString();
    }

    /** A shutter time as the camera shows it: "1/250", "0.5\"", "2\"", "30\""; "" for none. */
    static String shutter(double s) {
        if (!(s > 0) || s > 3600) return "";
        if (s >= 0.3) {   // from 0.3 s the camera counts in seconds
            double r = Math.round(s * 10) / 10.0;
            return (r == Math.floor(r) ? String.valueOf(Math.round(r)) : String.valueOf(r)) + "\"";
        }
        return "1/" + Math.round(1 / s);
    }

    /** A number as EXIF's strings give it: "0.004", "1/250", "28/10", "400" (the first of several); NaN if it isn't one. */
    static double number(String v) {
        if (v == null) return Double.NaN;
        String t = v.trim();
        int sep = t.indexOf(',');
        if (sep >= 0) t = t.substring(0, sep).trim();
        try {
            int slash = t.indexOf('/');
            if (slash < 0) return Double.parseDouble(t);
            double n = Double.parseDouble(t.substring(0, slash).trim()), d = Double.parseDouble(t.substring(slash + 1).trim());
            return d == 0 ? Double.NaN : n / d;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
