package com.latent.camera;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Camera;
import android.os.Handler;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;

/**
 * Does the camera store pictures while Latent runs the film look on its live frames? Exactly the
 * viewfinder's way: the frames as Sony's Smart Remote asks for them (320 px), the look on each on
 * Latent's worker at the viewfinder's pace, Latent's own focus and shutter, and Latent off the
 * processor while each picture is written (Viewfinder.STORE_QUIET_MS).
 *
 * The answer comes from the card itself, not only from Sony's catalogue: v0.35.0's test was fooled by
 * catalogue entries a frozen session had left without files. So: two pictures, Sony's catalogue told to
 * look 2.5 s after the last one (the camera writes a picture out then), the camera given back once both
 * are listed, Latent restarted (Relaunch), and the new process counts the new files on the card. Both
 * there: Film look on live view comes on.
 *
 * Kept off every roll (IGNORE.TXT). Log: LATENT/LIVELOOK.TXT, written before every risky step.
 */
final class LiveLookTest implements SurfaceHolder.Callback {
    static final String[] STEPS = { "Open the camera", "Live frames and the look", "Picture 1, look running", "Picture 2, look running",
            "Camera given back", "Latent restarted", "Pictures on the card" };
    static final int S_OPEN = 0, S_FRAMES = 1, S_SHOT1 = 2, S_SHOT2 = 3, S_BACK = 4, S_RESTART = 5, S_CARD = 6;
    static final int OK = Probe.OK, FAIL = Probe.FAIL, RUN = Probe.RUN, INFO = Probe.INFO;
    static final long FIRST_FRAME_MS = 15000, FRAMES_BEFORE_MS = 2500, FOCUS_TIMEOUT_MS = 6000, SHUTTER_TIMEOUT_MS = 10000,
            BETWEEN_MS = 8000;
    /** Prefs across the restart: when it was asked for, the card's highest photo before, pictures taken, the steps so far. */
    static final String P_AT = "lookTestAt", P_BEFORE = "lookTestBefore", P_SHOTS = "lookTestShots", P_STEPS = "lookTestSteps";

    private final Activity activity;
    private final File card;
    private final SurfaceView surface;
    private final SharedPreferences prefs;
    private final Probe.Listener out;
    private final LiveViewTest.Host host;
    private final Handler ui = new Handler();
    private final StringBuilder log = new StringBuilder();
    private final long t0 = SystemClock.uptimeMillis();
    private final int[] status = new int[STEPS.length];
    private final String[] detail = new String[STEPS.length];
    private Thread.UncaughtExceptionHandler previousHandler;

    private Object cameraEx, sequence;
    private Camera camera;
    private String mediaId;
    private boolean previewing, closed, held;
    private int highestBefore;

    // frames and the look, as the viewfinder does them (its thread, its priority, its pace)
    private final String lut;
    private final int[] params = new int[FilmCore.COUNT];
    private final java.util.concurrent.ExecutorService looker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final int[] px = new int[Viewfinder.FRAME_W * Viewfinder.FRAME_H], info = new int[FilmCore.L_COUNT];
    private Method mFrames, mSize, mRead, mRelease;
    private long framesAskedAt, firstFrameAt, lastFrameAt, lastLookAt, quietUntil;
    private int frames, grabFails, looks, lookMsSum, lookFails, levelSure;
    private volatile boolean looking;

    // pictures
    private int shotNo, taken;
    private boolean shooting, focusing;
    private long shutterAt, lastShutterAt;

    LiveLookTest(Activity activity, File card, SurfaceView surface, SharedPreferences prefs, Stock stock, Probe.Listener out, LiveViewTest.Host host) {
        this.activity = activity; this.card = card; this.surface = surface; this.prefs = prefs; this.out = out; this.host = host;
        this.lut = stock == null ? null : stock.lut.getPath();
        params[FilmCore.HIGHLIGHT] = stock == null ? 0 : stock.highlight;
        params[FilmCore.SHADOW] = stock == null ? 0 : stock.shadow;
        params[FilmCore.GRAIN_SIZE] = 1;
        params[FilmCore.QUALITY] = 90;
        params[FilmCore.THREADS] = 1;
        java.util.Arrays.fill(status, -1);
        java.util.Arrays.fill(detail, "");
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

    private int flushed;

    /** The log so far onto the card (only what's new), so a camera that hangs still leaves it. */
    private void flush() {
        if (flushed >= log.length()) return;
        String add = log.substring(flushed);
        flushed = log.length();
        append(card, add);
    }

    private static void append(File card, String text) {
        try {
            FileOutputStream o = new FileOutputStream(new File(Library.home(card), "LIVELOOK.TXT"), true);
            try { o.write(text.getBytes("UTF-8")); } finally { o.close(); }
        } catch (Exception ignored) {
            // the screen says the same
        }
    }

    // ------------------------------------------------------------------------------ open

    void start() {
        log.append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date())).append(" Latent live look test (v2: checked on the card)\r\n");
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try { note("crash: " + Probe.why(e)); flush(); } catch (Throwable ignored) { }
                if (previousHandler != null) previousHandler.uncaughtException(t, e);
            }
        });
        highestBefore = Roll.highest(Library.photos(card));
        out.step(S_OPEN, RUN, "opening…");
        Viewfinder.opened = true;   // the image memory is the camera's now, until this process ends
        long t = SystemClock.uptimeMillis();
        try {
            Class<?> cx = Class.forName("com.sony.scalar.hardware.CameraEx");
            Class<?> oc = Class.forName("com.sony.scalar.hardware.CameraEx$OpenOptions");
            Object options = null;
            String[] ids = (String[]) Class.forName("com.sony.scalar.provider.AvindexStore").getMethod("getExternalMediaIds").invoke(null);
            if (ids != null && ids.length > 0) {
                mediaId = ids[0];
                options = oc.newInstance();
                oc.getMethod("setTargetMedia", String.class).invoke(options, mediaId);
                try { oc.getMethod("setRecordingMode", int.class).invoke(options, 0); } catch (Throwable ignored) { }
            }
            cameraEx = cx.getMethod("open", int.class, oc).invoke(null, 0, options);
            camera = (Camera) cx.getMethod("getNormalCamera").invoke(cameraEx);
        } catch (Throwable e) {
            report(S_OPEN, FAIL, Probe.why(e));
            finish(false);
            return;
        }
        note("camera open in " + (SystemClock.uptimeMillis() - t) + " ms (media " + mediaId + "); the card's highest photo " + highestBefore);
        flush();
        host.showLive(true);
        SurfaceHolder h = surface.getHolder();
        h.addCallback(this);
        if (h.getSurface() != null && h.getSurface().isValid()) surfaceCreated(h);
        ui.postDelayed(new Runnable() {
            public void run() { if (!previewing && !closed) { report(S_OPEN, FAIL, "no live view surface"); close(); } }
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
            close();
            return;
        }
        report(S_OPEN, OK, "live view on");
        set("setShutterListener", "com.sony.scalar.hardware.CameraEx$ShutterListener", new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("onShutter") && a != null && a.length > 0) shutter((Integer) a[0]);
                return objectMethod(p, m, a);
            }
        });
        startFrames();
    }

    public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) { }

    public void surfaceDestroyed(SurfaceHolder h) { if (!closed && !held) close(); }

    // ------------------------------------------------------------------------------ frames and the look

    private void startFrames() {
        out.step(S_FRAMES, RUN, "asking for frames…");
        note("starting the live frames (" + Viewfinder.FRAME_W + " px) and the look");
        flush();
        try {
            Class<?> cs = Class.forName("com.sony.scalar.hardware.CameraSequence");
            sequence = cs.getMethod("open", Class.forName("com.sony.scalar.hardware.CameraEx")).invoke(null, cameraEx);
            Class<?> oc = Class.forName("com.sony.scalar.hardware.CameraSequence$Options");
            Object o = oc.newInstance();
            option(o, "PREVIEW_FRAME_RATE", 30000);
            option(o, "PREVIEW_FRAME_WIDTH", Viewfinder.FRAME_W);
            option(o, "PREVIEW_FRAME_HEIGHT", 0);
            option(o, "PREVIEW_FRAME_FORMAT", 0x100);
            option(o, "PREVIEW_FRAME_MAX_NUM", 1);
            option(o, "JPEG_COMPRESS_RATE_DENOM", 15);
            option(o, "JPEG_COMPRESS_MAX_SIZE", 100);
            cs.getMethod("startPreviewSequence", oc).invoke(sequence, o);
            mFrames = cs.getMethod("getPreviewSequenceFrames", int.class);
        } catch (Throwable e) {
            report(S_FRAMES, FAIL, Probe.why(e));
            stopFrames();
            close();
            return;
        }
        framesAskedAt = SystemClock.uptimeMillis();
        ui.post(poll);
    }

    /** As the viewfinder's poll: the next frame once the last look is done, nothing while a picture is written. */
    private final Runnable poll = new Runnable() {
        public void run() {
            if (closed || sequence == null) return;
            long now = SystemClock.uptimeMillis();
            if (now < quietUntil) { ui.postDelayed(this, 100); return; }
            if (!looking && now - lastLookAt >= Viewfinder.MIN_FRAME_MS) {
                byte[] f = grab();
                if (f != null) {
                    lastLookAt = now;
                    look(f);
                    if (frames == 1) {
                        firstFrameAt = now;
                        report(S_FRAMES, OK, "the first after " + (now - framesAskedAt) + " ms");
                        flush();
                        ui.postDelayed(new Runnable() { public void run() { nextShot(); } }, FRAMES_BEFORE_MS);
                    }
                }
            }
            if (frames == 0 && now - framesAskedAt > FIRST_FRAME_MS) {   // no frames: nothing to test, nothing pending
                report(S_FRAMES, FAIL, "none in " + FIRST_FRAME_MS / 1000 + " s");
                stopFrames();
                close();
                return;
            }
            ui.postDelayed(this, 40);
        }
    };

    private byte[] grab() {
        Object[] got = null;
        try {
            got = (Object[]) mFrames.invoke(sequence, 1);
            if (got == null || got.length == 0 || got[0] == null) return null;
            Object fr = got[0];
            if (mSize == null) { mSize = fr.getClass().getMethod("getSize"); mRead = fr.getClass().getMethod("read", byte[].class); mRelease = fr.getClass().getMethod("release"); }
            int size = (Integer) mSize.invoke(fr);
            if (size <= 0) return null;
            byte[] jpeg = new byte[size];
            mRead.invoke(fr, jpeg);
            lastFrameAt = SystemClock.uptimeMillis();
            frames++;
            return jpeg;
        } catch (Throwable e) {
            if (grabFails++ < 3) note("frame: " + Probe.why(e));
            return null;
        } finally {
            if (got != null) for (Object fr : got) if (fr != null) try { (mRelease != null ? mRelease : fr.getClass().getMethod("release")).invoke(fr); } catch (Throwable ignored) { }
        }
    }

    private void look(final byte[] jpeg) {
        looking = true;
        looker.execute(new Runnable() {
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE);
                long t = SystemClock.uptimeMillis();
                int rc = FilmCore.live(jpeg, jpeg.length, lut, params, px, Viewfinder.FRAME_W, Viewfinder.FRAME_H, info);
                final int ms = (int) (SystemClock.uptimeMillis() - t);
                final boolean ok = rc == 0, sure = info[FilmCore.L_CONFIDENCE] >= 30;
                ui.post(new Runnable() {
                    public void run() {
                        looking = false;
                        if (ok) { looks++; lookMsSum += ms; if (sure) levelSure++; }
                        else if (lookFails++ < 3) note("look: " + FilmCore.lastError());
                    }
                });
            }
        });
    }

    private void stopFrames() {
        ui.removeCallbacks(poll);
        if (sequence == null) return;
        try { sequence.getClass().getMethod("stopPreviewSequence").invoke(sequence); } catch (Throwable e) { note("stopPreviewSequence: " + Probe.why(e)); }
        try { sequence.getClass().getMethod("release").invoke(sequence); } catch (Throwable e) { note("sequence release: " + Probe.why(e)); }
        sequence = null;
        long span = Math.max(1, lastFrameAt - firstFrameAt);
        note("frames stopped: " + frames + " in all; the look on " + looks + " (" + String.format(Locale.US, "%.1f", looks * 1000.0 / span)
                + " a second over the run, " + (looks > 0 ? lookMsSum / looks : 0) + " ms each), the level sure on " + levelSure);
    }

    // ------------------------------------------------------------------------------ the pictures

    private int step() { return shotNo == 1 ? S_SHOT1 : S_SHOT2; }

    private void nextShot() {
        if (closed) return;
        shotNo++;
        out.step(step(), RUN, "focusing…");
        focusing = true;
        final long t = SystemClock.uptimeMillis();
        final int n = shotNo;
        try {
            camera.autoFocus(new Camera.AutoFocusCallback() {
                public void onAutoFocus(boolean success, Camera c) {
                    if (!focusing || n != shotNo) return;
                    focusing = false;
                    note("picture " + n + ": focus " + (success ? "locked" : "not locked") + " in " + (SystemClock.uptimeMillis() - t) + " ms");
                    shoot();
                }
            });
        } catch (Throwable e) {
            note("autoFocus: " + Probe.why(e));
            focusing = false;
            shoot();
            return;
        }
        ui.postDelayed(new Runnable() { public void run() { if (focusing && n == shotNo) { focusing = false; note("focus timed out"); shoot(); } } }, FOCUS_TIMEOUT_MS);
    }

    private void shoot() {
        if (closed) return;
        out.step(step(), RUN, "shooting…");
        shooting = true;
        shutterAt = SystemClock.uptimeMillis();
        quietUntil = shutterAt + SHUTTER_TIMEOUT_MS + Viewfinder.STORE_QUIET_MS;   // off the processor from the press
        note("picture " + shotNo + ": shutter, " + frames + " frames and " + looks + " looks so far");
        flush();   // if the camera hangs from here, the card already says how far it got
        try {
            call("burstableTakePicture");
        } catch (Throwable e) {
            note("burstableTakePicture: " + Probe.why(e));
            safely("cancelTakePicture");
            shooting = false;
            quietUntil = 0;
            report(step(), FAIL, "the camera wouldn't shoot: " + Probe.why(e));
            afterPicture();
            return;
        }
        ui.postDelayed(new Runnable() {
            public void run() {
                if (!shooting) return;
                shooting = false;
                note("no shutter answer in " + SHUTTER_TIMEOUT_MS / 1000 + " s");
                safely("cancelTakePicture");
                report(step(), FAIL, "no answer from the shutter");
                hold("a picture may be on its way with no answer from the shutter");
            }
        }, SHUTTER_TIMEOUT_MS);
    }

    /** The shutter's answer: 0 taken, 1 cancelled, 2 error. The capture is always ended (cancelTakePicture). */
    private void shutter(int st) {
        if (!shooting) return;
        shooting = false;
        long now = SystemClock.uptimeMillis();
        safely("cancelTakePicture");
        try { camera.cancelAutoFocus(); } catch (Throwable ignored) { }
        note("picture " + shotNo + ": shutter status " + st + " after " + (now - shutterAt) + " ms");
        if (st == 0) {
            taken++;
            lastShutterAt = now;
            quietUntil = now + Viewfinder.STORE_QUIET_MS;
            report(step(), OK, "taken; the look paused " + Viewfinder.STORE_QUIET_MS / 1000 + " s while it's written");
        } else {
            quietUntil = 0;
            report(step(), FAIL, st == 1 ? "cancelled by the camera" : "the camera reported an error");
        }
        flush();
        afterPicture();
    }

    private void afterPicture() {
        if (closed) return;
        if (shotNo < 2) ui.postDelayed(new Runnable() { public void run() { nextShot(); } }, BETWEEN_MS);
        else {
            out.step(S_BACK, RUN, "waiting for the pictures to be saved…");
            waitListed();
        }
    }

    /**
     * The camera keeps a new picture in its memory until told to look (Sony's catalogue, as Smart Remote
     * does it: "flushing the file"); told too soon it's lost (v0.35.0), never told it stays there and giving
     * the camera back freezes it (v0.35.1). So: 2.5 s after the last shutter, then every 1.5 s, until the
     * catalogue lists every picture taken; then the camera goes back. Not listed in 30 s: it's kept.
     */
    private void waitListed() {
        if (closed || held) return;
        long since = SystemClock.uptimeMillis() - lastShutterAt;
        if (since < Viewfinder.FLUSH_AFTER_MS) { ui.postDelayed(new Runnable() { public void run() { waitListed(); } }, Viewfinder.FLUSH_AFTER_MS - since); return; }
        if (since > Viewfinder.SAVE_WAIT_MS) { hold(taken - listed + " picture(s) not saved after " + Viewfinder.SAVE_WAIT_MS / 1000 + " s"); return; }
        new Thread(new Runnable() {
            public void run() {
                long t = SystemClock.uptimeMillis();
                String r = "ok";
                int n = 0;
                try {
                    // the camera lists a picture itself once it's stored; told to look, it listed unwritten ones (v0.35.2)
                    Class<?> media = Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
                    android.net.Uri uri = (android.net.Uri) media.getMethod("getContentUri", String.class).invoke(null, mediaId);
                    for (Probe.Row row : Probe.query(activity.getContentResolver(), uri)) if (row.folder * 10000 + row.number > highestBefore) n++;
                } catch (Throwable e) {
                    r = Probe.why(e);
                }
                final int found = n;
                final String res = r + ", " + (SystemClock.uptimeMillis() - t) + " ms";
                ui.post(new Runnable() {
                    public void run() {
                        listed = found;
                        note("the catalogue (" + res + "): " + found + " of " + taken + " listed, " + (SystemClock.uptimeMillis() - lastShutterAt) + " ms after the last shutter");
                        flush();
                        if (found >= taken) handBack();
                        else ui.postDelayed(new Runnable() { public void run() { waitListed(); } }, Viewfinder.FLUSH_EVERY_MS);
                    }
                });
            }
        }, "catalogue-look").start();
    }

    private int listed;

    /** Every picture has had its time: frames off, the camera back, then Latent restarts to count the files. */
    private void handBack() {
        if (closed || held) return;
        stopFrames();
        note("giving the camera back, " + (SystemClock.uptimeMillis() - lastShutterAt) / 1000 + " s after the last picture");
        flush();
        releaseCamera();
        report(S_BACK, OK, "released, " + taken + " picture(s) taken");
        flush();
        if (taken == 0) { finish(false); return; }
        out.step(S_RESTART, RUN, "restarting…");
        long at = SystemClock.uptimeMillis();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < STEPS.length; i++) b.append(status[i]).append('|').append(detail[i].replace('\n', ' ')).append('\n');
        prefs.edit().putLong(P_AT, at).putInt(P_BEFORE, highestBefore).putInt(P_SHOTS, taken).putString(P_STEPS, b.toString()).commit();
        finish(true);
        Intent i = new Intent(activity, Relaunch.class);
        i.putExtra(Relaunch.EXTRA_PID, android.os.Process.myPid());
        i.putExtra(Relaunch.EXTRA_AT, at);
        activity.startActivity(i);
        activity.overridePendingTransition(0, 0);
        // MainActivity.onPause ends this process (the camera was opened here)
    }

    /**
     * In the new process: are the pictures on the card? Worker thread. Both there: the film look on live
     * view comes on. They're kept off every roll either way.
     */
    static void checkCard(File card, SharedPreferences prefs, long createdAt, Probe.Listener out) {
        long at = prefs.getLong(P_AT, 0);
        int before = prefs.getInt(P_BEFORE, 0), shots = prefs.getInt(P_SHOTS, 0);
        StringBuilder log = new StringBuilder();
        String restart = at > 0 ? "back in " + (createdAt - at) + " ms" : "back";
        out.step(S_RESTART, OK, restart);
        log.append(STEPS[S_RESTART]).append(": ok - ").append(restart).append("\r\n");
        out.step(S_CARD, RUN, "looking…");
        List<File> photos = Library.photos(card);
        int found = 0;
        StringBuilder names = new StringBuilder();
        for (File f : photos) {
            if (Roll.number(f) <= before) continue;
            found++;
            names.append(names.length() > 0 ? ", " : "").append(f.getName()).append(" (").append(f.length() / 1024).append(" KB)");
            try { Library.ignore(card, f); } catch (Exception ignored) { }
        }
        boolean ok = shots > 0 && found >= shots;
        if (ok) prefs.edit().putBoolean("liveLook2", true).commit();
        String d = found + " of " + shots + " on the card" + (names.length() > 0 ? ": " + names : "") + (ok ? ". Film look on live view is on" : ". The film look stays off");
        out.step(S_CARD, ok ? OK : FAIL, d);
        log.append(STEPS[S_CARD]).append(": ").append(ok ? "ok" : "FAILED").append(" - ").append(d).append("\r\n");
        prefs.edit().remove(P_AT).remove(P_BEFORE).remove(P_SHOTS).remove(P_STEPS).commit();
        append(card, log.toString());
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
     * No answer from the shutter: a picture may be in the camera, and releasing it then froze it before.
     * The camera stays open, and the screen says what to do.
     */
    private void hold(String why) {
        held = true;
        stopFrames();
        note("HOLDING the camera: " + why + "; not released (that froze it before)");
        report(S_BACK, FAIL, why + ". The camera is kept open. Turn the camera off; if it won't go off, take the battery out.");
        flush();
        host.note("Turn the camera off (battery out if it won't)");
    }

    // ------------------------------------------------------------------------------ closing

    /** MENU or leaving Latent: the camera goes back, unless a picture may be on its way (then: when it's had its time). */
    void close() {
        if (closed || held) return;
        if (shooting || listed < taken) {   // a picture still in the camera's memory: it goes back once they're saved
            note("close asked with a picture not yet saved: the camera goes back once they are");
            flush();
            ui.removeCallbacksAndMessages(null);
            stopFrames();
            if (!shooting) waitListed();
            return;
        }
        stopFrames();
        releaseCamera();
        finish(false);
    }

    /** Still holding the camera (or waiting to give it back). */
    boolean busy() { return !closed; }

    private void releaseCamera() {
        if (cameraEx == null) return;
        try { if (camera != null) camera.cancelAutoFocus(); } catch (Throwable ignored) { }
        set("setShutterListener", null, null);
        try { if (camera != null && previewing) camera.stopPreview(); } catch (Throwable e) { note("stopPreview: " + Probe.why(e)); }
        try { if (camera != null) camera.release(); } catch (Throwable e) { note("camera release: " + Probe.why(e)); }
        try { cameraEx.getClass().getMethod("release").invoke(cameraEx); } catch (Throwable e) { note("release: " + Probe.why(e)); }
        cameraEx = null;
        camera = null;
        previewing = false;
    }

    private void finish(boolean restarting) {
        closed = true;
        ui.removeCallbacksAndMessages(null);
        looker.shutdown();
        try { surface.getHolder().removeCallback(this); } catch (Throwable ignored) { }
        host.showLive(false);
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        flush();
        if (!restarting) host.finished();
    }

    // ------------------------------------------------------------------------------ helpers

    private boolean set(String name, String iface, InvocationHandler h) {
        if (cameraEx == null) return false;
        try {
            for (Method m : cameraEx.getClass().getMethods()) {
                if (!m.getName().equals(name) || m.getParameterTypes().length != 1) continue;
                Object listener = null;
                if (h != null) {
                    Class<?> c = Class.forName(iface);
                    listener = Proxy.newProxyInstance(c.getClassLoader(), new Class<?>[] { c }, h);
                }
                m.invoke(cameraEx, listener);
                return true;
            }
            if (h != null) note(name + ": not on this camera");
        } catch (Throwable e) {
            if (h != null) note(name + ": " + Probe.why(e));
        }
        return false;
    }

    private void call(String name) throws Exception {
        try {
            cameraEx.getClass().getMethod(name).invoke(cameraEx);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            throw c instanceof Exception ? (Exception) c : e;
        }
    }

    private void safely(String name) {
        try { call(name); } catch (Throwable e) { note(name + ": " + Probe.why(e)); }
    }

    private static void option(Object options, String key, int value) throws Exception {
        for (Method m : options.getClass().getMethods()) {
            Class<?>[] t = m.getParameterTypes();
            if (m.getName().equals("setOption") && t.length == 2 && t[0] == String.class && t[1] == int.class) { m.invoke(options, key, value); return; }
        }
        throw new NoSuchMethodException("setOption(String, int)");
    }

    private static Object objectMethod(Object proxy, Method m, Object[] a) {
        String n = m.getName();
        if (n.equals("equals")) return a != null && a.length == 1 && proxy == a[0];
        if (n.equals("hashCode")) return System.identityHashCode(proxy);
        if (n.equals("toString")) return "Latent listener";
        return null;
    }
}
