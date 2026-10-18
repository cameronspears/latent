package com.latent.camera;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Host test of the viewfinder's logic: the shutter button (Release), the pictures the card's listing
 * doesn't show yet (Library.withShots), the exposure readout, and
 * (v0.45.0) when frames are asked for (FrameScheduler), the pace (Pacer), the store quiet, the /proc
 * readings, the catalogue's new pictures, the overlay's bands, and the presenter's state going out of date
 * (the film card and a note gone on time, frames or none); (v0.47.0) where focus is drawn (Focus), the focus check's
 * verdicts on the card's own scores, what the camera back says after it, and the steady shutter (Steady).
 *
 *   ViewfinderTest <empty dir>
 */
public class ViewfinderTest {
    public static void main(String[] a) throws Exception {
        release();
        always();
        shots(new File(a[0]));
        words();
        exposure();
        shutterOrder();
        scheduler();
        quiet();
        schedulerRun();
        pacer();
        procParsing();
        catalogue();
        overlayBands();
        presenterClears();
        tickClears();
        stages();
        focusAreas();
        focusVerdicts();
        focusLater(new File(a[0]));
        steady();
        System.out.println("ViewfinderTest: all passed");
    }

    /** VF-04: a press held through focus is taken inside focused(), so the viewfinder reads exposure only when it wasn't. */
    static void shutterOrder() {
        Cam c = new Cam();
        Release r = new Release(c, true);
        r.halfDown();
        r.fullDown();
        c.take();
        r.focused(true);
        check(r.shooting && c.take().equals("S"), "pressed through focus: the picture is asked for inside focused() (no exposure read before it)");
        r.shot(true);
        c.take();
        r.halfUp();
        r.halfDown();
        c.take();
        r.focused(true);
        check(!r.shooting && c.take().equals(""), "a lock with no press: nothing taken, so the exposure is read then");
    }

    /** VF-07: the frame scheduler: a freed look thread fed at once, the spacing kept, core order 2, 1, 3, nothing in the quiet. */
    static void scheduler() {
        FrameScheduler s = new FrameScheduler();
        boolean[] busy = { false, false, false };
        long m = Viewfinder.MIN_FRAME_MS;
        check(s.next(1000, 0, busy, false, false, m) == FrameScheduler.LOOK && s.looker == 0, "all free: core 2 (the first) gets the frame");
        busy[0] = true;
        check(s.next(1010, 1000, busy, false, false, m) == FrameScheduler.WAIT && s.wakeAt == 1000 + m,
                "core 1 free but only 10 ms since the last: wait to exactly " + m + " ms after it");
        check(s.next(1000 + m - 1, 1000, busy, false, false, m) == FrameScheduler.WAIT, "one ms short of the spacing: still waits");
        check(s.next(1000 + m, 1000, busy, false, false, m) == FrameScheduler.LOOK && s.looker == 1, "at the spacing: core 1");
        busy[1] = true;
        check(s.next(1200, 1100, busy, false, false, m) == FrameScheduler.LOOK && s.looker == 2, "cores 2 and 1 busy: core 3, Latent's own");
        busy[2] = true;
        check(s.next(1300, 1200, busy, false, false, m) == FrameScheduler.WAIT && s.wakeAt == 0, "all busy: no wake-up, the next frame goes when one finishes");
        busy[1] = false;
        check(s.next(1301, 1200, busy, false, false, m) == FrameScheduler.LOOK && s.looker == 1, "core 1 finishes: fed at once (no poll in between)");
        busy[0] = false;
        busy[1] = false;
        busy[2] = false;
        check(s.next(5000, 0, busy, true, false, m) == FrameScheduler.WAIT, "a picture storing (the quiet): nothing asked of the camera");
        check(s.next(5000, 4700, busy, false, true, m) == FrameScheduler.WAIT && s.wakeAt == 4700 + FrameScheduler.DRAIN_MS,
                "hidden or the look off: no look, the next drain " + FrameScheduler.DRAIN_MS + " ms after the last");
        check(s.next(5100, 4700, busy, false, true, m) == FrameScheduler.DRAIN, "... then one frame taken and dropped");
        check(s.next(5100, 4700, busy, true, true, m) == FrameScheduler.WAIT, "hidden and storing: not even a drain");
        check(s.next(5250, 5000, busy, false, false, 250) == FrameScheduler.LOOK && s.next(5100, 5000, busy, false, false, 250) == FrameScheduler.WAIT
                && s.wakeAt == 5250, "the still pace: frames " + 250 + " ms apart");
    }

    /** VF-08: the quiet after a shot is exactly v0.44's with the default floor (1.5 s, to the catalogue, at most 3 s). */
    static void quiet() {
        long quietMs = Viewfinder.STORE_QUIET_MS;
        check(Viewfinder.QUIET_FLOOR_MS == quietMs && Viewfinder.QUIET_CAP_MS == 2 * quietMs, "the default floor and cap are v0.44's 1.5 s and 3 s");
        int differ = 0, cases = 0;
        for (long answer = 1000; answer <= 9000; answer += 4000) {
            for (int phase = 0; phase < 3; phase++) {
                // 0: pressed, no answer yet; 1: answered, taken; 2: answered, cancelled
                long press = answer - 300;
                long oldUntil = phase == 0 ? press + quietMs + Viewfinder.SHUTTER_TIMEOUT_MS : phase == 1 ? answer + quietMs : 0;
                long until = phase == 0 ? press + quietMs + Viewfinder.SHUTTER_TIMEOUT_MS : phase == 1 ? answer + Viewfinder.QUIET_FLOOR_MS : 0;
                long cap = phase == 0 ? until + quietMs : phase == 1 ? answer + Viewfinder.QUIET_CAP_MS : 0;
                for (int listed = 0; listed < 2; listed++) {
                    int shots = phase == 1 ? 1 : 0, stored = phase == 1 ? listed : 0;
                    for (long now = 0; now < 16000; now += 7) {
                        boolean old = now < oldUntil || (stored < shots && now < oldUntil + quietMs);
                        boolean neu = FrameScheduler.quiet(now, until, stored >= shots, cap);
                        cases++;
                        if (old != neu) differ++;
                    }
                }
            }
        }
        check(differ == 0, "the quiet decided as v0.44 decided it, in " + cases + " cases");
        check(FrameScheduler.quiet(2000, 2500, true, 4000) && !FrameScheduler.quiet(2600, 2500, true, 4000), "listed: frames again once the floor has passed");
        check(FrameScheduler.quiet(3500, 2500, false, 4000) && !FrameScheduler.quiet(4000, 2500, false, 4000), "not listed: on until the cap, never longer");
    }

    /**
     * The scheduler driving three looks of 140 ms each on a pretend clock, fed when one finishes (event-driven),
     * with the camera giving a frame every 33 ms: the spacing is never broken and a free thread never waits longer
     * than the spacing and the camera's next frame.
     */
    static void schedulerRun() {
        FrameScheduler s = new FrameScheduler();
        boolean[] busy = new boolean[3];
        long[] doneAt = new long[3];
        long lastGrab = -1000, minGap = Long.MAX_VALUE, worstWait = 0;
        long[] freeSince = { 0, 0, 0 };
        int frames = 0, byCore3 = 0;
        for (long now = 0; now < 10000; now++) {
            for (int i = 0; i < 3; i++) if (busy[i] && now >= doneAt[i]) { busy[i] = false; freeSince[i] = now; }
            int act = s.next(now, lastGrab, busy, false, false, Viewfinder.MIN_FRAME_MS);
            if (act != FrameScheduler.LOOK) continue;
            if (now / 33 == lastGrab / 33) continue;   // the camera has no newer frame yet (the grab comes back empty)
            if (lastGrab >= 0) minGap = Math.min(minGap, now - lastGrab);
            worstWait = Math.max(worstWait, now - Math.max(freeSince[s.looker], lastGrab + Viewfinder.MIN_FRAME_MS));
            lastGrab = now;
            busy[s.looker] = true;
            doneAt[s.looker] = now + (s.looker == 2 ? 200 : 140);
            frames++;
            if (s.looker == 2) byCore3++;
        }
        check(minGap >= Viewfinder.MIN_FRAME_MS, "frames never closer than " + Viewfinder.MIN_FRAME_MS + " ms (closest " + minGap + ")");
        check(worstWait <= 33, "a free thread waits at most for the camera's next frame (" + worstWait + " ms)");
        check(frames >= 180 && byCore3 > 0 && byCore3 < frames / 3, frames + " frames in 10 s, " + byCore3 + " of them on core 3 (only when 2 and 1 are busy)");
    }

    /** VF-14: the pace: full rate while anything moves or is touched; slow once still and untouched; back at once. */
    static void pacer() {
        Pacer p = new Pacer();
        int[] still = new int[FilmCore.FI_SIG_N];
        for (int i = 0; i < still.length; i++) still[i] = 40 + i;
        long t = 0;
        p.input(t);
        for (; t < Pacer.IDLE_MS - 100; t += 90) p.whole(t, still.clone());
        check(!p.slow && p.spacing(45) == 45, "a still scene, but touched less than " + Pacer.IDLE_MS / 1000 + " s ago: full rate");
        boolean changed = false;
        for (; t < Pacer.IDLE_MS + 500 && !changed; t += 90) changed = p.whole(t, still.clone());
        check(p.slow && changed && p.spacing(45) == Pacer.SLOW_MS && p.ref != null && p.takeRefChange(),
                "still and untouched: slow, a check every " + Pacer.SLOW_MS + " ms, the reference handed over");
        int whole = 0, checks = 0;
        long from = t;
        for (; t < from + 5000; t += Pacer.SLOW_MS) { if (p.check(t)) checks++; else whole++; }
        check(whole >= 4 && whole <= 5 && checks >= 14, "while slow, a whole frame each second (" + whole + " in 5 s), checks between (" + checks + ")");
        int[] drift = still.clone();
        drift[5] += Pacer.THRESHOLD;
        check(!p.whole(t, drift) && p.slow, "a cell moving by " + Pacer.THRESHOLD + " (the threshold): still");
        int[] moved = still.clone();
        moved[7] += 20;
        check(p.whole(t += 250, moved) && !p.slow && p.ref == null && p.spacing(45) == 45, "a cell moving by 20: full rate at once");
        for (int i = 0; i < 200 && !p.slow; i++) p.whole(t += 90, moved.clone());
        check(p.slow, "still again for " + Pacer.STILL_MS / 1000 + " s, untouched: slow again");
        check(p.input(t += 10) && !p.slow && p.ref == null, "any key: full rate at once");
        check(!p.input(t += 10), "a key at full rate changes nothing");
        // slow changes of light add up: the spell starts again when the scene has moved from where it began
        Pacer q = new Pacer();
        q.input(0);
        int[] creep = still.clone();
        long u = Pacer.IDLE_MS;
        for (int i = 0; i < 40; i++) { for (int c = 0; c < creep.length; c++) creep[c] += 1; q.whole(u += 100, creep.clone()); }
        check(!q.slow, "a scene brightening a level every frame never counts as still");
        check(!Pacer.moved(still, still.clone()) && Pacer.moved(still, null) && Pacer.moved(still, new int[3]), "moved(): same, none, a different size");
    }

    /** VF-09: /proc read without a regex, the same numbers as v0.44's split. */
    static void procParsing() {
        String stat = "cpu  4705 356 584 3699 23 23 0 0 0\n"
                + "cpu0 1393280 32966 572056 13343292 6130 0 17875 0 0\n"
                + "cpu1 1335 2 101 52113 7 0 3\n"
                + "cpu2\t200 0 50 900\n"
                + "cpu3 77 1 2 3 4\n"
                + "intr 114930548 113199788 3 0 5 263 0 4 [...]\n"
                + "ctxt 1990473\n";
        int same = 0, lines = 0;
        for (String l : stat.split("\n")) {
            long[] mine = Viewfinder.cpuLine(l);
            long[] ref = splitCpu(l);
            lines++;
            if ((mine == null && ref == null) || (mine != null && ref != null && mine[0] == ref[0] && mine[1] == ref[1] && mine[2] == ref[2])) same++;
            else System.out.println("  differs: " + l + " -> " + java.util.Arrays.toString(mine) + " vs " + java.util.Arrays.toString(ref));
        }
        check(same == lines, "/proc/stat: each core's busy and total as the split read them (" + lines + " lines)");
        String self = "1234 (com.latent.camera) S 82 82 0 0 -1 4194624 9010 0 12 0 345 67 0 0 20 0 21 0 1234567 123456789 4567";
        int at = self.lastIndexOf(')') + 2;
        String[] f = self.substring(at).split(" ");
        check(Viewfinder.field(self, at, 11) == Long.parseLong(f[11]) && Viewfinder.field(self, at, 12) == Long.parseLong(f[12])
                && Viewfinder.field(self, at, 5) == -1 && Viewfinder.field(self, at, 99) == -1,
                "/proc/self/stat: utime and stime by field (" + f[11] + ", " + f[12] + "), -1 for no number or no field");
        check(Viewfinder.batteryText(new int[] { 81, 3912, 312 }).equals("81% 3912 mV 31.2 C") && Viewfinder.batteryText(null).equals("?"),
                "battery in words: " + Viewfinder.batteryText(new int[] { 81, 3912, 312 }));
        check(Viewfinder.cpuPartName("0xc09").equals(" (Cortex-A9)") && Viewfinder.cpuPartName("0x123").equals(""), "the CPU part's name");
    }

    /** v0.44's /proc/stat reading (a regex split), the reference for cpuLine. */
    static long[] splitCpu(String l) {
        if (!l.startsWith("cpu") || l.startsWith("cpu ")) return null;
        try {
            String[] f = l.trim().split("\\s+");
            int n = Integer.parseInt(f[0].substring(3));
            long idle = Long.parseLong(f[4]) + (f.length > 5 ? Long.parseLong(f[5]) : 0), sum = 0;
            for (int i = 1; i < f.length && i <= 7; i++) sum += Long.parseLong(f[i]);
            return new long[] { n, sum - idle, sum };
        } catch (Exception e) {
            return null;
        }
    }

    /** VF-10: the pictures new since the baseline, from the catalogue's numbers alone, in order. */
    static void catalogue() {
        int[] listed = { 1004690, 1004695, 1014001, 1004692, 1004693, 1004691 };
        int[] n = Viewfinder.newer(listed, 1004691);
        check(java.util.Arrays.equals(n, new int[] { 1004692, 1004693, 1004695, 1014001 }), "above the baseline, in order (folders too): " + java.util.Arrays.toString(n));
        check(Viewfinder.newer(listed, 1014001).length == 0 && Viewfinder.newer(new int[0], 0).length == 0, "nothing new: none");
    }

    /** VF-17: the overlay's two bands, in its rows, and the check that nothing lies between them. */
    static void overlayBands() {
        check(Viewfinder.overlayRow(Ui.OVL_TOP_END, 480) == Ui.OVL_TOP_END && Viewfinder.overlayRow(Ui.OVL_BOTTOM_START, 480) == Ui.OVL_BOTTOM_START,
                "on this screen the overlay's rows are the layout's (" + Ui.OVL_TOP_END + ", " + Ui.OVL_BOTTOM_START + ")");
        check(Viewfinder.overlayRow(Ui.OVL_BOTTOM_START, 240) == 192 && Viewfinder.overlayRow(600, 480) == 480, "scaled for another height, kept inside it");
        int w = 10, h = 48;
        int[] px = new int[w * h];
        px[3] = 0x10FFFFFF;
        px[(h - 1) * w + 9] = 0xFF000000;
        int top = Viewfinder.overlayRow(Ui.OVL_TOP_END, h), bottom = Viewfinder.overlayRow(Ui.OVL_BOTTOM_START, h);
        check(Viewfinder.clearBetween(px, w, top, bottom), "type in the bands only: nothing between");
        px[top * w] = 0x01000000;
        check(!Viewfinder.clearBetween(px, w, top, bottom), "one faint pixel between the bands is seen (the overlay is then drawn whole)");
    }

    /**
     * Frames coming (60-140 ms apart, as the look threads finish): each goes up under the state last given until
     * that state is out of date (Viewfinder.outOfDateAt), then a fresh one is made. The film card and a note are
     * gone from the picture by the first frame after they end, whatever the frames' timing (v0.45.0 kept the
     * state until a key: a card could stay up over the live view).
     */
    static void presenterClears() {
        long worst = 0;
        int cases = 0;
        for (int phase = 0; phase < 140; phase += 7) for (int seed = 1; seed <= 5; seed++) for (int what = 0; what < 2; what++) {
            long shown = 1000 + phase, until = shown + (what == 0 ? Viewfinder.CARD_MS : Viewfinder.NOTE_MS);
            boolean card = what == 0, on;
            // the card or the note just shown: a fresh state with it
            long stale = card ? Viewfinder.outOfDateAt(true, until, false, 0) : Viewfinder.outOfDateAt(false, 0, true, until);
            long off = -1, t = shown;
            java.util.Random r = new java.util.Random(seed);
            while (t < until + 2000 && off < 0) {
                t += 60 + r.nextInt(81);
                if (t < stale) continue;   // presentAgain: the frame goes up under the state it has
                on = t < until;            // out of date: a fresh state, as fill() makes it
                stale = card ? Viewfinder.outOfDateAt(on, until, false, 0) : Viewfinder.outOfDateAt(false, 0, on, until);
                if (!on) off = t;
            }
            cases++;
            if (off < 0) { check(false, (card ? "the card" : "a note") + " never left the presented state"); return; }
            worst = Math.max(worst, off - until);
        }
        check(worst <= 140, "the card and a note leave the picture by the first frame after they end (" + cases + " timings, at worst "
                + worst + " ms late, a frame's gap at most 140)");
        check(Viewfinder.outOfDateAt(false, 5000, false, 9000) == Long.MAX_VALUE && Viewfinder.outOfDateAt(true, 5000, true, 4000) == 4000,
                "a state with neither never runs out; with both, the first to end");
    }

    /**
     * No frames (a still scene, or the picture through Latent's screen): only the tick gives fresh states. Its runs
     * come 100 ms after the last one ended, late by however long the UI thread took (1-30 ms here). The last state
     * it gives is one without the card, at any lateness: the run after the last moving one gives one more
     * (Viewfinder.tickFresh). The first v0.45.0 rule, moving runs only, left the card up when a run stepped past
     * the 100 ms after its end.
     */
    static void tickClears() {
        int cases = 0, missedBefore = 0;
        boolean ok = true;
        for (int phase = 0; phase < 100; phase += 3) for (int late = 1; late <= 30; late++) {
            long shown = 2000 + phase, until = shown + Viewfinder.CARD_MS, t = shown;
            boolean moved = false, lastOn = true, lastOnBefore = true;
            while (true) {
                boolean m = Viewfinder.moving(t, false, 0, until, 0, -1);
                if (Viewfinder.tickFresh(m, moved)) lastOn = t < until;   // a fresh state: the card in it while it's up
                if (m) lastOnBefore = t < until;
                moved = m;
                if (!m) break;
                t += 100 + late;
            }
            cases++;
            if (lastOn) ok = false;
            if (lastOnBefore) missedBefore++;
        }
        check(ok, "no frames: the tick's last state is without the card at any lateness (" + cases + " timings; moving runs alone missed "
                + missedBefore + ")");
        check(Viewfinder.moving(0, true, 0, 0, 0, -1) && Viewfinder.moving(1000, false, 0, 0, 0, 0) && !Viewfinder.moving(Viewfinder.PRINT_MS + 200, false, 0, 0, 0, 0)
                && !Viewfinder.moving(5000, false, 4000, 4000, 4000, -1), "moving: the scale open, a print developing; nothing once all have ended");
    }

    /** The stats line names each of the image core's stages, as they measure now. */
    static void stages() {
        check(Viewfinder.STAGES.length == FilmCore.TM_COUNT && Viewfinder.STAGES[FilmCore.TM_BLEND].equals("of it one pass")
                && Viewfinder.STAGES[FilmCore.TM_DITHER].equals("type"), "the stats line's stages: " + String.join(", ", Viewfinder.STAGES));
    }

    /** A camera that records what Release asks of it; later() runs are kept to run by hand. */
    static final class Cam implements Release.Camera {
        final StringBuilder did = new StringBuilder();
        final List<Runnable> pending = new ArrayList<Runnable>();
        public void takeButton() { did.append("T"); }
        public void appFocus() { did.append("F"); }
        public void cancelFocus() { did.append("c"); }
        public void shoot() { did.append("S"); }
        public void handBack() { did.append("B"); }
        public void later(Runnable r, long ms) { pending.add(r); }
        void runLater() { List<Runnable> p = new ArrayList<Runnable>(pending); pending.clear(); for (Runnable r : p) r.run(); }
        String take() { String s = did.toString(); did.setLength(0); return s; }
    }

    static void release() {
        // the camera's own button: it focuses and shoots by itself; Latent follows its reports
        Cam c = new Cam();
        Release r = new Release(c, false);
        r.halfDown();
        check(r.state == Release.SEARCHING && !r.app && c.take().equals(""), "a half-press: the camera focuses by itself");
        r.cameraFocus(Release.AF_LOCK);
        check(r.state == Release.LOCKED, "the camera's lock shows");
        r.fullDown();
        check(!r.shooting && c.take().equals(""), "a full press: the camera takes it itself, Latent does nothing");
        r.cameraFocus(Release.AF_CLEAR);
        check(r.state == Release.LOCKED, "the camera letting focus go after the picture changes nothing while held");
        r.halfUp();
        check(r.state == Release.READY && c.take().equals(""), "let go: ready, the button still the camera's");
        r.cameraFocus(Release.AF_WORKING);
        check(r.state == Release.SEARCHING, "the camera's reports count even without the key (it has the button)");
        r.cameraFocus(Release.AF_CLEAR);
        check(r.state == Release.READY, "and clear when it lets go");

        // the camera can't lock while held: Latent takes the button and tries again, up to MAX_TRIES
        c = new Cam();
        r = new Release(c, false);
        r.halfDown();
        r.cameraFocus(Release.AF_WARN);
        check(r.state == Release.NO_LOCK && !r.app && c.pending.size() == 1, "the camera couldn't lock: a retry is scheduled");
        c.runLater();
        check(r.app && r.state == Release.SEARCHING && r.tries == 2 && c.take().equals("F"), "Latent takes the button and focuses (try 2)");
        r.cameraFocus(Release.AF_LOCK);
        check(r.state == Release.SEARCHING, "in app mode the camera's own reports are ignored");
        for (int i = 2; i < Release.MAX_TRIES; i++) { r.focused(false); c.runLater(); }
        check(r.tries == Release.MAX_TRIES && r.state == Release.SEARCHING, "tries go on while held");
        r.focused(false);
        c.runLater();
        check(r.state == Release.NO_LOCK && c.pending.isEmpty(), "after " + Release.MAX_TRIES + " tries it rests on can't focus");
        check(c.take().replace("cF", "").equals("c"), "each failed try is cancelled before the next");
        r.halfUp();
        check(!r.app && r.state == Release.READY && c.take().equals("B"), "let go: the button goes back to the camera");

        // a retry, then a lock, then a full press: Latent takes the picture, then hands the button back
        c = new Cam();
        r = new Release(c, false);
        r.halfDown();
        r.cameraFocus(Release.AF_WARN);
        c.runLater();
        r.focused(true);
        check(r.state == Release.LOCKED, "Latent's retry locked");
        r.fullDown();
        check(r.shooting && c.take().equals("FS"), "full press: Latent shoots");
        r.fullDown();
        check(c.take().equals(""), "a second full press while shooting does nothing");
        r.shot(true);
        check(!r.shooting && !r.app && r.state == Release.READY && c.take().equals("B"), "after the picture the button goes back to the camera");
        r.halfUp();
        check(c.take().equals(""), "let go afterwards: nothing more to hand back");

        // focus first in app mode: a full press while searching waits for the lock
        c = new Cam();
        r = new Release(c, false);
        r.halfDown();
        r.cameraFocus(Release.AF_WARN);
        c.runLater();
        r.fullDown();
        check(!r.shooting && r.waiting, "focus first: waits while Latent searches");
        r.focused(true);
        check(r.shooting && c.take().equals("FS"), "focus first: shoots once it locks");

        // focus first, never locks: no picture
        c = new Cam();
        r = new Release(c, false);
        r.halfDown();
        r.cameraFocus(Release.AF_WARN);
        c.runLater();
        r.fullDown();
        for (int i = 0; i < Release.MAX_TRIES; i++) { r.focused(false); c.runLater(); }
        check(!r.shooting && r.refused && !c.take().contains("S"), "focus first: no lock, no picture");
        r.fullDown();
        check(r.state == Release.SEARCHING && r.waiting && r.tries == 1, "another full press starts Latent's focus again");

        // a retry scheduled before the button was let go doesn't run after
        c = new Cam();
        r = new Release(c, false);
        r.halfDown();
        r.cameraFocus(Release.AF_WARN);
        r.halfUp();
        c.runLater();
        check(!r.app && r.state == Release.READY && c.take().equals(""), "no retry once let go");

        // letting go while Latent's picture is taken: it finishes, then the button goes back
        c = new Cam();
        r = new Release(c, false);
        r.halfDown();
        r.cameraFocus(Release.AF_WARN);
        c.runLater();
        r.focused(true);
        r.fullDown();
        r.halfUp();
        check(r.shooting, "the picture finishes after the button is let go");
        r.shot(true);
        check(r.state == Release.READY && !r.app && c.take().equals("FSB"), "then ready, the button the camera's");

        // the roll is full: the button becomes Latent's and takes nothing
        c = new Cam();
        r = new Release(c, false);
        r.lock();
        check(r.app && r.locked && c.take().equals("T"), "full roll: Latent takes the button");
        r.halfDown();
        r.focused(true);
        r.fullDown();
        check(!r.shooting && r.refused && !c.take().contains("S"), "and a full press takes nothing");
        r.halfUp();
        check(r.app && !c.take().contains("B"), "and it never goes back to the camera");
    }

    /** The button always Latent's (no direct shutter): v0.32.0's way, the Shooting test's. */
    static void always() {
        Cam c = new Cam();
        Release r = new Release(c, true);
        check(r.app, "the button is Latent's from the start");
        r.halfDown();
        check(r.state == Release.SEARCHING && r.tries == 1 && c.take().equals("F"), "a half-press is Latent's focus");
        r.focused(false);
        c.runLater();
        check(r.tries == 2 && c.take().equals("cF"), "no lock: tried again while held");
        r.focused(true);
        r.fullDown();
        check(r.shooting && c.take().equals("S"), "locked, full press: Latent's picture");
        r.shot(true);
        check(r.app && r.state == Release.SEARCHING && c.take().equals("cF"), "after it, still held: focus again; never handed back");
        r.halfUp();
        check(r.app && r.state == Release.READY && c.take().equals("c") && !c.did.toString().contains("B"), "let go: focus cancelled, still Latent's button");
        r.cameraFocus(Release.AF_LOCK);
        check(r.state == Release.READY, "the camera's own reports don't move the brackets");
        r.halfDown();
        r.fullDown();
        check(r.waiting && !r.shooting, "focus first: waits for the lock");
        for (int i = 0; i < Release.MAX_TRIES; i++) { r.focused(false); c.runLater(); }
        check(r.refused && !r.shooting, "never locked: no picture");
    }

    static void shots(File card) throws Exception {
        File dcim = new File(card, "DCIM/100MSDCF");
        dcim.mkdirs();
        new File(card, "LATENT").mkdirs();
        touch(new File(dcim, "DSC04690.JPG"), 1700000000000L);
        touch(new File(dcim, "DSC04691.JPG"), 1700000100000L);
        List<File> listed = Library.photos(card);
        check(Library.withShots(card, listed, null) == listed, "no SHOTS.TXT: the listing as it is");

        Library.recordShot(card, new File(dcim, "DSC04691.JPG"));   // the listing has it already
        Library.recordShot(card, new File(dcim, "DSC04692.JPG"));   // taken in the viewfinder, not listed yet
        Library.recordShot(card, new File(dcim, "DSC04693.JPG"));
        Library.recordShot(card, new File(dcim, "DSC04693.JPG"));   // twice: counted once
        java.util.Set<Integer> cat = new java.util.HashSet<Integer>(java.util.Arrays.asList(1004690, 1004691, 1004692, 1004693));
        List<File> all = Library.withShots(card, listed, cat);
        check(all.size() == 4 && all.get(0).getName().equals("DSC04691.JPG") && all.get(2).getName().equals("DSC04692.JPG")
                && all.get(3).getName().equals("DSC04693.JPG"), "the listing, then the pictures it doesn't show yet: " + all);
        check(all.get(2).getParentFile().getName().equals("100MSDCF") && Roll.number(all.get(2)) == 1004692, "by their folder and number");

        Roll r = Roll.create(card, "TRIX400", 12, 0, 1004690);
        check(r.exposures(all).size() == 3, "they are exposures on the roll in the camera");

        File list = new File(card, "LATENT/SHOTS.TXT");
        check(read(list).equals("100MSDCF/DSC04692.JPG\r\n100MSDCF/DSC04693.JPG\r\n"), "SHOTS.TXT keeps only those still missing");
        cat.remove(1004693);   // catalogued for a moment, then lost (the camera hung while storing it)
        all = Library.withShots(card, listed, cat);
        check(all.size() == 3 && all.get(2).getName().equals("DSC04692.JPG") && read(list).equals("100MSDCF/DSC04692.JPG\r\n"),
                "a picture the catalogue doesn't have was never stored: dropped");
        all = Library.withShots(card, listed, null);
        check(all.size() == 3, "the catalogue unreadable: the rest are kept");
        touch(new File(dcim, "DSC04692.JPG"), 1700000200000L);
        all = Library.withShots(card, Library.photos(card), null);
        check(all.size() == 3 && !list.exists(), "once the listing shows it, SHOTS.TXT goes");
    }

    static void words() {
        check(Viewfinder.shutterText(1, 125).equals("1/125"), "1/125");
        check(Viewfinder.shutterText(10, 4000).equals("1/400"), "10/4000 is 1/400");
        check(Viewfinder.shutterText(2, 1).equals("2\""), "2 seconds");
        check(Viewfinder.shutterText(13, 10).equals("1.3\""), "1.3 seconds");
        check(Viewfinder.apertureText(560).equals("F5.6") && Viewfinder.apertureText(1100).equals("F11") && Viewfinder.apertureText(350).equals("F3.5"), "apertures");
        check(Viewfinder.afModeText("af-s", "auto").equals("AF-S") && Viewfinder.afModeText("af-c", "auto").equals("AF-C")
                && Viewfinder.afModeText("af-s", "manual").equals("MF"), "focus modes");
        check(Viewfinder.areaText("center").equals("Centre") && Viewfinder.areaText("flex-spot").equals("Spot") && Viewfinder.areaText("wide").equals("Wide"), "focus areas");
    }

    static void touch(File f, long time) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(new byte[16]);
        o.close();
        f.setLastModified(time);
    }

    static String read(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        try { in.read(b); } finally { in.close(); }
        return new String(b, "UTF-8");
    }

    /** Decision B: DOWN opens the scale, the wheel moves it in thirds within the camera's range. */
    static void exposure() {
        check(Viewfinder.evIndex(1, 1f / 3) == 1 && Viewfinder.evIndex(-4, 1f / 3) == -4, "a third of a stop is one step of the camera's 1/3 index");
        check(Viewfinder.evIndex(3, 0.5f) == 2 && Viewfinder.evIndex(0, 0) == 0, "a camera with half stops gets the nearest step");
        check(Viewfinder.clampEv(10, -9, 9) == 9 && Viewfinder.clampEv(-10, -9, 9) == -9 && Viewfinder.clampEv(4, -9, 9) == 4, "kept within the camera's range");
        check(Ui.evText(0).equals("0") && Ui.evText(1).equals("+1/3") && Ui.evText(-5).equals("\u20131 2/3") && Ui.evText(6).equals("+2"),
                "exposure in words: " + Ui.evText(0) + " " + Ui.evText(1) + " " + Ui.evText(-5) + " " + Ui.evText(6));
        System.out.println("  ok  exposure compensation");
    }

    /** AF-01: the camera's AF cells and its focus area, as the viewfinder draws them. */
    static void focusAreas() {
        check(Focus.mask(new int[] { 8, 9, 13 }) == (1 << 7 | 1 << 8 | 1 << 12) && Focus.mask(new int[] { 0, 26, -1 }) == 0 && Focus.mask(null) == 0,
                "the report's areas 1-25 are the mask's bits 0-24 (others left out)");
        check(Focus.cells(1 << 7 | 1 << 8 | 1 << 12).equals("7 8 12") && Focus.cells(0).equals("none"), "cells in words: " + Focus.cells(1 << 7 | 1 << 8 | 1 << 12));
        float[][] b = Focus.boxes(1 << 12 | 1 << 24);
        check(b.length == 2 && Math.abs(b[0][0] - 0.44f) < 1e-4 && Math.abs(b[0][1] - 0.44f) < 1e-4 && Math.abs(b[0][2] - 0.12f) < 1e-4
                && Math.abs(b[1][0] - 0.84f) < 1e-4 && Math.abs(b[1][1] - 0.84f) < 1e-4, "a cell's box: 60% of the cell, about its middle (the centre cell at 0.44)");
        check(Focus.boxes(0) == null, "no cells, no boxes");
        float[] z = Focus.areaBox("center", "midium", "-752", "361");
        check(z != null && Math.abs(z[0] - 0.2f) < 1e-4 && Math.abs(z[1] - 0.2f) < 1e-4 && Math.abs(z[2] - 0.6f) < 1e-4 && Math.abs(z[3] - 0.6f) < 1e-4,
                "Center: the middle 3 x 3 cells, 60% of the picture each way");
        float[] m = Focus.areaBox("flex-spot", "midium", "0", "0");
        check(m != null && Math.abs(m[0] - 0.4f) < 1e-4 && Math.abs(m[1] - 0.4f) < 1e-4 && Math.abs(m[2] - 0.2f) < 1e-4, "Flexible Spot M at 0, 0: one cell's size in the middle");
        float[] f = Focus.areaBox("flex-spot", "small", "-752", "361");
        check(f != null && Math.abs(f[0] + f[2] / 2 - 0.124f) < 0.01 && Math.abs(f[1] + f[3] / 2 - 0.68f) < 0.01 && f[2] < m[2],
                "this camera's spot (-752, 361): left of and below the middle, smaller when small");
        float[] e = Focus.areaBox("flex-spot", "large", "-1000", "1000");
        check(e != null && e[0] == 0 && Math.abs(e[1] + e[3] - 1) < 1e-4, "a spot at the corner stays inside the picture");
        check(Focus.areaBox("wide", "midium", "0", "0") == null && Focus.areaBox("flex-spot", "midium", "x", "0") == null && Focus.areaBox(null, null, null, null) == null,
                "Wide, a point that isn't a number, nothing read: the usual bracket");
    }

    /** AF-02: the check's verdicts, on scores the camera's own photos gave (fltool focus, card of 2026-10-16). */
    static void focusVerdicts() {
        int[] p04627 = { 451, 408, 404, 408, 681, 603, 623, 658, 728, 662, 333, 314, 337, 326, 369, 306, 294, 306, 302, 349, 287, 306, 333, 314, 384 };
        int[] p04702 = { 833, 775, 857, 833, 705, 755, 798, 841, 923, 650, 728, 794, 806, 849, 689, 705, 740, 732, 791, 369, 666, 572, 474, 357, 314 };
        int[] p04608 = { 345, 333, 365, 376, 349, 333, 314, 376, 369, 353, 357, 408, 400, 365, 412, 400, 400, 400, 384, 419, 400, 419, 333, 412, 400 };
        int[] p04626 = { 333, 392, 333, 353, 349, 376, 439, 396, 548, 638, 654, 662, 736, 556, 615, 564, -1, -1, 509, 517, -1, -1, 306, 365, 384 };
        int[] p04664 = { 501, 443, 369, 400, 326, 646, 591, 392, 591, 533, 708, 544, 474, 724, 646, 705, 615, 666, 689, 712, 599, 615, 666, 599, 650 };
        double s60 = 1 / 60.0;
        check(Focus.verdict(p04627, 0x3000, s60) == Focus.MISSED, "04627, soft where it focused (cells 12, 13), sharp elsewhere: missed focus");
        check(Focus.verdict(p04664, 0x80, s60) == Focus.MISSED, "04664: missed focus");
        check(Focus.verdict(p04702, 0x23180, s60) == Focus.SHARP, "04702, sharp where it focused: sharp");
        check(Focus.verdict(p04626, 0x20100, s60) == Focus.SHARP, "04626 (0.55, 0.74 of its best: the nearest pass on the card): sharp, the cell with no score left out");
        check(Focus.verdict(p04608, 0x80, s60) == Focus.SOFT, "04608, soft everywhere at 1/60: not sharp");
        check(Focus.verdict(p04608, 0x80, 1 / 8.0) == Focus.MOVED, "the same at 1/8: blurred, the camera moved");
        check(Focus.verdict(p04608, 0, s60) == Focus.UNKNOWN && Focus.verdict(p04626, 1 << 16, s60) == Focus.UNKNOWN, "no cells, or only cells with no score: can't tell");
        int[] out = new int[FilmCore.FC_COUNT];
        System.arraycopy(p04627, 0, out, 0, 25);
        out[FilmCore.FC_MASK] = 0x3000;
        out[FilmCore.FC_EXPOSURE] = 125000;
        check(Focus.verdictOf(out, 1 << 7, s60) == Focus.MISSED, "the photo's own cells before the lock's");
        check(Focus.exposureOf(out, s60) == 0.125 && Focus.cellsOf(out, 1 << 7) == 0x3000, "the photo's own exposure before the lock's");
        out[FilmCore.FC_MASK] = 0;
        out[FilmCore.FC_EXPOSURE] = 0;
        check(Focus.cellsOf(out, 1 << 7) == 1 << 7 && Focus.exposureOf(out, s60) == s60, "a photo with no cells and no exposure: the lock's");
        String line = Focus.line("DSC04627.JPG", out, 0x3000, s60, Focus.MISSED);
        check(line.contains("missed focus") && line.contains("cells 12 13 from the lock") && line.contains("there 0.34, best 0.73") && line.contains("1/60 s"),
                "the log line: " + line);
        check(Focus.words(Focus.MISSED).equals("Missed focus") && Focus.words(Focus.SHARP).equals("") && Focus.words(Focus.UNKNOWN).equals(""),
                "the viewfinder says only soft ones");
    }

    /** AF-03: photos the viewfinder couldn't read are checked after the restart: TOCHECK.TXT, and what the camera back says. */
    static void focusLater(File card) throws Exception {
        List<String> lines = new ArrayList<String>();
        lines.add(Focus.pendingLine("/mnt/sdcard/DCIM/100MSDCF/DSC04709.JPG", 0x3000, 1 / 60.0));
        lines.add(Focus.pendingLine("/mnt/sdcard/DCIM/100MSDCF/DSC04710.JPG", 0, 0.125));
        Focus.addPending(card, lines);
        Focus.addPending(card, java.util.Collections.singletonList("a line that isn't one"));
        List<Focus.Waiting> w = Focus.takePending(card);
        check(w.size() == 2 && w.get(0).path.endsWith("DSC04709.JPG") && w.get(0).reportMask == 0x3000 && Math.abs(w.get(0).exposure - 1 / 60.0) < 1e-6
                && w.get(1).reportMask == 0 && w.get(1).exposure == 0.125, "TOCHECK.TXT: each photo, its lock's cells and speed, back as written (a bad line skipped)");
        check(!Focus.pendingFile(card).exists() && Focus.takePending(card).isEmpty(), "taken once: the file gone, nothing the second time");
        List<int[]> soft = new ArrayList<int[]>();
        soft.add(new int[] { 9, Focus.MISSED });
        check(Focus.summary(soft).equals("Frame 9: missed focus"), "one: " + Focus.summary(soft));
        soft.add(0, new int[] { 7, Focus.MISSED });
        check(Focus.summary(soft).equals("Frames 7 and 9: missed focus"), "two, one reason: " + Focus.summary(soft));
        soft.add(0, new int[] { 4, Focus.MOVED });
        check(Focus.summary(soft).equals("Frames 4, 7 and 9 came out soft"), "three, two reasons: " + Focus.summary(soft));
        List<int[]> moved = new ArrayList<int[]>();
        moved.add(new int[] { 12, Focus.MOVED });
        check(Focus.summary(moved).equals("Frame 12: blurred, the camera moved"), "moved: " + Focus.summary(moved));
        List<int[]> unknown = new ArrayList<int[]>();
        unknown.add(new int[] { 0, Focus.SOFT });
        check(Focus.summary(unknown).equals("A photo just taken: not sharp") && Focus.summary(new ArrayList<int[]>()).equals(""),
                "not one of the roll's frames: " + Focus.summary(unknown));
    }

    /** AF-04: the steady shutter: slower than 1/30 a press waits for a still frame, at most Steady.MAX_MS. */
    static void steady() {
        check(!Steady.wanted(1 / 60.0) && !Steady.wanted(1 / 30.0) && Steady.wanted(1 / 25.0) && Steady.wanted(0.5) && !Steady.wanted(0),
                "it waits only slower than 1/30 (and not when the speed isn't known)");
        double scale = Viewfinder.PHOTO_W / 320.0;
        Steady s = new Steady();
        s.begin(1000, 1 / 25.0, scale);
        check(Math.abs(s.aim() - Steady.STEADY_PX) < 1e-9, "at 1/25 it aims under " + Steady.STEADY_PX + " px of blur");
        check(!s.frame(2, 1, 70, 200) && s.waiting && s.lastBlur > 20, "the press's jolt (2 px a frame): " + Math.round(s.lastBlur) + " px of blur, it waits");
        check(s.frame(0.2, 0.1, 70, 200) && !s.waiting, "a frame moving 0.2 px: " + String.format(java.util.Locale.US, "%.1f", s.lastBlur) + " px, taken");
        check(!s.frame(0, 0, 70, 200) && !s.due(5000), "once taken, nothing more");
        Steady d = new Steady();
        d.begin(1000, 1 / 8.0, scale);
        check(d.aim() > Steady.STEADY_PX, "at 1/8 it aims at what a frame can tell (" + String.format(java.util.Locale.US, "%.1f", d.aim()) + " px)");
        check(!d.frame(0, 0, 70, Steady.DETAIL_MIN - 1) && d.judged == 0 && d.waiting, "a frame too plain to match isn't judged");
        check(!d.due(1000 + Steady.BLIND_MS - 1) && d.due(1000 + Steady.BLIND_MS) && !d.waiting, "nothing judged: taken after " + Steady.BLIND_MS + " ms, the jolt given time");
        Steady c = new Steady();
        c.begin(1000, 1 / 8.0, scale);
        c.frame(1, 0, 70, 200);
        check(c.judged == 1 && !c.due(1000 + Steady.BLIND_MS) && !c.due(1000 + Steady.MAX_MS - 1) && c.due(1000 + Steady.MAX_MS),
                "a camera never still: taken at " + Steady.MAX_MS + " ms anyway");
        check(!c.frame(0, 0, 0, 200) && !c.frame(0, 0, -5, 200), "no time between two frames: nothing judged");
        c.begin(0, 1 / 8.0, scale);
        c.cancel();
        check(!c.waiting && !c.due(10000), "dropped (hidden or closing): it never takes it");
        // the button: let go while a slow shot waits, and the picture is still taken (Release keeps shooting)
        Cam cam = new Cam();
        Release r = new Release(cam, true);
        r.halfDown();
        r.focused(true);
        r.fullDown();
        check(r.shooting && cam.take().endsWith("S"), "a full press on a lock: the picture asked for (the steady wait is inside it)");
        r.halfUp();
        check(r.shooting && cam.take().equals(""), "let go while it waits: still shooting, nothing cancelled");
        r.shot(true);
        check(!r.shooting && r.state == Release.READY, "taken: ready again");
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        System.out.println("ok  " + what);
    }
}
