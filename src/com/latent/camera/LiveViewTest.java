package com.latent.camera;

import android.app.Activity;
import android.hardware.Camera;
import android.os.Handler;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

/**
 * Can Latent show its film looks on live view? Opens the camera the way Recipe Lab does, shows live
 * view for a few seconds, and asks for the live frames through Android's camera interface: whether
 * they arrive, how fast, how big, and whether they hold a picture. Changes no camera setting, takes
 * no picture, and closes the camera again. Log: LATENT/LIVEVIEW.TXT. Runs on the UI thread (the
 * camera answers on the thread that opened it).
 */
final class LiveViewTest implements SurfaceHolder.Callback {
    static final String[] STEPS = { "Open the camera", "Live view", "Frames to Latent", "Frame format", "A picture in them",
            "Camera closed" };
    static final int OK = Probe.OK, FAIL = Probe.FAIL, RUN = Probe.RUN, INFO = Probe.INFO;
    static final long WATCH_MS = 4000;

    interface Host {
        /** Live view on screen behind Latent's own drawing (true), or not. */
        void showLive(boolean on);
        /** A short line shown over the live view. */
        void note(String text);
        void finished();
    }

    private final Activity activity;
    private final File card;
    private final SurfaceView surface;
    private final Probe.Listener out;
    private final Host host;
    private final Handler ui = new Handler();
    private final StringBuilder log = new StringBuilder();
    private Object cameraEx;
    private Camera camera;
    private boolean previewing, closed;
    private long started;
    private int frames, width, height, format;
    private long sumMean;
    private int minMean = 256, maxMean = -1, lastLen;

    LiveViewTest(Activity activity, File card, SurfaceView surface, Probe.Listener out, Host host) {
        this.activity = activity; this.card = card; this.surface = surface; this.out = out; this.host = host;
    }

    private void report(int i, int status, String detail) {
        if (status != RUN) log.append(STEPS[i]).append(": ").append(status == OK ? "ok" : status == FAIL ? "FAILED" : "info")
                .append(" - ").append(detail).append("\r\n");
        out.step(i, status, detail);
    }

    void start() {
        log.append("Latent live view test\r\n");
        long t0 = System.currentTimeMillis();
        out.step(0, RUN, "opening…");
        Viewfinder.opened = true;   // Sony's image memory is the camera's now, until Latent closes
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
        report(0, OK, "opened in " + (System.currentTimeMillis() - t0) + " ms");
        out.step(1, RUN, "starting…");
        host.showLive(true);
        SurfaceHolder h = surface.getHolder();
        h.addCallback(this);
        if (h.getSurface() != null && h.getSurface().isValid()) surfaceCreated(h);
        ui.postDelayed(new Runnable() {   // no surface after a while: give up cleanly
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
        try {
            Camera.Parameters p = camera.getParameters();
            Camera.Size s = p.getPreviewSize();
            width = s.width; height = s.height; format = p.getPreviewFormat();
            report(3, INFO, width + "x" + height + ", format " + formatName(format) + ", " + p.getPreviewFrameRate() + " fps asked");
            log.append("parameters: ").append(p.flatten()).append("\r\n");
        } catch (Throwable e) {
            report(3, INFO, "parameters unreadable: " + Probe.why(e));
        }
        out.step(2, RUN, "watching…");
        started = System.currentTimeMillis();
        try {
            camera.setPreviewCallback(new Camera.PreviewCallback() {
                public void onPreviewFrame(byte[] data, Camera c) { frame(data); }
            });
        } catch (Throwable e) {
            report(2, FAIL, "frames refused: " + Probe.why(e));
        }
        host.note("Live view test: point the camera around");
        ui.postDelayed(new Runnable() { public void run() { finish(); } }, WATCH_MS);
    }

    /** One live frame: count it, and how bright its picture is (a sample of the brightness plane). */
    private void frame(byte[] data) {
        if (data == null) return;
        frames++;
        lastLen = data.length;
        int n = width > 0 && height > 0 ? Math.min(data.length, width * height) : data.length, sum = 0, count = 0;
        for (int i = 0; i < n; i += 97) { sum += data[i] & 0xff; count++; }
        int mean = count > 0 ? sum / count : 0;
        sumMean += mean;
        if (mean < minMean) minMean = mean;
        if (mean > maxMean) maxMean = mean;
        if (frames % 5 == 0) host.note("Live view test: " + frames + " frames so far");
    }

    private void finish() {
        if (closed) return;
        try { camera.setPreviewCallback(null); } catch (Throwable ignored) { }
        long ms = Math.max(1, System.currentTimeMillis() - started);
        if (frames == 0) {
            report(2, FAIL, "none arrived in " + ms / 1000 + " s: live view stays inside the camera");
            report(4, INFO, "nothing to look at");
        } else {
            report(2, OK, frames + " in " + ms / 1000.0 + " s (" + String.format(java.util.Locale.US, "%.1f", frames * 1000.0 / ms)
                    + " a second), " + lastLen + " bytes each");
            report(4, maxMean - minMean > 3 || sumMean / frames > 8 ? OK : INFO,
                    "brightness " + sumMean / frames + " on average (" + minMean + " to " + maxMean + ")"
                    + (sumMean / frames <= 8 ? ": black frames" : ""));
        }
        close();
    }

    /** Stops live view and gives the camera back. Safe to call more than once (also when Latent is left). */
    void close() {
        if (closed) return;
        closed = true;
        ui.removeCallbacksAndMessages(null);
        try { if (camera != null) camera.setPreviewCallback(null); } catch (Throwable ignored) { }
        try { if (camera != null && previewing) camera.stopPreview(); } catch (Throwable ignored) { }
        try { if (cameraEx != null) cameraEx.getClass().getMethod("release").invoke(cameraEx); } catch (Throwable e) { log.append("release: ").append(Probe.why(e)).append("\r\n"); }
        try { surface.getHolder().removeCallback(this); } catch (Throwable ignored) { }
        camera = null;
        cameraEx = null;
        host.showLive(false);
        report(5, OK, "live view stopped, camera released");
        try {
            FileOutputStream o = new FileOutputStream(new File(Library.home(card), "LIVEVIEW.TXT"));
            try { o.write(log.toString().getBytes("UTF-8")); } finally { o.close(); }
        } catch (Exception ignored) {
            // the screen shows the same
        }
        host.finished();
    }

    public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) { }

    public void surfaceDestroyed(SurfaceHolder h) { if (!closed) close(); }

    private static String formatName(int f) {
        switch (f) {
            case 17: return "NV21";
            case 842094169: return "YV12";
            case 20: return "YUY2";
            case 16: return "NV16";
            case 4: return "RGB565";
            default: return String.valueOf(f);
        }
    }
}
