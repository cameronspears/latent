package com.latent.camera;

/**
 * Host tests of the app's small rules (tools/roll-test.sh runs them): the backlight (when the screen
 * dims, rests and wakes, and which press only wakes it), the save's settle (Sony's 300 ms kept while
 * the darkroom looks for the photo), the viewer's slots at the list's ends, the thumbnail cache's cap,
 * Photos' contact sheet (rolls on rows of their own, the keys, the pages, 100%'s edges) and its caption.
 */
public class LogicTest {
    public static void main(String[] a) throws Exception {
        backlight();
        settle();
        slots();
        sheet();
        caption();
        System.out.println("LOGIC OK");
    }

    /** Photos' contact sheet: rolls on rows of their own, the keys across rolls and rows, the pages, the 100% view's edges. */
    static void sheet() {
        // three rolls, newest first: 7 frames (rows of 5 and 2), 12 (5, 5, 2), 9 (5, 4): seven rows, three pages
        Photos.Sheet s = new Photos.Sheet(new int[] { 7, 12, 9 });
        check(s.count() == 28 && s.rows() == 7 && s.pages() == 3, "three rolls: 28 frames on 7 rows, 3 pages of 3 rows");
        check(s.row(0) == 0 && s.row(4) == 0 && s.row(5) == 1 && s.row(6) == 1 && s.row(7) == 2 && s.row(18) == 4 && s.row(19) == 5 && s.row(27) == 6,
                "each roll starts a row of its own");
        check(s.col(6) == 1 && s.col(7) == 0 && s.col(12) == 0 && s.col(19) == 0 && s.col(24) == 0, "columns count from each row's start");
        check(s.step(6, 1) == 7 && s.step(7, -1) == 6, "LEFT and RIGHT go from one roll into the next");
        check(s.step(0, -1) == 0 && s.step(27, 1) == 27, "and stay at either end");
        check(s.vertical(1, 1) == 6 && s.vertical(6, -1) == 1, "UP and DOWN keep the column");
        check(s.vertical(4, 1) == 6, "into a shorter row: its last frame");
        check(s.vertical(6, 1) == 8, "into the next roll's first row, the same column");
        check(s.vertical(2, -1) == 2 && s.vertical(26, 1) == 26, "at the top and the foot: where it is");
        check(s.page(11) == 0 && s.page(12) == 1 && s.page(19) == 1 && s.page(24) == 2, "three rows a page");
        check(s.pageStart(0) == 0 && s.pageEnd(0) == 12 && s.pageStart(1) == 12 && s.pageEnd(1) == 24 && s.pageStart(2) == 24 && s.pageEnd(2) == 28,
                "each page's frames");
        Photos.Sheet none = new Photos.Sheet(new int[0]), empty = new Photos.Sheet(new int[] { 0, 3 });
        check(none.count() == 0 && none.pages() == 0 && none.step(0, 1) == 0 && none.vertical(0, 1) == 0 && none.page(0) == 0, "nothing developed: nothing to move to");
        check(empty.rows() == 1 && empty.row(2) == 0, "a roll with no developed frames takes no row");

        // the page as Ui draws it: each roll's first row on the page carries its canister, a roll carried on too
        final int[] rollOf = new int[28];
        for (int i = 0; i < 28; i++) rollOf[i] = i < 7 ? 12 : i < 19 ? 11 : 9;
        Photos.Frames f = new Photos.Frames() {
            public Object thumb(int i) { return null; }
            public int number(int i) { return i < 7 ? 7 - i : i < 19 ? 19 - i : 28 - i; }
            public int roll(int i) { return rollOf[i]; }
            public UiState.Film film(int i) { UiState.Film x = new UiState.Film(); x.title = "Roll " + rollOf[i]; return x; }
        };
        UiState u = new UiState();
        Photos.page(u, s, 9, f);
        check(u.cells.size() == 12 && u.cellSel == 9 && u.cells.get(9).number == 10 && u.cells.get(9).row == 2 && u.cells.get(9).col == 2,
                "page one: 12 cells, the chosen one on the third row");
        check(u.cells.get(0).rollNumber == 12 && u.cells.get(7).rollNumber == 11 && u.cells.get(1).roll == null && u.cells.get(8).roll == null,
                "the canister on each roll's first cell");
        Photos.page(u, s, 20, f);
        check(u.cells.size() == 12 && u.cellSel == 8 && u.cells.get(0).rollNumber == 11 && u.cells.get(0).row == 0 && u.cells.get(7).rollNumber == 9,
                "page two: the roll carried on from page one marked again, then the next");

        // the 100% view never passes the photo's edge
        check(Photos.pan(0, 1, 427, 854, 6000) == 427 && Photos.pan(2300, 1, 427, 854, 6000) == 2573 && Photos.pan(-2573, -1, 427, 854, 6000) == -2573,
                "100%: half a screen a step, up to the photo's edge");
        check(Photos.pan(0, 1, 240, 480, 4000) == 240 && Photos.pan(1760, 1, 240, 480, 4000) == 1760, "and down to its foot");
        check(Photos.pan(0, 1, 427, 854, 640) == 0, "a photo smaller than the screen stays in the middle");
    }

    /** What the viewer's caption says from EXIF's strings, and that odd ones say nothing. */
    static void caption() {
        check(Photos.date("2026:10:12 14:03:22").equals("12 Oct 2026") && Photos.date("2026-01-05 09:00").equals("5 Jan 2026"), "the date it was taken");
        check(Photos.date("0000:00:00 00:00:00").equals("") && Photos.date("    :  :     ").equals("") && Photos.date(null).equals("")
                && Photos.date("2026:13:01").equals("") && Photos.date("garbage").equals(""), "a date the camera never set, or none: nothing");
        check(Photos.exposure("0.004", "5.6", "400").equals("1/250  F5.6  ISO 400"), "shutter, aperture and ISO, as the viewfinder writes them: "
                + Photos.exposure("0.004", "5.6", "400"));
        check(Photos.exposure("1/60", "28/10", "3200").equals("1/60  F2.8  ISO 3200"), "rationals too");
        check(Photos.exposure("0.016667", "8.0", "100").equals("1/60  F8  ISO 100") && Photos.exposure("2.5", "11", "").equals("2.5\"  F11")
                && Photos.exposure("30", null, null).equals("30\"") && Photos.exposure("0.5", "22.0", "6400, 0").equals("0.5\"  F22  ISO 6400"),
                "long exposures in seconds, whole f-numbers plain");
        check(Photos.exposure(null, null, null).equals("") && Photos.exposure("0", "0", "0").equals("") && Photos.exposure("x/0", "NaN", "-5").equals("")
                && Photos.exposure("1/0", "Infinity", "abc").equals(""), "odd tags say nothing");
        check(Photos.shutter(1 / 4000.0).equals("1/4000") && Photos.shutter(0.25).equals("1/4") && Photos.shutter(0.3).equals("0.3\"") && Photos.shutter(1).equals("1\""),
                "the shutter's steps");
    }

    /** The exporter's thread ends no sooner than EXPORT_SETTLE_MS after its release was posted, however the time is spent. */
    static void settle() throws Exception {
        final long[] endedAt = { 0 };
        final int[] ends = { 0 };
        Runnable end = new Runnable() { public void run() { endedAt[0] = System.nanoTime(); ends[0]++; } };
        long gapMs = HardwareDevelop.EXPORT_SETTLE_MS;
        {   // nothing to do meanwhile: the whole settle is waited, as v0.44 slept it
            long from = System.nanoTime();
            HardwareDevelop.Settle s = new HardwareDevelop.Settle(from, end);
            long waited = s.finish();
            long gap = (endedAt[0] - from) / 1000000L;
            check(ends[0] == 1 && gap >= gapMs && gap < gapMs + 250 && waited >= gapMs - 5, "no work meanwhile: ended " + gap + " ms after the release (waited " + waited + ")");
            s.finish();
            check(ends[0] == 1, "finished twice: the thread is ended once");
        }
        {   // the find took part of it: only the rest is waited, the gap is still kept
            ends[0] = 0;
            long from = System.nanoTime();
            HardwareDevelop.Settle s = new HardwareDevelop.Settle(from, end);
            Thread.sleep(180);   // looking for the new photo on the card
            long waited = s.finish();
            long gap = (endedAt[0] - from) / 1000000L;
            check(ends[0] == 1 && gap >= gapMs && waited <= gapMs - 150, "a 180 ms find meanwhile: ended " + gap + " ms after the release, " + waited + " ms of it waited");
        }
        {   // the find took longer than the settle: nothing more is waited
            ends[0] = 0;
            long from = System.nanoTime();
            HardwareDevelop.Settle s = new HardwareDevelop.Settle(from, end);
            Thread.sleep(gapMs + 60);
            long waited = s.finish();
            long gap = (endedAt[0] - from) / 1000000L;
            check(ends[0] == 1 && gap >= gapMs && waited <= 2, "a find longer than the settle: ended at once (" + gap + " ms, waited " + waited + ")");
        }
        boolean ok = true;
        for (int i = 0; i < 40 && ok; i++) {   // the minimum holds to the millisecond, at any point it's asked
            long from = System.nanoTime();
            Thread.sleep(i * 9 % 330);
            HardwareDevelop.waitOut(from, gapMs);
            ok = System.nanoTime() - from >= gapMs * 1000000L;
        }
        check(ok, "waitOut never ends early (40 tries)");
    }

    static void slots() {
        check(java.util.Arrays.equals(Slots.wanted(5, 12), new int[] { 5, 6, 4 }), "the viewer decodes the frame, then the next, then the one before");
        check(java.util.Arrays.equals(Slots.wanted(0, 12), new int[] { 0, 1 }), "at the first frame: nothing before it");
        check(java.util.Arrays.equals(Slots.wanted(11, 12), new int[] { 11, 10 }), "at the last frame: nothing after it");
        check(java.util.Arrays.equals(Slots.wanted(0, 1), new int[] { 0 }), "a roll of one frame: just it");
        check(Slots.wanted(0, 0).length == 0, "no frames: nothing");
        check(Slots.kept(4, 5) && Slots.kept(6, 5) && Slots.kept(5, 5) && !Slots.kept(3, 5) && !Slots.kept(7, 5), "kept: the frame on show and one either side");
        java.util.Map<String, Object> m = Slots.recent(3);
        Object a = new Object(), b = new Object(), c = new Object(), d = new Object();
        m.put("a", a); m.put("b", b); m.put("c", c);
        m.get("a");   // drawn again: the most recent now
        m.put("d", d);
        check(m.size() == 3 && m.containsKey("a") && !m.containsKey("b") && m.containsKey("c") && m.containsKey("d"), "the cache keeps its cap, dropping the least recently drawn");
        m.put("c", c);
        m.put("e", new Object());
        check(m.size() == 3 && !m.containsKey("a") && m.containsKey("c"), "putting an entry again counts as using it");
    }

    static void backlight() {
        final float FULL = Backlight.FULL, DARK = Backlight.DARK, REST = Backlight.REST;
        final long R = Backlight.REST_AFTER_MS, P = Backlight.PEEK_MS;
        {   // a still screen rests after a while untouched; the first press then only wakes it
            Backlight b = new Backlight(1000);
            b.still(true, 1000);
            check(b.level(1000) == FULL && b.next(1000) == 1000 + R, "a still screen starts lit, with its rest timer set");
            check(b.level(1000 + R - 1) == FULL && b.level(1000 + R) == REST, "it rests after " + R / 1000 + " s without a press");
            check(b.next(1000 + R) == -1, "resting, nothing more by time alone");
            check(b.press(1000 + R + 5, Backlight.PRESS) && b.level(1000 + R + 5) == FULL, "the first press on a rested screen only wakes it");
            check(!b.press(1000 + R + 10, Backlight.PRESS), "the next press acts");
            check(b.next(1000 + R + 10) == 1000 + R + 10 + R, "each press moves the rest timer on");
        }
        {   // the shutter wakes and acts
            Backlight b = new Backlight(0);
            b.still(true, 0);
            check(b.level(R) == REST && !b.press(R, Backlight.SHUTTER) && b.level(R) == FULL, "a half-press on a rested screen wakes it and acts");
        }
        {   // the viewfinder and the viewer never rest
            Backlight b = new Backlight(0);
            b.still(false, 0);
            check(b.level(10 * R) == FULL && b.next(0) == -1 && !b.press(10 * R, Backlight.PRESS), "a screen that isn't still never rests");
            b.still(true, 10 * R);
            check(b.level(10 * R) == FULL && b.level(11 * R) == REST, "back on a still screen: a fresh rest timer");
            b.still(false, 11 * R);
            check(b.level(11 * R) == FULL, "off a still screen (the viewfinder opening): lit at once");
        }
        {   // the darkroom: its own light, a peek on the wheel or an arrow, nothing swallowed
            Backlight b = new Backlight(0);
            b.still(true, 0);
            b.work(true, 0);
            check(b.level(0) == DARK && b.level(10 * R) == DARK && b.next(0) == -1, "working: the darkroom's light, never the rest level");
            check(!b.press(100, Backlight.PRESS) && b.level(100) == DARK, "a press in the darkroom acts (MENU pauses) and doesn't light it");
            check(!b.press(200, Backlight.PEEK) && b.level(200) == FULL && b.next(200) == 200 + P, "the wheel or an arrow brightens it for " + P / 1000 + " s");
            check(b.level(200 + P - 1) == FULL && b.level(200 + P) == DARK, "then back to the darkroom's light");
            b.press(1000, Backlight.PEEK);
            b.press(3000, Backlight.PEEK);
            check(b.level(3000 + P - 1) == FULL && b.level(3000 + P) == DARK, "another turn keeps it bright, from that turn");
            check(!b.press(4000, Backlight.SHUTTER), "the shutter acts in the darkroom (it pauses, then shoots)");
        }
        {   // a roll developed with nobody there stays dark when it ends; the first press only wakes it
            Backlight b = new Backlight(0);
            b.press(500, Backlight.PRESS);   // the press that started it
            b.work(true, 1000);
            check(b.developEnded(60000, 1000), "no press since the develop started: it rests");
            b.still(true, 60100);
            check(b.level(60100) == DARK && b.level(60100 + 10 * R) == DARK && b.next(60100) == -1, "resting: the darkroom's light until a press");
            check(b.press(70000, Backlight.PRESS) && b.level(70000) == FULL, "the first press only wakes it");
            check(!b.press(70100, Backlight.PRESS), "then presses act");
        }
        {   // ... except the shutter, which acts
            Backlight b = new Backlight(0);
            b.work(true, 1000);
            b.developEnded(60000, 1000);
            b.still(true, 60000);
            check(!b.press(61000, Backlight.SHUTTER) && b.level(61000) == FULL, "the shutter wakes a resting screen and acts");
        }
        {   // someone watched: it lights up when it ends, with a fresh rest timer
            Backlight b = new Backlight(0);
            b.still(true, 0);
            b.work(true, 1000);
            b.press(2000, Backlight.PEEK);
            check(!b.developEnded(90000, 1000), "a press during the develop: it doesn't rest");
            check(b.level(90000) == FULL && b.level(90000 + R) == REST, "lit at the end, resting a while after");
        }
        {   // long work that ends (the darkroom made ready, then not needed): its light while it runs, lit after
            Backlight b = new Backlight(0);
            b.still(true, 0);
            b.work(true, 5000);
            check(b.level(5000 + 2 * R) == DARK, "working, whatever the rest timer");
            b.work(false, 5000 + 2 * R);
            check(b.level(5000 + 2 * R) == FULL && b.next(5000 + 2 * R) == 5000 + 3 * R, "lit when it ends, a fresh rest timer");
            check(!b.press(5000 + 2 * R + 1, Backlight.PRESS), "a press right after acts");
        }
        {   // back on screen: lit
            Backlight b = new Backlight(0);
            b.work(true, 0);
            b.developEnded(1000, 0);
            b.shown(2000);
            b.still(true, 2000);
            check(b.level(2000) == FULL && !b.press(2001, Backlight.PRESS), "Latent back on screen: lit, and presses act");
        }
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  pass  " : "  FAIL  ") + what);
        if (!ok) System.exit(1);
    }
}
