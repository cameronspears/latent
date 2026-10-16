package com.latent.camera;

import android.app.Activity;
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
import java.util.Locale;

/**
 * Can the camera store pictures while it hands Latent live frames? One hypothesis, from Sony's own
 * Smart Remote (SmartRemote.odex, read on a Mac): it streams live view to a phone as these same JPEG
 * frames (CameraSequence's preview sequence, never stopped for a picture) and takes the picture as
 * Latent does (burstableTakePicture), but for every picture taken with the stream running it first
 * sets Auto Review to 0 (NormalCaptureStateEx: "disalbePictureReview") through Sony's
 * AutoPictureReviewControl, which its framework always installs, and puts your time back afterwards.
 * Latent never installed that control, and v0.31.0-0.31.2 lost every picture once frames had started.
 *
 * So: open the camera, Auto Review to 0 (your time kept in prefs and put back at the end, or on the
 * next start if Latent stops on the way), frames on, then two pictures with the frames running, each
 * waited for in Sony's catalogue. Every picture must be stored before the camera goes back; if one
 * isn't, the camera is NOT released (that froze it before) and the screen says so.
 *
 * Kept off every roll (IGNORE.TXT). Log: LATENT/LIVELOOK.TXT, written before every risky step.
 */
final class LiveLookTest implements SurfaceHolder.Callback {
    static final String[] STEPS = { "Open the camera", "Auto Review off", "Live frames", "Picture 1, frames running", "Picture 2, frames running",
            "Auto Review back", "Camera closed" };
    static final int S_OPEN = 0, S_REVIEW = 1, S_FRAMES = 2, S_SHOT1 = 3, S_SHOT2 = 4, S_RESTORE = 5, S_CLOSED = 6;
    static final int OK = Probe.OK, FAIL = Probe.FAIL, RUN = Probe.RUN, INFO = Probe.INFO;
    static final int FRAME_W = 320;
    static final long FIRST_FRAME_MS = 15000, FRAMES_BEFORE_MS = 2500, FOCUS_TIMEOUT_MS = 6000, SHUTTER_TIMEOUT_MS = 10000,
            STORE_MS = 15000, STORE_MORE_MS = 20000;
    /** Prefs: your Auto Review time while Latent has it at 0 (-1 = nothing to put back). */
    static final String P_REVIEW = "reviewToRestore";

    private final Activity activity;
    private final File card;
    private final SurfaceView surface;
    private final SharedPreferences prefs;
    private final Probe.Listener out;
    private final LiveViewTest.Host host;
    private final Handler ui = new Handler();
    private final StringBuilder log = new StringBuilder();
    private final long t0 = SystemClock.uptimeMillis();
    private Thread.UncaughtExceptionHandler previousHandler;

    private Object cameraEx, sequence, review;
    private Camera camera;
    private String mediaId;
    private boolean previewing, closed, held, closeAsked;
    private int yourReview = -1;

    // frames
    private Method mFrames, mSize, mRead, mRelease;
    private long framesAskedAt, firstFrameAt;
    private int frames, framesAtShot, grabFails;
    private long lastFrameAt, biggestGap;

    // pictures
    private int shotNo, highestBefore, highestSeen, stored;
    private boolean shooting, focusing;
    private long shutterAt, shutterAnsweredAt;
    private String storeReport;

    LiveLookTest(Activity activity, File card, SurfaceView surface, SharedPreferences prefs, Probe.Listener out, LiveViewTest.Host host) {
        this.activity = activity; this.card = card; this.surface = surface; this.prefs = prefs; this.out = out; this.host = host;
    }

    private void report(int i, int status, String detail) {
        if (status != RUN) log.append(STEPS[i]).append(": ").append(status == OK ? "ok" : status == FAIL ? "FAILED" : "info").append(" - ").append(detail).append("\r\n");
        out.step(i, status, detail);
    }

    private void note(String line) { log.append("  ").append(SystemClock.uptimeMillis() - t0).append(" ms  ").append(line).append("\r\n"); }

    private int flushed;

    /** The log so far onto the card (only what's new), so a camera that hangs still leaves it. */
    private void flush() {
        if (flushed >= log.length()) return;
        String add = log.substring(flushed);
        flushed = log.length();
        try {
            FileOutputStream o = new FileOutputStream(new File(Library.home(card), "LIVELOOK.TXT"), true);
            try { o.write(add.getBytes("UTF-8")); } finally { o.close(); }
        } catch (Exception ignored) {
            // the screen says the same
        }
    }

    // ------------------------------------------------------------------------------ open

    void start() {
        log.append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date())).append(" Latent live look test\r\n");
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try { note("crash: " + Probe.why(e)); flush(); if (!holding()) close(); } catch (Throwable ignored) { }
                if (previousHandler != null) previousHandler.uncaughtException(t, e);
            }
        });
        highestBefore = Roll.highest(Library.photos(card));
        highestSeen = highestBefore;
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
            finish();
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
        listen();
        reviewOff();
    }

    public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) { }

    public void surfaceDestroyed(SurfaceHolder h) { if (!closed && !holding()) close(); }

    /** The shutter, the store and the capture's own reports (the Shooting test's set, which saw its picture at once). */
    private void listen() {
        set("setShutterListener", "com.sony.scalar.hardware.CameraEx$ShutterListener", new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("onShutter") && a != null && a.length > 0) shutter((Integer) a[0]);
                return objectMethod(p, m, a);
            }
        });
        set("setStoreImageCompleteListener", "com.sony.scalar.hardware.CameraEx$StoreImageCompleteListener", new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("onDone") && a != null && a.length > 1) {
                    storeReport = field(a[1], "DirectoryName") + "/" + field(a[1], "FileName");
                    note("store report: status " + a[0] + ", " + storeReport);
                }
                return objectMethod(p, m, a);
            }
        });
        for (String iface : new String[] { "com.sony.scalar.hardware.CameraEx$OnCaptureStatusListener", "com.sony.scalar.hardware.CameraEx$CaptureStatusListener" }) {
            if (set("setCaptureStatusListener", iface, new InvocationHandler() {
                public Object invoke(Object p, Method m, Object[] a) {
                    if (m.getName().equals("onStart") || m.getName().equals("onEnd"))
                        note("capture " + m.getName().substring(2).toLowerCase(Locale.US) + (a != null && a.length > 1 ? " " + java.util.Arrays.toString(a) : ""));
                    return objectMethod(p, m, a);
                }
            })) break;
        }
    }

    // ------------------------------------------------------------------------------ Auto Review, as Smart Remote does

    private void reviewOff() {
        out.step(S_REVIEW, RUN, "installing Sony's review control…");
        String back = restore(cameraEx, prefs);   // a time left at 0 by a test that stopped on the way
        if (back != null) note(back);
        try {
            Class<?> rc = Class.forName("com.sony.scalar.hardware.CameraEx$AutoPictureReviewControl");
            review = rc.newInstance();
            cameraEx.getClass().getMethod("setAutoPictureReviewControl", rc).invoke(cameraEx, review);   // installed first, then asked (better-timelapse: an NPE otherwise)
            yourReview = (Integer) rc.getMethod("getPictureReviewTime").invoke(review);
            String times = "?";
            try { times = java.util.Arrays.toString((int[]) rc.getMethod("getPictureReviewSupportedTimes").invoke(review)); } catch (Throwable ignored) { }
            note("Auto Review: yours is " + yourReview + " (times offered " + times + ")");
            if (yourReview == 0) {   // the hypothesis can't explain v0.31's lost pictures: don't risk the frames
                report(S_REVIEW, FAIL, "it was off already, so it can't be what stopped pictures; no frames started");
                flush();
                close();
                return;
            }
            prefs.edit().putInt(P_REVIEW, yourReview).commit();   // put back on the next start if Latent stops on the way
            note("setting Auto Review to 0");
            flush();
            rc.getMethod("setPictureReviewTime", int.class).invoke(review, 0);
            NeutralSettings.wrote();
            int now = (Integer) rc.getMethod("getPictureReviewTime").invoke(review);
            if (now != 0) {
                report(S_REVIEW, FAIL, "asked for 0, the camera says " + now + "; no frames started");
                close();
                return;
            }
            report(S_REVIEW, OK, "0 (yours was " + yourReview + "; it goes back at the end)");
        } catch (Throwable e) {
            report(S_REVIEW, FAIL, Probe.why(e) + "; no frames started");
            close();
            return;
        }
        startFrames();
    }

    /** Your Auto Review time back, if Latent has it at 0. Safe to call more than once. */
    private void reviewBack() {
        if (yourReview <= 0 || review == null) return;
        try {
            review.getClass().getMethod("setPictureReviewTime", int.class).invoke(review, yourReview);
            NeutralSettings.wrote();
            int now = (Integer) review.getClass().getMethod("getPictureReviewTime").invoke(review);
            report(S_RESTORE, now == yourReview ? OK : FAIL, now == yourReview ? "back to " + yourReview : "asked for " + yourReview + ", the camera says " + now);
            if (now == yourReview) prefs.edit().remove(P_REVIEW).commit();
        } catch (Throwable e) {
            report(S_RESTORE, FAIL, Probe.why(e) + " (it goes back on the next start)");
        }
        yourReview = -1;
    }

    /**
     * On Latent's start: an Auto Review time left at 0 by a test that stopped on the way. It can only be
     * set with the camera open; the next camera open puts it back (restore()).
     */
    static int pending(SharedPreferences prefs) { return prefs.getInt(P_REVIEW, -1); }

    /**
     * With the camera open (the viewfinder or a test, before anything else): puts back an Auto Review
     * time a test left at 0. Returns a line for the log, or null if there was nothing to do.
     */
    static String restore(Object cameraEx, SharedPreferences prefs) {
        int t = pending(prefs);
        if (t <= 0) return null;
        try {
            Class<?> rc = Class.forName("com.sony.scalar.hardware.CameraEx$AutoPictureReviewControl");
            Object r = rc.newInstance();
            cameraEx.getClass().getMethod("setAutoPictureReviewControl", rc).invoke(cameraEx, r);
            rc.getMethod("setPictureReviewTime", int.class).invoke(r, t);
            NeutralSettings.wrote();
            prefs.edit().remove(P_REVIEW).commit();
            return "Auto Review put back to " + t + " (a test had left it at 0)";
        } catch (Throwable e) {
            return "Auto Review not put back: " + Probe.why(e);
        }
    }

    // ------------------------------------------------------------------------------ frames, as Smart Remote asks for them

    private void startFrames() {
        out.step(S_FRAMES, RUN, "asking for frames…");
        note("starting the live frames (" + FRAME_W + " px)");
        flush();
        try {
            Class<?> cs = Class.forName("com.sony.scalar.hardware.CameraSequence");
            sequence = cs.getMethod("open", Class.forName("com.sony.scalar.hardware.CameraEx")).invoke(null, cameraEx);
            Class<?> oc = Class.forName("com.sony.scalar.hardware.CameraSequence$Options");
            Object o = oc.newInstance();
            option(o, "PREVIEW_FRAME_RATE", 30000);
            option(o, "PREVIEW_FRAME_WIDTH", FRAME_W);
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
            reviewBack();
            close();
            return;
        }
        framesAskedAt = SystemClock.uptimeMillis();
        ui.post(poll);
    }

    private final Runnable poll = new Runnable() {
        public void run() {
            if (closed || sequence == null) return;
            long now = SystemClock.uptimeMillis();
            if (grab()) {
                if (frames == 1) {
                    firstFrameAt = now;
                    note("first frame after " + (now - framesAskedAt) + " ms");
                    report(S_FRAMES, OK, "coming, the first after " + (now - framesAskedAt) + " ms");
                    flush();
                    ui.postDelayed(new Runnable() { public void run() { nextShot(); } }, FRAMES_BEFORE_MS);
                }
            } else if (frames == 0 && now - framesAskedAt > FIRST_FRAME_MS) {   // no frames: nothing to test, nothing pending
                report(S_FRAMES, FAIL, "none in " + FIRST_FRAME_MS / 1000 + " s");
                stopFrames();
                reviewBack();
                close();
                return;
            }
            ui.postDelayed(this, 60);
        }
    };

    private boolean grab() {
        Object[] got = null;
        try {
            got = (Object[]) mFrames.invoke(sequence, 1);
            if (got == null || got.length == 0 || got[0] == null) return false;
            Object fr = got[0];
            if (mSize == null) { mSize = fr.getClass().getMethod("getSize"); mRead = fr.getClass().getMethod("read", byte[].class); mRelease = fr.getClass().getMethod("release"); }
            int size = (Integer) mSize.invoke(fr);
            if (size <= 0) return false;
            mRead.invoke(fr, new byte[size]);
            long now = SystemClock.uptimeMillis();
            if (lastFrameAt > 0) biggestGap = Math.max(biggestGap, now - lastFrameAt);
            lastFrameAt = now;
            frames++;
            return true;
        } catch (Throwable e) {
            if (grabFails++ < 3) note("frame: " + Probe.why(e));
            return false;
        } finally {
            if (got != null) for (Object fr : got) if (fr != null) try { (mRelease != null ? mRelease : fr.getClass().getMethod("release")).invoke(fr); } catch (Throwable ignored) { }
        }
    }

    private void stopFrames() {
        ui.removeCallbacks(poll);
        if (sequence == null) return;
        try { sequence.getClass().getMethod("stopPreviewSequence").invoke(sequence); } catch (Throwable e) { note("stopPreviewSequence: " + Probe.why(e)); }
        try { sequence.getClass().getMethod("release").invoke(sequence); } catch (Throwable e) { note("sequence release: " + Probe.why(e)); }
        sequence = null;
        note("frames stopped: " + frames + " in all, the longest gap " + biggestGap + " ms");
    }

    // ------------------------------------------------------------------------------ the pictures, with the frames running

    private int step() { return shotNo == 1 ? S_SHOT1 : S_SHOT2; }

    private void nextShot() {
        if (closed) return;
        if (closeAsked) { endPictures(); return; }
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
        framesAtShot = frames;
        storeReport = null;
        note("picture " + shotNo + ": shutter, " + frames + " frames so far, the frames still running");
        flush();   // if the camera hangs from here, the card already says how far it got
        shooting = true;
        shutterAt = SystemClock.uptimeMillis();
        try {
            call("burstableTakePicture");
        } catch (Throwable e) {
            note("burstableTakePicture: " + Probe.why(e));
            safely("cancelTakePicture");
            shooting = false;
            report(step(), FAIL, "the camera wouldn't shoot: " + Probe.why(e));
            endPictures();
            return;
        }
        ui.postDelayed(new Runnable() {
            public void run() {
                if (!shooting) return;
                shooting = false;
                note("no shutter answer in " + SHUTTER_TIMEOUT_MS / 1000 + " s");
                safely("cancelTakePicture");
                report(step(), FAIL, "no answer from the shutter");
                waitStored(SystemClock.uptimeMillis(), true);   // something may still be on its way
            }
        }, SHUTTER_TIMEOUT_MS);
    }

    /** The shutter's answer: 0 taken, 1 cancelled, 2 error. The capture is always ended (cancelTakePicture). */
    private void shutter(int status) {
        if (!shooting) return;
        shooting = false;
        shutterAnsweredAt = SystemClock.uptimeMillis();
        safely("cancelTakePicture");
        try { camera.cancelAutoFocus(); } catch (Throwable ignored) { }
        note("picture " + shotNo + ": shutter status " + status + " after " + (shutterAnsweredAt - shutterAt) + " ms");
        flush();
        if (status != 0) { report(step(), FAIL, status == 1 ? "cancelled by the camera" : "the camera reported an error"); endPictures(); return; }
        out.step(step(), RUN, "storing…");
        waitStored(shutterAnsweredAt, false);
    }

    /**
     * The picture is stored once Sony's catalogue has it (asked on this thread, as the Shooting test did,
     * which saw its picture 0.2 s after the shutter). The frames keep running meanwhile.
     */
    private void waitStored(final long since, final boolean framesStopped) {
        if (closed) return;
        long waited = SystemClock.uptimeMillis() - since;
        Probe.Row row = newestCatalogued();
        if (row != null && row.folder * 10000 + row.number > highestSeen) {
            highestSeen = row.folder * 10000 + row.number;
            stored++;
            File f = dcfFile(row.folder, row.number);
            try { Library.ignore(card, f); } catch (Exception e) { note("ignore: " + Probe.why(e)); }
            note("picture " + shotNo + ": " + f.getName() + " in the catalogue " + waited + " ms after the shutter's answer"
                    + (storeReport != null ? "; store report " + storeReport : "; no store report") + "; " + (frames - framesAtShot) + " frames since the shutter");
            report(step(), OK, f.getName() + " stored " + waited + " ms after the shutter" + (framesStopped ? " (frames stopped by then)" : ", " + (frames - framesAtShot) + " frames since"));
            flush();
            if (shotNo < 2 && !framesStopped && !closeAsked) ui.postDelayed(new Runnable() { public void run() { nextShot(); } }, 2000);
            else endPictures();
            return;
        }
        if (!framesStopped && waited > STORE_MS) {   // not stored with the frames running: stop them, and give it longer
            note("picture " + shotNo + ": not in the catalogue in " + STORE_MS / 1000 + " s; stopping the frames and waiting " + STORE_MORE_MS / 1000 + " s more");
            flush();
            stopFrames();
            waitStored(SystemClock.uptimeMillis() - STORE_MS, true);
            return;
        }
        if (framesStopped && waited > STORE_MS + STORE_MORE_MS) {
            hold("picture " + shotNo + " was never stored");
            return;
        }
        if (waited / 1000 != (waited - 300) / 1000) out.step(step(), RUN, "storing… " + waited / 1000 + " s");
        ui.postDelayed(new Runnable() { public void run() { waitStored(since, framesStopped); } }, 300);
    }

    /** Every picture asked for is stored: frames off, your Auto Review back, the camera released. */
    private void endPictures() {
        if (closed) return;
        stopFrames();
        if (stored < shotNo && shutterAnsweredAt > 0) {   // a shutter that fired may still be storing
            hold("a picture may still be storing");
            return;
        }
        reviewBack();
        flush();
        ui.postDelayed(new Runnable() { public void run() { close(); } }, 1000);
    }

    /**
     * A picture the camera may still be holding: releasing the camera now froze it (v0.31.1, v0.31.2).
     * Your Auto Review goes back, the camera stays open, and the screen says what to do.
     */
    private void hold(String why) {
        held = true;
        note("HOLDING the camera: " + why + "; not released (that froze it before)");
        reviewBack();
        report(S_CLOSED, FAIL, why + ". The camera is kept open. Turn the camera off; if it won't go off, take the battery out.");
        flush();
        host.note("Turn the camera off (battery out if it won't)");
    }

    private boolean holding() { return held; }

    // ------------------------------------------------------------------------------ closing

    /** MENU, leaving Latent, or the end: the camera goes back, unless a picture may still be in it. */
    void close() {
        if (closed) return;
        if (held) { note("close asked while holding: the camera stays open"); flush(); return; }
        if (shooting || (stored < shotNo && shutterAnsweredAt > 0)) {   // never released with a picture on its way
            if (closeAsked) return;
            closeAsked = true;
            note("close asked with a picture on its way: waiting for it first");
            flush();
            if (!shooting) waitStored(shutterAnsweredAt, sequence == null);
            return;
        }
        closed = true;
        ui.removeCallbacksAndMessages(null);
        stopFrames();
        reviewBack();
        if (cameraEx != null) {
            try { if (camera != null) camera.cancelAutoFocus(); } catch (Throwable ignored) { }
            set("setShutterListener", null, null);
            set("setStoreImageCompleteListener", null, null);
            set("setCaptureStatusListener", null, null);
            try {
                Class<?> rc = Class.forName("com.sony.scalar.hardware.CameraEx$AutoPictureReviewControl");
                cameraEx.getClass().getMethod("setAutoPictureReviewControl", rc).invoke(cameraEx, (Object) null);
            } catch (Throwable ignored) { }
            try { if (camera != null && previewing) camera.stopPreview(); } catch (Throwable e) { note("stopPreview: " + Probe.why(e)); }
            try { if (camera != null) camera.release(); } catch (Throwable e) { note("camera release: " + Probe.why(e)); }
            try { cameraEx.getClass().getMethod("release").invoke(cameraEx); } catch (Throwable e) { note("release: " + Probe.why(e)); }
            report(S_CLOSED, OK, "released, " + stored + " of " + shotNo + " picture(s) stored, " + frames + " frames");
        }
        finish();
    }

    private void finish() {
        closed = true;
        try { surface.getHolder().removeCallback(this); } catch (Throwable ignored) { }
        cameraEx = null;
        camera = null;
        host.showLive(false);
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        flush();
        host.finished();
    }

    // ------------------------------------------------------------------------------ helpers

    private Probe.Row newestCatalogued() {
        if (mediaId == null) return null;
        try {
            Class<?> media = Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
            android.net.Uri uri = (android.net.Uri) media.getMethod("getContentUri", String.class).invoke(null, mediaId);
            Probe.Row best = null;
            for (Probe.Row r : Probe.query(activity.getContentResolver(), uri)) {
                int n = r.folder * 10000 + r.number;
                if (n > highestBefore && (best == null || n > best.folder * 10000 + best.number)) best = r;
            }
            return best;
        } catch (Throwable e) {
            return null;
        }
    }

    private File dcfFile(int folder, int number) {
        File dcim = new File(card, "DCIM"), dir = new File(dcim, folder + "MSDCF");
        File[] dirs = dcim.listFiles();
        if (dirs != null) for (File d : dirs) if (d.getName().startsWith(String.valueOf(folder))) dir = d;
        return new File(dir, String.format(Locale.US, "DSC%05d.JPG", number % 100000));
    }

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

    private static String field(Object o, String name) {
        try { return String.valueOf(o.getClass().getField(name).get(o)); } catch (Throwable e) { return "?"; }
    }

    private static Object objectMethod(Object proxy, Method m, Object[] a) {
        String n = m.getName();
        if (n.equals("equals")) return a != null && a.length == 1 && proxy == a[0];
        if (n.equals("hashCode")) return System.identityHashCode(proxy);
        if (n.equals("toString")) return "Latent listener";
        return null;
    }
}
