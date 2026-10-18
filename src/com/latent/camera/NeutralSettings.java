package com.latent.camera;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * While a roll is loaded the camera shoots neutral, so the film look starts from plain colour: white
 * balance Auto with no fine-tune, Creative Style Standard 0 / 0 / 0, DRO off. Your own values are
 * kept in LATENT/NEUTRAL.TXT and go back once the roll is developed (or no roll is loaded).
 *
 * Careful by design:
 *  - Nothing is written unless every setting could be read first.
 *  - NEUTRAL.TXT is on the card before the first write, so a crash or dead battery mid-way can
 *    always be undone on the next start.
 *  - Each write is read back. If one fails, those already made are put back, and nothing is left half
 *    done.
 *  - Restoring only touches a setting still at the value Latent set. Anything you changed yourself
 *    in the meantime stays as you left it.
 *  - Every call has a time limit; if the camera stops answering, Latent stops asking.
 *  - While a roll is loaded, each visit reads them back (check, v0.46.1) and notes any that changed since: reads only.
 *    While a check's request goes unanswered, nothing else of Sony's runs beside it (unanswered()), and checks pause.
 */
final class NeutralSettings {
    /** The settings switched, and their neutral values. WB mode first: its fine-tune copies follow it. */
    static final int[] IDS = { CameraSettings.WB_MODE, CameraSettings.WB_AB, CameraSettings.WB_GM, CameraSettings.WB_AB_AUTO,
            CameraSettings.WB_GM_AUTO, CameraSettings.STYLE, CameraSettings.CONTRAST, CameraSettings.SATURATION,
            CameraSettings.SHARPNESS, CameraSettings.DRO, CameraSettings.DRO_LEVEL };
    static final int[] NEUTRAL = { 1 /* Auto */, 0, 0, 0, 0, 1 /* Standard */, 0, 0, 0, 0 /* off */, 1 };
    /** Each setting's menu range, as jni/settings_bridge.c fences writes (SettingsTest checks they agree). */
    static final int[] LO = { 0, -7, -7, -7, -7, 1, -3, -3, -3, 0, 1 }, HI = { 31, 7, 7, 7, 7, 14, 3, 3, 3, 6, 6 };
    /** check()'s time limit for each call: its 22 requests take about 2.5 ms each on the camera. */
    private static final long CHECK_MS = 1500;
    /** After the service didn't answer a check, how long no check asks again (NOANSWER.TXT). */
    private static final long HUNG_PAUSE_MS = 30 * 60 * 1000L;

    private final File file;
    private final StringBuilder log;
    private static volatile boolean stuck;   // the settings service stopped answering: leave it alone
    /** check()'s call that went unanswered, while its thread is still waiting on the settings service. */
    private static volatile Thread pending;
    private static volatile boolean pauseNoted;   // "not checked (paused)" said once this run

    /**
     * A check's request is still with the camera's settings service: Sony's delete, a hardware develop and opening the
     * camera wait until it answers (never seen: these reads answer in milliseconds).
     */
    static boolean unanswered() { Thread t = pending; return t != null && t.isAlive(); }

    /** A check's reads and writes nothing else: a time limit of its own, and an unanswered call is kept (pending). */
    private static Object checkCall(final HardwareDevelop.Call c) throws Exception {
        final Object[] r = new Object[2];
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { r[0] = c.run(); } catch (Throwable e) { r[1] = e; }
            }
        }, "sony-call");
        t.start();
        t.join(CHECK_MS);
        if (t.isAlive()) { pending = t; throw new HardwareDevelop.Stuck(); }
        if (r[1] instanceof Exception) throw (Exception) r[1];
        if (r[1] != null) throw new RuntimeException(Probe.why((Throwable) r[1]));
        return r[0];
    }

    /** A settings call's failure that may still reach the camera (the service didn't answer it). */
    static boolean noAnswer(String why) { return why != null && (why.contains("did not answer") || why.contains("no message buffer")); }
    /**
     * When Latent last changed a camera setting (a monotonic clock in ms, 0 = not this run). The A5100
     * restarts if its shooting mode starts within a second or two of a change (seen twice in v0.14.4),
     * so going to shoot waits until this is long enough ago.
     */
    private static volatile long lastWriteAt;

    private static long now() { return System.nanoTime() / 1000000; }

    /** Milliseconds since Latent last changed a camera setting, or -1 if it hasn't this run. */
    /** A camera setting was just changed some other way (Auto Review): shooting mode waits as after any write. */
    static void wrote() { lastWriteAt = now(); }

    static long sinceLastWrite() { return lastWriteAt == 0 ? -1 : now() - lastWriteAt; }

    NeutralSettings(File card, StringBuilder log) {
        this.file = new File(Library.home(card), "NEUTRAL.TXT");
        this.log = log;
    }

    /** Your settings are put away (or being put away): the camera is shooting neutral for a roll. */
    boolean active() { return file.exists(); }

    /** Sets the camera to neutral, keeping your values. Returns null when done, or why nothing changed. */
    String apply() {
        if (active()) return null;
        if (stuck) return "the camera's settings didn't answer earlier; restart Latent";
        int[] yours = new int[IDS.length];
        try {
            if (!available()) return "camera settings unavailable: " + CameraSettings.lastError();
            for (int i = 0; i < IDS.length; i++) yours[i] = readByte(IDS[i]);   // all or nothing
        } catch (Exception e) {
            return "couldn't read your settings, so nothing was changed (" + Probe.why(e) + ")";
        }
        Properties p = new Properties();
        for (int i = 0; i < IDS.length; i++) {
            p.setProperty("yours." + hex(IDS[i]), String.valueOf(yours[i]));
            p.setProperty("ours." + hex(IDS[i]), String.valueOf(NEUTRAL[i]));
        }
        p.setProperty("summary", describe(yours));
        try {
            save(p);   // on the card before any write, so it can always be undone
        } catch (IOException e) {
            return "couldn't keep a note of your settings, so nothing was changed (" + e.getMessage() + ")";
        }
        List<Integer> written = new ArrayList<Integer>();
        String why = null;
        boolean unsure = false;   // a write that failed so that it may have reached the camera, or may yet
        try {
            for (int i = 0; i < IDS.length && why == null; i++) {
                if (yours[i] == NEUTRAL[i]) continue;
                why = writeByte(IDS[i], NEUTRAL[i]);
                if (why == null) written.add(i);
            }
        } catch (Exception e) {
            why = Probe.why(e);
            unsure = true;
        }
        if (why != null) {   // put back what was changed; the note stays if even that fails, or a write may have landed
            boolean back = !unsure && !noAnswer(why);   // (yours stay on the card for restore, and the next check notes it)
            for (int i : written) {
                try { back &= writeByte(IDS[i], yours[i]) == null; } catch (Exception e) { back = false; }
            }
            sync();
            if (back) file.delete();
            log.append("neutral: not applied (").append(why).append(")").append(back ? ", undone\r\n" : ", NOT fully undone\r\n");
            return (back ? "left as it was: " : "not fully set to neutral: ") + why;
        }
        sync();
        log.append("neutral: applied; yours were ").append(describe(yours)).append("\r\n");
        return null;
    }

    /**
     * While a roll is loaded and your settings are put away (v0.46.1): reads the switched settings back and notes
     * them in DEVELOP.TXT, all 11 as numbers, with any that no longer hold the value Latent set (changed in the
     * camera's menus, or by another app, while Latent was away). Reads only: the same reads apply() makes, each
     * time-limited, and nothing written. A call that doesn't answer is noted on the card (NOANSWER.TXT), and no check
     * asks again for HUNG_PAUSE_MS; it doesn't mark the service stuck, so apply() and restore() run as before. A note
     * that can't be read is noted and left as it is (restore() needs it).
     */
    void check() {
        if (!active() || stuck) return;
        if (unanswered() || hung()) {
            if (!pauseNoted) log.append("neutral: not checked (a check had no answer from the camera; paused)\r\n");
            pauseNoted = true;
            return;
        }
        long t0 = now();
        int[] ours = new int[IDS.length], cam = new int[IDS.length];
        try {
            Properties p = load();
            for (int i = 0; i < IDS.length; i++)
                ours[i] = Integer.parseInt(p.getProperty("ours." + hex(IDS[i]), String.valueOf(NEUTRAL[i])).trim());
        } catch (Exception e) {
            log.append("neutral: not checked (NEUTRAL.TXT: ").append(Probe.why(e)).append(")\r\n");
            return;
        }
        try {
            if (!(Boolean) checkCall(new HardwareDevelop.Call() { public Object run() { return CameraSettings.available(); } })) {
                log.append("neutral: not checked (").append(CameraSettings.lastError()).append(")\r\n");
                return;
            }
            for (int i = 0; i < IDS.length; i++) cam[i] = checkRead(IDS[i]);
        } catch (HardwareDevelop.Stuck e) {
            hung("no answer in " + CHECK_MS + " ms");
            return;
        } catch (Exception e) {
            String why = Probe.why(e);
            if (noAnswer(why)) hung(why);
            else log.append("neutral: not checked (").append(why).append(")\r\n");
            return;
        }
        StringBuilder raw = new StringBuilder(), moved = new StringBuilder();
        for (int i = 0; i < IDS.length; i++) {
            raw.append(i == 0 ? "" : " ").append(hex(IDS[i])).append('=').append(cam[i]);
            if (cam[i] == ours[i]) continue;
            moved.append(moved.length() == 0 ? "" : ", ").append(hex(IDS[i])).append(' ').append(ours[i]).append("->").append(cam[i])
                 .append(cam[i] < LO[i] || cam[i] > HI[i] ? " (outside what Latent may write)" : "");
        }
        log.append("neutral: read back in ").append(now() - t0).append(" ms: ").append(raw).append("\r\n");
        log.append(moved.length() == 0 ? "neutral: as Latent set them\r\n"
                : "neutral: changed since Latent set them: " + moved + " (the camera now: " + describe(cam) + "); read only, nothing written\r\n");
    }

    /** A check's call went unanswered within HUNG_PAUSE_MS of now, on the camera's clock since it started (one that went back: it restarted). */
    private boolean hung() {
        File f = hungFile();
        if (!f.exists()) return false;
        long at = -1;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            try { at = Long.parseLong(r.readLine().trim()); } finally { r.close(); }
        } catch (Exception e) {
            at = -1;
        }
        long since = now() - at;
        if (at >= 0 && since >= 0 && since < HUNG_PAUSE_MS) return true;
        f.delete();
        return false;
    }

    private void hung(String why) {
        log.append("neutral: not checked (").append(why).append("); not asked again for ").append(HUNG_PAUSE_MS / 60000).append(" min\r\n");
        try {
            FileOutputStream out = new FileOutputStream(hungFile());
            try { out.write((now() + "\r\n").getBytes("UTF-8")); } finally { out.close(); }
        } catch (IOException e) {
            log.append("  (NOANSWER.TXT not written: ").append(e.getMessage()).append(")\r\n");
        }
    }

    private File hungFile() { return new File(file.getParentFile(), "NOANSWER.TXT"); }   // 8.3, as the card needs

    /** One of check()'s reads: CHECK_MS, and a timeout leaves `stuck` alone (the caller notes it). */
    private static int checkRead(final int id) throws Exception {
        byte[] b = (byte[]) checkCall(new HardwareDevelop.Call() { public Object run() { return CameraSettings.read(id); } });
        if (b == null || b.length != 1) throw new IOException(b == null ? CameraSettings.lastError() : hex(id) + " is " + b.length + " bytes");
        return b[0];
    }

    private Properties load() throws IOException {
        Properties p = new Properties();
        FileInputStream in = new FileInputStream(file);
        try { p.load(in); } finally { in.close(); }
        return p;
    }

    /** Puts your settings back (only those still as Latent set them). Returns null when done, or why not. */
    String restore() {
        if (!active()) return null;
        if (stuck) return "the camera's settings didn't answer earlier; restart Latent";
        Properties p = new Properties();
        try {
            FileInputStream in = new FileInputStream(file);
            try { p.load(in); } finally { in.close(); }
        } catch (IOException e) {
            return "couldn't read NEUTRAL.TXT: " + e.getMessage();
        }
        String why = null;
        int kept = 0;
        try {
            if (!available()) return "camera settings unavailable: " + CameraSettings.lastError();
            for (int i = 0; i < IDS.length; i++) {
                String y = p.getProperty("yours." + hex(IDS[i])), o = p.getProperty("ours." + hex(IDS[i]));
                if (y == null || o == null) continue;
                int yours = Integer.parseInt(y), ours = Integer.parseInt(o), now = readByte(IDS[i]);
                if (now == yours) continue;
                if (now != ours) { kept++; continue; }   // changed by you while the roll was loaded: yours now
                String w = writeByte(IDS[i], yours);
                if (w != null && why == null) why = w;
            }
        } catch (Exception e) {
            why = Probe.why(e);
        }
        sync();
        if (why != null) {
            log.append("neutral: restore incomplete (").append(why).append("); retried next start\r\n");
            return "your camera settings couldn't all be put back (" + why + "); Latent will try again";
        }
        file.delete();
        log.append("neutral: restored ").append(p.getProperty("summary", "")).append(kept > 0 ? "; " + kept + " you changed meanwhile were left as they are" : "").append("\r\n");
        return null;
    }

    /** What the note says your settings are, for the screen ("A2 · M2, contrast +1, …"), or null. */
    String yoursSummary() {
        Properties p = new Properties();
        try {
            FileInputStream in = new FileInputStream(file);
            try { p.load(in); } finally { in.close(); }
        } catch (IOException e) {
            return null;
        }
        return p.getProperty("summary");
    }

    /** Your values in the menus' words; only what differs from neutral. */
    static String describe(int[] v) {
        List<String> parts = new ArrayList<String>();
        int mode = v[0] & 0xff, ab = v[1], gm = v[2], style = v[5] & 0xff, con = v[6], sat = v[7], sharp = v[8], dro = v[9] & 0xff;
        if (mode != 1) parts.add("white balance " + CameraSettings.wbMode(mode));
        if (ab != 0 || gm != 0) parts.add(CameraSettings.amberBlue(ab) + " · " + CameraSettings.greenMagenta(gm));
        if (style != 1) parts.add(CameraSettings.style(style));
        if (con != 0) parts.add("contrast " + CameraSettings.signed(con));
        if (sat != 0) parts.add("saturation " + CameraSettings.signed(sat));
        if (sharp != 0) parts.add("sharpness " + CameraSettings.signed(sharp));
        if (dro != 0) parts.add("DRO " + CameraSettings.dro(dro));
        if (parts.isEmpty()) return "already neutral";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) b.append(i == 0 ? "" : ", ").append(parts.get(i));
        return b.toString();
    }

    // ------------------------------------------------------------------------------ one call at a time, each time-limited

    private boolean available() throws Exception {
        return (Boolean) call(new HardwareDevelop.Call() { public Object run() { return CameraSettings.available(); } });
    }

    private int readByte(final int id) throws Exception {
        byte[] b = (byte[]) call(new HardwareDevelop.Call() { public Object run() { return CameraSettings.read(id); } });
        if (b == null || b.length != 1) throw new IOException(b == null ? CameraSettings.lastError() : hex(id) + " is " + b.length + " bytes");
        return b[0];
    }

    /** Writes and reads back; null when the camera holds the new value, else why not. */
    private String writeByte(final int id, final int value) throws Exception {
        int r = (Integer) call(new HardwareDevelop.Call() { public Object run() { return CameraSettings.write(id, value); } });
        if (r != 0) return CameraSettings.lastError();
        int back = readByte(id);
        lastWriteAt = now();
        if (back != value) return hex(id) + " reads " + back + " after writing " + value;
        log.append("  wrote ").append(hex(id)).append(" = ").append(value).append("\r\n");
        return null;
    }

    private void sync() {
        try {
            call(new HardwareDevelop.Call() { public Object run() { CameraSettings.sync(); return null; } });
            lastWriteAt = now();
        } catch (Exception e) {
            log.append("  sync: ").append(Probe.why(e)).append("\r\n");
        }
    }

    private static Object call(HardwareDevelop.Call c) throws Exception {
        try {
            return HardwareDevelop.guarded(c, 8000);
        } catch (HardwareDevelop.Stuck e) {
            stuck = true;
            throw e;
        }
    }

    private void save(Properties p) throws IOException {
        File tmp = new File(file.getParentFile(), "NEUTRAL.TMP");
        FileOutputStream out = new FileOutputStream(tmp);
        try { p.store(out, "Latent: your camera settings while a roll is loaded (put back after developing)"); } finally { out.close(); }
        if (file.exists() && !file.delete()) throw new IOException("cannot replace " + file);
        if (!tmp.renameTo(file)) throw new IOException("cannot write " + file);
    }

    private static String hex(int id) { return String.format("0x%08x", id); }
}
