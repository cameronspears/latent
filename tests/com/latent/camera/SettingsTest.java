package com.latent.camera;

import java.io.File;

/** Mac test of the neutral switch: the real native bridge against tools/fakeosal.c's stand-in settings service. */
public class SettingsTest {
    static volatile boolean front = true;   // Latent in front, the camera not opened here: the gate MainActivity passes
    static final NeutralSettings.Gate GATE = new NeutralSettings.Gate() { public boolean mayWrite() { return front; } };

    public static void main(String[] a) throws Exception {
        File card = new File(a[0]);
        new File(card, "LATENT").mkdirs();
        check(CameraSettings.available(), "settings service found");
        check(v(CameraSettings.WB_AB) == 2 && v(CameraSettings.WB_GM) == 2 && v(CameraSettings.CONTRAST) == 1 && v(CameraSettings.DRO) == 1,
                "reads the camera's A2 / M2, contrast +1, DRO Auto");
        // the fence: only the switched settings, only in range
        check(CameraSettings.write(CameraSettings.WB_KELVIN, 50) < 0 && v(CameraSettings.WB_KELVIN) == 60, "colour temperature can't be written");
        check(CameraSettings.write(CameraSettings.EV, 0) < 0, "exposure compensation can't be written");
        check(CameraSettings.write(CameraSettings.CONTRAST, 5) < 0 && v(CameraSettings.CONTRAST) == 1, "out-of-range contrast refused");
        check(CameraSettings.write(CameraSettings.WB_AB, -9) < 0 && v(CameraSettings.WB_AB) == 2, "out-of-range fine-tune refused");
        check(CameraSettings.write(0x01079999, 0) < 0, "an unknown (read-only) setting refused");
        boolean ranges = true;   // NeutralSettings' menu ranges are the native fence's
        for (int i = 0; i < NeutralSettings.IDS.length; i++)
            ranges &= CameraSettings.write(NeutralSettings.IDS[i], NeutralSettings.LO[i] - 1) == -2000
                    && CameraSettings.write(NeutralSettings.IDS[i], NeutralSettings.HI[i] + 1) == -2000;
        check(ranges, "NeutralSettings.LO / HI are the native fence's ranges");

        // on the way to shooting with no note yet: yours kept, then neutral (as apply)
        StringBuilder log = new StringBuilder();
        NeutralSettings ns = new NeutralSettings(card, log);
        File note = new File(card, "LATENT/NEUTRAL.TXT"), fakeLog = new File(System.getenv("FAKE_LOG"));
        check(ns.neutralize(GATE) == null && ns.active(), "neutral set, your settings kept: " + log.toString().replace("\r\n", " | "));
        check(ns.yoursSummary().equals("A2 · M2, contrast +1, saturation +1, DRO Auto"), "summary in the menus' words: " + ns.yoursSummary());
        check(allNeutral(), "every switched setting reads neutral");

        // nothing changed since: all 11 read back, nothing written, no wait before shooting
        long writes = lines(fakeLog);
        int before = log.length();
        check(ns.neutralize(GATE) == null && lines(fakeLog) == writes && log.substring(before).contains("neutral: as Latent set them")
                && log.substring(before).contains("0x01070178=0"), "unchanged: read back, nothing written: " + log.substring(before).replace("\r\n", " | "));

        // Roll 005: A2 / M2 set in the camera's menu (and contrast -1): yours from now on, then neutral again for Latent
        control("set 0x01070017 2\nset 0x01070016 2\nset 0x0107067f 2\nset 0x0107067e 2\nset 0x01070178 -1\n");
        control("");
        before = log.length();
        String w = ns.neutralize(GATE);
        String said = log.substring(before);
        check(w == null && allNeutral() && said.contains("changed since Latent set them: 0x01070017 0->2") && said.contains("0x01070178 0->-1")
                && said.contains("set again for shooting"), "a menu change: neutral again: " + said.replace("\r\n", " | "));
        check(ns.yoursSummary().equals("A2 · M2, contrast -1, saturation +1, DRO Auto"), "... and it's yours now (comes back on Exit): " + ns.yoursSummary());
        check(NeutralSettings.sinceLastWrite() >= 0 && NeutralSettings.sinceLastWrite() < 2000, "... and shooting waits the settle after it");

        // a value Latent may not write (DRO 9): said and left; the rest neutral again
        control("set 0x01070104 9\nset 0x0107018a 2\n");
        control("");
        w = ns.neutralize(GATE);
        check(w != null && w.contains("0x01070104 0->9") && v(CameraSettings.SHARPNESS) == 0 && v(CameraSettings.DRO) == 9,
                "DRO outside what Latent may write: said (" + w + "), the rest neutral");
        control("set 0x01070104 0\n");
        control("");

        // Latent left meanwhile (the gate): nothing written, the camera as it was
        control("set 0x01070178 2\n");
        control("");
        front = false;
        writes = lines(fakeLog);
        w = ns.neutralize(GATE);
        check(w != null && w.contains("Latent was left") && lines(fakeLog) == writes && v(CameraSettings.CONTRAST) == 2,
                "with Latent away nothing is written: " + w);
        front = true;
        check(ns.neutralize(GATE) == null && v(CameraSettings.CONTRAST) == 0, "back in front: neutral again");

        // a refused write: this batch's changes put back, and shooting still waits (the refused one may have landed)
        control("set 0x01070178 1\nset 0x01070187 -1\n");
        control("refuse 0x01070187\n");
        Thread.sleep(1100);
        w = ns.neutralize(GATE);
        check(w != null && w.startsWith("left as it was") && v(CameraSettings.CONTRAST) == 1 && v(CameraSettings.SATURATION) == -1,
                "a refused write: contrast put back too, 'left as it was': " + w);
        check(NeutralSettings.sinceLastWrite() >= 0 && NeutralSettings.sinceLastWrite() < 1000, "... and the settle starts all the same");
        control("");
        check(ns.neutralize(GATE) == null && allNeutral(), "(neutral)");
        control("set 0x01070187 -1\n");   // one change, its one write refused: only the request itself can start the settle
        control("refuse 0x01070187\n");
        Thread.sleep(1100);
        w = ns.neutralize(GATE);
        check(w != null && NeutralSettings.sinceLastWrite() >= 0 && NeutralSettings.sinceLastWrite() < 1000,
                "a refused write alone starts the settle (asked for is enough): " + w);
        control("");

        // the note between its two renames (a crash mid-replace): found again
        File old = new File(card, "LATENT/NEUTRAL.OLD");
        check(note.renameTo(old) && ns.active() && note.exists() && !old.exists(), "a note cut off mid-replace is found again (NEUTRAL.OLD)");
        check(ns.neutralize(GATE) == null && allNeutral(), "(neutral)");

        // Exit Latent: yours back, except what you changed yourself since
        check(CameraSettings.write(CameraSettings.SHARPNESS, 3) == 0, "(you set sharpness +3 in the menu, Latent's note kept)");
        front = false;
        writes = lines(fakeLog);
        w = ns.restore(GATE);
        check(w != null && ns.active() && lines(fakeLog) == writes, "with Latent away: nothing put back, the note kept: " + w);
        front = true;
        before = log.length();
        check(ns.restore(GATE) == null && !ns.active() && !old.exists(), "restored, note removed: " + log.substring(before).replace("\r\n", " | "));
        check(v(CameraSettings.WB_AB) == 2 && v(CameraSettings.WB_GM) == 2 && v(CameraSettings.WB_AB_AUTO) == 2 && v(CameraSettings.WB_GM_AUTO) == 2,
                "fine-tune A2 / M2 back, Auto copies too");
        check(v(CameraSettings.CONTRAST) == 1 && v(CameraSettings.SATURATION) == -1 && v(CameraSettings.DRO) == 1 && v(CameraSettings.STYLE) == 1
                && v(CameraSettings.DRO_LEVEL) == 1, "your contrast +1, saturation -1 (as last changed), DRO Auto, Standard back");
        check(v(CameraSettings.SHARPNESS) == 3, "your sharpness +3 is left alone");
        check("A2 · M2, contrast +1, saturation -1, sharpness +3, DRO Auto".equals(ns.restored), "... and the screen says what the camera has now: " + ns.restored);
        check(ns.restore(GATE) == null, "restoring with nothing kept is a no-op");

        // a value outside what Latent may write (DRO 9, as HDR leaves it) is left as it is, and Exit still completes
        control("set 0x01070104 9\n");
        control("");
        w = ns.neutralize(GATE);
        check(w != null && w.contains("outside") && v(CameraSettings.DRO) == 9 && v(CameraSettings.CONTRAST) == 0 && ns.active(),
                "no note yet, DRO 9: left as it is, the rest neutral: " + w);
        control("set 0x01070104 1\n");   // HDR off in the menu: DRO Auto, back in range
        control("");
        w = ns.neutralize(GATE);
        check(w == null && allNeutral(), "... back in range later (HDR off): yours, and set neutral like the rest: " + w);
        control("set 0x01070104 9\n");
        control("");
        check(ns.neutralize(GATE) != null && v(CameraSettings.DRO) == 9, "(DRO 9 again: left)");
        check(ns.restore(GATE) == null && !ns.active() && v(CameraSettings.DRO) == 9 && v(CameraSettings.CONTRAST) == 1, "... and Exit puts the rest back: " + ns.restored);
        control("set 0x01070104 1\n");
        control("");

        // a WB mode changed in the menu: its own fine-tune comes with it (not the old mode's A2 / M2 on Exit)
        check(ns.neutralize(GATE) == null && ns.active() && ns.yoursSummary().startsWith("A2 · M2"), "(neutral, yours A2 / M2)");
        control("set 0x01070019 14\nset 0x01070017 0\nset 0x01070016 0\n");   // colour temperature, its grid at 0
        control("");
        check(ns.neutralize(GATE) == null && allNeutral() && ns.yoursSummary().startsWith("white balance Colour temperature")
                && !ns.yoursSummary().contains("A2"), "the new mode adopted with its own grid: " + ns.yoursSummary());
        check(ns.restore(GATE) == null && v(CameraSettings.WB_MODE) == 14 && v(CameraSettings.WB_AB) == 0 && v(CameraSettings.WB_GM) == 0,
                "Exit: colour temperature back, with its own 0 / 0");
        control("set 0x01070019 1\nset 0x01070017 2\nset 0x01070016 2\n");   // as the user had it, for the tests after
        control("");

        // a read that never answers: one short wait, the service then left alone (and Sony's delete, a develop, the camera)
        check(ns.neutralize(GATE) == null && ns.active(), "(neutral again for the hang test)");
        control("hangread 0x01070187\n");
        long t0 = System.currentTimeMillis();
        before = log.length();
        w = ns.neutralize(GATE);
        long waited = System.currentTimeMillis() - t0;
        File hungNote = new File(card, "LATENT/NOANSWER.TXT");
        check(w != null && waited < 3000 && log.substring(before).contains("not asked again for 30 min") && hungNote.exists(),
                "a hung read: " + waited + " ms, noted on the card: " + w);
        check(NeutralSettings.unanswered(), "... and nothing of Sony's runs beside it while it waits");
        java.lang.reflect.Field pending = NeutralSettings.class.getDeclaredField("pending");
        pending.setAccessible(true);
        pending.set(null, null);   // as a new run (the next open), the old request gone
        control("");
        before = log.length();
        w = ns.neutralize(GATE);
        check(w != null && w.contains("a little earlier") && !log.substring(before).contains("read back"), "nor asked in the next run within 30 min: " + w);
        write(hungNote, (System.nanoTime() / 1000000 - 31 * 60 * 1000L) + "\r\n");
        before = log.length();
        check(ns.neutralize(GATE) == null && log.substring(before).contains("neutral: read back in ") && !hungNote.exists(), "after 30 min it reads again");

        // a write that never answers: the note kept for later, and the service fenced for the rest of the run
        check(ns.restore(GATE) == null && !ns.active(), "(your settings back for the hang test)");
        control("hang 0x01070016\n");
        t0 = System.currentTimeMillis();
        w = ns.neutralize(GATE);
        check(w != null && w.startsWith("not fully set to neutral") && ns.active() && System.currentTimeMillis() - t0 < 12000,
                "a write that never answers: the note kept, 'not fully set to neutral': " + w);
        check(NeutralSettings.unanswered() && NeutralSettings.sinceLastWrite() >= 0, "... shooting, develops and Sony's delete fenced; the settle armed");
        t0 = System.currentTimeMillis();
        w = ns.neutralize(GATE);
        check(w != null && w.contains("didn't answer earlier") && System.currentTimeMillis() - t0 < 100, "... and not asked again this run: " + w);
        System.out.println("SETTINGS OK");
        System.exit(0);   // the hung calls' threads never end
    }

    static boolean allNeutral() {
        boolean ok = true;
        for (int i = 0; i < NeutralSettings.IDS.length; i++) ok &= v(NeutralSettings.IDS[i]) == NeutralSettings.NEUTRAL[i];
        return ok;
    }

    static void control(String lines) throws Exception {
        write(new File(System.getenv("FAKE_CONTROL")), lines);
        CameraSettings.read(CameraSettings.WB_MODE);   // any request reads it again
    }

    static String read(File f) throws Exception {
        if (!f.exists()) return "";
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
    }

    static void write(File f, String text) throws Exception {
        java.io.FileOutputStream o = new java.io.FileOutputStream(f);
        try { o.write(text.getBytes("UTF-8")); } finally { o.close(); }
    }

    static long lines(File f) throws Exception {
        String t = read(f);
        long n = 0;
        for (int i = 0; i < t.length(); i++) if (t.charAt(i) == '\n') n++;
        return n;
    }

    static int v(int id) { byte[] b = CameraSettings.read(id); if (b == null) throw new RuntimeException(CameraSettings.lastError()); return b[0]; }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  pass  " : "  FAIL  ") + what);
        if (!ok) System.exit(1);
    }
}
