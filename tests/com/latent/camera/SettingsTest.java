package com.latent.camera;

import java.io.File;

/** Mac test of the neutral switch: the real native bridge against tools/fakeosal.c's stand-in settings service. */
public class SettingsTest {
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

        StringBuilder log = new StringBuilder();
        NeutralSettings ns = new NeutralSettings(card, log);
        check(ns.apply() == null && ns.active(), "neutral applied, note kept: " + log.toString().replace("\r\n", " | "));
        check(ns.yoursSummary().equals("A2 · M2, contrast +1, saturation +1, DRO Auto"), "summary in the menus' words: " + ns.yoursSummary());
        int[] ids = NeutralSettings.IDS;
        boolean neutral = true;
        for (int i = 0; i < ids.length; i++) neutral &= v(ids[i]) == NeutralSettings.NEUTRAL[i];
        check(neutral, "every switched setting reads neutral");
        int before = log.length();
        check(ns.apply() == null && log.length() == before, "applying again changes nothing");

        // check (v0.46.1): read back once a visit, reads only
        File note = new File(card, "LATENT/NEUTRAL.TXT"), fakeLog = new File(System.getenv("FAKE_LOG"));
        String noted = read(note);
        long writes = lines(fakeLog);
        before = log.length();
        ns.check();
        String said = log.substring(before);
        check(said.contains("neutral: read back in ") && said.contains("0x01070178=0") && said.contains("neutral: as Latent set them")
                && lines(fakeLog) == writes, "no change since: all 11 read and noted, nothing written: " + said.replace("\r\n", " | "));
        control("set 0x01070178 -1\nset 0x01070187 1\nset 0x0107018a -1\nset 0x01070017 2\nset 0x01070016 2\nset 0x01070104 9\n");
        before = log.length();
        ns.check();
        said = log.substring(before);
        check(said.contains("changed since Latent set them") && said.contains("0x01070178 0->-1") && said.contains("0x0107018a 0->-1")
                && said.contains("0x01070017 0->2") && said.contains("0x01070104 0->9 (outside what Latent may write)")
                && said.contains("read only, nothing written"), "a drift (Roll 005's, and DRO out of range) noted: " + said.replace("\r\n", " | "));
        check(lines(fakeLog) == writes && v(CameraSettings.CONTRAST) == -1 && read(note).equals(noted), "a drift: nothing written, the note unchanged");
        control("set 0x01070178 0\nset 0x01070187 0\nset 0x0107018a 0\nset 0x01070017 0\nset 0x01070016 0\nset 0x01070104 0\n");
        check(v(CameraSettings.CONTRAST) == 0, "(the camera neutral again)");
        control("");
        write(note, noted.replace("ours.0x01070178=0", "ours.0x01070178=x"));
        before = log.length();
        ns.check();
        said = log.substring(before);
        check(said.contains("neutral: not checked (NEUTRAL.TXT") && read(note).contains("=x"), "a damaged note: noted, not checked, left as it is (no crash)");
        write(note, noted);
        // you change something yourself while the roll is loaded
        check(CameraSettings.write(CameraSettings.CONTRAST, 2) == 0, "(you set contrast +2 mid-roll)");
        check(ns.restore() == null && !ns.active(), "restored, note removed: " + log.substring(before).replace("\r\n", " | "));
        check(v(CameraSettings.WB_AB) == 2 && v(CameraSettings.WB_GM) == 2 && v(CameraSettings.WB_AB_AUTO) == 2 && v(CameraSettings.WB_GM_AUTO) == 2,
                "fine-tune A2 / M2 back, Auto copies too");
        check(v(CameraSettings.SATURATION) == 1 && v(CameraSettings.DRO) == 1 && v(CameraSettings.STYLE) == 1 && v(CameraSettings.DRO_LEVEL) == 1,
                "saturation +1, DRO Auto, Standard back");
        check(v(CameraSettings.CONTRAST) == 2, "your mid-roll contrast +2 is left alone");
        check(ns.restore() == null, "restoring with nothing to restore is a no-op");

        // a read that never answers: one short wait, then no check again this run, and none for 30 min after (NOANSWER.TXT)
        check(ns.apply() == null && ns.active(), "(neutral again for the hang test)");
        control("hangread 0x01070187\n");
        before = log.length();
        long t0 = System.currentTimeMillis();
        ns.check();
        long waited = System.currentTimeMillis() - t0;
        said = log.substring(before);
        File hungNote = new File(card, "LATENT/NOANSWER.TXT");
        check(waited < 3000 && said.contains("not asked again for 30 min") && hungNote.exists(),
                "a hung read: " + waited + " ms, noted on the card: " + said.replace("\r\n", " | "));
        before = log.length();
        t0 = System.currentTimeMillis();
        ns.check();
        check(System.currentTimeMillis() - t0 < 100 && log.substring(before).contains("paused") && NeutralSettings.unanswered(),
                "then no check at all while the read is still with the camera (nor Sony's delete, a develop or the camera open)");
        java.lang.reflect.Field pending = NeutralSettings.class.getDeclaredField("pending");
        pending.setAccessible(true);
        pending.set(null, null);   // as a new run (the next open), the old request gone
        java.lang.reflect.Field noted2 = NeutralSettings.class.getDeclaredField("pauseNoted");
        noted2.setAccessible(true);
        noted2.set(null, false);
        control("");
        before = log.length();
        ns.check();
        check(!NeutralSettings.unanswered() && log.substring(before).contains("paused") && !log.substring(before).contains("read back") && hungNote.exists(),
                "nor in the next run within 30 min (said once)");
        write(hungNote, (System.nanoTime() / 1000000 - 31 * 60 * 1000L) + "\r\n");
        before = log.length();
        ns.check();
        check(log.substring(before).contains("neutral: read back in ") && !hungNote.exists(), "after 30 min it reads again");

        // apply()'s failures (v0.46.1): refused cleanly, all undone and the note gone; unanswered, the note kept
        check(ns.restore() == null && !ns.active(), "(your settings back for the apply tests)");
        int ab = v(CameraSettings.WB_AB), con = v(CameraSettings.CONTRAST);
        control("refuse 0x01070178\n");
        before = log.length();
        String w = ns.apply();
        check(w != null && w.startsWith("left as it was") && !ns.active() && v(CameraSettings.WB_AB) == ab && ab != 0 && v(CameraSettings.CONTRAST) == con,
                "a refused write: undone, note removed, 'left as it was': " + w);
        control("hang 0x01070016\n");
        t0 = System.currentTimeMillis();
        w = ns.apply();
        check(w != null && w.startsWith("not fully set to neutral") && ns.active() && System.currentTimeMillis() - t0 < 12000,
                "a write that never answers: the note kept for restore, 'not fully set to neutral': " + w);
        System.out.println("SETTINGS OK");
        System.exit(0);   // the hung read's thread never ends
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
