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
 * left as it is. Every camera call is on the UI thread, which opened the camera, and the camera is
 * always released at the end, on MENU, when Latent is left, or if Latent crashes. The calls follow
 * apps verified on the A5100 and the A5000 (BetterManual, FocusBracket, sony-remote-reboot).
 * Log: LATENT/SHOOTING.TXT, one frame: LATENT/LIVEFRM.JPG.
 */
final class ShootingTest implements SurfaceHolder.Callback {
    static final String[] STEPS = { "Open the camera", "Live view", "Focus options", "Level sensor", "Focus", "Take a picture",
            "Live frames", "A look on a frame", "Develop the shot", "Camera closed" };
    static final int S_OPEN = 0, S_LIVE = 1, S_OPTIONS = 2, S_LEVEL = 3, S_FOCUS = 4, S_SHOT = 5, S_FRAMES = 6, S_LOOK = 7, S_DEVELOP = 8,
            S_CLOSED = 9;
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
        out.step(S_OPEN, RUN, "opening…");
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
                }
            } catch (Throwable e) {
                note("no target media: " + Probe.why(e));
                options = null;
            }
            note("opening with target media " + media);
            cameraEx = open.invoke(null, 0, options);
            camera = (Camera) cx.getMethod("getNormalCamera").invoke(cameraEx);
        } catch (Throwable e) {
            report(S_OPEN, FAIL, Probe.why(e));
            close();
            return;
        }
        report(S_OPEN, OK, "opened in " + (SystemClock.uptimeMillis() - t) + " ms");
        out.step(S_LIVE, RUN, "starting…");
        host.showLive(true);
        SurfaceHolder h = surface.getHolder();
        h.addCallback(this);
        if (h.getSurface() != null && h.getSurface().isValid()) surfaceCreated(h);
        ui.postDelayed(new Runnable() {
            public void run() { if (!previewing && !closed) { report(S_LIVE, FAIL, "no live view surface"); close(); } }
        }, 4000);
    }

    public void surfaceCreated(SurfaceHolder h) {
        if (previewing || closed || camera == null) return;
        try {
            camera.setPreviewDisplay(h);
            camera.startPreview();
            previewing = true;
        } catch (Throwable e) {
            report(S_LIVE, FAIL, Probe.why(e));
            close();
            return;
        }
        report(S_LIVE, OK, "on screen");
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
        out.step(S_OPTIONS, RUN, "reading…");
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
        out.step(S_LEVEL, RUN, "listening…");
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
        tries++;
        focusing = true;
        focusStart = SystemClock.uptimeMillis();
        out.step(S_FOCUS, RUN, tries == 1 ? "focusing…" : "focusing, try " + tries + "…");
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
        out.step(S_SHOT, RUN, "shooting…");
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
        if (status != 0) { report(S_SHOT, FAIL, status == 1 ? "cancelled by the camera" : "the camera reported an error"); afterPicture(); return; }
        out.step(S_SHOT, RUN, "saving…");
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
                    report(S_SHOT, OK, shot.getName() + " stored: shutter " + shutterMs + " ms, in the catalogue " + waited + " ms later");
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
        if (waited > 0 && waited % 3000 < 300) out.step(S_SHOT, RUN, "saving… " + waited / 1000 + " s");
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

    /** Live frames and the look timing are known (v0.21-0.23); this run develops right after the release instead. */
    static final boolean FRAMES = false;

    private void afterPicture() {
        if (closed) return;
        if (FRAMES) { frames(); return; }
        report(S_FRAMES, INFO, "skipped this run (up to 19 a second before)");
        report(S_LOOK, INFO, "skipped this run (143 ms at 320 px before)");
        closeCamera();
        out.step(S_DEVELOP, RUN, "developing…");
        new Thread(new Runnable() {
            public void run() {
                waitForImageMemory();
                final String[] dev = developShot();
                ui.post(new Runnable() { public void run() { report(S_DEVELOP, Integer.parseInt(dev[0]), dev[1]); finish(); } });
            }
        }, "develop-shot").start();
    }

    /** With the camera given back: does a photo the test didn't see land now? (Background thread.) */
    private void watchAfterRelease() {
        if (photoSeen || shutterStart == 0) return;
        final long released = SystemClock.uptimeMillis();
        for (int i = 0; i < 40; i++) {
            if (Roll.highest(Library.photos(card)) > highestBefore) {
                note("photo landed " + (SystemClock.uptimeMillis() - released) + " ms after the camera was released");
                return;
            }
            try { Thread.sleep(250); } catch (InterruptedException e) { return; }
        }
        note("no photo within 10 s of the release either");
    }

    // ------------------------------------------------------------------------------ live frames

    /** Live view as small JPEG frames (CameraSequence's preview sequence), polled for a few seconds. */
    private void frames() {
        out.step(S_FRAMES, RUN, "asking for frames…");
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
        out.step(S_LOOK, RUN, "timing…");
        closeCamera();   // the look runs with the camera given back already
        new Thread(new Runnable() {
            public void run() {
                watchAfterRelease();
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
                ui.post(new Runnable() { public void run() { report(S_LOOK, s, r); out.step(S_DEVELOP, RUN, "developing…"); } });
                final String[] dev = developShot();
                ui.post(new Runnable() { public void run() { report(S_DEVELOP, Integer.parseInt(dev[0]), dev[1]); finish(); } });
            }
        }, "look-timing").start();
    }

    /**
     * v0.23.1: after the camera was released, Sony's image processor gave no buffer at all. Is that
     * for a while, or for as long as Latent runs? Asks for a small buffer now and then, up to 20 s.
     * Background thread.
     */
    private void waitForImageMemory() {
        long released = SystemClock.uptimeMillis();
        for (long at : new long[] { 0, 1000, 3000, 6000, 10000, 20000 }) {
            long wait = released + at - SystemClock.uptimeMillis();
            if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
            Object proc = null, buf = null;
            try {
                Class<?> dsp = Class.forName("com.sony.scalar.hardware.DSP");
                proc = dsp.getMethod("createProcessor", String.class).invoke(null, "sony-di-dsp");
                buf = HardwareDevelop.call(proc, "createBuffer", 65536);   // as the darkroom asks
                note("image memory " + at / 1000 + " s after the release: " + (buf != null ? "a buffer" : "none"));
                if (buf != null) return;
            } catch (Throwable e) {
                note("image memory " + at / 1000 + " s after the release: " + Probe.why(e));
            } finally {
                try { if (buf != null) buf.getClass().getMethod("release").invoke(buf); } catch (Throwable ignored) { }
                try { if (proc != null) proc.getClass().getMethod("release").invoke(proc); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * The shot through Latent's darkroom, as Photo mode would: found in Sony's catalogue by its number,
     * decoded by the camera, the look applied, saved as a new photo. The print is kept off every roll
     * too. Returns {status, detail}. Background thread, camera already released.
     */
    private String[] developShot() {
        if (shot == null) return new String[] { String.valueOf(INFO), "no shot to develop" };
        if (stock == null) return new String[] { String.valueOf(INFO), "no film looks on the card" };
        StringBuilder dl = new StringBuilder();
        try {
            HardwareDevelop h = new HardwareDevelop(context, card, dl, null);
            h.prepare(false);
            int[] p = new int[FilmCore.COUNT];
            p[FilmCore.HIGHLIGHT] = stock.highlight;
            p[FilmCore.SHADOW] = stock.shadow;
            p[FilmCore.GRAIN] = stock.grain;
            p[FilmCore.GRAIN_SIZE] = stock.grainSize;
            p[FilmCore.QUALITY] = 95;
            p[FilmCore.SEED] = shot.getName().hashCode();
            p[FilmCore.THREADS] = 1;
            long t = SystemClock.uptimeMillis();
            HardwareDevelop.Print print = h.develop(shot, stock.lut.getPath(), p, null);
            long ms = SystemClock.uptimeMillis() - t;
            try { Library.ignore(card, print.file); } catch (Exception e) { note("ignore: " + Probe.why(e)); }
            note("develop log:\r\n" + dl);
            return new String[] { String.valueOf(print.catalogued ? OK : INFO), stock.title + ": " + print.file.getName() + " in " + ms / 1000.0 + " s"
                    + (print.catalogued ? "" : " (not catalogued)") };
        } catch (Throwable e) {
            note("develop log:\r\n" + dl);
            return new String[] { String.valueOf(FAIL), Probe.why(e) };
        }
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
        report(S_CLOSED, OK, "live view stopped, camera released");
    }

    /** cancel focus and capture, stop the sequence and live view, then release: the order the verified apps use. */
    private void releaseCamera() {
        ui.removeCallbacks(poll);
        stopSensors();
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
