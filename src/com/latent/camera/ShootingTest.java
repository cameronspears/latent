package com.latent.camera;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
 * left as it is. The capture-hook experiment (CAPTURE_HOOK, off) loaded the firmware's memory map and
 * set an allocation policy while the test ran, both undone after the camera was released. Every camera
 * call is on the UI thread, which opened the camera, and the camera is
 * always released at the end, on MENU, when Latent is left, or if Latent crashes. The calls follow
 * apps verified on the A5100 and the A5000 (BetterManual, FocusBracket, sony-remote-reboot).
 * Log: LATENT/SHOOTING.TXT, one frame: LATENT/LIVEFRM.JPG.
 */
final class ShootingTest implements SurfaceHolder.Callback {
    static final String[] STEPS = { "Open the camera", "Live view", "Focus options", "Level sensor", "Focus", "Take a picture",
            "Developed in the camera", "Look on the capture", "Stored", "Camera closed" };
    static final int S_OPEN = 0, S_LIVE = 1, S_OPTIONS = 2, S_LEVEL = 3, S_FOCUS = 4, S_SHOT = 5, S_DEVCAM = 6, S_LOOKCAP = 7, S_STORED = 8,
            S_CLOSED = 9;
    /** Live frames and the look on one: known from v0.21-0.23 (up to 19 a second; 143 ms at 320 px), not on screen this run. */
    static final int S_FRAMES = -1, S_LOOK = -1;
    static final int OK = Probe.OK, FAIL = Probe.FAIL, RUN = Probe.RUN, INFO = Probe.INFO;
    static final int FOCUS_TRIES = 4;
    static final long FOCUS_TIMEOUT_MS = 6000, SHUTTER_TIMEOUT_MS = 10000, SAVE_TIMEOUT_MS = 15000, FRAMES_MS = 3000;

    private final Context context;
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
    private String mediaId;
    private long catalogueSeen = -1, pathSeen = -1;

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

    ShootingTest(Context context, File card, SurfaceView surface, Stock stock, Probe.Listener out, LiveViewTest.Host host) {
        this.context = context; this.card = card; this.surface = surface; this.stock = stock; this.out = out; this.host = host;
    }

    private void step(int i, int status, String detail) { if (i >= 0) out.step(i, status, detail); }

    private void report(int i, int status, String detail) {
        if (i < 0) { if (status != RUN) note("(" + detail + ")"); return; }
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
        out.step(S_OPEN, RUN, "copying the camera's libraries…");
        new Thread(new Runnable() {
            public void run() {
                copyFirmware();
                if (CAPTURE_HOOK) copyMemoryMaps();
                ui.post(new Runnable() { public void run() { startCamera(); } });
            }
        }, "firmware-copy").start();
    }

    /**
     * Read only: copies Sony's camera framework and native camera libraries to LATENT/FW, so the
     * capture hook's refusal ('setShutterSequenceCallbackMode failed') can be traced in them on a
     * computer. Skips files already copied (same size). Background thread.
     */
    private void copyFirmware() {
        // the camera mounts the card with 8.3 names only: each file goes in as FW/F0001.BIN, with FW/INDEX.TXT naming it
        File to = new File(Library.home(card), "FW");
        if (new File(to, "INDEX.TXT").exists()) { note("firmware: copied before (LATENT/FW)"); return; }
        to.mkdirs();
        StringBuilder index = new StringBuilder();
        int files = 0, n = 0;
        long bytes = 0;
        String[][] what = { { "/system/framework", "" }, { "/system/lib", "camera|scalar|sony|seq|dsp|still|avindex|osal|diadem|jpeg|imag" },
                { "/system/etc/permissions", "" }, { "/system", "build\\.prop" } };
        for (String[] w : what) {
            File[] list = new File(w[0]).listFiles();
            if (list == null) { note("firmware: " + w[0] + " not readable"); continue; }
            java.util.Arrays.sort(list);
            for (File f : list) {
                if (!f.isFile() || (w[1].length() > 0 && !f.getName().toLowerCase(java.util.Locale.US).matches(".*(" + w[1] + ").*"))) continue;
                File dst = new File(to, String.format(java.util.Locale.US, "F%04d.BIN", ++n));
                index.append(dst.getName()).append('\t').append(f.getPath()).append('\t').append(f.length()).append("\r\n");
                if (dst.exists() && dst.length() == f.length()) continue;
                try {
                    java.io.InputStream in = new java.io.FileInputStream(f);
                    FileOutputStream o = new FileOutputStream(dst);
                    try {
                        byte[] b = new byte[65536];
                        for (int k; (k = in.read(b)) > 0; ) o.write(b, 0, k);
                    } finally { in.close(); o.close(); }
                    files++;
                    bytes += f.length();
                } catch (Exception e) {
                    note("firmware: " + f + ": " + Probe.why(e));
                }
            }
        }
        try {
            FileOutputStream o = new FileOutputStream(new File(to, "INDEX.TXT"));
            try { o.write(index.toString().getBytes("UTF-8")); } finally { o.close(); }
        } catch (Exception e) {
            note("firmware index: " + Probe.why(e));
        }
        note("firmware: " + files + " files copied (" + bytes / 1024 + " KB) to LATENT/FW, " + n + " listed in INDEX.TXT");
    }

    private void startCamera() {
        // if anything goes badly wrong, the camera is still given back (a held camera can freeze the body)
        if (!handlerSet) {   // once: a second open (another allocation policy) keeps the first handler
            handlerSet = true;
            previousHandler = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                public void uncaughtException(Thread t, Throwable e) {
                    try { note("crash: " + Probe.why(e)); releaseCamera(); if (CAPTURE_HOOK) saveSystemLog(); } catch (Throwable ignored) { }
                    if (previousHandler != null) previousHandler.uncaughtException(t, e);
                }
            });
        }
        highestBefore = Roll.highest(Library.photos(card));
        step(S_OPEN, RUN, "opening…");
        // before the camera opens, as an app would at start-up: the map, and how it is fitted in
        if (CAPTURE_HOOK) {
            loadMap();
            setPolicy(OPEN_POLICIES[openPass]);
        }
        long t = SystemClock.uptimeMillis();
        try {
            Class<?> cx = Class.forName("com.sony.scalar.hardware.CameraEx");
            Class<?> oc = Class.forName("com.sony.scalar.hardware.CameraEx$OpenOptions");
            Method open = cx.getMethod("open", int.class, oc);
            // where pictures go: the memory card, as Sony's own apps say when they open the camera
            // (opened without it, v0.21.0's photo reached the card only after the camera was released)
            Object options = null;
            String media = null;
            try {
                String[] ids = (String[]) Class.forName("com.sony.scalar.provider.AvindexStore").getMethod("getExternalMediaIds").invoke(null);
                if (ids != null && ids.length > 0) {
                    media = ids[0];
                    mediaId = media;
                    options = oc.newInstance();
                    oc.getMethod("setTargetMedia", String.class).invoke(options, media);
                    try { oc.getMethod("setRecordingMode", int.class).invoke(options, 0); } catch (Throwable e) { note("recording mode: " + Probe.why(e)); }   // stills, as Sony's apps open it
                    if (SEQUENCE_LIVE) try { oc.getMethod("setPreview", boolean.class).invoke(options, false); } catch (Throwable e) { note("preview off: " + Probe.why(e)); }
                }
            } catch (Throwable e) {
                note("no target media: " + Probe.why(e));
                options = null;
            }
            note("opening with target media " + media + ", stills" + (SEQUENCE_LIVE ? ", normal preview off" : ""));
            cameraEx = open.invoke(null, 0, options);
            camera = (Camera) cx.getMethod("getNormalCamera").invoke(cameraEx);
        } catch (Throwable e) {
            report(S_OPEN, FAIL, Probe.why(e));
            close();
            return;
        }
        report(S_OPEN, OK, "opened in " + (SystemClock.uptimeMillis() - t) + " ms");
        step(S_LIVE, RUN, "starting…");
        if (SEQUENCE_LIVE) { startLiveSequence(); return; }
        host.showLive(true);
        SurfaceHolder h = surface.getHolder();
        h.addCallback(this);
        if (h.getSurface() != null && h.getSurface().isValid()) surfaceCreated(h);
        ui.postDelayed(liveTimeout, 4000);
    }

    private final Runnable liveTimeout = new Runnable() {
        public void run() { if (!previewing && !closed) { report(S_LIVE, FAIL, "no live view surface"); close(); } }
    };

    /**
     * Live view failed with the map loaded (v0.28.0, under the default policy): give everything back
     * (camera, policy, map) and open again under the next allocation policy. Returns false when none is left.
     */
    private boolean reopen(String why) {
        if (!CAPTURE_HOOK || openPass + 1 >= OPEN_POLICIES.length || closed) return false;
        ui.removeCallbacks(liveTimeout);
        note("live view under allocation policy " + OPEN_POLICIES[openPass] + ": " + why + "; the camera goes back and opens again under policy "
                + OPEN_POLICIES[openPass + 1]);
        releaseCamera();   // also unloads the map and resets the policy
        if (!SEQUENCE_LIVE) host.showLive(false);   // hidden and shown again, so the second open gets a new surface
        openPass++;
        ui.postDelayed(new Runnable() { public void run() { if (!closed) startCamera(); } }, 1000);
        return true;
    }

    /**
     * v0.30.0: the camera opened with normal preview off, live view as the preview sequence's frames
     * (as LiveViewTest found, v0.21-0.23). Portrait Lighting's memory map may give normal preview's
     * buffers to its own work; v0.29.0 found the camera hardware wouldn't open with the map loaded
     * and normal preview on. Not drawn on screen: the frames are counted, to show live view runs.
     */
    static final boolean SEQUENCE_LIVE = false;   // only with CAPTURE_HOOK
    static final long LIVE_WAIT_MS = 3000;

    private long liveStart;
    private int liveFrames;

    private void startLiveSequence() {
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
            flushLog();   // if the camera hangs here, the card already says how far it got
            cs.getMethod("startPreviewSequence", oc).invoke(sequence, o);
        } catch (Throwable e) {
            report(S_LIVE, FAIL, "no preview sequence: " + Probe.why(e));
            stopSequence();
            if (!reopen(Probe.why(e))) close();
            return;
        }
        note("preview sequence started");
        liveStart = SystemClock.uptimeMillis();
        liveFrames = 0;
        ui.post(livePoll);
    }

    /** Takes the live frames as they come (and drops them). The first one says live view runs; then the test goes on. */
    private final Runnable livePoll = new Runnable() {
        public void run() {
            if (closed || sequence == null) return;
            byte[] f = grab();
            if (f != null && liveFrames++ == 0) {
                lastFrame = f;
                BitmapFactory.Options b = new BitmapFactory.Options();
                b.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(f, 0, f.length, b);
                try {   // the frame itself, to see what live view looks like with the map loaded
                    FileOutputStream o = new FileOutputStream(new File(Library.home(card), "LIVEFRM.JPG"));
                    try { o.write(f); } finally { o.close(); }
                } catch (Exception e) {
                    note("live frame: " + Probe.why(e));
                }
                report(S_LIVE, OK, "frames " + b.outWidth + "x" + b.outHeight + " after " + (SystemClock.uptimeMillis() - liveStart)
                        + " ms, allocation policy " + policy);
                liveRunning();
            }
            if (liveFrames == 0 && SystemClock.uptimeMillis() - liveStart > LIVE_WAIT_MS) {
                report(S_LIVE, FAIL, "no frames in " + LIVE_WAIT_MS / 1000 + " s");
                if (!reopen("no frames")) close();
                return;
            }
            ui.postDelayed(this, liveFrames == 0 ? 30 : 100);
        }
    };

    /** Live view runs: the hook, then the rest of the test, as with the surface. */
    private void liveRunning() {
        if (CAPTURE_HOOK) hookAfterLiveView(false);
        listen();
        focusOptions();
        level();
        host.note("Shooting test: point at something and hold still");
        ui.postDelayed(new Runnable() { public void run() { focus(); } }, 2500);   // after the level's two seconds
    }

    public void surfaceCreated(SurfaceHolder h) {
        if (previewing || closed || camera == null) return;
        try {
            camera.setPreviewDisplay(h);
            camera.startPreview();
            previewing = true;
        } catch (Throwable e) {
            report(S_LIVE, FAIL, Probe.why(e));
            if (!reopen(Probe.why(e))) close();
            return;
        }
        report(S_LIVE, OK, "on screen, allocation policy " + policy);
        if (CAPTURE_HOOK) hookAfterLiveView(false);
        listen();
        focusOptions();
        level();
        host.note("Shooting test: point at something and hold still");
        ui.postDelayed(new Runnable() { public void run() { focus(); } }, 2500);   // after the level's two seconds
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

    // ------------------------------------------------------------------------------ focus options (read only)

    /** The getters asked about, and only these: some others have crashed other Sony bodies. */
    private static final String[] FOCUS_GETTERS = { "getAutoFocusMode", "getSupportedAutoFocusModes", "getFocusAreaMode",
            "getSupportedFocusAreaModes", "getFocusAreaFlexibleSpotSize", "getSupportedFocusAreaFlexibleSpotSizes", "getFocusPointIndex",
            "getSupportedQuickAutoFocus", "getSupportedAutoFocusStartTriggers", "getFaceDetectionMode", "getSupportedFaceDetectionModes",
            "getMaxNumDetectedFaces", "getTrackingFocus", "getSupportedTrackingFocusModes", "isFocusDriveSupported", "isSupportedFocusHold",
            "getMaxFocusDriveSpeed" };

    /** What the camera offers for focusing: Android's focus modes and Sony's own (AF mode, areas, faces, tracking). */
    private void focusOptions() {
        step(S_OPTIONS, RUN, "reading…");
        try {
            Camera.Parameters p = camera.getParameters();
            note("focus mode " + p.getFocusMode() + ", supported " + p.getSupportedFocusModes());
            Object m = cameraEx.getClass().getMethod("createParametersModifier", Camera.Parameters.class).invoke(cameraEx, p);
            StringBuilder sum = new StringBuilder();
            for (String g : FOCUS_GETTERS) {
                Object v;
                try { v = m.getClass().getMethod(g).invoke(m); }
                catch (NoSuchMethodException e) { note(g + ": not on this camera"); continue; }
                catch (Throwable e) { note(g + ": " + Probe.why(e)); continue; }
                note(g + " = " + v);
                if (g.equals("getAutoFocusMode")) sum.append(v);
                if (g.equals("getFocusAreaMode")) sum.append(", ").append(v);
                if (g.equals("getSupportedFaceDetectionModes")) sum.append(", faces ").append(v);
                if (g.equals("getSupportedTrackingFocusModes") && v != null) sum.append(", tracking");
            }
            String flat = p.flatten();
            for (String kv : flat.split(";")) if (kv.contains("focus") || kv.contains("af") || kv.contains("face")) note("  " + kv);
            report(S_OPTIONS, OK, sum.length() > 0 ? sum.toString() : "read; see the log");
        } catch (Throwable e) {
            report(S_OPTIONS, FAIL, Probe.why(e));
        }
    }

    // ------------------------------------------------------------------------------ level sensor (read only)

    private SensorManager sensors;
    private int readings;
    private float gx, gy, gz;
    private String sensorNames = "";

    private final SensorEventListener gravity = new SensorEventListener() {
        public void onSensorChanged(SensorEvent e) {
            if (e.values == null || e.values.length < 3) return;
            if (readings++ < 3) note("sensor " + e.sensor.getType() + ": " + e.values[0] + ", " + e.values[1] + ", " + e.values[2]);
            gx = e.values[0]; gy = e.values[1]; gz = e.values[2];
        }
        public void onAccuracyChanged(Sensor s, int a) { }
    };
    private final SensorEventListener idle = new SensorEventListener() {
        public void onSensorChanged(SensorEvent e) { }
        public void onAccuracyChanged(Sensor s, int a) { }
    };

    /**
     * Can the camera tell how level it is? Lists its motion sensors, listens to them for two seconds
     * (with the magnetic one on too, as Sony's level does), and asks whether it has a digital level.
     */
    private void level() {
        step(S_LEVEL, RUN, "listening…");
        try {
            Class<?> sp = Class.forName("com.sony.scalar.sysutil.ScalarProperties");
            for (String prop : new String[] { "PROP_DEVICE_DIGITAL_LEVEL", "PROP_UI_DIGITAL_LEVEL_TYPE" }) {
                try {
                    Object key = sp.getField(prop).get(null);
                    Method get = null;
                    for (Method mm : sp.getMethods()) if (mm.getName().equals("getInt") && mm.getParameterTypes().length == 1) get = mm;
                    note(prop + " = " + (get != null ? get.invoke(null, key) : "?"));
                } catch (Throwable e) { note(prop + ": " + Probe.why(e)); }
            }
        } catch (Throwable e) { note("ScalarProperties: " + Probe.why(e)); }
        try {
            sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            StringBuilder names = new StringBuilder();
            for (Sensor x : sensors.getSensorList(Sensor.TYPE_ALL)) names.append(names.length() > 0 ? ", " : "").append(x.getName()).append(" (").append(x.getType()).append(")");
            sensorNames = names.length() > 0 ? names.toString() : "none";
            note("sensors: " + sensorNames);
            for (int type : new int[] { Sensor.TYPE_ACCELEROMETER, 9 /* gravity */, Sensor.TYPE_MAGNETIC_FIELD }) {
                Sensor x = sensors.getDefaultSensor(type);
                boolean ok = x != null && sensors.registerListener(type == Sensor.TYPE_MAGNETIC_FIELD ? idle : gravity, x, SensorManager.SENSOR_DELAY_GAME);
                note("sensor type " + type + (ok ? " listening" : " not available"));
            }
        } catch (Throwable e) {
            note("sensors: " + Probe.why(e));
        }
        try {
            Class<?> dm = Class.forName("com.sony.scalar.hardware.avio.DisplayManager");
            displayManager = dm.newInstance();
            Class<?> li = Class.forName("com.sony.scalar.hardware.avio.DisplayManager$OrientationRollListener");
            Object l = Proxy.newProxyInstance(li.getClassLoader(), new Class<?>[] { li }, new InvocationHandler() {
                public Object invoke(Object p, Method m, Object[] a) {
                    if (m.getName().equals("onChanged") && a != null && a.length > 0) { rollValue = String.valueOf(a[0]); note("display roll: " + a[0]); }
                    return objectMethod(p, m, a);
                }
            });
            dm.getMethod("setOrientationRollListener", li).invoke(displayManager, l);
            note("display roll listener set");
        } catch (Throwable e) {
            note("display roll: " + Probe.why(e));
            displayManager = null;
        }
        ui.postDelayed(new Runnable() { public void run() { levelDone(); } }, 2000);
    }

    private Object displayManager;
    private String rollValue;

    private void levelDone() {
        stopSensors();
        if (readings == 0) { report(S_LEVEL, rollValue != null ? INFO : FAIL, "no sensor readings" + (rollValue != null ? "; display roll " + rollValue : "; no display roll either")); return; }
        // held level, the camera's up axis reads +9.8; roll is the turn about the lens, pitch the tilt up or down
        double roll = Math.toDegrees(Math.atan2(gx, gy)), pitch = Math.toDegrees(Math.atan2(gz, Math.sqrt(gx * gx + gy * gy)));
        report(S_LEVEL, OK, readings + " readings in 2 s: roll " + String.format(java.util.Locale.US, "%.1f", roll) + "\u00b0, pitch "
                + String.format(java.util.Locale.US, "%.1f", pitch) + "\u00b0");
    }

    private void stopSensors() {
        if (displayManager != null) {
            try { displayManager.getClass().getMethod("releaseOrientationRollListener").invoke(displayManager); } catch (Throwable ignored) { }
            try { displayManager.getClass().getMethod("finish").invoke(displayManager); } catch (Throwable ignored) { }
            displayManager = null;
        }
        if (sensors == null) return;
        try { sensors.unregisterListener(gravity); sensors.unregisterListener(idle); } catch (Throwable ignored) { }
        sensors = null;
    }

    // ------------------------------------------------------------------------------ focus

    /** The camera's own autofocus; when it can't lock, cancel and go again, up to FOCUS_TRIES times. */
    private void focus() {
        if (closed) return;
        if (hooking) { ui.postDelayed(new Runnable() { public void run() { focus(); } }, 500); return; }   // the hook tries come first
        tries++;
        focusing = true;
        focusStart = SystemClock.uptimeMillis();
        step(S_FOCUS, RUN, tries == 1 ? "focusing…" : "focusing, try " + tries + "…");
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
            report(S_FOCUS, OK, "locked in " + ms + " ms" + (attempt > 1 ? " on try " + attempt : "") + lamp);
            shoot();   // with focus still held, as the camera does on a full press
            return;
        }
        if (attempt < FOCUS_TRIES) {
            try { camera.cancelAutoFocus(); } catch (Throwable e) { note("cancelAutoFocus: " + Probe.why(e)); }
            ui.postDelayed(new Runnable() { public void run() { focus(); } }, 300);
            return;
        }
        report(S_FOCUS, FAIL, "no lock in " + FOCUS_TRIES + " tries" + lamp + "; shooting anyway");
        shoot();
    }

    // ------------------------------------------------------------------------------ the picture

    private void shoot() {
        if (closed) return;
        step(S_SHOT, RUN, "shooting…");
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
                        note("capture " + m.getName().substring(2).toLowerCase(java.util.Locale.US) + (a != null && a.length > 2 ? ", reason " + a[1] : ""));
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
        if (!listening) { report(S_SHOT, FAIL, "no shutter listener on this camera"); afterPicture(); return; }
        shutterStart = SystemClock.uptimeMillis();
        if (sequenced) ui.postDelayed(captureWatchdog, 15000);
        try {
            call("burstableTakePicture");
        } catch (Throwable e) {
            note("burstableTakePicture: " + Probe.why(e));
            safely("cancelTakePicture");
            shooting = false;
            report(S_SHOT, FAIL, "the camera wouldn't shoot: " + Probe.why(e));
            afterPicture();
            return;
        }
        ui.postDelayed(new Runnable() {
            public void run() {
                if (shooting) { note("no shutter answer"); safely("cancelTakePicture"); shooting = false; report(S_SHOT, FAIL, "no answer from the shutter"); afterPicture(); }
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
        if (status != 0) {
            report(S_SHOT, FAIL, status == 1 ? "cancelled by the camera" : "the camera reported an error");
            if (sequenced) unlockShutter("the shutter failed");
            afterPicture();
            return;
        }
        if (sequenced) { step(S_SHOT, RUN, "exposed; developing…"); return; }   // the capture hook carries on from here
        step(S_SHOT, RUN, "saving…");
        waitForFile(ms, SystemClock.uptimeMillis());
    }

    /**
     * The camera writes the photo itself. Watch three ways: the folder listing (what Latent uses), the
     * photo's own path (from the catalogue), and Sony's catalogue, which the camera updates when it stores.
     */
    private void waitForFile(final long shutterMs, final long since) {
        if (closed) return;
        int now = Roll.highest(Library.photos(card));
        long waited = SystemClock.uptimeMillis() - since;
        if (catalogueSeen < 0 || pathSeen < 0) {
            Probe.Row row = newestCatalogued();
            if (row != null) {
                if (catalogueSeen < 0) {
                    catalogueSeen = waited;
                    note("in the catalogue " + waited + " ms after the shutter: " + row.data + " (" + row.folder + "/" + row.number + ")");
                    shot = dcfFile(row.folder, row.number);
                    try { Library.ignore(card, shot); note(shot.getName() + " kept off every roll (IGNORE.TXT)"); } catch (Exception e) { note("ignore: " + Probe.why(e)); }
                    // the camera has stored it: that is enough to go on (Linux sees the file only once the card is read afresh)
                    report(S_SHOT, OK, shot.getName() + (sequenced ? " in the catalogue" : " stored: shutter " + shutterMs + " ms, in the catalogue " + waited + " ms later"));
                    ui.postDelayed(new Runnable() { public void run() { afterPicture(); } }, 1500);
                    return;
                }
                File f = row.data == null ? null : new File(row.data);
                if (pathSeen < 0 && f != null && f.exists() && f.length() > 0) { pathSeen = waited; note("its path answers " + waited + " ms after the shutter, " + f.length() + " bytes"); }
            }
        }
        if (now > highestBefore) {
            note("new photo on the card " + waited + " ms after the shutter");
            photoSeen = true;
            report(S_SHOT, OK, "DSC" + String.format(java.util.Locale.US, "%05d", now % 10000) + ".JPG on the card: shutter " + shutterMs + " ms, saved "
                    + waited + " ms later" + (storedName != null ? "" : " (no store report)"));
            ui.postDelayed(new Runnable() { public void run() { afterPicture(); } }, 1500);   // Auto Review, if on, has its moment
            return;
        }
        if (waited > SAVE_TIMEOUT_MS) {
            String how = catalogueSeen >= 0 ? "; catalogued after " + catalogueSeen + " ms" + (pathSeen >= 0 ? ", path readable after " + pathSeen + " ms" : ", path not readable") : "; not in the catalogue either";
            report(S_SHOT, FAIL, "not in the folder in " + SAVE_TIMEOUT_MS / 1000 + " s" + how);
            afterPicture();
            return;
        }
        if (waited > 0 && waited % 3000 < 300) step(S_SHOT, RUN, "saving… " + waited / 1000 + " s");
        ui.postDelayed(new Runnable() { public void run() { waitForFile(shutterMs, since); } }, 300);
    }

    private boolean photoSeen;
    private File shot;   // the test's photo, by its DCF name (readable only through the catalogue until the card is read again)

    /** DCIM/100MSDCF/DSC04680.JPG for folder 100, number 4680 (the folder's suffix as on the card). */
    private File dcfFile(int folder, int number) {
        File dcim = new File(card, "DCIM"), dir = new File(dcim, folder + "MSDCF");
        File[] dirs = dcim.listFiles();
        if (dirs != null) for (File d : dirs) if (d.getName().startsWith(String.valueOf(folder))) dir = d;
        return new File(dir, String.format(java.util.Locale.US, "DSC%05d.JPG", number));
    }

    /** The catalogue's newest photo numbered above the card's highest before the shot, or null. */
    private Probe.Row newestCatalogued() {
        if (mediaId == null) return null;
        try {
            Class<?> media = Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
            android.net.Uri uri = (android.net.Uri) media.getMethod("getContentUri", String.class).invoke(null, mediaId);
            Probe.Row best = null;
            for (Probe.Row r : Probe.query(context.getContentResolver(), uri)) {
                int n = r.folder * 10000 + r.number;
                if (n > highestBefore && (best == null || n > best.folder * 10000 + best.number)) best = r;
            }
            return best;
        } catch (Throwable e) {
            if (catalogueSeen == -1) { note("catalogue: " + Probe.why(e)); catalogueSeen = -2; }
            return null;
        }
    }

    /** Live frames and the look timing are known (v0.21-0.23): off for this run. */
    static final boolean FRAMES = false;

    private void afterPicture() {
        if (closed) return;
        if (FRAMES) { frames(); return; }
        close();
    }

    // ------------------------------------------------------------------------------ the look at capture (Sony's own capture-time path)

    private Object sequence2;          // the capture sequence (CameraSequence) holding the shutter callback
    private boolean sequenced, locked, stored;
    private volatile boolean lookRunning;
    private boolean closeAfterLook;
    private long captureAt;

    /**
     * Sony's capture-time path, as its Picture Effect app does: a shutter-sequence callback gets each
     * capture before it is stored. With it installed the camera stores nothing by itself, and locks the
     * shutter after each capture until we say so. Returns false (and the camera stores as usual) if
     * this camera won't have it.
     */
    /**
     * The capture-time hook, off since v0.30.1: closed on this camera (docs/CALLBACK-FINDINGS.md).
     * Without a memory map the main processor refuses it (RESULT ERROR(2)); with the only map this body
     * ships (Portrait Lighting's) the camera hardware won't open. With this off, nothing below runs:
     * no map, no allocation policy, no hook, normal live view, and no system log at close.
     */
    static final boolean CAPTURE_HOOK = false;

    /** The ways tried, in order: Sony's full set; the map alone; the map with the counts. */
    static final String[] HOOK_VARIANTS = { "counts, release lock and map", "map only", "counts and map" };

    /**
     * v0.29.0: the memory map is loaded (MemoryMapConfig.loadExtraMap, message 0x000E) and the
     * allocation policy set before the camera opens, policy 1 first; v0.28.0 loaded the map under the
     * default policy and live view failed. If live view fails, the camera opens again under policy 2.
     * With live view running, each variant is tried, then again a second later. Stops at the first the
     * camera takes. The camera's answers go to LATENT/LOGCAT.TXT at close (docs/CALLBACK-FINDINGS.md).
     */
    static final int[] OPEN_POLICIES = { 1, 2 };   // MemoryMapConfig.ALLOCATION_POLICY_VERSION_1, _2

    private int openPass;          // which of OPEN_POLICIES the camera is open under
    private boolean handlerSet;
    private boolean hooking;       // focus waits while the hook is tried

    private void hookAfterLiveView(final boolean again) {
        hooking = true;
        String when = "map " + (mapLoaded ? "loaded" : "NOT loaded") + ", policy " + policy + (again ? ", 1 s later" : "");
        for (int v = 0; v < HOOK_VARIANTS.length && !sequenced; v++) sequenced = hookCapture(v, when);
        if (!sequenced && !again) {
            ui.postDelayed(new Runnable() { public void run() { if (!closed) hookAfterLiveView(true); } }, 1000);
            return;
        }
        hooking = false;
    }

    // ------------------------------------------------------------------------------ the memory map (MemoryMapConfig)

    private String map;           // the firmware's map for this camera, as the main processor sees it
    private boolean mapLoaded;    // loadExtraMap succeeded: unloadExtraMap at close
    private int policy;           // the allocation policy set now; 0 is the camera's default

    /** Loads the firmware's memory map for this camera. Before the camera opens. Undone by releaseMemory. */
    private void loadMap() {
        map = firmwareMap();
        if (map == null) return;
        note("loading the memory map " + map);
        flushLog();   // if the camera hangs here, the card already says how far it got
        try {
            Class.forName("com.sony.scalar.hardware.MemoryMapConfig").getMethod("loadExtraMap", String.class).invoke(null, map);
            mapLoaded = true;
            note("memory map loaded");
        } catch (Throwable e) {
            note("memory map: " + Probe.why(e));
        }
        flushLog();
    }

    private void setPolicy(int p) {
        if (p == policy) return;
        try {
            Class.forName("com.sony.scalar.hardware.MemoryMapConfig").getMethod("setAllocationPolicy", int.class).invoke(null, p);
            policy = p;
            note("allocation policy " + p);
        } catch (Throwable e) {
            note("allocation policy " + p + ": " + Probe.why(e));
        }
    }

    /** The camera's memory as it was: policy back to the default, the map unloaded. After the camera is released. */
    private void releaseMemory() {
        if (policy != 0) setPolicy(0);
        if (!mapLoaded) return;
        try {
            Class.forName("com.sony.scalar.hardware.MemoryMapConfig").getMethod("unloadExtraMap").invoke(null);
            note("memory map unloaded");
        } catch (Throwable e) {
            note("memory map unload: " + Probe.why(e));
        }
        mapLoaded = false;
    }

    /** Read only: the firmware's memory maps to LATENT/FW as M0001.BIN ..., with FW/MEMMAP.TXT naming them. Background thread. */
    private void copyMemoryMaps() {
        File to = new File(Library.home(card), "FW");
        if (new File(to, "MEMMAP.TXT").exists()) return;
        to.mkdirs();
        StringBuilder index = new StringBuilder();
        int n = 0;
        for (String dir : new String[] { "/system/etc/memmap", "/etc/memmap" }) {
            File[] list = new File(dir).listFiles();
            if (list == null || list.length == 0) continue;
            java.util.Arrays.sort(list);
            for (File f : list) {
                if (!f.isFile()) continue;
                File dst = new File(to, String.format(java.util.Locale.US, "M%04d.BIN", ++n));
                index.append(dst.getName()).append('\t').append(f.getPath()).append('\t').append(f.length()).append("\r\n");
                try {
                    java.io.InputStream in = new java.io.FileInputStream(f);
                    FileOutputStream o = new FileOutputStream(dst);
                    try {
                        byte[] b = new byte[65536];
                        for (int k; (k = in.read(b)) > 0; ) o.write(b, 0, k);
                    } finally { in.close(); o.close(); }
                } catch (Exception e) {
                    note("memory map copy: " + f + ": " + Probe.why(e));
                }
            }
            break;   // /etc is a link to /system/etc: one copy
        }
        try {
            FileOutputStream o = new FileOutputStream(new File(to, "MEMMAP.TXT"));
            try { o.write(index.toString().getBytes("UTF-8")); } finally { o.close(); }
        } catch (Exception e) {
            note("memory map index: " + Probe.why(e));
        }
        note("memory maps: " + n + " copied to LATENT/FW (FW/MEMMAP.TXT)");
    }

    private boolean hookCapture(int variant, String when) {
        try {
            Class<?> cs = Class.forName("com.sony.scalar.hardware.CameraSequence");
            Class<?> cx = Class.forName("com.sony.scalar.hardware.CameraEx");
            Class<?> oc = Class.forName("com.sony.scalar.hardware.CameraSequence$Options");
            Class<?> cb = Class.forName("com.sony.scalar.hardware.CameraSequence$ShutterSequenceCallback");
            sequence2 = sequence != null ? sequence : cs.getMethod("open", cx).invoke(null, cameraEx);   // one sequence for live view and the hook
            Object o = oc.newInstance();
            if (variant != 1) {
                oc.getMethod("setOption", String.class, int.class).invoke(o, oc.getField("EXPOSURE_COUNT").get(null), 1);
                oc.getMethod("setOption", String.class, int.class).invoke(o, oc.getField("RECORD_COUNT").get(null), 1);
            }
            if (variant == 0) oc.getMethod("setOption", String.class, boolean.class).invoke(o, oc.getField("AUTO_RELEASE_LOCK_ENABLED").get(null), true);
            Object callback = Proxy.newProxyInstance(cb.getClassLoader(), new Class<?>[] { cb }, new InvocationHandler() {
                public Object invoke(Object p, Method m, Object[] a) {
                    if (m.getName().equals("onShutterSequence") && a != null && a.length > 0) onCapture(a[0]);
                    return objectMethod(p, m, a);
                }
            });
            // the map loaded before the camera opened (loadMap), named here as the sequence's memory map
            if (map == null) throw new IllegalStateException("no firmware memory map for this camera");
            oc.getMethod("setOption", String.class, String.class).invoke(o, "MEMORY_MAP_FILE", map);
            note("hooking (" + when + ", " + HOOK_VARIANTS[variant] + ") with " + map);
            flushLog();   // if the camera hangs here, the card already says how far it got
            cs.getMethod("setShutterSequenceCallback", cb, oc).invoke(sequence2, callback, o);
            if (variant == 0) {
                cs.getMethod("setReleaseLock", boolean.class).invoke(sequence2, false);
                locked = true;   // from the shutter until unlockShutter
            }
            note("capture hook SET (" + when + ", " + HOOK_VARIANTS[variant] + ")");
            flushLog();
            return true;
        } catch (Throwable e) {
            note("capture hook (" + when + ", " + HOOK_VARIANTS[variant] + "): " + Probe.why(e));
            releaseSequence();
            return false;
        }
    }

    /** Never leave the shutter locked: if the capture never reaches us, unlock and give up. */
    private final Runnable captureWatchdog = new Runnable() {
        public void run() {
            if (captureAt != 0 || closed) return;
            report(S_DEVCAM, FAIL, "the capture never arrived (15 s)");
            unlockShutter("watchdog");
            afterPicture();
        }
    };

    /** The capture: developed by the camera into an image we may change, then the look, then stored. UI thread. */
    private void onCapture(Object raw) {
        captureAt = SystemClock.uptimeMillis();
        ui.removeCallbacks(captureWatchdog);
        note("capture arrived " + (captureAt - shutterStart) + " ms after the shutter");
        Object img = null;
        long t = SystemClock.uptimeMillis();
        try {
            Object filter = Class.forName("com.sony.scalar.hardware.CameraSequence$DefaultDevelopFilter").newInstance();
            Class<?> rd = Class.forName("com.sony.scalar.hardware.CameraSequence$RawData");
            filter.getClass().getMethod("setSource", rd, boolean.class).invoke(filter, raw, true);   // the filter owns raw now
            raw = null;
            HardwareDevelop.call(filter, "execute");
            img = HardwareDevelop.call(filter, "getOutput");
            HardwareDevelop.call(filter, "release");
            if (img == null) throw new IllegalStateException("the develop filter gave no image");
            int w = (Integer) HardwareDevelop.call(img, "getWidth"), h = (Integer) HardwareDevelop.call(img, "getHeight");
            report(S_DEVCAM, OK, w + "x" + h + " in " + (SystemClock.uptimeMillis() - t) + " ms");
        } catch (Throwable e) {
            report(S_DEVCAM, FAIL, Probe.why(e));
            if (img == null && raw != null) storeRaw(raw);   // the photo, as the camera would have stored it
            else if (img != null) store(img, false, "stored untouched");
            else unlockShutter("no image");
            return;
        }
        // the image's layout, as the darkroom reads it (Sony's DSP properties)
        final int[] g = new int[8];
        Object proc = null;
        t = SystemClock.uptimeMillis();
        try {
            Class<?> dsp = Class.forName("com.sony.scalar.hardware.DSP");
            Class<?> memory = Class.forName("com.sony.scalar.hardware.DeviceMemory");
            proc = dsp.getMethod("createProcessor", String.class).invoke(null, "sony-di-dsp");
            Method prop = dsp.getMethod("getPropertyAsInt", memory, String.class);
            for (int i = 0; i < 8; i++) g[i] = (Integer) prop.invoke(proc, img, SonyMemory.KEYS[i]);
            note("layout " + java.util.Arrays.toString(g) + " (read in " + (SystemClock.uptimeMillis() - t) + " ms)");
        } catch (Throwable e) {
            report(S_LOOKCAP, FAIL, "layout unreadable: " + Probe.why(e));
            store(img, false, "stored untouched");
            return;
        } finally {
            try { if (proc != null) HardwareDevelop.call(proc, "release"); } catch (Throwable ignored) { }
        }
        // the look, off the UI thread; then stored on it
        step(S_LOOKCAP, RUN, "the look…");
        flushLog();
        final Object image = img;
        lookRunning = true;
        new Thread(new Runnable() {
            public void run() {
                String result;
                boolean changed = false;
                int status;
                long t0 = SystemClock.uptimeMillis();
                try {
                    if (stock == null) throw new IllegalStateException("no film looks on the card");
                    int handle = SonyMemory.handle(image);
                    if (SonyMemory.verifyInPlace(handle, g, 1048576) != 0) throw new IllegalStateException(SonyMemory.lastError());
                    int[] p = lookParams();
                    p[FilmCore.THREADS] = 3;
                    changed = true;   // from here on the image may be partly changed
                    if (SonyMemory.developInPlace(handle, g, 1048576, stock.lut.getPath(), p, null) != 0) throw new IllegalStateException(SonyMemory.lastError());
                    int[] tm = new int[FilmCore.T_COUNT];
                    FilmCore.timing(tm);
                    result = stock.title + " in " + (SystemClock.uptimeMillis() - t0) + " ms (read " + tm[FilmCore.T_READ] + ", look "
                            + tm[FilmCore.T_LOOK] + ", write " + tm[FilmCore.T_WRITE] + ", " + tm[FilmCore.T_THREADS] + " threads)";
                    status = OK;
                } catch (Throwable e) {
                    result = Probe.why(e) + (changed ? " (part-way)" : " (image untouched)");
                    status = FAIL;
                }
                final String r = result;
                final int st = status;
                ui.post(new Runnable() {
                    public void run() {
                        lookRunning = false;
                        report(S_LOOKCAP, st, r);
                        store(image, st == OK, st == OK ? "with the look" : "as it is");
                        if (closeAfterLook) close();
                    }
                });
            }
        }, "capture-look").start();
    }

    /** sequence.storeImage(img, true): the camera encodes, names, catalogues and frees it. Then the shutter is unlocked. UI thread. */
    private void store(Object img, boolean looked, String how) {
        long t = SystemClock.uptimeMillis();
        try {
            Class<?> oi = Class.forName("com.sony.scalar.graphics.OptimizedImage");
            sequence2.getClass().getMethod("storeImage", oi, boolean.class).invoke(sequence2, img, true);
            stored = true;
            report(S_STORED, looked ? OK : INFO, how + ": stored in " + (SystemClock.uptimeMillis() - t) + " ms, "
                    + (SystemClock.uptimeMillis() - shutterStart) + " ms after the shutter");
        } catch (Throwable e) {
            report(S_STORED, FAIL, Probe.why(e));
            try { HardwareDevelop.call(img, "release"); } catch (Throwable ignored) { }
        }
        unlockShutter("stored");
        step(S_SHOT, RUN, "finding it in the catalogue…");
        waitForFile(SystemClock.uptimeMillis() - shutterStart, SystemClock.uptimeMillis());
    }

    private void storeRaw(Object raw) {
        try {
            Class<?> rd = Class.forName("com.sony.scalar.hardware.CameraSequence$RawData");
            sequence2.getClass().getMethod("storeImage", rd, boolean.class).invoke(sequence2, raw, true);
            report(S_STORED, INFO, "stored as the camera develops it");
        } catch (Throwable e) {
            report(S_STORED, FAIL, Probe.why(e));
        }
        unlockShutter("stored raw");
        afterPicture();
    }

    /** The shutter lock the capture hook set: always lifted. */
    private void unlockShutter(String why) {
        if (!locked || sequence2 == null) return;
        try {
            sequence2.getClass().getMethod("setReleaseLock", boolean.class).invoke(sequence2, false);
            note("shutter unlocked (" + why + ")");
        } catch (Throwable e) {
            note("unlock: " + Probe.why(e));
        }
        locked = false;
    }

    /**
     * The firmware's memory map for this camera, named as Sony's MemoryMapFile names them:
     * lib + device.memory + raw megapixels + "m_" + app + "_" + index + "_" + version + ".so", in
     * /etc/memmap (which the camera's native side sees under /android/system). Lists what is there
     * first. Returns the path to give the sequence, or null when there is none for this camera.
     */
    private String firmwareMap() {
        String mem = property("device.memory"), mp = property("mem.rawimage.size.in.mega.pixel");
        String prefix = "lib" + mem + mp + "m_";
        note("device.memory " + mem + ", raw megapixels " + mp + ", platform " + platform() + "; maps named " + prefix + "...");
        String best = null;
        int bestRank = 0;
        for (String dir : new String[] { "/etc/memmap", "/system/etc/memmap" }) {
            String[] names = new File(dir).list();
            note(dir + ": " + (names == null ? "not readable" : names.length + " files"));
            if (names == null) continue;
            java.util.Arrays.sort(names);
            for (String n : names) {
                note("  " + n);
                if (!n.startsWith(prefix) || !n.endsWith(".so")) continue;
                // closest to ours: Picture Effect (one capture, developed, an effect, stored), then any single capture
                int rank = n.contains("PictureEffect") ? 3 : n.contains("Single") || n.contains("_00_") ? 2 : 1;
                if (rank > bestRank || (rank == bestRank && best != null && n.compareTo(best) > 0)) { best = n; bestRank = rank; }
            }
            if (best != null) break;
        }
        if (best == null) { note("no map for this camera"); return null; }
        return "/android/system/etc/memmap/" + best;
    }

    private static String property(String key) {
        try {
            Class<?> sp = Class.forName("com.sony.scalar.sysutil.ScalarProperties");
            return String.valueOf(sp.getMethod("getString", String.class).invoke(null, key));
        } catch (Throwable e) {
            return "?";
        }
    }

    /**
     * The system log to LATENT/LOGCAT.TXT, read straight from the kernel's log device (/dev/log/main),
     * since this camera won't start another program (logcat hung in v0.26.3, as sync did in v0.12).
     * READ_LOGS lets an app read it on Android 2.3. Each read gives one entry: a 20-byte header
     * (payload length, pid, tid, time) then priority, tag and message. The camera service may say
     * there why the capture hook was refused. Reads for at most 3 s.
     */
    private void saveSystemLog() {
        final File out = new File(Library.home(card), "LOGCAT.TXT");
        final StringBuilder text = new StringBuilder();
        final int[] entries = { 0 };
        final String[] problem = { null };
        Thread t = new Thread(new Runnable() {
            public void run() {
                java.io.FileInputStream in = null;
                try {
                    in = new java.io.FileInputStream("/dev/log/main");
                    byte[] b = new byte[5 * 1024 + 20];
                    for (int n; (n = in.read(b)) > 20; ) {
                        int len = (b[0] & 0xff) | (b[1] & 0xff) << 8;
                        int pid = le(b, 4), sec = le(b, 12);
                        int end = Math.min(n, 20 + len), p = 20;
                        int prio = b[p++];
                        int tagEnd = p;
                        while (tagEnd < end && b[tagEnd] != 0) tagEnd++;
                        String tag = new String(b, p, tagEnd - p, "UTF-8");
                        int msgEnd = tagEnd + 1;
                        while (msgEnd < end && b[msgEnd] != 0) msgEnd++;
                        String msg = msgEnd > tagEnd + 1 ? new String(b, tagEnd + 1, msgEnd - tagEnd - 1, "UTF-8") : "";
                        synchronized (text) {
                            text.append(sec).append(' ').append(pid).append(' ').append("VDIWEF".charAt(Math.max(0, Math.min(5, prio - 2))))
                                    .append('/').append(tag).append(": ").append(msg.trim()).append("\r\n");
                            entries[0]++;
                        }
                    }
                } catch (Throwable e) {
                    problem[0] = Probe.why(e);
                } finally {
                    try { if (in != null) in.close(); } catch (Throwable ignored) { }
                }
            }
        }, "log-reader");
        t.start();
        try { t.join(3000); } catch (InterruptedException ignored) { }   // the device blocks once it has given all it holds
        synchronized (text) {
            try {
                FileOutputStream o = new FileOutputStream(out);
                try { o.write(text.toString().getBytes("UTF-8")); } finally { o.close(); }
            } catch (Exception e) {
                note("system log: " + Probe.why(e));
            }
            note("system log: " + entries[0] + " entries in LATENT/LOGCAT.TXT" + (problem[0] != null ? " (" + problem[0] + ")" : ""));
        }
    }

    private static int le(byte[] b, int i) {
        return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8 | (b[i + 2] & 0xff) << 16 | (b[i + 3] & 0xff) << 24;
    }

    /** The log so far onto the card, before a step that could hang the camera. */
    private void flushLog() {
        try {
            FileOutputStream o = new FileOutputStream(new File(Library.home(card), "SHOOTING.TXT"));
            try { o.write((log + "  (written before a risky step; the test went on)\r\n").getBytes("UTF-8")); } finally { o.close(); }
        } catch (Exception ignored) { }
    }

    private static String platform() {
        try {
            Class<?> sp = Class.forName("com.sony.scalar.sysutil.ScalarProperties");
            return String.valueOf(sp.getMethod("getString", String.class).invoke(null, "version.platform"));
        } catch (Throwable e) {
            return "?";
        }
    }

    private void releaseSequence() {
        if (sequence2 == null) return;
        unlockShutter("closing");
        try {
            Class<?> cb = Class.forName("com.sony.scalar.hardware.CameraSequence$ShutterSequenceCallback");
            sequence2.getClass().getMethod("setShutterSequenceCallback", cb).invoke(sequence2, (Object) null);
        } catch (Throwable ignored) { }
        if (sequence2 != sequence)   // the live sequence is released by stopSequence
            try { sequence2.getClass().getMethod("release").invoke(sequence2); } catch (Throwable e) { note("capture sequence release: " + Probe.why(e)); }
        sequence2 = null;
    }

    private int[] lookParams() {
        int[] p = new int[FilmCore.COUNT];
        p[FilmCore.HIGHLIGHT] = stock.highlight;
        p[FilmCore.SHADOW] = stock.shadow;
        p[FilmCore.GRAIN] = stock.grain;
        p[FilmCore.GRAIN_SIZE] = stock.grainSize;
        p[FilmCore.QUALITY] = 95;
        p[FilmCore.SEED] = shot == null ? 1 : shot.getName().hashCode();
        p[FilmCore.THREADS] = 1;
        return p;
    }

    // ------------------------------------------------------------------------------ live frames

    /** Live view as small JPEG frames (CameraSequence's preview sequence), polled for a few seconds. */
    private void frames() {
        step(S_FRAMES, RUN, "asking for frames…");
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
            report(S_FRAMES, FAIL, "no preview sequence: " + Probe.why(e));
            stopSequence();
            lookOnFrame(null);
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
            report(S_FRAMES, FAIL, "none in " + ms / 1000 + " s (" + emptyPolls + " empty asks)");
            lookOnFrame(null);
            return;
        }
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(lastFrame, 0, lastFrame.length, b);
        report(S_FRAMES, OK, frameCount + " in " + ms / 1000.0 + " s (" + String.format(java.util.Locale.US, "%.1f", frameCount * 1000.0 / ms)
                + " a second), " + b.outWidth + "x" + b.outHeight + ", " + frameBytes / frameCount / 1024 + " KB each");
        lookOnFrame(lastFrame);
    }

    /** How long a film look takes on one live frame: decides how smooth a live look could be. */
    private void lookOnFrame(final byte[] jpeg) {
        step(S_LOOK, RUN, "timing…");
        closeCamera();   // the look runs with the camera given back already
        new Thread(new Runnable() {
            public void run() {
                String result;
                int status;
                try {
                    if (jpeg == null) throw new IllegalStateException("no frame to try");
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
                ui.post(new Runnable() { public void run() { report(S_LOOK, s, r); finish(); } });
            }
        }, "look-timing").start();
    }

    // ------------------------------------------------------------------------------ closing

    /** MENU, leaving Latent, or a failed step: the camera goes back at once. Safe to call more than once. */
    void close() {
        if (closed) return;
        if (lookRunning) { closeAfterLook = true; note("close asked during the look: after it's stored"); return; }   // never free the capture under the look
        closeCamera();
        releaseMemory();   // also when the camera never opened
        if (CAPTURE_HOOK) saveSystemLog();   // the camera's answers, all of them, every run
        finish();
    }

    private void closeCamera() {
        if (cameraEx == null) return;
        releaseCamera();
        host.showLive(false);
        report(S_CLOSED, OK, "live view stopped, camera released");
    }

    /** cancel focus and capture, stop the sequence and live view, then release: the order the verified apps use. */
    private void releaseCamera() {
        ui.removeCallbacks(poll);
        ui.removeCallbacks(livePoll);
        if (liveFrames > 0) note("live frames: " + liveFrames + " in " + (SystemClock.uptimeMillis() - liveStart) / 1000 + " s");
        liveFrames = 0;
        stopSensors();
        releaseSequence();   // the hook first: it may be on the live sequence
        stopSequence();
        if (cameraEx == null) { releaseMemory(); return; }
        try { if (camera != null) camera.cancelAutoFocus(); } catch (Throwable ignored) { }
        if (shooting) safely("cancelTakePicture");
        set("setShutterListener", null, null);
        set("setAutoFocusDoneListener", null, null);
        set("setFocusLightStateListener", null, null);
        set("setStoreImageCompleteListener", null, null);
        set("setCaptureStatusListener", null, null);
        try { if (camera != null && previewing) camera.stopPreview(); } catch (Throwable e) { note("stopPreview: " + Probe.why(e)); }
        // Sony's own apps release the Android camera before CameraEx (their ExecutorCreator.terminate)
        try { if (camera != null) camera.release(); } catch (Throwable e) { note("camera release: " + Probe.why(e)); }
        try { cameraEx.getClass().getMethod("release").invoke(cameraEx); } catch (Throwable e) { note("release: " + Probe.why(e)); }
        try { surface.getHolder().removeCallback(this); } catch (Throwable ignored) { }
        cameraEx = null;
        camera = null;
        previewing = false;
        releaseMemory();   // the map goes after the camera that used it
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
