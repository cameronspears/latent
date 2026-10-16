package com.latent.camera;

import android.graphics.BitmapFactory;
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

/**
 * Can Latent be the camera, the way the X Half is? Opens the camera with live view, focuses itself
 * (trying again when it can't lock, as in a dark room, and noting when the AF lamp is on), takes
 * one picture that the camera saves to the card as usual, then asks for live view as small JPEG
 * frames and times a film look on one.
 *
 * Changes no camera setting: no exposure, focus or drive setting, no direct shutter, Auto Review
 * left as it is. Every camera call is on the UI thread, which opened the camera, and the camera is
 * always released at the end, on MENU, when Latent is left, or if Latent crashes. The calls follow
 * apps verified on the A5100 and the A5000 (BetterManual, FocusBracket, sony-remote-reboot).
 * Log: LATENT/SHOOTING.TXT, one frame: LATENT/LIVEFRM.JPG.
 */
final class ShootingTest implements SurfaceHolder.Callback {
    static final String[] STEPS = { "Open the camera", "Live view", "Focus", "Take a picture", "Live frames", "A look on a frame",
            "Camera closed" };
    static final int OK = Probe.OK, FAIL = Probe.FAIL, RUN = Probe.RUN, INFO = Probe.INFO;
    static final int FOCUS_TRIES = 4;
    static final long FOCUS_TIMEOUT_MS = 6000, SHUTTER_TIMEOUT_MS = 10000, SAVE_TIMEOUT_MS = 30000, FRAMES_MS = 3000;

    private final File card;
    private final SurfaceView surface;
    private final Stock stock;
    private final Probe.Listener out;
    private final LiveViewTest.Host host;
    private final Handler ui = new Handler();
    private final StringBuilder log = new StringBuilder();
    private final long t0 = SystemClock.uptimeMillis();
    private Object cameraEx;
    private Camera camera;
    private Object sequence;
    private boolean previewing, closed, focusing, shooting;
    private Thread.UncaughtExceptionHandler previousHandler;

    // focus
    private int tries;
    private long focusStart, lampOnAt, lampMs;
    private int lastAfStatus = -1;
    // picture
    private int highestBefore;
    private long shutterStart;
    private String storedName;
    // frames
    private long framesStart;
    private int frameCount, emptyPolls, frameBytes;
    private byte[] lastFrame;

    ShootingTest(File card, SurfaceView surface, Stock stock, Probe.Listener out, LiveViewTest.Host host) {
        this.card = card; this.surface = surface; this.stock = stock; this.out = out; this.host = host;
    }

    private void report(int i, int status, String detail) {
        if (status != RUN) log.append(STEPS[i]).append(": ").append(status == OK ? "ok" : status == FAIL ? "FAILED" : "info")
                .append(" - ").append(detail).append("\r\n");
        out.step(i, status, detail);
    }

    private void note(String line) {
        log.append("  ").append(SystemClock.uptimeMillis() - t0).append(" ms  ").append(line).append("\r\n");
    }

    // ------------------------------------------------------------------------------ open and live view

    void start() {
        log.append("Latent shooting test\r\n");
        // if anything goes badly wrong, the camera is still given back (a held camera can freeze the body)
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try { note("crash: " + Probe.why(e)); releaseCamera(); } catch (Throwable ignored) { }
                if (previousHandler != null) previousHandler.uncaughtException(t, e);
            }
        });
        highestBefore = Roll.highest(Library.photos(card));
        out.step(0, RUN, "opening…");
        long t = SystemClock.uptimeMillis();
        try {
            Class<?> cx = Class.forName("com.sony.scalar.hardware.CameraEx");
            Method open = cx.getMethod("open", int.class, Class.forName("com.sony.scalar.hardware.CameraEx$OpenOptions"));
            cameraEx = open.invoke(null, 0, null);
            camera = (Camera) cx.getMethod("getNormalCamera").invoke(cameraEx);
        } catch (Throwable e) {
            report(0, FAIL, Probe.why(e));
            close();
            return;
        }
        report(0, OK, "opened in " + (SystemClock.uptimeMillis() - t) + " ms");
        out.step(1, RUN, "starting…");
        host.showLive(true);
        SurfaceHolder h = surface.getHolder();
        h.addCallback(this);
        if (h.getSurface() != null && h.getSurface().isValid()) surfaceCreated(h);
        ui.postDelayed(new Runnable() {
            public void run() { if (!previewing && !closed) { report(1, FAIL, "no live view surface"); close(); } }
        }, 4000);
    }

    public void surfaceCreated(SurfaceHolder h) {
        if (previewing || closed || camera == null) return;
        try {
            camera.setPreviewDisplay(h);
            camera.startPreview();
            previewing = true;
        } catch (Throwable e) {
            report(1, FAIL, Probe.why(e));
            close();
            return;
        }
        report(1, OK, "on screen");
        listen();
        host.note("Shooting test: point at something and hold still");
        ui.postDelayed(new Runnable() { public void run() { focus(); } }, 2000);
    }

    /** Listeners that only report: focus results and the AF lamp. Each is optional (bodies differ). */
    private void listen() {
        set("setAutoFocusDoneListener", "com.sony.scalar.hardware.CameraEx$AutoFocusDoneListener", new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("onDone") && a != null && a.length > 0) {
                    lastAfStatus = (Integer) a[0];
                    note("AF done: status " + lastAfStatus + " (" + afName(lastAfStatus) + ")");
                }
                return objectMethod(p, m, a);
            }
        });
        set("setFocusLightStateListener", "com.sony.scalar.hardware.CameraEx$FocusLightStateListener", new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("onChanged") && a != null && a.length > 1) {
                    boolean on = (Boolean) a[1];
                    long now = SystemClock.uptimeMillis();
                    if (on && lampOnAt == 0) lampOnAt = now;
                    if (!on && lampOnAt > 0) { lampMs += now - lampOnAt; lampOnAt = 0; }
                    note("AF lamp " + (on ? "on" : "off") + (((Boolean) a[0]) ? "" : " (not ready)"));
                }
                return objectMethod(p, m, a);
            }
        });
    }

    // ------------------------------------------------------------------------------ focus

    /** The camera's own autofocus; when it can't lock, cancel and go again, up to FOCUS_TRIES times. */
    private void focus() {
        if (closed) return;
        tries++;
        focusing = true;
        focusStart = SystemClock.uptimeMillis();
        out.step(2, RUN, tries == 1 ? "focusing…" : "focusing, try " + tries + "…");
        final int attempt = tries;
        try {
            camera.autoFocus(new Camera.AutoFocusCallback() {
                public void onAutoFocus(boolean success, Camera c) { focused(attempt, success); }
            });
        } catch (Throwable e) {
            note("autoFocus: " + Probe.why(e));
            focused(attempt, false);
            return;
        }
        ui.postDelayed(new Runnable() {
            public void run() { if (focusing && tries == attempt) { note("focus try " + attempt + " timed out"); focused(attempt, false); } }
        }, FOCUS_TIMEOUT_MS);
    }

    private void focused(int attempt, boolean success) {
        if (closed || !focusing || attempt != tries) return;
        focusing = false;
        long ms = SystemClock.uptimeMillis() - focusStart;
        note("focus try " + attempt + ": " + (success ? "locked" : "no lock") + " in " + ms + " ms");
        if (lampOnAt > 0) { lampMs += SystemClock.uptimeMillis() - lampOnAt; lampOnAt = SystemClock.uptimeMillis(); }
        String lamp = lampMs > 0 ? ", AF lamp on " + lampMs + " ms in all" : "";
        if (success) {
            report(2, OK, "locked in " + ms + " ms" + (attempt > 1 ? " on try " + attempt : "") + lamp);
            shoot();   // with focus still held, as the camera does on a full press
            return;
        }
        if (attempt < FOCUS_TRIES) {
            try { camera.cancelAutoFocus(); } catch (Throwable e) { note("cancelAutoFocus: " + Probe.why(e)); }
            ui.postDelayed(new Runnable() { public void run() { focus(); } }, 300);
            return;
        }
        report(2, FAIL, "no lock in " + FOCUS_TRIES + " tries" + lamp + "; shooting anyway");
        shoot();
    }

    // ------------------------------------------------------------------------------ the picture

    private void shoot() {
        if (closed) return;
        out.step(3, RUN, "shooting…");
        shooting = true;
        set("setStoreImageCompleteListener", "com.sony.scalar.hardware.CameraEx$StoreImageCompleteListener", new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("onDone") && a != null && a.length > 1) {
                    storedName = field(a[1], "DirectoryName") + "/" + field(a[1], "FileName");
                    note("stored: status " + a[0] + ", " + storedName);
                }
                return objectMethod(p, m, a);
            }
        });
        for (String iface : new String[] { "com.sony.scalar.hardware.CameraEx$OnCaptureStatusListener",
                "com.sony.scalar.hardware.CameraEx$CaptureStatusListener" }) {
            if (set("setCaptureStatusListener", iface, new InvocationHandler() {
                public Object invoke(Object p, Method m, Object[] a) {
                    if (m.getName().equals("onStart") || m.getName().equals("onEnd"))
                        note("capture " + m.getName().substring(2).toLowerCase(java.util.Locale.US) + (a != null && a.length > 1 ? ", reason " + a[1] : ""));
                    return objectMethod(p, m, a);
                }
            })) break;
        }
        boolean listening = set("setShutterListener", "com.sony.scalar.hardware.CameraEx$ShutterListener", new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("onShutter") && a != null && a.length > 0) shutter((Integer) a[0]);
                return objectMethod(p, m, a);
            }
        });
        if (!listening) { report(3, FAIL, "no shutter listener on this camera"); afterPicture(); return; }
        shutterStart = SystemClock.uptimeMillis();
        try {
            call("burstableTakePicture");
        } catch (Throwable e) {
            note("burstableTakePicture: " + Probe.why(e));
            safely("cancelTakePicture");
            shooting = false;
            report(3, FAIL, "the camera wouldn't shoot: " + Probe.why(e));
            afterPicture();
            return;
        }
        ui.postDelayed(new Runnable() {
            public void run() {
                if (shooting) { note("no shutter answer"); safely("cancelTakePicture"); shooting = false; report(3, FAIL, "no answer from the shutter"); afterPicture(); }
            }
        }, SHUTTER_TIMEOUT_MS);
    }

    /** The shutter's answer: 0 taken, 1 cancelled, 2 error. Always end the capture, or the next one never fires. */
    private void shutter(final int status) {
        if (!shooting) return;
        shooting = false;
        final long ms = SystemClock.uptimeMillis() - shutterStart;
        note("shutter: status " + status + " after " + ms + " ms");
        safely("cancelTakePicture");
        set("setShutterListener", null, null);
        try { camera.cancelAutoFocus(); } catch (Throwable ignored) { }
        if (status != 0) { report(3, FAIL, status == 1 ? "cancelled by the camera" : "the camera reported an error"); afterPicture(); return; }
        out.step(3, RUN, "saving…");
        waitForFile(ms, SystemClock.uptimeMillis());
    }

    /** The camera writes the photo itself: watch the card for a new photo number. */
    private void waitForFile(final long shutterMs, final long since) {
        if (closed) return;
        int now = Roll.highest(Library.photos(card));
        long waited = SystemClock.uptimeMillis() - since;
        if (now > highestBefore) {
            note("new photo on the card " + waited + " ms after the shutter");
            report(3, OK, "DSC" + String.format(java.util.Locale.US, "%05d", now % 10000) + ".JPG on the card: shutter " + shutterMs + " ms, saved "
                    + waited + " ms later" + (storedName != null ? "" : " (no store report)"));
            ui.postDelayed(new Runnable() { public void run() { afterPicture(); } }, 1500);   // Auto Review, if on, has its moment
            return;
        }
        if (waited > SAVE_TIMEOUT_MS) { report(3, FAIL, "shutter fired (" + shutterMs + " ms) but no new photo in " + SAVE_TIMEOUT_MS / 1000 + " s"); afterPicture(); return; }
        if (waited > 0 && waited % 3000 < 300) out.step(3, RUN, "saving… " + waited / 1000 + " s");
        ui.postDelayed(new Runnable() { public void run() { waitForFile(shutterMs, since); } }, 300);
    }

    private void afterPicture() {
        if (closed) return;
        frames();
    }

    // ------------------------------------------------------------------------------ live frames

    /** Live view as small JPEG frames (CameraSequence's preview sequence), polled for a few seconds. */
    private void frames() {
        out.step(4, RUN, "asking for frames…");
        try {
            Class<?> cs = Class.forName("com.sony.scalar.hardware.CameraSequence");
            Class<?> cx = Class.forName("com.sony.scalar.hardware.CameraEx");
            sequence = cs.getMethod("open", cx).invoke(null, cameraEx);
            Class<?> oc = Class.forName("com.sony.scalar.hardware.CameraSequence$Options");
            Object o = oc.newInstance();
            option(o, (String) oc.getField("PREVIEW_FRAME_MAX_NUM").get(null), 1);
            option(o, (String) oc.getField("PREVIEW_FRAME_WIDTH").get(null), 640);
            option(o, (String) oc.getField("PREVIEW_FRAME_HEIGHT").get(null), 0);
            option(o, "PREVIEW_FRAME_FORMAT", 0x100);   // JPEG
            option(o, "JPEG_COMPRESS_RATE_DENOM", 15);
            option(o, "JPEG_COMPRESS_MAX_SIZE", 100);
            cs.getMethod("startPreviewSequence", oc).invoke(sequence, o);
        } catch (Throwable e) {
            report(4, FAIL, "no preview sequence: " + Probe.why(e));
            stopSequence();
            report(5, INFO, "no frame to try");
            close();
            return;
        }
        note("preview sequence started");
        host.note("Shooting test: live frames…");
        framesStart = SystemClock.uptimeMillis();
        ui.post(poll);
    }

    private final Runnable poll = new Runnable() {
        public void run() {
            if (closed || sequence == null) return;
            byte[] f = grab();
            if (f != null) { frameCount++; frameBytes += f.length; lastFrame = f; }
            else emptyPolls++;
            if (SystemClock.uptimeMillis() - framesStart < FRAMES_MS) ui.postDelayed(this, 30);
            else framesDone();
        }
    };

    private byte[] grab() {
        Object[] frames = null;
        try {
            frames = (Object[]) sequence.getClass().getMethod("getPreviewSequenceFrames", int.class).invoke(sequence, 1);
            if (frames == null || frames.length == 0 || frames[0] == null) return null;
            Object fr = frames[0];
            int size = (Integer) fr.getClass().getMethod("getSize").invoke(fr);
            if (size <= 0) return null;
            byte[] data = new byte[size];
            fr.getClass().getMethod("read", byte[].class).invoke(fr, data);
            return data;
        } catch (Throwable e) {
            if (frameCount == 0 && emptyPolls < 3) note("grab: " + Probe.why(e));
            return null;
        } finally {
            if (frames != null) for (Object fr : frames) {
                if (fr != null) try { fr.getClass().getMethod("release").invoke(fr); } catch (Throwable ignored) { }
            }
        }
    }

    private void framesDone() {
        long ms = Math.max(1, SystemClock.uptimeMillis() - framesStart);
        stopSequence();
        if (frameCount == 0) {
            report(4, FAIL, "none in " + ms / 1000 + " s (" + emptyPolls + " empty asks)");
            report(5, INFO, "no frame to try");
            close();
            return;
        }
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(lastFrame, 0, lastFrame.length, b);
        report(4, OK, frameCount + " in " + ms / 1000.0 + " s (" + String.format(java.util.Locale.US, "%.1f", frameCount * 1000.0 / ms)
                + " a second), " + b.outWidth + "x" + b.outHeight + ", " + frameBytes / frameCount / 1024 + " KB each");
        lookOnFrame(lastFrame);
    }

    /** How long a film look takes on one live frame: decides how smooth a live look could be. */
    private void lookOnFrame(final byte[] jpeg) {
        out.step(5, RUN, "timing…");
        closeCamera();   // the look runs with the camera given back already
        new Thread(new Runnable() {
            public void run() {
                String result;
                int status;
                try {
                    File f = new File(Library.home(card), "LIVEFRM.JPG");
                    FileOutputStream o = new FileOutputStream(f);
                    try { o.write(jpeg); } finally { o.close(); }
                    if (stock == null) throw new IllegalStateException("no film looks on the card");
                    int[] p = new int[FilmCore.COUNT];
                    p[FilmCore.HIGHLIGHT] = stock.highlight;
                    p[FilmCore.SHADOW] = stock.shadow;
                    p[FilmCore.GRAIN_SIZE] = 1;
                    p[FilmCore.QUALITY] = 90;
                    p[FilmCore.THREADS] = 1;
                    int[] px = new int[640 * 480], size = new int[2];
                    StringBuilder r = new StringBuilder(stock.title + ":");
                    int[][] ways = { { 640, 480, 1 }, { 640, 480, 3 }, { 320, 240, 3 } };   // width, height, threads
                    for (int k = 0; k < ways.length; k++) {
                        int[] w = ways[k];
                        p[FilmCore.THREADS] = w[2];
                        long best = Long.MAX_VALUE;
                        for (int i = 0; i < 3; i++) {   // the fastest of three: the first warms the caches
                            long t = SystemClock.uptimeMillis();
                            if (FilmCore.preview(f.getPath(), stock.lut.getPath(), p, FilmCore.PREVIEW_FIT, px, w[0], w[1], size) != 0)
                                throw new IllegalStateException(FilmCore.lastError());
                            best = Math.min(best, SystemClock.uptimeMillis() - t);
                        }
                        r.append(k == 0 ? " " : ", ").append(size[0]).append("x").append(size[1]).append(" on ").append(w[2])
                                .append(w[2] == 1 ? " core " : " cores ").append(best).append(" ms");
                        note("look " + size[0] + "x" + size[1] + " threads " + w[2] + ": " + best + " ms a frame, decode included");
                    }
                    result = r.toString();
                    status = OK;
                } catch (Throwable e) {
                    result = Probe.why(e);
                    status = FAIL;
                }
                final String r = result;
                final int s = status;
                ui.post(new Runnable() { public void run() { report(5, s, r); finish(); } });
            }
        }, "look-timing").start();
    }

    // ------------------------------------------------------------------------------ closing

    /** MENU, leaving Latent, or a failed step: the camera goes back at once. Safe to call more than once. */
    void close() {
        if (closed) return;
        closeCamera();
        finish();
    }

    private void closeCamera() {
        if (cameraEx == null) return;
        releaseCamera();
        host.showLive(false);
        report(6, OK, "live view stopped, camera released");
    }

    /** cancel focus and capture, stop the sequence and live view, then release: the order the verified apps use. */
    private void releaseCamera() {
        ui.removeCallbacks(poll);
        stopSequence();
        if (cameraEx == null) return;
        try { if (camera != null) camera.cancelAutoFocus(); } catch (Throwable ignored) { }
        if (shooting) safely("cancelTakePicture");
        set("setShutterListener", null, null);
        set("setAutoFocusDoneListener", null, null);
        set("setFocusLightStateListener", null, null);
        set("setStoreImageCompleteListener", null, null);
        set("setCaptureStatusListener", null, null);
        try { if (camera != null && previewing) camera.stopPreview(); } catch (Throwable e) { note("stopPreview: " + Probe.why(e)); }
        try { cameraEx.getClass().getMethod("release").invoke(cameraEx); } catch (Throwable e) { note("release: " + Probe.why(e)); }
        try { surface.getHolder().removeCallback(this); } catch (Throwable ignored) { }
        cameraEx = null;
        camera = null;
        previewing = false;
    }

    private void stopSequence() {
        if (sequence == null) return;
        try { sequence.getClass().getMethod("stopPreviewSequence").invoke(sequence); } catch (Throwable e) { note("stopPreviewSequence: " + Probe.why(e)); }
        try { sequence.getClass().getMethod("release").invoke(sequence); } catch (Throwable e) { note("sequence release: " + Probe.why(e)); }
        sequence = null;
    }

    private boolean finished;

    private void finish() {
        if (finished) return;
        finished = true;
        closed = true;
        ui.removeCallbacksAndMessages(null);
        if (cameraEx != null) closeCamera();
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        try {
            FileOutputStream o = new FileOutputStream(new File(Library.home(card), "SHOOTING.TXT"));
            try { o.write(log.toString().getBytes("UTF-8")); } finally { o.close(); }
        } catch (Exception ignored) {
            // the screen shows the same
        }
        host.finished();
    }

    public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) { }

    public void surfaceDestroyed(SurfaceHolder h) { if (!closed) close(); }

    // ------------------------------------------------------------------------------ reflection (the Sony classes aren't in the build)

    /** cameraEx.name(listener), the listener made from 'iface' around 'h' (null clears it). False if the camera lacks it. */
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
            note(name + ": not on this camera");
        } catch (Throwable e) {
            note(name + ": " + Probe.why(e));
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
            if (m.getName().equals("setOption") && t.length == 2 && t[0] == String.class && t[1] == int.class) {
                m.invoke(options, key, value);
                return;
            }
        }
        throw new NoSuchMethodException("setOption(String, int)");
    }

    private static String field(Object o, String name) {
        try { return String.valueOf(o.getClass().getField(name).get(o)); } catch (Throwable e) { return "?"; }
    }

    /** A proxy's own equals / hashCode / toString. */
    private static Object objectMethod(Object proxy, Method m, Object[] a) {
        String n = m.getName();
        if (n.equals("equals")) return a != null && a.length == 1 && proxy == a[0];
        if (n.equals("hashCode")) return System.identityHashCode(proxy);
        if (n.equals("toString")) return "Latent listener";
        return null;
    }

    private static String afName(int s) {
        switch (s) {
            case 0: return "clear"; case 1: return "lock"; case 2: return "lock warning"; case 3: return "working"; case 4: return "continuous";
            default: return "?";
        }
    }
}
