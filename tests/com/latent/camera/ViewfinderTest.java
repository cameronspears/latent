package com.latent.camera;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Host test of the viewfinder's logic: the shutter button (Release), the pictures the card's listing
 * doesn't show yet (Library.withShots), the exposure readout, and
 * (v0.45.0) when frames are asked for (FrameScheduler), the pace (Pacer), the store quiet, the /proc
 * readings, the catalogue's new pictures and the overlay's bands.
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

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        System.out.println("ok  " + what);
    }
}
