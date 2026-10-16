package com.latent.camera;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Handler;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Can Latent get Sony's image memory back without being left? The darkroom needs that memory, and
 * once a process has opened the camera it stays taken until that process ends (v0.23-0.24). So:
 * open the camera the proven way (live view only: no picture, no live frames), give it back, then
 * end Latent's process while a small screen of Latent's own (Relaunch, in a process of its own)
 * stays in front, and start Latent again from it. The new process checks the memory.
 *
 * First, read only: copies Sony's installed apps (Smart Remote among them, which streams live view
 * while the camera shoots) and their native libraries to LATENT/APPS, to be read on a computer.
 *
 * Steps 0-2 run here (UI thread, as the camera needs); 3-4 in the new process (resume). The steps'
 * results so far are kept in prefs across the restart. Log: LATENT/RESTART.TXT.
 */
final class RestartTest implements SurfaceHolder.Callback {
    static final String[] STEPS = { "Sony's apps copied", "Open the camera", "Camera given back", "Latent restarted", "Image memory back" };
    static final int S_APPS = 0, S_OPEN = 1, S_BACK = 2, S_RESTART = 3, S_MEMORY = 4;
    static final int OK = Probe.OK, FAIL = Probe.FAIL, RUN = Probe.RUN, INFO = Probe.INFO;
    static final long LIVE_MS = 1500;
    /** Prefs: when the restart was asked for (uptime ms), and the steps so far ("status|detail" each, by \n). */
    static final String P_AT = "restartTestAt", P_STEPS = "restartTestSteps";

    private final Activity activity;
    private final File card;
    private final SurfaceView surface;
    private final Probe.Listener out;
    private final LiveViewTest.Host host;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler();
    private final StringBuilder log = new StringBuilder();
    private final long t0 = SystemClock.uptimeMillis();
    private final int[] status = new int[STEPS.length];
    private final String[] detail = new String[STEPS.length];
    private Object cameraEx;
    private Camera camera;
    private boolean previewing, closed;

    RestartTest(Activity activity, File card, SurfaceView surface, SharedPreferences prefs, Probe.Listener out, LiveViewTest.Host host) {
        this.activity = activity; this.card = card; this.surface = surface; this.prefs = prefs; this.out = out; this.host = host;
        Arrays.fill(status, -1);
        Arrays.fill(detail, "");
    }

    private void report(int i, int st, String d) {
        if (st != RUN) {
            log.append(STEPS[i]).append(": ").append(st == OK ? "ok" : st == FAIL ? "FAILED" : "info").append(" - ").append(d).append("\r\n");
            status[i] = st;
            detail[i] = d;
        }
        out.step(i, st, d);
    }

    private void note(String line) { log.append("  ").append(SystemClock.uptimeMillis() - t0).append(" ms  ").append(line).append("\r\n"); }

    void start() {
        log.append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date())).append(" Latent restart test\r\n");
        out.step(S_APPS, RUN, "copying…");
        new Thread(new Runnable() {
            public void run() {
                final String d = copyApps(activity, card, log);
                ui.post(new Runnable() { public void run() { report(S_APPS, d.startsWith("!") ? FAIL : OK, d.startsWith("!") ? d.substring(1) : d); flush(); openCamera(); } });
            }
        }, "apps-copy").start();
    }

    // ------------------------------------------------------------------------------ Sony's apps, read only

    /**
     * Copies each installed com.sony app's package and native libraries to LATENT/APPS (8.3 names, with
     * INDEX.TXT naming each), and lists /system/app and /system/etc/memmap. Only reads the camera's
     * files. Skips files already copied (same size). Returns a line for the screen ("!..." on failure).
     */
    static String copyApps(Activity a, File card, StringBuilder log) {
        File to = new File(Library.home(card), "APPS");
        to.mkdirs();
        StringBuilder index = new StringBuilder();
        int n = 0, files = 0, apps = 0;
        long bytes = 0;
        try {
            PackageManager pm = a.getPackageManager();
            List<ApplicationInfo> list = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : list) {
                if (!ai.packageName.startsWith("com.sony")) continue;
                apps++;
                String version = "?";
                try { version = pm.getPackageInfo(ai.packageName, 0).versionName; } catch (Throwable ignored) { }
                index.append(ai.packageName).append(" ").append(version).append(" uid ").append(ai.uid).append(" flags 0x").append(Integer.toHexString(ai.flags)).append("\r\n");
                String[] srcs = ai.publicSourceDir != null && !ai.publicSourceDir.equals(ai.sourceDir) ? new String[] { ai.sourceDir, ai.publicSourceDir } : new String[] { ai.sourceDir };
                for (String s : srcs) {
                    if (s == null) continue;
                    File src = new File(s), dst = new File(to, String.format(Locale.US, "A%04d.APK", ++n));
                    long c = copy(src, dst);
                    index.append("  ").append(dst.getName()).append('\t').append(s).append('\t').append(c < 0 ? "unreadable" : String.valueOf(src.length())).append("\r\n");
                    if (c > 0) { files++; bytes += c; }
                    // a system app's code is beside it, optimised (SmartRemote.odex)
                    File odex = new File(s.replaceAll("\\.apk$", ".odex"));
                    if (s.endsWith(".apk") && odex.isFile()) {
                        File od = new File(to, String.format(Locale.US, "A%04d.ODX", n));
                        long co = copy(odex, od);
                        index.append("  ").append(od.getName()).append('\t').append(odex.getPath()).append('\t').append(co < 0 ? "unreadable" : String.valueOf(odex.length())).append("\r\n");
                        if (co > 0) { files++; bytes += co; }
                    }
                }
                String libDir = ai.nativeLibraryDir != null ? ai.nativeLibraryDir : ai.dataDir + "/lib";
                File[] libs = new File(libDir).listFiles();
                index.append("  libraries in ").append(libDir).append(": ").append(libs == null ? "not readable" : libs.length + " file(s)").append("\r\n");
                if (libs != null) {
                    Arrays.sort(libs);
                    for (File l : libs) {
                        if (!l.isFile()) continue;
                        File dst = new File(to, String.format(Locale.US, "L%04d.BIN", ++n));
                        long c = copy(l, dst);
                        index.append("  ").append(dst.getName()).append('\t').append(l.getPath()).append('\t').append(c < 0 ? "unreadable" : String.valueOf(l.length())).append("\r\n");
                        if (c > 0) { files++; bytes += c; }
                    }
                }
            }
            for (String dir : new String[] { "/system/app", "/system/etc/memmap", "/data/app", "/system/lib" }) {
                File[] l = new File(dir).listFiles();
                index.append(dir).append(": ").append(l == null ? "not readable" : l.length + " file(s)").append("\r\n");
                if (l != null) { Arrays.sort(l); for (File f : l) index.append("  ").append(f.getName()).append('\t').append(f.length()).append("\r\n"); }
            }
        } catch (Throwable e) {
            index.append("stopped: ").append(Probe.why(e)).append("\r\n");
            write(new File(to, "INDEX.TXT"), index.toString());
            return "!" + Probe.why(e);
        }
        write(new File(to, "INDEX.TXT"), index.toString());
        log.append("  apps: ").append(apps).append(" Sony app(s), ").append(files).append(" file(s) copied (").append(bytes / 1024).append(" KB) to LATENT/APPS\r\n");
        return apps + " Sony app(s), " + n + " file(s) in LATENT/APPS";
    }

    /** Bytes copied, 0 if already there, -1 if unreadable. */
    private static long copy(File src, File dst) {
        if (dst.exists() && dst.length() == src.length()) return 0;
        try {
            InputStream in = new FileInputStream(src);
            FileOutputStream o = new FileOutputStream(dst);
            long total = 0;
            try {
                byte[] b = new byte[65536];
                for (int k; (k = in.read(b)) > 0; ) { o.write(b, 0, k); total += k; }
            } finally { in.close(); o.close(); }
            return total;
        } catch (Exception e) {
            return -1;
        }
    }

    private static void write(File f, String text) {
        try {
            FileOutputStream o = new FileOutputStream(f);
            try { o.write(text.getBytes("UTF-8")); } finally { o.close(); }
        } catch (Exception ignored) {
            // the screen says the same
        }
    }

    // ------------------------------------------------------------------------------ the camera, the proven way

    private void openCamera() {
        out.step(S_OPEN, RUN, "opening…");
        Viewfinder.opened = true;   // the image memory is the camera's now, until this process ends
        long t = SystemClock.uptimeMillis();
        try {
            Class<?> cx = Class.forName("com.sony.scalar.hardware.CameraEx");
            Class<?> oc = Class.forName("com.sony.scalar.hardware.CameraEx$OpenOptions");
            Object options = null;
            String[] ids = (String[]) Class.forName("com.sony.scalar.provider.AvindexStore").getMethod("getExternalMediaIds").invoke(null);
            if (ids != null && ids.length > 0) {
                options = oc.newInstance();
                oc.getMethod("setTargetMedia", String.class).invoke(options, ids[0]);
                try { oc.getMethod("setRecordingMode", int.class).invoke(options, 0); } catch (Throwable ignored) { }
            }
            cameraEx = cx.getMethod("open", int.class, oc).invoke(null, 0, options);
            camera = (Camera) cx.getMethod("getNormalCamera").invoke(cameraEx);
        } catch (Throwable e) {
            report(S_OPEN, FAIL, Probe.why(e));
            close(false);
            return;
        }
        note("camera open in " + (SystemClock.uptimeMillis() - t) + " ms");
        flush();
        host.showLive(true);
        SurfaceHolder h = surface.getHolder();
        h.addCallback(this);
        if (h.getSurface() != null && h.getSurface().isValid()) surfaceCreated(h);
        ui.postDelayed(new Runnable() {
            public void run() { if (!previewing && !closed) { report(S_OPEN, FAIL, "no live view surface"); close(false); } }
        }, 4000);
    }

    public void surfaceCreated(SurfaceHolder h) {
        if (previewing || closed || camera == null) return;
        try {
            camera.setPreviewDisplay(h);
            camera.startPreview();
            previewing = true;
        } catch (Throwable e) {
            report(S_OPEN, FAIL, "live view: " + Probe.why(e));
            close(false);
            return;
        }
        report(S_OPEN, OK, "live view on, no picture taken");
        host.note("Restart test: the camera goes back in a moment");
        ui.postDelayed(new Runnable() { public void run() { close(true); } }, LIVE_MS);
    }

    public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) { }

    public void surfaceDestroyed(SurfaceHolder h) { if (!closed) close(false); }

    /** Gives the camera back (Viewfinder's order: stop live view, the Android camera, then CameraEx); then restarts, if asked. */
    void close(boolean thenRestart) {
        if (closed) return;
        closed = true;
        ui.removeCallbacksAndMessages(null);
        out.step(S_BACK, RUN, "releasing…");
        try { if (camera != null && previewing) camera.stopPreview(); } catch (Throwable e) { note("stopPreview: " + Probe.why(e)); }
        try { if (camera != null) camera.release(); } catch (Throwable e) { note("camera release: " + Probe.why(e)); }
        try { if (cameraEx != null) cameraEx.getClass().getMethod("release").invoke(cameraEx); } catch (Throwable e) { note("release: " + Probe.why(e)); }
        try { surface.getHolder().removeCallback(this); } catch (Throwable ignored) { }
        camera = null;
        cameraEx = null;
        host.showLive(false);
        host.note("");
        String mem = memory();
        StringBuilder hl = new StringBuilder();
        String full;
        long t = SystemClock.uptimeMillis();
        try { new HardwareDevelop(activity, card, hl, null).prepare(true); full = "passed"; }
        catch (Throwable e) { full = "failed (" + Probe.why(e) + ")"; }
        note("in this process after the release: 64 KB buffer " + mem + "; the darkroom's check " + full + " in "
                + (SystemClock.uptimeMillis() - t) + " ms: " + hl.toString().trim().replace("\r\n", "; "));
        report(S_BACK, OK, "released; in this process the buffer is " + mem + ", the darkroom's check " + (full.startsWith("passed") ? "passed" : "failed"));
        if (!thenRestart) { flush(); host.finished(); return; }
        out.step(S_RESTART, RUN, "restarting…");
        long at = SystemClock.uptimeMillis();
        note("restart asked for");
        flush();
        SharedPreferences.Editor e = prefs.edit().putLong(P_AT, at).putString(P_STEPS, saveSteps());
        e.commit();
        // Latent's own screen stays in front while this process ends, so the camera never leaves Latent
        Intent i = new Intent(activity, Relaunch.class);
        i.putExtra(Relaunch.EXTRA_PID, android.os.Process.myPid());
        i.putExtra(Relaunch.EXTRA_AT, at);
        activity.startActivity(i);
        activity.overridePendingTransition(0, 0);
        // MainActivity.onPause ends this process (the camera was opened here)
    }

    private String saveSteps() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < STEPS.length; i++) b.append(status[i]).append('|').append(detail[i].replace('\n', ' ')).append('\n');
        return b.toString();
    }

    /** The steps as they were before the restart, into status and detail (for the new process). */
    static void restoreSteps(SharedPreferences prefs, int[] status, String[] detail) {
        String[] lines = prefs.getString(P_STEPS, "").split("\n");
        for (int i = 0; i < lines.length && i < status.length; i++) {
            int bar = lines[i].indexOf('|');
            if (bar <= 0) continue;
            try { status[i] = Integer.parseInt(lines[i].substring(0, bar)); } catch (NumberFormatException ignored) { }
            detail[i] = lines[i].substring(bar + 1);
        }
    }

    /**
     * Whether Sony's image memory can be had right now: one 64 KB buffer from the camera's processor,
     * released at once (what the darkroom asks for first). "available", or why not.
     */
    static String memory() {
        Object proc = null, buf = null;
        try {
            Class<?> dsp = Class.forName("com.sony.scalar.hardware.DSP");
            proc = dsp.getMethod("createProcessor", String.class).invoke(null, "sony-di-dsp");
            if (proc == null) return "no processor";
            buf = HardwareDevelop.call(proc, "createBuffer", 65536);
            return buf == null ? "taken (no buffer)" : "available";
        } catch (Throwable e) {
            return "taken (" + Probe.why(e) + ")";
        } finally {
            try { if (buf != null) buf.getClass().getMethod("release").invoke(buf); } catch (Throwable ignored) { }
            try { if (proc != null) proc.getClass().getMethod("release").invoke(proc); } catch (Throwable ignored) { }
        }
    }

    private void flush() {
        try {
            FileOutputStream o = new FileOutputStream(new File(Library.home(card), "RESTART.TXT"), true);
            try { o.write(log.toString().getBytes("UTF-8")); } finally { o.close(); }
            log.setLength(0);
        } catch (Exception ignored) {
            // the screen says the same
        }
    }

    /** In the new process: how long the restart took, and whether the memory is back. Worker thread; returns the log lines. */
    static String finish(android.content.Context ctx, File card, SharedPreferences prefs, long createdAt, Probe.Listener out) {
        long at = prefs.getLong(P_AT, 0);
        StringBuilder log = new StringBuilder();
        long ms = at > 0 ? createdAt - at : -1;
        String restart = ms >= 0 ? "back in " + ms + " ms" : "back (time unknown)";
        out.step(S_RESTART, OK, restart);
        log.append(STEPS[S_RESTART]).append(": ok - ").append(restart).append("\r\n");
        out.step(S_MEMORY, RUN, "checking…");
        String mem = memory();
        log.append("  64 KB buffer at once: ").append(mem).append("\r\n");
        // the darkroom's own check (Sony's buffers against the private read/write, 64 KB to 2 MB), until it passes:
        // v0.33.0 found the buffer at once, yet the darkroom's check failed ~10 s after the restart
        boolean ok = false;
        long since = SystemClock.uptimeMillis() - at, first = SystemClock.uptimeMillis();
        String last = "";
        for (long wait : new long[] { 0, 5000, 10000, 20000, 30000, 45000, 60000, 90000 }) {
            long due = first + wait;
            while (SystemClock.uptimeMillis() < due) { try { Thread.sleep(200); } catch (InterruptedException e) { break; } }
            long t = SystemClock.uptimeMillis();
            StringBuilder hl = new StringBuilder();
            try {
                new HardwareDevelop(ctx, card, hl, null).prepare(true);
                ok = true;
            } catch (Throwable e) {
                last = Probe.why(e);
            }
            long after = t - at;
            log.append("  darkroom check ").append(after / 1000).append(" s after the restart was asked (").append(SystemClock.uptimeMillis() - t).append(" ms): ")
                    .append(ok ? "passed" : "failed, " + hl.toString().trim().replace("\r\n", "; ")).append("\r\n");
            out.step(S_MEMORY, ok ? OK : RUN, ok ? "the darkroom can run, " + after / 1000 + " s after the camera went back" : "not yet (" + after / 1000 + " s)…");
            append(card, log);
            log.setLength(0);
            if (ok) break;
        }
        if (!ok) out.step(S_MEMORY, FAIL, "still taken after 90 s: " + last);
        log.append(STEPS[S_MEMORY]).append(": ").append(ok ? "ok" : "FAILED").append(" (first ask ").append(since).append(" ms after the restart was asked)\r\n");
        prefs.edit().remove(P_AT).remove(P_STEPS).commit();
        append(card, log);
        return log.toString();
    }

    private static void append(File card, StringBuilder log) {
        try {
            FileOutputStream o = new FileOutputStream(new File(Library.home(card), "RESTART.TXT"), true);
            try { o.write(log.toString().getBytes("UTF-8")); } finally { o.close(); }
        } catch (Exception ignored) {
            // the screen says the same
        }
    }
}
