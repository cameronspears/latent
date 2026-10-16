package com.latent.camera;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Host test of the viewfinder's logic: the shutter button (Release), the level's steadying (Level),
 * the pictures the card's listing doesn't show yet (Library.withShots), and the exposure readout.
 *
 *   ViewfinderTest <empty dir>
 */
public class ViewfinderTest {
    public static void main(String[] a) throws Exception {
        release();
        level();
        shots(new File(a[0]));
        words();
        System.out.println("ViewfinderTest: all passed");
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
        Release r = new Release(c, true);
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
        r = new Release(c, true);
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
        r = new Release(c, true);
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
        r = new Release(c, true);
        r.halfDown();
        r.cameraFocus(Release.AF_WARN);
        c.runLater();
        r.fullDown();
        check(!r.shooting && r.waiting, "focus first: waits while Latent searches");
        r.focused(true);
        check(r.shooting && c.take().equals("FS"), "focus first: shoots once it locks");

        // focus first, never locks: no picture
        c = new Cam();
        r = new Release(c, true);
        r.halfDown();
        r.cameraFocus(Release.AF_WARN);
        c.runLater();
        r.fullDown();
        for (int i = 0; i < Release.MAX_TRIES; i++) { r.focused(false); c.runLater(); }
        check(!r.shooting && r.refused && !c.take().contains("S"), "focus first: no lock, no picture");
        r.fullDown();
        check(r.state == Release.SEARCHING && r.waiting && r.tries == 1, "another full press starts Latent's focus again");

        // focus first off: Latent shoots at once in app mode
        c = new Cam();
        r = new Release(c, false);
        r.halfDown();
        r.cameraFocus(Release.AF_WARN);
        c.runLater();
        c.take();
        r.fullDown();
        check(r.shooting && c.take().equals("cS"), "focus first off: the search is cancelled and it shoots");
        r.focused(true);
        check(r.shooting, "a late focus answer while shooting is ignored");
        r.shot(false);
        check(!r.app && !r.shooting && c.take().equals("B"), "the camera wouldn't shoot: the button goes back");

        // a retry scheduled before the button was let go doesn't run after
        c = new Cam();
        r = new Release(c, true);
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
        r = new Release(c, true);
        r.lock();
        check(r.app && r.locked && c.take().equals("T"), "full roll: Latent takes the button");
        r.halfDown();
        r.focused(true);
        r.fullDown();
        check(!r.shooting && r.refused && !c.take().contains("S"), "and a full press takes nothing");
        r.halfUp();
        check(r.app && !c.take().contains("B"), "and it never goes back to the camera");
    }

    static void level() {
        Level l = new Level();
        long t = 0;
        l.frame(250, 10, 40, t);
        check(!l.shown, "unsure frames (low confidence) don't show the level");
        l.frame(250, 80, 3, t += 140);
        check(!l.shown, "nor do frames with too few edges");
        l.frame(250, 60, 30, t += 140);
        check(l.shown && near(l.angle, 2.5f) && !l.level, "a sure frame shows it at once: 2.5 degrees, not level");
        l.frame(150, 60, 30, t += 140);
        check(l.angle < 2.5f && l.angle > 1.5f, "it eases toward the next reading: " + l.angle);
        for (int i = 0; i < 20; i++) l.frame(30, 60, 30, t += 140);
        check(near(l.angle, 0.3f) && l.level, "settled at 0.3 degrees: level (green)");
        l.frame(70, 60, 30, t += 140);
        for (int i = 0; i < 5; i++) l.frame(70, 60, 30, t += 140);
        check(l.level, "0.7 degrees: still green (it leaves only past " + Level.LEVEL_OUT + ")");
        for (int i = 0; i < 20; i++) l.frame(120, 60, 30, t += 140);
        check(!l.level, "1.2 degrees: no longer level");
        l.frame(-900, 60, 30, t += 140);
        check(near(l.angle, 1.2f), "one odd reading far away is ignored");
        l.frame(-900, 60, 30, t += 140);
        l.frame(-900, 60, 30, t += 140);
        check(near(l.angle, -9f), "three in a row: the camera really turned");
        l.tick(t += 1000);
        check(l.shown, "still shown a second after the last sure frame");
        l.frame(0, 5, 2, t += 600);
        check(!l.shown && !l.level, "hidden once no sure frame came for " + Level.HOLD_MS + " ms");
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

    static boolean near(float a, float b) { return Math.abs(a - b) < 0.05f; }

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

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        System.out.println("ok  " + what);
    }
}
