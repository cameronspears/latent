package com.latent.camera;

/**
 * Host tests of the app's small rules (tools/roll-test.sh runs them): the backlight (when the screen
 * dims, rests and wakes, and which press only wakes it), the save's settle (Sony's 300 ms kept while
 * the darkroom looks for the photo), the viewer's slots at the roll's ends, the thumbnail cache's cap.
 */
public class LogicTest {
    public static void main(String[] a) throws Exception {
        backlight();
        settle();
        slots();
        System.out.println("LOGIC OK");
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
        {   // a Lab save or a test: the darkroom's light while it runs, lit after
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
