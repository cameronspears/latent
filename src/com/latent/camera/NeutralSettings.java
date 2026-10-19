package com.latent.camera;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Whenever you shoot with Latent the camera is neutral, so the film look starts from plain colour: white balance Auto
 * with no fine-tune, Creative Style Standard 0 / 0 / 0, DRO off. Your own values are kept in LATENT/NEUTRAL.TXT and go
 * back when you leave from Latent's menu (Exit Latent), or once a roll is developed and none is loaded.
 *
 * Careful by design:
 *  - Nothing is written unless every setting could be read first, and only while the Gate allows it (Latent in front,
 *    the camera never opened in this process), asked again before each write.
 *  - NEUTRAL.TXT is on the card (synced) before the first write, so a crash or dead battery mid-way can always be undone.
 *    It is replaced by way of NEUTRAL.OLD, so there is never a moment without one.
 *  - Each write is read back. If one fails, those already made are put back (Latent still in front), and nothing is left
 *    half done.
 *  - A setting you change in the camera's menu while your values are kept becomes yours (the note first), and is set
 *    neutral again the next time you shoot with Latent (neutralize, v0.48.0).
 *  - Restoring only touches a setting still at the value Latent set. Anything you changed yourself stays as you left it.
 *  - Every call has a time limit. If the camera stops answering, Latent stops asking, and nothing else of Sony's runs
 *    beside the call while it waits (unanswered()).
 *  - When each write was asked for is kept before it's sent (lastWriteAt): shooting waits after one that failed too.
 */
final class NeutralSettings {
    /** The settings switched, and their neutral values. WB mode first: its fine-tune copies follow it. */
    static final int[] IDS = { CameraSettings.WB_MODE, CameraSettings.WB_AB, CameraSettings.WB_GM, CameraSettings.WB_AB_AUTO,
            CameraSettings.WB_GM_AUTO, CameraSettings.STYLE, CameraSettings.CONTRAST, CameraSettings.SATURATION,
            CameraSettings.SHARPNESS, CameraSettings.DRO, CameraSettings.DRO_LEVEL };
    static final int[] NEUTRAL = { 1 /* Auto */, 0, 0, 0, 0, 1 /* Standard */, 0, 0, 0, 0 /* off */, 1 };
    /** Each setting's menu range, as jni/settings_bridge.c fences writes (SettingsTest checks they agree). */
    static final int[] LO = { 0, -7, -7, -7, -7, 1, -3, -3, -3, 0, 1 }, HI = { 31, 7, 7, 7, 7, 14, 3, 3, 3, 6, 6 };
    /** neutralize()'s reads, each this long at most: its 22 requests take about 2.5 ms each on the camera. */
    private static final long READ_MS = 1500;
    /** After the service didn't answer a read, how long neutralize() doesn't ask again (NOANSWER.TXT). */
    private static final long HUNG_PAUSE_MS = 30 * 60 * 1000L;

    /** Whether Latent may write a camera setting now: asked before every write (Latent in front, the camera not opened here). */
    interface Gate { boolean mayWrite(); }

    private final File file, old;
    private final StringBuilder log;
    private static volatile boolean stuck;   // a call didn't answer in its time: leave the service alone this run
    /** A read that went unanswered, while its thread is still waiting on the settings service. */
    private static volatile Thread pending;
    private static volatile boolean pauseNoted;   // "not checked (paused)" said once this run

    /**
     * A settings request is still with the camera, or one didn't answer this run (stuck): Sony's delete, a hardware
     * develop and opening the camera wait (never seen: these calls answer in milliseconds).
     */
    static boolean unanswered() { Thread t = pending; return stuck || (t != null && t.isAlive()); }

    /** A read with a time limit of its own: an unanswered one is kept (pending), and the service isn't asked again this run. */
    private static Object timed(final HardwareDevelop.Call c) throws Exception {
        final Object[] r = new Object[2];
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { r[0] = c.run(); } catch (Throwable e) { r[1] = e; }
            }
        }, "sony-call");
        t.start();
        t.join(READ_MS);
        if (t.isAlive()) { pending = t; throw new HardwareDevelop.Stuck(); }
        if (r[1] instanceof Exception) throw (Exception) r[1];
        if (r[1] != null) throw new RuntimeException(Probe.why((Throwable) r[1]));
        return r[0];
    }

    /** A settings call's failure that may still reach the camera (the service didn't answer it). */
    static boolean noAnswer(String why) { return why != null && (why.contains("did not answer") || why.contains("no message buffer")); }
    /**
     * When Latent last asked for a camera setting to change (a monotonic clock in ms, 0 = not this run). The A5100
     * restarts if its shooting mode starts within a second or two of a change (seen twice in v0.14.4), so going to
     * shoot waits until this is long enough ago. Set before each request, so a write that failed counts too.
     */
    private static volatile long lastWriteAt;
    /** How long after a setting change before the camera may shoot (in its own mode, or Latent's): 6 s, with margin. */
    static final long SETTLE_MS = 6000;

    private static long now() { return System.nanoTime() / 1000000; }

    /** A camera setting was just changed some other way (exposure, the viewfinder's picture keys): shooting mode waits as after any write. */
    static void wrote() { lastWriteAt = now(); }

    /** Milliseconds since Latent last changed a camera setting, or -1 if it hasn't this run. */
    static long sinceLastWrite() { return lastWriteAt == 0 ? -1 : now() - lastWriteAt; }

    NeutralSettings(File card, StringBuilder log) {
        this.file = new File(Library.home(card), "NEUTRAL.TXT");
        this.old = new File(Library.home(card), "NEUTRAL.OLD");
        this.log = log;
    }

    /** The note, put back from NEUTRAL.OLD if a replace was cut short (the card's moment between two renames). */
    private File note() {
        if (!file.exists() && old.exists()) old.renameTo(file);
        return file;
    }

    /** Your settings are put away: the camera is neutral for Latent. (Card access: not on the UI thread.) */
    boolean active() { return note().exists(); }

    /**
     * Neutral now, for shooting with Latent (v0.48.0). With no note yet, as apply(). With one, the 11 are read back, each
     * read time-limited: a setting changed since Latent set it (in the camera's menu while Latent was away) is yours from
     * now on, the note first, then set neutral again. Returns null when the camera is neutral (nothing to write, or
     * written), else why not, for the screen.
     */
    String neutralize(Gate gate) {
        if (unanswered()) return "the camera's settings didn't answer earlier; turn the camera off and on";
        if (hung()) {
            if (!pauseNoted) log.append("neutral: not checked (a read had no answer from the camera; paused)\r\n");
            pauseNoted = true;
            return "the camera's settings didn't answer a little earlier, so they weren't checked";
        }
        if (!active()) return apply(gate);
        long t0 = now();
        int n = IDS.length;
        int[] ours = new int[n], yours = new int[n], cam = new int[n];
        boolean[] wasLeft = new boolean[n];
        Properties p;
        try {
            p = load();
            for (int i = 0; i < n; i++) {
                ours[i] = Integer.parseInt(p.getProperty("ours." + hex(IDS[i]), String.valueOf(NEUTRAL[i])).trim());
                yours[i] = Integer.parseInt(p.getProperty("yours." + hex(IDS[i]), String.valueOf(ours[i])).trim());
            }
            // one apply() had to leave (outside what Latent may write): neutral is what it's set to once it's back in range
            for (int i = 0; i < n; i++) if (ours[i] < LO[i] || ours[i] > HI[i]) wasLeft[i] = true;
        } catch (Exception e) {
            log.append("neutral: not checked (NEUTRAL.TXT: ").append(Probe.why(e)).append(")\r\n");
            return "Latent's note of your settings can't be read (NEUTRAL.TXT), so nothing was changed";
        }
        try {
            if (!(Boolean) timed(new HardwareDevelop.Call() { public Object run() { return CameraSettings.available(); } })) {
                log.append("neutral: not checked (").append(CameraSettings.lastError()).append(")\r\n");
                return "camera settings unavailable: " + CameraSettings.lastError();
            }
            for (int i = 0; i < n; i++) cam[i] = timedRead(IDS[i]);
        } catch (HardwareDevelop.Stuck e) {
            hung("no answer in " + READ_MS + " ms");
            return "the camera's settings didn't answer";
        } catch (Exception e) {
            String why = Probe.why(e);
            if (noAnswer(why)) hung(why);
            else log.append("neutral: not checked (").append(why).append(")\r\n");
            return "couldn't read the camera's settings (" + why + ")";
        }
        StringBuilder raw = new StringBuilder(), moved = new StringBuilder(), outside = new StringBuilder();
        List<Integer> changed = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            raw.append(i == 0 ? "" : " ").append(hex(IDS[i])).append('=').append(cam[i]);
            if (wasLeft[i] && cam[i] >= LO[i] && cam[i] <= HI[i]) ours[i] = NEUTRAL[i];   // back in range (HDR off, say): neutral from now
            if (cam[i] == ours[i]) continue;
            String step = hex(IDS[i]) + " " + ours[i] + "->" + cam[i];
            if (cam[i] < LO[i] || cam[i] > HI[i]) { outside.append(outside.length() == 0 ? "" : ", ").append(step); continue; }
            moved.append(moved.length() == 0 ? "" : ", ").append(step);
            changed.add(i);
        }
        log.append("neutral: read back in ").append(now() - t0).append(" ms: ").append(raw).append("\r\n");
        String left = outside.length() == 0 ? null : "not all neutral: " + outside + " is outside what Latent may set";
        if (outside.length() > 0) log.append("neutral: changed outside what Latent may write: ").append(outside).append("; left as it is\r\n");
        if (changed.isEmpty()) {
            if (left == null) log.append("neutral: as Latent set them\r\n");
            return left;
        }
        // what you changed is yours from now on (it comes back with the rest): on the card before anything is written
        for (int i : changed) {
            yours[i] = cam[i];
            p.setProperty("yours." + hex(IDS[i]), String.valueOf(cam[i]));
            p.setProperty("ours." + hex(IDS[i]), String.valueOf(ours[i]));   // NEUTRAL for one apply() had left
        }
        if (changed.contains(0)) for (int i = 1; i <= 2; i++) {   // a WB mode changed in the menu: the grid read with it is that mode's, yours too
            yours[i] = cam[i];
            p.setProperty("yours." + hex(IDS[i]), String.valueOf(cam[i]));
        }
        p.setProperty("summary", describe(yours));
        try {
            save(p);
        } catch (IOException e) {
            log.append("neutral: not set again (the note couldn't be updated: ").append(e.getMessage()).append(")\r\n");
            return "couldn't keep a note of your settings, so nothing was changed (" + e.getMessage() + ")";
        }
        log.append("neutral: changed since Latent set them: ").append(moved).append(" (yours now: ").append(describe(yours))
           .append("); setting neutral again\r\n");
        boolean[] undone = new boolean[1];
        String why = writeAll(changed, ours, cam, gate, undone);
        if (why != null) return (undone[0] ? "left as it was: " : "not fully set to neutral: ") + why;   // the note keeps yours either way
        log.append("neutral: set again for shooting\r\n");
        return left;
    }

    /**
     * Sets the camera to neutral, keeping your values (the note first). Returns null when done, or why nothing (or not
     * everything) changed.
     */
    String apply(Gate gate) {
        if (active()) return null;
        if (stuck) return "the camera's settings didn't answer earlier; turn the camera off and on";
        int n = IDS.length;
        int[] yours = new int[n];
        try {
            if (!available()) return "camera settings unavailable: " + CameraSettings.lastError();
            for (int i = 0; i < n; i++) yours[i] = readByte(IDS[i]);   // all or nothing
        } catch (Exception e) {
            return "couldn't read your settings, so nothing was changed (" + Probe.why(e) + ")";
        }
        // a value outside what Latent may write (HDR's DRO, say) is left as it is: it could never be put back
        int[] target = NEUTRAL.clone();
        StringBuilder outside = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (yours[i] >= LO[i] && yours[i] <= HI[i]) continue;
            target[i] = yours[i];
            outside.append(outside.length() == 0 ? "" : ", ").append(hex(IDS[i])).append(' ').append(yours[i]);
        }
        Properties p = new Properties();
        for (int i = 0; i < n; i++) {
            p.setProperty("yours." + hex(IDS[i]), String.valueOf(yours[i]));
            p.setProperty("ours." + hex(IDS[i]), String.valueOf(target[i]));
        }
        p.setProperty("summary", describe(yours));
        try {
            save(p);   // on the card before any write, so it can always be undone
        } catch (IOException e) {
            return "couldn't keep a note of your settings, so nothing was changed (" + e.getMessage() + ")";
        }
        if (outside.length() > 0) log.append("neutral: left as they are (outside what Latent may write): ").append(outside).append("\r\n");
        List<Integer> todo = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) if (yours[i] != target[i]) todo.add(i);
        boolean[] undone = new boolean[1];
        String why = writeAll(todo, target, yours, gate, undone);
        if (why != null) {
            if (undone[0]) deleteNote();   // all as it was: nothing to put back later
            return (undone[0] ? "left as it was: " : "not fully set to neutral: ") + why;   // else the note stays for restore
        }
        log.append("neutral: applied; yours were ").append(describe(yours)).append("\r\n");
        return outside.length() == 0 ? null : "not all neutral: " + outside + " is outside what Latent may set";
    }

    /**
     * Writes target[i] for each i given (IDS order), each read back, then one sync. A WB mode written can move its
     * fine-tune copies, so then all 11 are read once more and any not at target set too. On a failure what this batch
     * changed is put back to before[i] while the gate allows (undone[0] says if all was). Returns null, or why not.
     */
    private String writeAll(List<Integer> todo, int[] target, int[] before, Gate gate, boolean[] undone) {
        if (todo.isEmpty()) return null;
        List<Integer> written = new ArrayList<Integer>();
        String why = null;
        boolean unsure = false;   // a write that failed so that it may have reached the camera, or may yet
        try {
            for (int i : todo) {
                if (!gate.mayWrite()) { why = "Latent was left"; break; }
                why = writeByte(IDS[i], target[i]);
                if (why != null) break;
                written.add(i);
            }
            if (why == null && written.contains(0)) {   // the mode first moved: its fine-tune copies may have followed it
                for (int i = 1; i <= 4 && why == null; i++) {
                    if (before[i] < LO[i] || before[i] > HI[i]) continue;   // outside what Latent may write: left, as said
                    if (readByte(IDS[i]) == target[i]) continue;
                    if (!gate.mayWrite()) { why = "Latent was left"; break; }
                    why = writeByte(IDS[i], target[i]);
                    if (why == null && !written.contains(i)) written.add(i);
                }
            }
        } catch (Exception e) {
            why = Probe.why(e);
            unsure = true;
        }
        if (why == null) {
            sync(gate);
            return null;
        }
        boolean back = !unsure && !noAnswer(why) && !stuck;
        for (int i : written) {   // put back what this batch changed: never with Latent away
            if (!gate.mayWrite()) { back = false; break; }
            try { back &= writeByte(IDS[i], before[i]) == null; } catch (Exception e) { back = false; break; }
        }
        if (!written.isEmpty()) sync(gate);
        undone[0] = back;
        log.append("neutral: not set (").append(why).append(")").append(back ? ", undone\r\n" : ", NOT fully undone\r\n");
        return why;
    }

    /** A read neutralize() made that didn't answer within HUNG_PAUSE_MS of now, on the camera's clock since it started (one that went back: it restarted). */
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

    /** One of neutralize()'s reads: READ_MS, and a timeout is kept as pending (the caller notes it). */
    private static int timedRead(final int id) throws Exception {
        byte[] b = (byte[]) timed(new HardwareDevelop.Call() { public Object run() { return CameraSettings.read(id); } });
        if (b == null || b.length != 1) throw new IOException(b == null ? CameraSettings.lastError() : hex(id) + " is " + b.length + " bytes");
        return b[0];
    }

    private Properties load() throws IOException {
        Properties p = new Properties();
        FileInputStream in = new FileInputStream(note());
        try { p.load(in); } finally { in.close(); }
        return p;
    }

    /** Puts your settings back (only those still as Latent set them). Returns null when done, or why not. */
    String restore(Gate gate) {
        if (!active()) return null;
        if (stuck) return "the camera's settings didn't answer earlier; turn the camera off and on";
        Properties p;
        try {
            p = load();
        } catch (IOException e) {
            return "couldn't read NEUTRAL.TXT: " + e.getMessage();
        }
        String why = null;
        int kept = 0;
        boolean wrote = false;
        int[] after = NEUTRAL.clone();   // what the camera holds once done, for the screen
        try {
            if (!available()) return "camera settings unavailable: " + CameraSettings.lastError();
            for (int i = 0; i < IDS.length; i++) {
                String y = p.getProperty("yours." + hex(IDS[i])), o = p.getProperty("ours." + hex(IDS[i]));
                int now = readByte(IDS[i]);
                after[i] = now;
                if (y == null || o == null) continue;
                int yours = Integer.parseInt(y.trim()), ours = Integer.parseInt(o.trim());
                if ((i == 1 || i == 2) && after[0] != Integer.parseInt(p.getProperty("yours." + hex(IDS[0]), "1").trim())) continue;   // on a mode you chose since: its own grid
                if (now == yours) continue;
                if (yours < LO[i] || yours > HI[i]) continue;   // left as it was by apply (outside what Latent may write)
                if (now != ours) { kept++; continue; }   // changed by you since: yours now
                if (!gate.mayWrite()) { why = "Latent was left"; break; }
                wrote = true;
                String w = writeByte(IDS[i], yours);
                if (w != null && why == null) why = w;
                if (w == null) after[i] = yours;
            }
        } catch (Exception e) {
            why = Probe.why(e);
        }
        if (wrote) sync(gate);
        if (why != null) {
            log.append("neutral: restore incomplete (").append(why).append("); tried again next time\r\n");
            return "your camera settings couldn't all be put back (" + why + "); Latent will try again";
        }
        deleteNote();
        restored = describe(after);
        log.append("neutral: restored; the camera now: ").append(restored).append(kept > 0 ? " (" + kept + " you changed meanwhile left as they are)" : "").append("\r\n");
        return null;
    }

    /** After restore(): your settings as the camera now has them, in the menus' words ("already neutral" if so), or null. */
    String restored;

    /** What the note says your settings are, for the screen ("A2 · M2, contrast +1, …"), or null. */
    String yoursSummary() {
        try {
            return load().getProperty("summary");
        } catch (IOException e) {
            return null;
        }
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
        lastWriteAt = now();   // before the request: one refused, or never answered, may still have reached the camera
        int r = (Integer) call(new HardwareDevelop.Call() { public Object run() { return CameraSettings.write(id, value); } });
        lastWriteAt = now();
        if (r != 0) return CameraSettings.lastError();
        int back = readByte(id);
        if (back != value) return hex(id) + " reads " + back + " after writing " + value;
        log.append("  wrote ").append(hex(id)).append(" = ").append(value).append("\r\n");
        return null;
    }

    /** Asks the camera to keep what was written: a request like a write, so only while the gate allows. */
    private void sync(Gate gate) {
        if (!gate.mayWrite()) { log.append("  sync: not asked (Latent was left)\r\n"); return; }
        try {
            lastWriteAt = now();
            call(new HardwareDevelop.Call() { public Object run() { CameraSettings.sync(); return null; } });
            lastWriteAt = now();
        } catch (Exception e) {
            log.append("  sync: ").append(Probe.why(e)).append("\r\n");
        }
    }

    /** One request, at most 8 s; one that doesn't answer marks the service stuck, and no other is sent this run. */
    private static Object call(HardwareDevelop.Call c) throws Exception {
        if (stuck) throw new IOException("the camera's settings did not answer earlier");
        try {
            return HardwareDevelop.guarded(c, 8000);
        } catch (HardwareDevelop.Stuck e) {
            stuck = true;
            throw e;
        }
    }

    /** The note onto the card and synced, replacing the old one by way of NEUTRAL.OLD (never a moment with none). */
    private void save(Properties p) throws IOException {
        File tmp = new File(file.getParentFile(), "NEUTRAL.TMP");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            p.store(out, "Latent: your camera settings while Latent shoots neutral (put back by Exit Latent, or after developing)");
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (file.exists()) {
            old.delete();
            if (!file.renameTo(old)) throw new IOException("cannot replace " + file);
        }
        if (!tmp.renameTo(file)) {
            old.renameTo(file);
            throw new IOException("cannot write " + file);
        }
        old.delete();
    }

    private void deleteNote() {
        file.delete();
        old.delete();
    }

    private static String hex(int id) { return String.format("0x%08x", id); }
}
