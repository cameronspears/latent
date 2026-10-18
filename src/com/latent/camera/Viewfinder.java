package com.latent.camera;

import android.app.Activity;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.os.Handler;
import android.os.StatFs;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Latent's own viewfinder, the way the X Half is the camera: live view with the roll's film look on
 * it, focus on a half-press, the picture on a full press.
 *
 * How, all of it found on this camera (the Shooting, Live look and Restart tests, v0.3x-v0.44):
 * - The camera opens through Sony's CameraEx on the UI thread, naming the memory card as its target.
 *   Every camera call and callback is on this thread.
 * - Live view runs as usual on the surface behind Latent's drawing; the camera also hands Latent the same
 *   picture as small JPEG frames (CameraSequence's preview sequence, 320 px, asked for as Sony's Smart
 *   Remote asks). Each gets the look on a look thread (FilmCore.frame), and is put up over the camera's
 *   own. Centre shows the camera's own live view instead, until it is pressed again.
 * - Focus is the camera's own (Camera.autoFocus), retried while the button is held (Release).
 * - The picture is the camera's own (burstableTakePicture): it stores it itself, with your settings.
 *   Latent learns its name from Sony's catalogue, told to look after each picture (Smart Remote does).
 * - Exposure compensation (DOWN, the ± key) is the camera's own setting, shared with shooting mode.
 *   No other camera setting is changed.
 * - The camera is given back on MENU (when pictures wait, so Latent can restart and develop them),
 *   when Latent is left, and if Latent crashes; never while a picture may still be storing.
 *
 * Once this process has opened the camera, Sony's image memory stays taken until it ends, so the
 * darkroom can't run here (see opened): MainActivity restarts Latent to develop.
 * Log: LATENT/VIEWFIND.TXT, a few lines per visit.
 */
final class Viewfinder implements SurfaceHolder.Callback, Release.Camera {
    interface Host {
        /** The camera's own live view on screen, behind Latent's drawing (true), or not. */
        void showLive(boolean on);
        /** Something on screen changed. */
        void changed();
        /** Only the picture changed (a new live frame): redraw just its area (on its own surface: the last state again). */
        void frame();
        /** Something over the picture moves (the print, the film card, a note): its area again, from a fresh state. */
        void animate();
        /** The picture's own surface can't be used any more (the look failed): take it away, so the camera's view shows. */
        void pictureGone();
        /**
         * The picture's own surface on the screen (true) or off it (false: hidden behind MENU, or the look off,
         * so the camera's own view shows). Moved, never made again: a surface has never been made with the
         * camera open, so the one made before it opened is kept for the visit.
         */
        void picturePlace(boolean onScreen);
        /** Live view is on: the shutter button works from now. */
        void live();
        /**
         * A picture was taken: its file (from the catalogue), or null if the catalogue didn't say (the
         * usual case: the camera lists it once given back), and the film it was taken in.
         */
        void shot(File photo, String film);
        /** The camera is given back; why is null when asked to close, else what went wrong. */
        void closed(String why);
        /** A picture won't save, so the camera can't be given back safely: what to tell you. */
        void stuck(String what);
        /** Latent's own drawing over live view, shown (true) or not (only the camera's picture). */
        void hud(boolean on);
    }

    /**
     * This process has opened the camera. Sony's image memory, which the darkroom needs, is then gone
     * until Latent closes (v0.23-0.24: not after a release, not in another process), so nothing
     * develops until the next time Latent opens.
     */
    static volatile boolean opened;

    /**
     * Direct shutter (the button is the camera's own). Off: Latent focuses and takes the pictures
     * itself, exactly as the Shooting test has every time without a fault (focus, burstableTakePicture,
     * stored and catalogued about 0.2 s later).
     */
    static final boolean DIRECT_SHUTTER = false;

    static final int FRAME_W = 320, FRAME_H = 240;
    static final int FRAME_COMPRESS = 8;
    static final long MIN_FRAME_MS = 45;   // at most ~22 a second (v0.38.0: two look threads, the screen copying one picture)
    /**
     * Each finished look thread gets the next frame at once (v0.45.0: FrameScheduler); this watchdog only
     * keeps the stats, the no-frames fallback and the end of the store quiet going (it was the 10-20 ms poll).
     */
    static final long WATCHDOG_MS = 100, HIDDEN_WATCHDOG_MS = 500;
    /** The frame rate follows the scene: slower while it's still and nothing is touched (Pacer). Off: full rate always. */
    static final boolean PACE = true;
    /**
     * Once-a-visit measurements already in the history (the 16/32-bit copy probe, LIVE.JPG, each core's
     * clock, unreadable here): off, so they cost the live view nothing. On only to measure again.
     */
    static final boolean DIAG = false;
    /** The core's speed probe (FilmCore.probe) runs on this frame, not the first, so the first frame isn't late. */
    static final int PROBE_AT_FRAME = 40;
    /** Frame widths to ask the camera for, in turn, if frames don't come. */
    static final int[] WIDTHS = { 320, 640 };
    static final long FRAMES_WAIT_MS = 2500, FOCUS_TIMEOUT_MS = 6000, SHUTTER_TIMEOUT_MS = 10000, STORE_WAIT_MS = 8000;
    static final long PRINT_MS = 4000, FLASH_MS = 140, CARD_MS = 1600, NOTE_MS = 2600;
    /** Display modes: up and down cycle them. */
    static final int D_STANDARD = 0, D_GRID = 1, D_CLEAN = 2;
    static final String[] DISPLAY_NAMES = { "Standard", "Grid", "Clean" };

    private final Activity activity;
    private final File card;
    private final SurfaceView surface;
    private final ExecutorService worker;   // FilmCore's one thread
    private final Host host;
    private final Handler ui = new Handler();
    private final ExecutorService catalogue = Executors.newSingleThreadExecutor();
    private final StringBuilder log = new StringBuilder();
    private final long t0 = SystemClock.uptimeMillis();
    final Release release;

    // the film
    private String lut;
    private final int[] params = new int[FilmCore.COUNT];

    // the camera
    private Object cameraEx, sequence;
    private Camera camera;
    private String mediaId;
    private boolean previewing, closed;
    private Thread.UncaughtExceptionHandler previousHandler;

    // live frames
    private int widthIndex;
    private boolean sequenceStopped;
    private long framesAskedAt, lastFrameAt, lastGrabAt, statsAt;
    private int grabs, grabMsSum, statFrames, statLookMs;
    private long lastLookMs, grabCpuNs;
    private int slowSpells;
    private int frames, lookMsSum, grabFails, lookErrors;
    private final FrameScheduler scheduler = new FrameScheduler();
    private final boolean[] busyNow = new boolean[LOOK_CORES.length];
    private int drains, bitmapMisses, recomposeWaits;
    private final int[] lateBy = new int[LOOK_CORES.length], statLateBy = new int[LOOK_CORES.length];
    private long ageSum;     // grab to put up (on its surface) or shown (through Latent's screen), under shown16
    private int ageN;
    // the pace (Pacer): slower while the scene is still and nothing is touched
    private final Pacer pacer = new Pacer();
    private boolean pacing = PACE;   // off for the visit if the native core can't check a frame
    private int stillChecks, stillFrames, paceChanges;
    private long slowMsSum;
    private final long[] stageMs = new long[FilmCore.TM_COUNT];
    private final long[] statStage = new long[FilmCore.TM_COUNT];
    /**
     * Two look threads, on cores 2 and 1 (the camera starts Latent on core 3 only, which the screen keeps;
     * cores 1 and 2 sit almost idle), each with its own composer (FilmCore.frame): frames go to whichever is
     * free, so two are made at once. v0.37.0 had one, on core 2: 2.7 frames a second.
     */
    static final int[] LOOK_CORES = { 2, 1, 3 };
    /**
     * Never core 0: it is the camera's own. v0.41-0.42 ran a third look thread there (background priority)
     * and the overlay's (normal priority); in a demo the camera's live view tore into green and pink lines and
     * the camera restarted itself, a store took 3.5 s, not 0.5: its own video path starved. Kept for the
     * record: no look thread is put there any more.
     *
     * Core 3 is Latent's own, the screen's: with the picture on its own surface (v0.43.1) it sat half idle
     * (44-51%) while cores 1 and 2 did every frame. A third look thread runs there at background priority,
     * so the screen and the presenter always come first, and only gets a frame when 2 and 1 are busy.
     */
    static final int LOW_PRIORITY_CORE = 3;
    private final Looker[] lookers = new Looker[LOOK_CORES.length];
    private int seq, shownSeq;
    private long lastShownAt;
    private volatile boolean probed, probeWanted;
    private final int[] framesBy = new int[LOOK_CORES.length], statFramesBy = new int[LOOK_CORES.length];
    /** The look's grain moves every frame, as film's does (blue noise: it reads as grain, never a pattern). */
    static final boolean MOVING_GRAIN = false;
    /**
     * The screen's pictures, made whole by the composer (the look, Latent's type and fades over it, dithered):
     * RGB565, opaque, so the screen copies rather than blends, holding the 4-bit values the 16-bit plane
     * shows exactly. One shown, the rest on their way or being made.
     */
    private final Bitmap[] shown16 = new Bitmap[6];   // one shown, up to three on their way, up to three being made
    private volatile int front16 = -1, posted16;   // posted16: a bit for each made and not yet shown
    private int frontSeq;                            // under shown16: the shown picture's frame number
    private long frontGrabAt;                        // ... and when it was taken from the camera
    private Looker frontBy;                          // UI thread: the look thread that made it
    private int frontOverlay;                        // the overlay generation in the shown picture (0: none)
    /**
     * Hidden or the look off, the shown picture is out of date: nothing uses it (no print is kept from it, the
     * camera's own view shows) until a frame taken since (at or after resumedAt) is up.
     */
    private boolean awaitingFresh;
    private long resumedAt;
    private volatile boolean bitmapsDirect = true;   // written in place (libjnigraphics), else through a short[]
    private int liveW, liveH;

    /** The picture's size on the screen in real pixels (the 3:2 area), so the 16-bit look is made 1:1. */
    void setLiveSize(int w, int h) { liveW = w; liveH = h; }

    private Method mFrames, mSize, mRead, mFrameRelease;
    boolean lookOn = true;     // the film look on live view; off: the camera's own picture
    private boolean lookFailed;
    int[] hist;

    // focus
    private int focusGen;
    private long focusStart;
    private boolean lamp, lampThisTry;
    private int locks, misses;
    String afMode = "", afArea = "";

    // exposure
    String shutter = "", aperture = "", iso = "";
    boolean isoAuto;
    int ev;                    // exposure compensation, in thirds of a stop (the camera's own setting)
    private int evMin = -9, evMax = 9;
    private float evStep = 1f / 3;
    boolean evEditing;         // DOWN (±): the scale is open and the wheel moves it
    private long evEditUntil;
    int shotsLeft = -1;
    private long photoBytes = 6 * 1024 * 1024;

    // pictures
    private long shutterAt, flashUntil, printAt, fullPressAt;
    int shots;                 // taken on this visit
    private int stored;        // of those, found in the catalogue
    private int highestKnown;  // the highest photo number known (the card's, then each new one)
    private boolean catalogueBusy;
    /**
     * The print in the corner, and the one being kept for the next: two bitmaps made once and drawn into in
     * turn, never freed while the viewfinder is open (the presenter may be drawing the last one; v0.44 freed
     * it on the UI thread, a check-then-draw race in native code). The reaper frees them with the pictures.
     */
    private Bitmap print;
    private final Bitmap[] prints = new Bitmap[2];
    /** A picture may be being taken or stored (storing(), kept for the threads that mustn't read the card then). */
    private volatile boolean storeBusy;

    // the screen
    int display = D_STANDARD;
    private long cardUntil, noteUntil;
    private String note = "";

    Viewfinder(Activity activity, File card, SurfaceView surface, ExecutorService worker, Stock stock, int highestPhoto, Host host) {
        this.activity = activity; this.card = card; this.surface = surface; this.worker = worker; this.host = host;
        this.release = new Release(this, !DIRECT_SHUTTER);
        this.highestKnown = highestPhoto;
        setFilm(stock);
        overlayer.execute(new Runnable() { public void run() { FilmCore.setOverlay(null, 0, 0); } });   // none from a last visit
        pacer.input(SystemClock.uptimeMillis());   // opening is a touch: full rate to begin with
    }

    private String filmId;                                                   // the film on live view now
    private final java.util.LinkedList<String> shotFilms = new java.util.LinkedList<String>();   // each picture's film, until it's counted

    /** The film whose look is on live view (and the next picture's). */
    void setFilm(Stock stock) {
        filmId = stock == null ? null : stock.id;
        lut = stock == null ? null : stock.lut.getPath();
        params[FilmCore.HIGHLIGHT] = stock == null ? 0 : stock.highlight;
        params[FilmCore.SHADOW] = stock == null ? 0 : stock.shadow;
        params[FilmCore.GRAIN_SIZE] = 1;
        params[FilmCore.QUALITY] = 90;
        params[FilmCore.THREADS] = 1;
    }

    // ---- films read ahead into the image core's shared film cache (FilmCore.cacheLut), so the wheel's next film
    // is on the next frame, not after each look thread has read and parsed its 862 KB file (v0.44: ~200 ms each)

    private ExecutorService warmer;
    private final java.util.HashSet<String> warmed = new java.util.HashSet<String>();   // UI thread: asked for already
    private boolean warmerSet, warmFailed;   // warmer thread
    private volatile boolean framesCame;     // the first frame is up
    private int warmNotes;

    /**
     * UI thread: these films into the cache, in this order, on a thread of their own (Latent's core 3): the
     * first at once and at normal priority (the roll's film, wanted by the first frame), the rest at background
     * priority once frames are coming. Never while a picture may be storing (it waits, or skips).
     */
    void prewarm(String... luts) {
        if (closed || luts == null) return;
        if (warmer == null) warmer = Executors.newSingleThreadExecutor();
        for (final String l : luts) {
            if (l == null || !warmed.add(l)) continue;
            final boolean first = warmed.size() == 1;
            try {
                warmer.execute(new Runnable() { public void run() { warm(l, first); } });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                return;   // closed
            }
        }
    }

    /** Warmer thread: one film into the shared cache. */
    private void warm(String lut, boolean first) {
        if (warmFailed || closed) return;
        try {
            if (!warmerSet) {
                warmerSet = true;
                try { FilmCore.pinCore(LOW_PRIORITY_CORE); } catch (Throwable ignored) { }   // Latent's own core, never the camera's
            }
            if (!first) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                for (int i = 0; i < 50 && !framesCame && !closed; i++) Thread.sleep(100);   // after the first frame
            }
            for (int i = 0; i < 100 && storeBusy && !closed; i++) Thread.sleep(100);   // a picture storing: the card is the camera's
            if (closed || storeBusy) return;
            long t = SystemClock.uptimeMillis();
            int rc = FilmCore.cacheLut(lut);
            if (warmNotes++ < 8) note("film cached: " + new File(lut).getName() + " in " + (SystemClock.uptimeMillis() - t) + " ms"
                    + (rc == 0 ? "" : " (" + FilmCore.lastError() + ")"));
        } catch (InterruptedException e) {
            // closing
        } catch (Throwable e) {   // an image core without the shared cache: each look thread reads its film, as before
            warmFailed = true;
            note("film cache: " + Probe.why(e));
        }
    }

    private void note(String line) {
        synchronized (log) { noteLocked(line); }
    }

    private void noteLocked(String line) {
        log.append("  ").append(SystemClock.uptimeMillis() - t0).append(" ms  ").append(line).append("\r\n");
    }

    // ------------------------------------------------------------------------------ open

    /**
     * The camera's own 16-bit drawing plane (RGBA4444) while the camera is Latent's, as Sony's apps shoot
     * (their BaseApp sets it at boot and keeps it; full colour only for the playback grid). Latent ran
     * full colour (ABGR8888) everywhere: the first picture after the camera opened was stored, every
     * later one waited, and each was stored 0.3 s after Latent's pause put the 16-bit plane back
     * (v0.35.4-v0.35.7). Full colour again once the camera is given back.
     */
    static final boolean SHOOT_16BIT = true;

    private boolean openCalled;

    boolean opened() { return openCalled; }

    void open() {
        if (openCalled) return;
        openCalled = true;
        opened = true;
        if (SHOOT_16BIT) Display.fullColour(false);
        synchronized (log) { log.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append(" viewfinder\r\n"); }
        if (DIAG) note("cores: " + cpuFreqs());
        openedAt = SystemClock.uptimeMillis();
        onLogger(new Runnable() {   // once a visit: which cores these are, and the battery as the visit starts
            public void run() {
                openBattery = battery(activity);
                note("cpu: " + cpuInfo() + "; battery " + batteryText(openBattery));
            }
        });
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                // a camera held by a dead app can freeze the body: give it back first
                try { note("crash: " + Probe.why(e)); releaseCamera(); writeLog(); } catch (Throwable ignored) { }
                if (previousHandler != null) previousHandler.uncaughtException(t, e);
            }
        });
        long t = SystemClock.uptimeMillis();
        try {
            Class<?> cx = Class.forName("com.sony.scalar.hardware.CameraEx");
            Class<?> oc = Class.forName("com.sony.scalar.hardware.CameraEx$OpenOptions");
            Object options = null;
            try {   // the memory card as the target, as Sony's own apps open it
                String[] ids = (String[]) Class.forName("com.sony.scalar.provider.AvindexStore").getMethod("getExternalMediaIds").invoke(null);
                if (ids != null && ids.length > 0) {
                    mediaId = ids[0];
                    options = oc.newInstance();
                    oc.getMethod("setTargetMedia", String.class).invoke(options, mediaId);
                    try { oc.getMethod("setRecordingMode", int.class).invoke(options, 0); } catch (Throwable e) { note("recording mode: " + Probe.why(e)); }
                }
            } catch (Throwable e) {
                note("no target media: " + Probe.why(e));
                options = null;
            }
            cameraEx = cx.getMethod("open", int.class, oc).invoke(null, 0, options);
            camera = (Camera) cx.getMethod("getNormalCamera").invoke(cameraEx);
        } catch (Throwable e) {
            note("open: " + Probe.why(e));
            fail("The camera wouldn't open");
            return;
        }
        note("camera open in " + (SystemClock.uptimeMillis() - t) + " ms (media " + mediaId + ")");
        flushLog();
        host.showLive(true);
        SurfaceHolder h = surface.getHolder();
        h.addCallback(this);
        if (h.getSurface() != null && h.getSurface().isValid()) surfaceCreated(h);
        ui.postDelayed(new Runnable() {
            public void run() { if (!previewing && !closed) { note("no live view surface in 4 s"); fail("Live view didn't start"); } }
        }, 4000);
    }

    public void surfaceCreated(SurfaceHolder h) {
        if (previewing || closed || camera == null) return;
        try {
            camera.setPreviewDisplay(h);
            camera.startPreview();
            previewing = true;
        } catch (Throwable e) {
            note("live view: " + Probe.why(e));
            fail("Live view didn't start");
            return;
        }
        note("live view on; load " + loadAvg());
        catalogue.execute(baseline);
        listen();
        // the shutter button is the camera's own, as in Sony's apps and sony-remote-reboot: started
        // before the live frames, it is what lets the camera store pictures with the frames running
        if (DIRECT_SHUTTER) {
            startDirect();
            note(direct ? "direct shutter on" : "no direct shutter: Latent takes the pictures");
            if (!direct) release.app = true;
        }
        readSettings();
        startFrames();
        ui.post(tick);
        host.live();
        host.changed();
    }

    public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) { }

    public void surfaceDestroyed(SurfaceHolder h) { if (!closed) close(null); }

    /**
     * The exposure-change and AF-start listeners, off: the viewfinder is the only code that ever set them,
     * and no picture it took was ever stored while it held the camera (v0.31-v0.35.3; v0.32.1's only once
     * Latent was left), while the Shooting test and the Live look test, without them, stored theirs.
     * Exposure is read when focus locks instead (readExposure).
     */
    static final boolean EXPOSURE_LISTENERS = false;

    /** The camera's answers: the shutter, the AF lamp, and exposure as it changes. Each is optional (bodies differ). */
    private void listen() {
        set("setShutterListener", "com.sony.scalar.hardware.CameraEx$ShutterListener", new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("onShutter") && a != null && a.length > 0) onShutter((Integer) a[0]);
                return objectMethod(p, m, a);
            }
        });
        set("setFocusLightStateListener", "com.sony.scalar.hardware.CameraEx$FocusLightStateListener", new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("onChanged") && a != null && a.length > 1) {
                    lamp = (Boolean) a[1];
                    if (lamp) lampThisTry = true;
                    host.changed();
                }
                return objectMethod(p, m, a);
            }
        });
        set("setAutoFocusDoneListener", "com.sony.scalar.hardware.CameraEx$AutoFocusDoneListener", new InvocationHandler() {
            private int logged;
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("onDone") && a != null && a.length > 1 && a[0] instanceof Integer) {
                    int status = (Integer) a[0];
                    lastReportAt = SystemClock.uptimeMillis();
                    if (logged++ < 20) note("camera's focus: status " + status + ", areas " + (a[1] instanceof int[] ? java.util.Arrays.toString((int[]) a[1]) : String.valueOf(a[1])));
                    if (!release.app && status == Release.AF_WARN) lampThisTry = lamp || lampThisTry;
                    release.cameraFocus(status);
                    host.changed();
                }
                return objectMethod(p, m, a);
            }
        });
        if (EXPOSURE_LISTENERS) set("setAutoFocusStartListener", "com.sony.scalar.hardware.CameraEx$AutoFocusStartListener", new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("onStart")) { lampThisTry = lamp; release.cameraFocus(Release.AF_WORKING); host.changed(); }
                return objectMethod(p, m, a);
            }
        });
        if (EXPOSURE_LISTENERS) set("setShutterSpeedChangeListener", "com.sony.scalar.hardware.CameraEx$ShutterSpeedChangeListener", new InvocationHandler() {
            private boolean logged;
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().startsWith("on") && a != null && a.length > 0 && a[0] != null) {
                    if (!logged) { logged = true; note("shutter speed info: " + fields(a[0])); }
                    int n = intField(a[0], "currentShutterSpeed_n"), d = intField(a[0], "currentShutterSpeed_d");
                    if (n > 0 && d > 0) { shutter = shutterText(n, d); host.changed(); }
                }
                return objectMethod(p, m, a);
            }
        });
        if (EXPOSURE_LISTENERS) set("setApertureChangeListener", "com.sony.scalar.hardware.CameraEx$ApertureChangeListener", new InvocationHandler() {
            private boolean logged;
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().startsWith("on") && a != null && a.length > 0 && a[0] != null) {
                    if (!logged) { logged = true; note("aperture info: " + fields(a[0])); }
                    int f = intField(a[0], "currentAperture");
                    if (f > 0) { aperture = apertureText(f); host.changed(); }
                }
                return objectMethod(p, m, a);
            }
        });
        // the camera's live view back after a capture (Sony's framework: DevelopmentState waits for this, then
        // EEState calls startPreview() again)
        set("setPreviewStartListener", "com.sony.scalar.hardware.CameraEx$PreviewStartListener", new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("onStart")) previewStarted();
                return objectMethod(p, m, a);
            }
        });
        // the store and capture reports the Shooting and Live look tests had set (both stored their pictures)
        set("setStoreImageCompleteListener", "com.sony.scalar.hardware.CameraEx$StoreImageCompleteListener", new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("onDone") && a != null && a.length > 1 && storeReports++ < 30)
                    note("store report: status " + a[0] + ", " + field(a[1], "DirectoryName") + "/" + field(a[1], "FileName"));
                return objectMethod(p, m, a);
            }
        });
        for (String iface : new String[] { "com.sony.scalar.hardware.CameraEx$OnCaptureStatusListener", "com.sony.scalar.hardware.CameraEx$CaptureStatusListener" }) {
            if (set("setCaptureStatusListener", iface, new InvocationHandler() {
                public Object invoke(Object p, Method m, Object[] a) {
                    if ((m.getName().equals("onStart") || m.getName().equals("onEnd")) && captureReports++ < 30)
                        note("capture " + m.getName().substring(2).toLowerCase(Locale.US) + (a != null && a.length > 1 ? " " + a[0] + (a.length > 2 ? ", " + a[1] : "") : ""));
                    return objectMethod(p, m, a);
                }
            })) break;
        }
        if (EXPOSURE_LISTENERS) set("setAutoISOSensitivityListener", "com.sony.scalar.hardware.CameraEx$AutoISOSensitivityListener", new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().startsWith("on") && a != null && a.length > 0 && a[0] instanceof Integer) {
                    int v = (Integer) a[0];
                    if (v > 0 && isoAuto) { iso = String.valueOf(v); host.changed(); }
                }
                return objectMethod(p, m, a);
            }
        });
    }

    /**
     * Shutter speed, aperture and ISO as the camera has them now (on a focus lock; the listeners are off). Changes
     * nothing; the caller redraws.
     */
    private void readExposure() {
        try {
            Camera.Parameters p = camera.getParameters();
            Object m = cameraEx.getClass().getMethod("createParametersModifier", Camera.Parameters.class).invoke(cameraEx, p);
            Object pair = m.getClass().getMethod("getShutterSpeed").invoke(m);
            if (pair instanceof android.util.Pair) {
                Object n = ((android.util.Pair<?, ?>) pair).first, d = ((android.util.Pair<?, ?>) pair).second;
                if (n instanceof Integer && d instanceof Integer && (Integer) n > 0 && (Integer) d > 0) shutter = shutterText((Integer) n, (Integer) d);
            }
            Object f = m.getClass().getMethod("getAperture").invoke(m);
            if (f instanceof Integer && (Integer) f > 0) aperture = apertureText((Integer) f);
        } catch (Throwable e) {
            if (exposureFails++ < 3) note("exposure: " + Probe.why(e));
        }
    }

    private int exposureFails;

    /** Focus mode and area, exposure as set, and how many more photos fit on the card. Read once; changes nothing. */
    private void readSettings() {
        try {
            Camera.Parameters p = camera.getParameters();
            afMode = afModeText(p.get("af-mode"), p.getFocusMode());
            afArea = areaText(p.get("focus-area-type"));
            float step = p.getExposureCompensationStep();
            evStep = step > 0 ? step : 1f / 3;
            ev = Math.round(p.getExposureCompensation() * evStep * 3);
            evMin = Math.round(p.getMinExposureCompensation() * evStep * 3);
            evMax = Math.round(p.getMaxExposureCompensation() * evStep * 3);
            if (evMin >= evMax) { evMin = -9; evMax = 9; }
            evApplied = ev;
            try {
                Object m = cameraEx.getClass().getMethod("createParametersModifier", Camera.Parameters.class).invoke(cameraEx, p);
                Object pair = m.getClass().getMethod("getShutterSpeed").invoke(m);
                if (pair instanceof android.util.Pair) {
                    Object n = ((android.util.Pair<?, ?>) pair).first, d = ((android.util.Pair<?, ?>) pair).second;
                    if (n instanceof Integer && d instanceof Integer && (Integer) n > 0 && (Integer) d > 0) shutter = shutterText((Integer) n, (Integer) d);
                }
                Object f = m.getClass().getMethod("getAperture").invoke(m);
                if (f instanceof Integer && (Integer) f > 0) aperture = apertureText((Integer) f);
                Object i = m.getClass().getMethod("getISOSensitivity").invoke(m);
                if (i instanceof Integer) { isoAuto = (Integer) i == 0; iso = isoAuto ? "" : String.valueOf(i); }
            } catch (Throwable e) {
                note("exposure: " + Probe.why(e));
            }
            note("focus " + afMode + ", " + afArea + "; " + shutter + " " + aperture + " ISO " + (isoAuto ? "auto" : iso) + ", exposure " + ev + "/3");
        } catch (Throwable e) {
            note("settings: " + Probe.why(e));
        }
        catalogue.execute(new Runnable() {   // reading the card's folders takes a while: not on this thread
            public void run() {
                int left;
                try {
                    StatFs fs = new StatFs(card.getPath());
                    List<File> newest = Library.photos(card);
                    if (!newest.isEmpty() && newest.get(0).length() > 1024 * 1024) photoBytes = newest.get(0).length();
                    left = (int) Math.min(9999, (long) fs.getAvailableBlocks() * fs.getBlockSize() / photoBytes);
                } catch (Throwable e) {
                    left = -1;
                }
                final int l = left;
                ui.post(new Runnable() { public void run() { shotsLeft = l; host.changed(); } });
            }
        });
    }

    // ------------------------------------------------------------------------------ live frames with the look

    /**
     * The film look on live view comes from the camera's live JPEG frames (CameraSequence's preview sequence),
     * asked for as Sony's Smart Remote asks for them. v0.31 lost its pictures with them running, but it also
     * never told Sony's catalogue to look (so it never saw them) and released or reopened the camera with them
     * in doubt; v0.34.1's Live look test stored two pictures with the frames running, each in the catalogue
     * ~2.2 s later once flushed. A setting the test turned on until v0.45.0; always on since.
     */
    private void startFrames() {
        try {
            Class<?> cs = Class.forName("com.sony.scalar.hardware.CameraSequence");
            if (sequence == null) sequence = cs.getMethod("open", Class.forName("com.sony.scalar.hardware.CameraEx")).invoke(null, cameraEx);
            Class<?> oc = Class.forName("com.sony.scalar.hardware.CameraSequence$Options");
            Object o = oc.newInstance();
            option(o, "PREVIEW_FRAME_RATE", 30000);   // as Smart Remote and the Live look test ask
            option(o, (String) oc.getField("PREVIEW_FRAME_MAX_NUM").get(null), 1);
            option(o, (String) oc.getField("PREVIEW_FRAME_WIDTH").get(null), WIDTHS[widthIndex]);
            option(o, (String) oc.getField("PREVIEW_FRAME_HEIGHT").get(null), 0);   // the picture's own shape
            option(o, "PREVIEW_FRAME_FORMAT", 0x100);                                // JPEG
            // a frame about 1/8 of its raw size (~17 KB at 320 px), not 1/15 (8 KB, JPEG quality ~60: blotchy, soft edges)
            option(o, "JPEG_COMPRESS_RATE_DENOM", FRAME_COMPRESS);
            option(o, "JPEG_COMPRESS_MAX_SIZE", 100);
            cs.getMethod("startPreviewSequence", oc).invoke(sequence, o);
            mFrames = cs.getMethod("getPreviewSequenceFrames", int.class);
        } catch (Throwable e) {
            note("live frames: " + Probe.why(e));
            noLook("no live frames");
            return;
        }
        sequenceStopped = false;
        framesAskedAt = SystemClock.uptimeMillis();
        note("live frames asked for at " + WIDTHS[widthIndex] + " px");
        ui.post(poll);
    }

    /** The camera's own live view from now on, with a word on the screen. */
    private void noLook(String why) {
        if (pictureHolder != null) host.pictureGone();
        if (lookFailed) return;
        lookFailed = true;
        lookOn = false;
        say("Film look unavailable on live view");
        note("look off: " + why);
        host.changed();
    }

    /**
     * While the camera writes a picture, Latent stays off its processor: no frames asked for, no look
     * (the camera's own live view shows for the moment). v0.34.1 stored with plain frames running; the
     * v0.35.0 runs that also ran the look lost theirs.
     */
    static final long STORE_QUIET_MS = 1500;   // the frame taken holds on screen meanwhile, as a camera shows what it took
    /**
     * The quiet after the shutter's answer: at least QUIET_FLOOR_MS, and on until the picture is in the
     * catalogue, never past QUIET_CAP_MS: 1.5 s and 3 s, exactly as since v0.35.1, though v0.44.0 had every
     * picture listed ~0.4 s after the shutter. A shorter floor is your call, from the 'live again' lines.
     */
    static final long QUIET_FLOOR_MS = STORE_QUIET_MS, QUIET_CAP_MS = 2 * STORE_QUIET_MS;
    private long quietUntil, quietCapAt;

    /** A picture is being written (or may be): no frames, no look. */
    private boolean quietNow(long now) { return FrameScheduler.quiet(now, quietUntil, stored >= shots, quietCapAt); }

    /**
     * The watchdog (it was the poll, every 10-20 ms, until v0.45.0): the stats every 10 s, the fallback when
     * no frames come at all, and frames again once a picture's quiet is over. Frames themselves are asked
     * for by feed, the moment a look thread is free.
     */
    private final Runnable poll = new Runnable() {
        public void run() {
            if (closed || closing || sequence == null || sequenceStopped) return;
            long now = SystemClock.uptimeMillis();
            feed();
            if (now - statsAt >= 10000) stats(now, !hidden);   // hidden: counted again from the return, nothing written
            if (frames == 0 && lastFrameAt == 0 && now - framesAskedAt > FRAMES_WAIT_MS) {   // none yet: another size, or none at all
                stopSequence();
                if (++widthIndex < WIDTHS.length) { note("no frames at " + WIDTHS[widthIndex - 1] + " px"); startFrames(); }
                else noLook("no frames at any size");
                return;
            }
            ui.postDelayed(this, hidden ? HIDDEN_WATCHDOG_MS : WATCHDOG_MS);
        }
    };

    private final Runnable feeder = new Runnable() { public void run() { feed(); } };

    /**
     * The next frame from the camera, when something wants one now (FrameScheduler), else a wake-up for when
     * something might. UI thread: when a look thread finishes, on a key, from the watchdog. Each ask is a call
     * to the camera's own processor, never closer together than MIN_FRAME_MS (v0.31.0's camera slowed down
     * with the frames going flat out).
     */
    private void feed() {
        if (closed || closing || sequence == null || sequenceStopped) return;
        ui.removeCallbacks(feeder);
        long now = SystemClock.uptimeMillis();
        boolean drain = hidden || !lookOn;   // out of sight, or the camera's own view: frames only drained
        if (pacing && !drain) pace(now);
        long spacing = pacing ? pacer.spacing(MIN_FRAME_MS) : MIN_FRAME_MS;
        for (int n = 0; n <= LOOK_CORES.length; n++) {
            if (n > 0) now = SystemClock.uptimeMillis();   // after a grab: its ~10 ms count toward the spacing
            boolean quiet = quietNow(now);
            for (int i = 0; i < lookers.length; i++) {
                if (lookers[i] == null) lookers[i] = new Looker(LOOK_CORES[i]);
                busyNow[i] = lookers[i].busy;
            }
            int act = scheduler.next(now, lastGrabAt, busyNow, quiet, drain, spacing);
            if (act == FrameScheduler.WAIT) {
                long at = scheduler.wakeAt > 0 ? scheduler.wakeAt : quiet && quietUntil > now ? quietUntil : 0;
                if (at > now) ui.postDelayed(feeder, at - now);   // else a look thread finishing, the catalogue or the watchdog
                return;
            }
            long cpu = android.os.Debug.threadCpuTimeNanos(), g = SystemClock.uptimeMillis();
            byte[] f = grab();
            if (f == null) { ui.postDelayed(feeder, act == FrameScheduler.DRAIN ? 50 : 10); return; }   // the camera has none ready yet
            lastGrabAt = g;
            if (act == FrameScheduler.DRAIN) {   // out of sight or the camera's own view: taken and dropped, nothing made
                drains++;
                continue;
            }
            long cpuAfter = android.os.Debug.threadCpuTimeNanos();
            grabMsSum += SystemClock.uptimeMillis() - g;
            if (cpu >= 0 && cpuAfter >= cpu) grabCpuNs += cpuAfter - cpu;
            grabs++;
            boolean check = pacing && lookOn && pacer.check(now);
            if (check) stillChecks++;
            if (DIAG && frames == 60 && !frameSaved && !storing()) saveFrame(f);
            look(lookers[scheduler.looker], f, check, g);
        }
    }

    /** Before a frame is asked for: full rate again while anything is touched, moving on screen or storing. */
    private void pace(long now) {
        if (active(now) && pacer.input(now)) paced(now, "something on screen or a picture");
        stillRef();
    }

    /** The shutter half-pressed, the scale open, a picture storing, or something over the picture moving. */
    private boolean active(long now) {
        return release.half || evEditing || storing() || now < cardUntil || now < noteUntil || now < flashUntil + 100
                || (print != null && now - printAt < PRINT_MS + 200);
    }

    /**
     * Something over the picture moves at 'now', so the tick gives fresh states: the exposure scale open, the blink,
     * the film card or a note up (each with 100 ms more), the print developing (printAt, or -1 with no print).
     */
    static boolean moving(long now, boolean evEditing, long flashUntil, long cardUntil, long noteUntil, long printAt) {
        return evEditing || now < flashUntil + 100 || now < cardUntil + 100 || now < noteUntil + 100 || (printAt >= 0 && now - printAt < PRINT_MS + 200);
    }

    /** Any key in the viewfinder: the frame rate back to full at once. */
    void touched() {
        long now = SystemClock.uptimeMillis();
        if (!pacer.input(now)) return;
        paced(now, "a key");
        stillRef();
        feed();
    }

    /** The image core's still check follows the Pacer's reference (null: none, every frame made). */
    private void stillRef() {
        if (!pacer.takeRefChange()) return;
        try {
            FilmCore.setStillRef(pacer.ref, Pacer.THRESHOLD);
        } catch (Throwable e) {   // a core without it: full rate for the visit
            pacing = false;
            pacer.input(SystemClock.uptimeMillis());
            note("pace: off (" + Probe.why(e) + ")");
        }
    }

    /** The pace changed: the log says so (the first few times a visit). */
    private void paced(long now, String why) {
        paceChanges++;
        if (pacer.slow) {
            if (paceChanges <= 20) note("pace: still, nothing touched for " + Pacer.IDLE_MS / 1000 + " s: a check every " + Pacer.SLOW_MS
                    + " ms, a whole frame each second");
        } else {
            slowMsSum += now - pacer.slowSince;
            if (paceChanges <= 20) note("pace: full rate (" + why + ") after " + (now - pacer.slowSince) / 100 / 10.0 + " s slow");
        }
    }

    private byte[] grab() {
        Object[] got = null;
        try {
            got = (Object[]) mFrames.invoke(sequence, 1);
            if (got == null || got.length == 0 || got[0] == null) return null;
            Object fr = got[0];
            if (mSize == null) {
                mSize = fr.getClass().getMethod("getSize");
                mRead = fr.getClass().getMethod("read", byte[].class);
                mFrameRelease = fr.getClass().getMethod("release");
            }
            int size = (Integer) mSize.invoke(fr);
            if (size <= 0) return null;
            byte[] data = new byte[size];
            mRead.invoke(fr, data);
            lastFrameAt = SystemClock.uptimeMillis();
            return data;
        } catch (Throwable e) {
            if (grabFails++ < 3) note("frame: " + Probe.why(e));
            return null;
        } finally {
            if (got != null) for (Object fr : got) {
                if (fr == null) continue;
                try {
                    if (mFrameRelease != null) mFrameRelease.invoke(fr); else fr.getClass().getMethod("release").invoke(fr);
                } catch (Throwable ignored) { }
            }
        }
    }

    /** One look thread: its core, its composer, what it read. */
    private static final class Looker {
        final int core;
        final ExecutorService exec = Executors.newSingleThreadExecutor();
        long ctx;
        boolean busy;          // as the UI thread sees it
        String pinned;
        short[] out;           // only when bitmaps can't be written in place
        final int[] info = new int[FilmCore.FI_COUNT], tm = new int[FilmCore.TM_COUNT];
        Looker(int core) { this.core = core; }
    }

    /** What a look thread made of one frame, for the UI thread. */
    private static final class Looked {
        Looker by;
        int seq, w, h, made = -1, overlay;
        long ms, grabAt;
        int[] tm, hist;
        int[] sig;             // the frame's brightness in cells (FilmCore.FI_SIG), when the core gives it
        boolean still;         // a still check, and the scene was still: nothing made, the shown picture stays
        boolean miss;          // every screen bitmap was busy: the frame went
        boolean noSig;         // made from a frame, but the core gave no signature (it can't check for stillness)
        String err;
    }

    /** Which of LOOK_CORES this core is, or -1. */
    private static int coreIndex(int core) {
        for (int i = 0; i < LOOK_CORES.length; i++) if (LOOK_CORES[i] == core) return i;
        return -1;
    }

    /** Look thread: a bitmap for the next picture, neither shown nor made and not yet shown; -1 if all are busy. */
    private int takeBitmap() {
        synchronized (shown16) {
            for (int i = 0; i < shown16.length; i++) {
                if (i == front16 || i == presenting || (posted16 & 1 << i) != 0) continue;
                posted16 |= 1 << i;
                return i;
            }
        }
        return -1;
    }

    private void giveBack(int i) {
        if (i >= 0) synchronized (shown16) { posted16 &= ~(1 << i); }
    }

    /**
     * The look on one frame (jpeg null: that thread's last frame again, under a new overlay), made whole on a
     * look thread straight into a screen bitmap; shown on the UI thread. check: only if the scene moved
     * (FilmCore.F_STILL: the Pacer's slow pace). grabAt: when the frame was taken from the camera.
     */
    private void look(final Looker k, final byte[] jpeg, boolean check, final long grabAt) {
        k.busy = true;
        final String l = lut;
        final int[] p = params.clone();
        final int n = ++seq;
        // the histogram only when it shows
        final int flags = (jpeg != null && display == D_GRID ? FilmCore.F_HIST : 0)
                | (MOVING_GRAIN ? FilmCore.F_TEMPORAL : 0) | (check && jpeg != null ? FilmCore.F_STILL : 0);
        final int w = liveW, h = liveH;
        // once a visit, on core 2, a while in (not the first frame, which it made late): this core's and its memory's speed
        final boolean probe = probeWanted && !probed && jpeg != null && k.core == LOOK_CORES[0];
        k.exec.execute(new Runnable() {
            public void run() {
                if (k.pinned == null) {
                    k.pinned = FilmCore.pinCore(k.core) == 0 ? "core " + k.core : "Latent's core (" + FilmCore.lastError() + ")";
                    k.ctx = FilmCore.frameNew();
                    // set once (v0.44 set and reset it every frame: on core 3 two cgroup moves a frame): a little behind
                    // the screen and the camera's callbacks, ahead of the darkroom (v0.31's background priority starved
                    // it under the camera's own load); on core 3, Latent's own, only what the screen leaves
                    android.os.Process.setThreadPriority(k.core == LOW_PRIORITY_CORE ? android.os.Process.THREAD_PRIORITY_BACKGROUND
                            : android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE);
                }
                if (probe && !probed && w > 0 && h > 0) {
                    probed = true;
                    Bitmap pb = null;
                    try {
                        pb = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
                        note("speed: " + FilmCore.probe(pb));
                    } catch (Throwable e) {
                        note("speed: " + Probe.why(e));
                    } finally {
                        if (pb != null) pb.recycle();   // its own bitmap, made and freed on this thread
                    }
                }
                final Looked r = new Looked();
                r.by = k;
                r.seq = n;
                r.grabAt = jpeg == null ? 0 : grabAt;   // a frame again (under a new overlay) has no age of its own
                long t = SystemClock.uptimeMillis();
                try {
                    if (closed) return;
                    if (k.ctx == 0) { r.err = "no memory for the look"; return; }
                    if (w <= 0 || h <= 0) { r.err = "no screen size"; return; }
                    r.made = takeBitmap();
                    if (r.made < 0) { r.miss = true; return; }   // every picture busy: this frame goes
                    Bitmap b = shown16[r.made];
                    if (b == null || b.isRecycled()) b = shown16[r.made] = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
                    int len = jpeg == null ? 0 : jpeg.length, rc = -2;
                    k.info[FilmCore.FI_SIG] = -1;   // stays so if the core writes no signature
                    if (bitmapsDirect) {
                        rc = FilmCore.frame(k.ctx, jpeg, len, l, p, flags, n, null, b, w, h, 1500, FilmCore.FMT_565, null, FRAME_W, FRAME_H, k.info, k.tm);
                        if (rc == -2) bitmapsDirect = false;
                    }
                    if (rc == -2) {   // no bitmap access: through a short[] and a copy
                        if (k.out == null || k.out.length != w * h) k.out = new short[w * h];
                        rc = FilmCore.frame(k.ctx, jpeg, len, l, p, flags, n, k.out, null, w, h, 1500, FilmCore.FMT_565, null, FRAME_W, FRAME_H, k.info, k.tm);
                        if (rc == 0) b.copyPixelsFromBuffer(java.nio.ShortBuffer.wrap(k.out));
                    }
                    if (jpeg != null && (rc == 0 || rc == 1)) {
                        if (k.info[FilmCore.FI_SIG] >= 0) {
                            r.sig = new int[FilmCore.FI_SIG_N];
                            System.arraycopy(k.info, FilmCore.FI_SIG, r.sig, 0, FilmCore.FI_SIG_N);
                        } else r.noSig = true;
                    }
                    if (rc == 1) { r.still = true; giveBack(r.made); r.made = -1; return; }   // still: the bitmap untouched, given back
                    if (rc != 0) { r.err = FilmCore.lastError(); giveBack(r.made); r.made = -1; return; }
                    r.w = k.info[0];
                    r.h = k.info[1];
                    r.overlay = k.info[FilmCore.FI_OVERLAY];
                    r.tm = k.tm.clone();
                    if ((flags & FilmCore.F_HIST) != 0) {
                        r.hist = new int[FilmCore.FI_HIST_BINS];
                        System.arraycopy(k.info, FilmCore.FI_HIST, r.hist, 0, FilmCore.FI_HIST_BINS);
                    }
                } catch (Throwable e) {
                    r.err = Probe.why(e);
                    giveBack(r.made);
                    r.made = -1;
                } finally {
                    r.ms = SystemClock.uptimeMillis() - t;
                    ui.post(new Runnable() {
                        public void run() {
                            k.busy = false;
                            if (closed) { giveBack(r.made); return; }
                            done(r);
                        }
                    });
                }
            }
        });
    }

    /** A look thread is done with a frame (UI thread): show it, count it, and give that thread the next one at once. */
    private void done(Looked r) {
        long now = SystemClock.uptimeMillis();
        if (r.err != null) {
            if (frames == 0) noLook(r.err);
            else if (lookErrors++ < 3) note("look: " + r.err);
        } else if (r.miss) {
            bitmapMisses++;
        } else {
            if (r.noSig && pacing) {   // an image core without the still check (signatures): full rate for the visit
                pacing = false;
                pacer.input(now);
                note("pace: off (the image core gives no frame signature)");
            }
            if (pacing && r.sig != null && !r.still) {
                if (pacer.whole(now, r.sig)) paced(now, "the scene moved");
                stillRef();
            }
            if (r.still) stillFrames++;
            else show(r);
        }
        feed();
    }

    private void show(Looked r) {
        if (r.made < 0) return;
        if (hidden || !lookOn) { giveBack(r.made); return; }   // out of sight, or the camera's own view shows
        int core = coreIndex(r.by.core);
        if (r.seq < shownSeq) { giveBack(r.made); if (core >= 0) lateBy[core]++; return; }   // a later frame is already up
        shownSeq = r.seq;
        long now = SystemClock.uptimeMillis();
        lastShownAt = now;
        boolean onItsSurface = onSurface(), back = false;
        if (awaitingFresh && r.grabAt >= resumedAt) {   // the first frame since the return: the surface back on the screen once it's up
            awaitingFresh = false;
            back = true;
            if (!placed) placeFrom = r.seq;
        }
        synchronized (shown16) {
            front16 = r.made;
            posted16 &= ~(1 << r.made);
            frontSeq = r.seq;
            frontGrabAt = r.grabAt;
            if (!onItsSurface && r.grabAt > 0) { ageSum += now - r.grabAt; ageN++; }   // through Latent's screen: shown now
        }
        frontBy = r.by;
        frontOverlay = r.overlay;
        if (r.tm == null) return;
        if (DIAG && frames == 20) probeBlit(shown16[r.made]);
        if (frames >= PROBE_AT_FRAME && !probed) probeWanted = true;
        if (frames++ == 0) {
            framesCame = true;
            note("first frame " + r.w + "x" + r.h + " after " + (now - framesAskedAt) + " ms; " + r.ms + " ms on " + r.by.pinned
                    + (bitmapsDirect ? "" : ", through a copy"));
            flushLogSoon();
        }
        if (liveAgainFrom > 0 && r.grabAt > liveAgainFrom) {
            if (liveNotes++ < 30) note("live again " + (now - liveAgainFrom) + " ms after the shutter");
            liveAgainFrom = 0;
        }
        lookMsSum += r.ms;
        lastLookMs = r.ms;
        if (core >= 0) framesBy[core]++;
        for (int i = 0; i < stageMs.length; i++) stageMs[i] += r.tm[i];
        if (r.hist != null) hist = r.hist;
        if (frames == 1 || back) host.changed();   // the look newly on the picture: what's drawn over it changes too
        else host.frame();
    }

    private long liveAgainFrom;   // the shutter's answer, until the first frame taken after it is up
    private int liveNotes;

    /** Each core's clock as the kernel has it (now / most, in MHz) and its governor, or "?" where it can't be read. */
    static String cpuFreqs() {
        StringBuilder b = new StringBuilder();
        for (int n = 0; n < 4; n++) {
            String d = "/sys/devices/system/cpu/cpu" + n + "/cpufreq/";
            String cur = readLine(d + "scaling_cur_freq"), max = readLine(d + "cpuinfo_max_freq"), gov = readLine(d + "scaling_governor");
            b.append(n == 0 ? "" : ", ").append(n).append(": ").append(mhz(cur)).append('/').append(mhz(max)).append(" MHz ").append(gov);
        }
        return b.toString();
    }

    private static String mhz(String khz) {
        try { return String.valueOf(Long.parseLong(khz.trim()) / 1000); } catch (Throwable e) { return "?"; }
    }

    private static String readLine(String path) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path));
            try { String l = r.readLine(); return l == null ? "?" : l.trim(); } finally { r.close(); }
        } catch (Throwable e) {
            return "?";
        }
    }

    private boolean blitProbed;

    /**
     * Once a visit, on the UI thread: the picture copied into a 16-bit and a 32-bit bitmap, to tell Skia's own
     * cost from the window's (each frame's copy took 44-59 ms in v0.41.0, the window already 16-bit).
     */
    void probeBlit(Bitmap pic) {
        if (blitProbed || pic == null || pic.isRecycled()) return;
        blitProbed = true;
        StringBuilder b = new StringBuilder("blit: ");
        Bitmap.Config[] cs = { Bitmap.Config.RGB_565, Bitmap.Config.ARGB_8888 };
        for (Bitmap.Config cf : cs) {
            Bitmap dst = null;
            try {
                dst = Bitmap.createBitmap(pic.getWidth(), pic.getHeight(), cf);
                android.graphics.Canvas c = new android.graphics.Canvas(dst);
                c.drawBitmap(pic, 0, 0, null);   // once to warm up
                long t = SystemClock.uptimeMillis();
                for (int i = 0; i < 4; i++) c.drawBitmap(pic, 0, 0, null);
                b.append(cf == Bitmap.Config.RGB_565 ? "into 16-bit " : ", into 32-bit ").append((SystemClock.uptimeMillis() - t) / 4).append(" ms");
            } catch (Throwable e) {
                b.append(" ").append(Probe.why(e));
            } finally {
                if (dst != null) dst.recycle();
            }
        }
        note(b.toString());
    }

    // ---- the picture on a 16-bit surface of its own (v0.43.1). The Screen test: 31.5 frames a second there against
    // 20.9 through Latent's screen, whose copy of each picture into its 32-bit window took ~45 ms of the UI thread.
    // A presenter thread puts the newest picture up and draws what moves over it; Latent's screen keeps the side bars.
    // The surface is made once, before the camera opens, and kept for the visit: behind MENU or with the look off it
    // is moved off the screen (v0.44 took it away for good, so every frame after MENU went through the slow 32-bit
    // window, and with the look off its last frame stayed frozen over the camera's own view).

    private volatile SurfaceHolder pictureHolder;
    private final Object presentLock = new Object();
    private UiState presentState;
    private boolean presentWanted, presenterRunning;   // under presentLock
    private volatile int presenting = -1;   // the bitmap being copied up: no look thread writes it meanwhile
    private Thread presenter;
    private int presents, presentMsSum, statPresents, statPresentMs;
    private int presentedSeq;               // under shown16: the last frame put up (its first put-up gives its age)
    // the surface on the screen or off it (UI thread), and the frame that brings it back (set by show, taken by the presenter)
    private boolean placed = true;          // made on the screen, before the camera opened
    private volatile int placeFrom;
    private int placeNotes;
    // what the presenter was last given (UI thread), to tell whether the next frame can go up under the same state
    private boolean presentedLook, presentedOverOverlay;
    private long presentedAt, presentedUntil;   // given then; out of date by the clock from then (outOfDateAt)

    /** UI thread: the picture's surface is ready (null: it's gone). A presenter that has ended is started again. */
    void pictureSurface(SurfaceHolder h) {
        synchronized (presentLock) {
            pictureHolder = h;
            if (h != null && !presenterRunning) {
                presenterRunning = true;
                presenter = new Thread(new Runnable() { public void run() { presentLoop(); } }, "presenter");
                presenter.start();
                note("the picture on its own 16-bit surface");
                presentWanted = true;   // black until the first frame
            }
            presentLock.notifyAll();
        }
    }

    /** The picture goes up on its own surface (not through Latent's screen). */
    boolean onSurface() { return pictureHolder != null && lookOn && !lookFailed && !sequenceStopped && !closed && !hidden; }

    /** UI thread: the newest state, to draw over the newest picture. Not changed again once given. */
    void present(UiState s) {
        s.vfPart = UiState.PART_PICTURE;
        presentedLook = s.lookLive;
        presentedOverOverlay = s.vfLayer == UiState.LAYER_SCREEN;
        presentedAt = SystemClock.uptimeMillis();
        presentedUntil = outOfDateAt(s.lookShown, cardUntil, s.vfNote.length() > 0, noteUntil);
        synchronized (presentLock) { presentState = s; presentWanted = true; presentLock.notifyAll(); }
    }

    /**
     * When a state that shows the film card (card) or a note (note) goes out of date by the clock alone: the
     * first of their ends, or Long.MAX_VALUE when it shows neither. The next frame after it gets a fresh state.
     */
    static long outOfDateAt(boolean card, long cardUntil, boolean note, long noteUntil) {
        return Math.min(card ? cardUntil : Long.MAX_VALUE, note ? noteUntil : Long.MAX_VALUE);
    }

    /**
     * UI thread: the newest picture under the state last given, with nothing built again (v0.44 built a whole
     * state for every frame). False when that state no longer fits (the look newly on, the type now in the
     * frames, the histogram moving, the card or a note in it over), so a fresh one has to be given instead.
     */
    boolean presentAgain() {
        boolean look = lookOn && hasFreshLook() && !sequenceStopped;
        long now = SystemClock.uptimeMillis();
        if (look != presentedLook || (look && frontOverlay > 0) != presentedOverOverlay || now >= presentedUntil
                || (display == D_GRID && now - presentedAt >= 100)) return false;
        synchronized (presentLock) {
            if (presentState == null) return false;
            presentWanted = true;
            presentLock.notifyAll();
        }
        return true;
    }

    private void presentLoop() {
        boolean ended = false;
        try {
            AndroidGfx gfx = new AndroidGfx(activity.getAssets());
            while (true) {
                UiState s;
                synchronized (presentLock) {
                    while (!presentWanted && !closed && pictureHolder != null) {
                        try { presentLock.wait(); } catch (InterruptedException e) { presenterRunning = false; ended = true; return; }
                    }
                    if (closed || pictureHolder == null) { presenterRunning = false; ended = true; return; }
                    presentWanted = false;
                    s = presentState;
                }
                SurfaceHolder h = pictureHolder;
                int f, fs;
                long fg;
                synchronized (shown16) { f = front16; fs = frontSeq; fg = frontGrabAt; presenting = f; }
                long t = SystemClock.uptimeMillis();
                boolean up = false;
                try {
                    Bitmap b = f >= 0 ? shown16[f] : null;
                    if (h == null) continue;
                    android.graphics.Canvas c = h.lockCanvas();
                    if (c == null) continue;
                    try {
                        if (b == null || b.isRecycled()) { c.drawColor(0xFF000000); continue; }   // no frame yet: black, not what was there
                        c.drawBitmap(b, 0, 0, null);
                        if (s != null) {
                            c.save();
                            c.scale(b.getWidth() / 720f, b.getHeight() / 480f);   // Latent's layout, the picture's 720 x 480 of it
                            c.translate(-(s.width - 720) / 2, 0);
                            gfx.begin(c);
                            Ui.draw(gfx, s);
                            c.restore();
                        }
                        up = true;
                    } finally {
                        h.unlockCanvasAndPost(c);
                    }
                    presents++;
                    presentMsSum += SystemClock.uptimeMillis() - t;
                } catch (Throwable e) {
                    if (presents < 3) note("presenter: " + Probe.why(e));
                } finally {
                    long now = SystemClock.uptimeMillis();
                    synchronized (shown16) {
                        presenting = -1;
                        if (up && fs != presentedSeq) {   // this frame's first time up: how old it is (taken from the camera to here)
                            presentedSeq = fs;
                            if (fg > 0) { ageSum += now - fg; ageN++; }
                        }
                    }
                }
                int pf = placeFrom;
                if (up && pf > 0 && fs >= pf) { placeFrom = 0; ui.post(placeNow); }   // a frame of now is up on it: on the screen again
            }
        } finally {
            if (!ended) synchronized (presentLock) { presenterRunning = false; }
        }
    }

    /** UI thread: a frame taken since the return is up on the surface, so it can go back on the screen. */
    private final Runnable placeNow = new Runnable() {
        public void run() {
            if (placed || closed || !onSurface()) return;
            placed = true;
            host.picturePlace(true);
            if (placeNotes++ < 20) note("the picture's surface on the screen again, " + (SystemClock.uptimeMillis() - resumedAt) + " ms after the return");
        }
    };

    /** Hidden, or the look off: the surface off the screen (kept, not freed), and the picture on it out of date. */
    private void away() {
        awaitingFresh = true;
        placeFrom = 0;
        if (!placed) return;
        placed = false;
        host.picturePlace(false);
        if (pictureHolder != null && placeNotes++ < 20) note("the picture's surface off the screen (kept for the return)");
    }

    /** A frame of the look is ready to show. */
    private boolean hasLook() {
        int f = front16;
        return f >= 0 && shown16[f] != null && !shown16[f].isRecycled();
    }

    /** ... and taken since the return (hidden or with the look off, the one kept is out of date). */
    private boolean hasFreshLook() { return !awaitingFresh && hasLook(); }

    // ---- Latent's type over the picture: drawn once when it changes (not every frame), laid into each frame natively

    private final ExecutorService overlayer = Executors.newSingleThreadExecutor();
    /**
     * Core 1, with a look thread, never the camera's core 0 (see LOOK_CORES), at the look threads' own priority
     * (v0.44 ran it above them): the type still answers within a render (the counter, exposure on a lock), and
     * the look thread there keeps half the core meanwhile instead of stopping. Core 3 at background priority
     * was the other choice, but the screen and the presenter keep it busy, and the type would lag behind them.
     */
    static final int OVERLAY_CORE = 1;
    private boolean overlayPinned;
    private final Object overlayLock = new Object();
    private String overlayKey = "";
    private UiState overlayWanted;   // the latest asked for: the overlay thread takes it
    private int overlaysMade, statOverlays;
    private long overlayMsSum;
    private AndroidGfx overlayGfx;
    private Bitmap overlayBitmap;
    private int[] overlayPx;
    // overlay thread: whether the type keeps to its two bands (Ui.OVL_TOP_END, Ui.OVL_BOTTOM_START), so only those
    // are cleared, drawn and read back (the middle 320 rows hold nothing without the grid); seen once a visit
    private boolean bandsSeen, bandsOk, middleClear = true;

    /**
     * The screen, about to draw (UI thread): which layer to draw, and a new overlay when Latent's type over
     * the picture changed. With the overlay in the frames, the screen draws only what moves (focus, the print)
     * over one opaque picture.
     */
    void layer(UiState s) {
        if (!s.liveExact) { s.vfLayer = UiState.LAYER_ALL; return; }
        String key = Ui.overlayKey(s) + liveW + "x" + liveH;
        if (!key.equals(overlayKey)) {
            overlayKey = key;
            UiState o = s.copy();
            o.vfLayer = UiState.LAYER_OVERLAY;
            synchronized (overlayLock) { overlayWanted = o; }
            overlayer.execute(renderOverlay);
        }
        s.vfLayer = frontOverlay > 0 ? UiState.LAYER_SCREEN : UiState.LAYER_ALL;
    }

    /** The overlay bitmap's row for a row of Latent's layout (480 tall): rows map 1:1 on this screen. */
    static int overlayRow(int layoutRow, int h) { return Math.max(0, Math.min(h, Math.round(layoutRow * h / Ui.H))); }

    /** Nothing at all in rows [top, bottom) of an overlay w wide: the type kept to its bands. */
    static boolean clearBetween(int[] px, int w, int top, int bottom) {
        for (int i = top * w, end = bottom * w; i < end; i++) if (px[i] != 0) return false;
        return true;
    }

    private final Runnable renderOverlay = new Runnable() {
        public void run() {
            UiState s;
            synchronized (overlayLock) { s = overlayWanted; overlayWanted = null; }
            final int w = liveW, h = liveH;
            if (s == null || closed || w <= 0 || h <= 0) return;   // a later one was already made
            if (!overlayPinned) {   // off the screen's core (3, the busiest), onto a look thread's (1), never the camera's (0)
                overlayPinned = true;
                String where = FilmCore.pinCore(OVERLAY_CORE) == 0 ? "core " + OVERLAY_CORE : "Latent's core (" + FilmCore.lastError() + ")";
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE);
                note("overlay thread: " + where + ", at the look threads' priority");
            }
            long t = SystemClock.uptimeMillis();
            try {
                if (overlayGfx == null) overlayGfx = new AndroidGfx(activity.getAssets());
                if (overlayBitmap == null || overlayBitmap.getWidth() != w || overlayBitmap.getHeight() != h) {
                    overlayBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    overlayPx = new int[w * h];
                    middleClear = true;
                }
                int top = overlayRow(Ui.OVL_TOP_END, h), bottom = overlayRow(Ui.OVL_BOTTOM_START, h);
                boolean bands = !s.grid && bandsOk && top < bottom;
                android.graphics.Canvas c = new android.graphics.Canvas(overlayBitmap);
                if (bands) {   // only the two bands: cleared, drawn and read back (Skia skips what falls outside)
                    c.clipRect(0, 0, w, top);
                    c.clipRect(0, bottom, w, h, android.graphics.Region.Op.UNION);
                }
                c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
                c.scale(w / 720f, h / 480f);           // the picture's 720 x 480 of Latent's layout
                c.translate(-(s.width - 720) / 2, 0);
                overlayGfx.begin(c);
                Ui.draw(overlayGfx, s);
                if (bands) {
                    if (!middleClear) { java.util.Arrays.fill(overlayPx, top * w, bottom * w, 0); middleClear = true; }
                    overlayBitmap.getPixels(overlayPx, 0, w, 0, 0, w, top);
                    overlayBitmap.getPixels(overlayPx, bottom * w, w, 0, bottom, w, h - bottom);
                } else {
                    overlayBitmap.getPixels(overlayPx, 0, w, 0, 0, w, h);
                    middleClear = clearBetween(overlayPx, w, top, bottom);
                    if (!s.grid && !bandsSeen) {   // the first whole one without the grid: does the type keep to its bands?
                        bandsSeen = true;
                        bandsOk = middleClear && top < bottom;
                        note(bandsOk ? "overlay: the type keeps to rows 0-" + top + " and " + bottom + "-" + h + ", only those drawn from now on"
                                : "overlay: drawn between rows " + top + " and " + bottom + ", so drawn whole");
                    }
                }
                final int gen = FilmCore.setOverlay(overlayPx, w, h);
                final long ms = SystemClock.uptimeMillis() - t;
                ui.post(new Runnable() {
                    public void run() {
                        overlaysMade++;
                        overlayMsSum += ms;
                        if (gen > 0) overlayReady();
                    }
                });
            } catch (Throwable e) {
                note("overlay: " + Probe.why(e));
            }
        }
    };

    /**
     * A new overlay: frames from now on carry it; while none come (a picture being written), the shown frame
     * again under it, by the thread that made it (another's last frame could be older: the picture you took
     * jumped back a frame as the counter moved on).
     */
    private void overlayReady() {
        long now = SystemClock.uptimeMillis();
        if (closed || frames == 0 || !quietNow(now)) return;
        Looker k = frontBy;
        if (k == null || k.ctx == 0) return;
        if (k.busy) {   // still finishing a frame: a moment later
            ui.removeCallbacks(recompose);
            ui.postDelayed(recompose, 50);
            recomposeWaits++;
            return;
        }
        look(k, null, false, 0);
    }

    private final Runnable recompose = new Runnable() { public void run() { overlayReady(); } };

    private boolean frameSaved;

    /** Once a visit (DIAG), one live frame as the camera sends it, to LATENT/LIVE.JPG: to judge its quality off the camera. */
    private void saveFrame(final byte[] jpeg) {
        frameSaved = true;
        catalogue.execute(new Runnable() {
            public void run() {
                try {
                    FileOutputStream o = new FileOutputStream(new File(Library.home(card), "LIVE.JPG"));
                    try { o.write(jpeg); } finally { o.close(); }
                } catch (Throwable e) {
                    note("live frame not kept: " + Probe.why(e));
                }
            }
        });
    }

    // ------------------------------------------------------------------------------ keys

    private int keyNotes;

    /** The shutter button's edges, in the log, while a picture is being saved (and the first few). */
    private void keyNote(String what) {
        if (keyNotes++ < 12 || stored < shots) note("button: " + what + (stored < shots ? " (a picture being saved)" : ""));
    }

    void halfDown() {
        keyNote("half-press");
        if (!ready()) return;
        closeEv();
        release.halfDown();
        if (!release.app) {   // the camera focuses; if it says nothing at all (manual focus, say), Latent tries
            final long at = SystemClock.uptimeMillis();
            ui.postDelayed(new Runnable() {
                public void run() {
                    if (release.half && !release.app && release.state == Release.SEARCHING && lastReportAt < at) {
                        note("the camera's focus said nothing in 4 s");
                        release.cameraFocus(Release.AF_WARN);
                        host.changed();
                    }
                }
            }, 4000);
        }
        host.changed();
    }

    private long lastReportAt;
    private int storeReports, captureReports;

    void halfUp() { keyNote("let go"); if (ready()) { release.halfUp(); host.changed(); } }

    /**
     * A full press. While the camera has the button it has taken the picture itself: Latent keeps the
     * frame as the print and watches the catalogue for it. In app mode Latent takes it (Release).
     */
    void fullDown() {
        keyNote("full press");
        if (!ready()) return;
        if (release.locked) { say("Roll finished"); return; }
        fullPressAt = SystemClock.uptimeMillis();
        if (!release.app) {
            keepPrint();
            expectPicture();
            if (nativeShots++ < 30) note("full press: the camera's own shutter");
            flushLog();   // synchronous: the camera is taking a picture
            return;
        }
        release.fullDown();
        if (release.refused) say("Not in focus");
        host.changed();
    }

    /** The roll is full: the button becomes Latent's and takes nothing more. */
    void lockRoll() { release.lock(); host.changed(); }

    private boolean ready() { return previewing && !closed && !closing && !hidden; }

    private boolean hidden;   // MENU: the camera back is on screen, the camera kept open behind it
    private long hiddenAt;
    private int drainsAtHide;

    /**
     * Out of sight (the camera stays open, its live view behind Latent's other screens): no look and nothing
     * drawn, the camera's frames only taken and dropped now and then (FrameScheduler), the picture's surface
     * moved off the screen.
     */
    void hide() {
        if (hidden) return;
        if (release.half) release.halfUp();
        closeEv();
        cancelFocus();
        hidden = true;
        hiddenAt = SystemClock.uptimeMillis();
        drainsAtHide = drains;
        away();
        note("hidden (MENU): the camera stays open, its frames only drained");
        flushLogSoon();
    }

    /** Back on screen: frames with the look again at once. */
    void show() {
        if (!hidden) return;
        hidden = false;
        long now = SystemClock.uptimeMillis();
        resumedAt = now;
        note("shown again after " + (now - hiddenAt) / 1000 + " s hidden (" + (drains - drainsAtHide) + " frames drained)");
        if (pacer.input(now)) stillRef();
        stats(now, false);   // the stats count from here
        ui.removeCallbacks(poll);
        ui.post(poll);
        host.changed();
    }

    /** Centre: the film look on live view, or the camera's own picture (smooth, at full speed: Latent makes nothing). */
    void toggleLook() {
        if (lookFailed) { say("Film look unavailable on live view"); return; }
        lookOn = !lookOn;
        if (lookOn) {
            resumedAt = SystemClock.uptimeMillis();
            if (pacer.input(resumedAt)) stillRef();
        } else away();   // the surface off the screen: the camera's own view shows, and moves
        if (lookNotes++ < 20) note(lookOn ? "look on by you" : "look off by you: the camera's own view");
        say(lookOn ? "Film look on" : "Film look off  ·  the camera's own view");
        host.changed();
        feed();
    }

    private int lookNotes;

    void cycleDisplay(int step) {
        display = (display + step + DISPLAY_NAMES.length) % DISPLAY_NAMES.length;
        say(display == D_GRID ? "Grid and histogram" : DISPLAY_NAMES[display]);
        host.changed();
    }

    void showCard() { cardUntil = SystemClock.uptimeMillis() + CARD_MS; ui.removeCallbacks(tick); ui.post(tick); host.changed(); }

    void say(String text) { note = text; noteUntil = SystemClock.uptimeMillis() + NOTE_MS; ui.removeCallbacks(tick); ui.post(tick); host.changed(); }

    // ------------------------------------------------------------------------------ exposure compensation (decision B)

    static final long EV_EDIT_MS = 5000, EV_APPLY_MS = 150;

    /** DOWN (the ± key): opens the exposure scale, or closes it. */
    void toggleEv() {
        if (!ready()) return;
        evEditing = !evEditing;
        evEditUntil = SystemClock.uptimeMillis() + EV_EDIT_MS;
        if (!evEditing) applyEvNow();
        ui.removeCallbacks(tick);
        ui.post(tick);
        host.changed();
    }

    /** Closes the scale (a half-press, centre, MENU), keeping what's set. */
    void closeEv() {
        if (!evEditing) return;
        evEditing = false;
        applyEvNow();
        host.changed();
    }

    /** The wheel (or left / right) while the scale is open: a third of a stop each click. */
    void evBy(int thirds) {
        if (!evEditing || !ready()) return;
        int next = clampEv(ev + thirds, evMin, evMax);
        evEditUntil = SystemClock.uptimeMillis() + EV_EDIT_MS;
        if (next == ev) return;
        ev = next;
        ui.removeCallbacks(applyEv);
        ui.postDelayed(applyEv, EV_APPLY_MS);   // a quick turn of the wheel is one change, not many
        host.changed();
    }

    static int clampEv(int ev, int min, int max) { return Math.max(min, Math.min(max, ev)); }

    /** Thirds of a stop as the camera's compensation index (its step is a third on the A5100). */
    static int evIndex(int thirds, float step) { return Math.round(thirds / (3 * (step > 0 ? step : 1f / 3))); }

    private int evApplied = Integer.MIN_VALUE;
    private final Runnable applyEv = new Runnable() { public void run() { applyEvNow(); } };

    /**
     * Sets the camera's own exposure compensation, as its shooting mode would (decision B: shared with
     * shooting mode, not put back). Only the one value is sent (createEmptyParameters, as BetterManual
     * does), and shooting mode then waits as after any setting change.
     */
    private void applyEvNow() {
        ui.removeCallbacks(applyEv);
        // not once closing (leaving Latent, or a restart): the camera goes back, and its own mode may shoot soon after;
        // a wheel turn in the last 150 ms before that is dropped, never written on the way out
        if (camera == null || cameraEx == null || closed || closing || ev == evApplied) return;
        try {
            Camera.Parameters p = (Camera.Parameters) cameraEx.getClass().getMethod("createEmptyParameters").invoke(cameraEx);
            p.setExposureCompensation(evIndex(ev, evStep));
            camera.setParameters(p);
            NeutralSettings.wrote();
            if (evNotes++ < 20) note("exposure compensation " + ev + "/3");
            evApplied = ev;
        } catch (Throwable e) {
            note("exposure compensation: " + Probe.why(e));
            say("The camera kept its exposure");
        }
    }

    private int evNotes;

    // ------------------------------------------------------------------------------ the button: the camera's, or Latent's (Release.Camera)

    private boolean direct;           // direct shutter is on: the camera has the button
    private boolean directStopping;   // stopDirectShutter asked, its answer not yet in
    private Runnable afterStop;       // what to do once it has stopped
    private int nativeShots;

    private void startDirect() {
        try {
            call("startDirectShutter");
            direct = true;
        } catch (Throwable e) {
            note("startDirectShutter: " + Probe.why(e));
        }
    }

    /** Direct shutter off (asynchronous), then 'then'. At once if it's off already. */
    private void stopDirect(Runnable then) {
        if (!direct) { if (then != null) then.run(); return; }
        afterStop = then;
        if (directStopping) return;
        directStopping = true;
        final long t = SystemClock.uptimeMillis();
        boolean asked = set("stopDirectShutter", "com.sony.scalar.hardware.CameraEx$DirectShutterStoppedCallback", new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("onShutterStopped")) {
                    note("direct shutter stopped in " + (SystemClock.uptimeMillis() - t) + " ms");
                    stopped();
                }
                return objectMethod(p, m, a);
            }
        });
        if (!asked) { stopped(); return; }
        ui.postDelayed(new Runnable() { public void run() { if (directStopping) { note("direct shutter: no answer in 1.5 s"); stopped(); } } }, 1500);
    }

    private void stopped() {
        if (!directStopping) return;
        directStopping = false;
        direct = false;
        Runnable r = afterStop;
        afterStop = null;
        if (r != null) r.run();
    }

    public void takeButton() { stopDirect(null); }

    public void appFocus() {
        if (camera == null || closed) return;
        stopDirect(new Runnable() { public void run() { if (!closed && release.app && release.state == Release.SEARCHING) autoFocus(); } });
    }

    private void autoFocus() {
        if (stored < shots && !closed) {   // a picture still being saved: focus waits (see SAVE_FIRST)
            if (focusWaits++ < 10) note("focus waits: a picture is being saved");
            ui.postDelayed(new Runnable() { public void run() { if (!closed && release.app && release.state == Release.SEARCHING) autoFocus(); } }, 150);
            return;
        }
        final int g = ++focusGen;
        focusStart = SystemClock.uptimeMillis();
        lampThisTry = lamp;
        try {
            camera.autoFocus(new Camera.AutoFocusCallback() {
                public void onAutoFocus(boolean success, Camera c) { focusDone(g, success); }
            });
        } catch (Throwable e) {
            note("autoFocus: " + Probe.why(e));
            focusDone(g, false);
            return;
        }
        ui.postDelayed(new Runnable() { public void run() { if (g == focusGen) { note("focus timed out"); focusDone(g, false); } } }, FOCUS_TIMEOUT_MS);
    }

    private void focusDone(int g, boolean locked) {
        if (g != focusGen || closed) return;
        focusGen++;   // this try is answered
        long ms = SystemClock.uptimeMillis() - focusStart;
        if (locked) locks++; else misses++;
        if (locks + misses <= 12) note("Latent's focus, try " + release.tries + ": " + (locked ? "locked" : "no lock") + " in " + ms + " ms" + (lampThisTry ? ", AF lamp" : ""));
        // first: a press held through focus takes the picture now (v0.44 read the exposure before it, 10-40 ms
        // of shutter lag); while it's being taken nothing else is asked of the camera, and the next lock reads it
        release.focused(locked);
        if (locked && !EXPOSURE_LISTENERS && !release.shooting) readExposure();
        host.changed();
    }

    public void cancelFocus() {
        focusGen++;
        try { if (camera != null) camera.cancelAutoFocus(); } catch (Throwable e) { note("cancelAutoFocus: " + Probe.why(e)); }
    }

    public void handBack() {
        cancelFocus();
        if (!DIRECT_SHUTTER) return;
        if (closed || closing || camera == null) return;
        if (directStopping) { afterStop = new Runnable() { public void run() { if (!closed && !closing) startDirect(); } }; return; }
        if (!direct) startDirect();
    }

    public void later(Runnable r, long ms) { ui.postDelayed(r, ms); }

    // ------------------------------------------------------------------------------ the picture

    /**
     * Latent takes the picture (app mode: the camera couldn't focus by itself and Latent's own focus
     * did, or focus first is off). The sequence sony-remote-reboot uses on the A5000: direct shutter
     * off (it is, in app mode), burstableTakePicture, and in the shutter's answer cancelTakePicture,
     * then the button goes back to the camera. The live frames keep running, as they do there.
     */
    /**
     * Nothing new is asked of the camera (no focus, no picture) while a picture is still being saved, as
     * Sony's own capture states wait for theirs. Every run that lost its pictures (v0.31-v0.35.2) had
     * Latent focusing again within 0.2 s of the shutter, the button still half-pressed; the Shooting test
     * and the Live look test cancel focus after the shot and stored theirs, the Shooting test's listed by
     * the camera itself 0.2 s later.
     */
    static final boolean SAVE_FIRST = true;
    private int focusWaits, shootWaits;

    public void shoot() {
        if (closed) return;
        if (SAVE_FIRST && stored < shots) {   // the last picture first
            if (shootWaits++ < 10) note("the shutter waits: a picture is being saved");
            ui.postDelayed(new Runnable() { public void run() { shoot(); } }, 100);
            return;
        }
        shutterAt = SystemClock.uptimeMillis();
        note("Latent's shutter");
        quietUntil = shutterAt + STORE_QUIET_MS + SHUTTER_TIMEOUT_MS;   // from the press; set again at the answer
        quietCapAt = quietUntil + STORE_QUIET_MS;
        storeBusy = true;
        flushLog();   // synchronous: if the camera hangs from here, the card already says how far it got
        stopDirect(new Runnable() {
            public void run() {
                if (closed) return;
                try {
                    takePicture();
                } catch (Throwable e) {
                    note("burstableTakePicture: " + Probe.why(e));
                    safely("cancelTakePicture");
                    say("The camera wouldn't take it");
                    release.shot(false);
                    storeBusy = storing();
                    return;
                }
                long asked = SystemClock.uptimeMillis();
                // the picture is asked for first; then the frame you took is kept as the print (the quiet is on, so
                // no newer frame replaces it meanwhile, and the shutter's answer comes after this, on this thread)
                keepPrint();
                if (shutterNotes++ < 30) note("picture asked for " + (asked - shutterAt) + " ms after Latent's shutter, "
                        + (fullPressAt > 0 && fullPressAt <= shutterAt ? (asked - fullPressAt) + " ms after the full press" : "no full press"));
                ui.postDelayed(shutterTimeout, SHUTTER_TIMEOUT_MS);
            }
        });
    }

    private int shutterNotes;
    private Method mTake;

    /** burstableTakePicture, the Method found once (it was looked up on every press). */
    private void takePicture() throws Exception {
        try {
            if (mTake == null) mTake = cameraEx.getClass().getMethod("burstableTakePicture");
            mTake.invoke(cameraEx);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            throw c instanceof Exception ? (Exception) c : e;
        }
    }

    private final Runnable shutterTimeout = new Runnable() {
        public void run() {
            if (!release.shooting || closed) return;
            note("no answer from the shutter in " + SHUTTER_TIMEOUT_MS / 1000 + " s");
            safely("cancelTakePicture");
            say("The camera didn't answer");
            release.shot(false);
            storeBusy = storing();
        }
    };

    /**
     * The shutter's answer: 0 taken, 1 cancelled, 2 error. For Latent's own picture the capture is
     * ended (cancelTakePicture), or the next one never fires. A picture the camera took by itself
     * (its own button) is only counted.
     */
    private void onShutter(int status) {
        if (closed) return;
        boolean ours = release.shooting;
        long now = SystemClock.uptimeMillis();
        if (ours) {
            ui.removeCallbacks(shutterTimeout);
            safely("cancelTakePicture");
        }
        if (status == 0) {
            shots++;
            lastAnswerAt = now;
            awaitingPreview = shots;
            if (BLACKOUT) blackout(true);
            ui.removeCallbacks(restartPreviewLate);
            ui.postDelayed(restartPreviewLate, PREVIEW_LATE_MS);
            shotFilms.add(filmId != null ? filmId : "");
            quietUntil = now + QUIET_FLOOR_MS;
            quietCapAt = now + QUIET_CAP_MS;
            liveAgainFrom = now;
            flashUntil = now + FLASH_MS;
            if (pendingPrint != null) { print = pendingPrint; pendingPrint = null; printAt = now; }   // the last one is kept, not freed
            if (shots <= 30) note("shot " + shots + (ours ? ": Latent's shutter " + (now - shutterAt) + " ms" : ": the camera's shutter"));
            expectPicture();
            ui.removeCallbacks(tick);
            ui.post(tick);
        } else {
            quietUntil = 0;   // nothing being written
            quietCapAt = 0;
            note("shutter status " + status + (ours ? " after " + (now - shutterAt) + " ms" : " (the camera's)"));
            say(status == 1 ? "The camera cancelled it" : "The camera reported an error");
        }
        flushLogSoon();
        if (ours) release.shot(status == 0);
        storeBusy = storing();
        host.changed();
    }

    private Bitmap pendingPrint;   // the frame at the full press, the print once the shutter answers
    private android.graphics.Paint printPaint;

    /**
     * The shown frame kept as the next print: drawn into whichever of the two print bitmaps isn't the print in
     * the corner now (made once, at the first; never freed while the presenter may draw it).
     */
    private void keepPrint() {
        Bitmap src = hasFreshLook() ? shown16[front16] : null;
        if (src == null) return;
        try {   // half size and filtered: the 16-bit picture's dither averages away, as a print is smooth
            int w = Math.max(1, src.getWidth() / 2), h = Math.max(1, src.getHeight() / 2);
            for (int i = 0; i < prints.length; i++) {
                Bitmap b = prints[i];
                if (b == null || b.getWidth() != w || b.getHeight() != h) prints[i] = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            Bitmap dst = prints[0] != print ? prints[0] : prints[1];
            if (printPaint == null) printPaint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            new android.graphics.Canvas(dst).drawBitmap(src, null, new android.graphics.Rect(0, 0, w, h), printPaint);
            pendingPrint = dst;
        } catch (Throwable e) {
            pendingPrint = null;
        }
    }

    /** Pictures to look for in the catalogue: watched for this long after the last shutter or full press. */
    private long watchUntil;

    private void expectPicture() {
        long now = SystemClock.uptimeMillis();
        if (now >= watchUntil) storeSince = now;
        watchUntil = now + STORE_WAIT_MS;
        ui.removeCallbacks(findAgain);
        ui.postDelayed(findAgain, 300);
    }

    /** v0.35.5 let focus go again 0.3-8 s after each shot: no picture was stored any sooner for it. */

    /**
     * Sony's own capture states go Capture -> Development -> EE, and EEState.onResume calls
     * camera.startPreview() each time: after every picture the app restarts live view, once the camera
     * says it's back (PreviewStartListener), and Latent never did. Leaving Latent (Playback) rebuilds live
     * view, and every waiting picture was stored then (v0.35.4, v0.35.5: within 0.3 s). So: startPreview()
     * on the camera's word, or 1 s after the shutter's answer if it says nothing.
     */
    static final long PREVIEW_LATE_MS = 1000;

    /**
     * Nothing of Latent's over live view from the shutter's answer until the picture is listed, as a
     * shutter's blackout: only the camera's own picture. The tests that stored their pictures drew a
     * small label over live view; the viewfinder draws its whole HUD, redrawn at each press; and leaving
     * Latent (its drawing gone) stored every waiting picture. v0.35.6: live view restarted as Sony's apps
     * do, and the second picture still waited 20 s, until Playback.
     */
    static final boolean BLACKOUT = false;   // v0.35.7: the second picture still waited with nothing of Latent's on screen
    static final long BLACKOUT_MAX_MS = 15000;
    private boolean blackedOut;
    private long blackoutAt;

    private void blackout(boolean on) {
        if (on == blackedOut) return;
        blackedOut = on;
        long now = SystemClock.uptimeMillis();
        if (on) {
            blackoutAt = now;
            ui.removeCallbacks(blackoutEnd);
            ui.postDelayed(blackoutEnd, BLACKOUT_MAX_MS);
        } else {
            ui.removeCallbacks(blackoutEnd);
            if (blackoutNotes++ < 30) note("Latent's drawing back after " + (now - blackoutAt) + " ms (picture " + stored + " listed)");
        }
        host.hud(!on);
    }

    private int blackoutNotes;
    private final Runnable blackoutEnd = new Runnable() {
        public void run() { if (blackedOut) { note("blackout ended after " + BLACKOUT_MAX_MS / 1000 + " s with " + (shots - stored) + " picture(s) not listed"); blackout(false); } }
    };
    private int awaitingPreview, previewNotes;

    private void previewStarted() {
        if (previewNotes++ < 20) note("the camera's live view is back" + (awaitingPreview > 0 ? " after picture " + awaitingPreview : ""));
        if (awaitingPreview > 0) restartPreview("on the camera's word");
    }

    private final Runnable restartPreviewLate = new Runnable() {
        public void run() { if (awaitingPreview > 0) restartPreview("1 s after the shutter, the camera said nothing"); }
    };

    private void restartPreview(String why) {
        int n = awaitingPreview;
        awaitingPreview = 0;
        ui.removeCallbacks(restartPreviewLate);
        if (closed || camera == null) return;
        try {
            camera.startPreview();
            if (previewNotes++ < 30) note("live view restarted (startPreview) after picture " + n + ", " + why);
        } catch (Throwable e) {
            note("startPreview after picture " + n + ": " + Probe.why(e));
        }
        if (!systemLogSaved) ui.postDelayed(new Runnable() { public void run() { if (stored < shots && !systemLogSaved) saveSystemLog("VFLOG.TXT"); } }, 4000);
    }
    private boolean systemLogSaved;

    /** The camera's own log as it stands, to LATENT/name (threads of their own, 3 s; never touches the camera). */
    private void saveSystemLog(final String name) {
        systemLogSaved = true;
        note("saving the camera's own log to LATENT/" + name);
        final StringBuilder text = new StringBuilder();
        final Thread reader = new Thread(new Runnable() {
            public void run() {
                java.io.FileInputStream in = null;
                try {
                    in = new java.io.FileInputStream("/dev/log/main");
                    byte[] b = new byte[5 * 1024 + 20];
                    for (int n; (n = in.read(b)) > 20; ) {   // blocks once it has given all it holds
                        int len = (b[0] & 0xff) | (b[1] & 0xff) << 8;
                        int pid = (b[4] & 0xff) | (b[5] & 0xff) << 8, sec = (b[12] & 0xff) | (b[13] & 0xff) << 8 | (b[14] & 0xff) << 16;
                        int end = Math.min(n, 20 + len), p = 20;
                        int prio = b[p++];
                        int tagEnd = p;
                        while (tagEnd < end && b[tagEnd] != 0) tagEnd++;
                        int msgEnd = tagEnd + 1;
                        while (msgEnd < end && b[msgEnd] != 0) msgEnd++;
                        synchronized (text) {
                            text.append(sec).append(' ').append(pid).append(' ').append("VDIWEF".charAt(Math.max(0, Math.min(5, prio - 2)))).append('/')
                                    .append(new String(b, p, tagEnd - p, "UTF-8")).append(": ")
                                    .append(msgEnd > tagEnd + 1 ? new String(b, tagEnd + 1, msgEnd - tagEnd - 1, "UTF-8").trim() : "").append("\r\n");
                        }
                    }
                } catch (Throwable e) {
                    synchronized (text) { text.append("stopped: ").append(Probe.why(e)).append("\r\n"); }
                } finally {
                    try { if (in != null) in.close(); } catch (Throwable ignored) { }
                }
            }
        }, "system-log");
        reader.setDaemon(true);
        reader.start();
        new Thread(new Runnable() {
            public void run() {
                try { reader.join(3000); } catch (InterruptedException ignored) { }
                String t;
                synchronized (text) { t = text.toString(); }
                try {
                    FileOutputStream o = new FileOutputStream(new File(Library.home(card), name));
                    try { o.write(t.getBytes("UTF-8")); } finally { o.close(); }
                } catch (Throwable ignored) { }
            }
        }, "system-log-write").start();
    }

    /** A picture is being taken or stored (or may be): the camera mustn't be given back yet. */
    private boolean storing() {
        return release.shooting || stored < shots;   // until Sony's catalogue lists every picture (it's written then)
    }

    /**
     * The camera stores and catalogues each picture itself (about 0.2 s after the shutter); Linux sees
     * the file only once the card is read again, so its name comes from the catalogue, asked on a
     * thread of its own every 300 ms (as the Shooting test did) while pictures are expected. Every new
     * photo found is an exposure, also one whose shutter wasn't reported.
     */
    private void findStored() {
        if (catalogueBusy || closed) return;
        long now = SystemClock.uptimeMillis();
        if (now >= watchUntil && stored >= shots) {   // every picture listed, nothing more to wait for
            pendingPrint = null;   // a full press that took nothing (the bitmap is kept for the next)
            flushLogSoon();
            return;
        }
        if (!baselineKnown || mediaId == null) { ui.postDelayed(findAgain, 300); return; }
        catalogueBusy = true;
        final int after = highestKnown;
        // Sony's catalogue is never told to look (loadMedia ...): v0.35.0 and v0.35.2 had it list pictures
        // that were never written, so a listing it was told to make doesn't mean the picture is saved.
        // The camera lists a picture itself once it's stored (the Shooting test: 0.2 s later).
        final boolean flushNow = false;
        catalogue.execute(new Runnable() {
            public void run() {
                int[] found = new int[0];
                String problem = null;
                long ft = SystemClock.uptimeMillis();
                if (flushNow) flushCatalogue();
                final long flushMs = flushNow ? SystemClock.uptimeMillis() - ft : -1;
                try {
                    Class<?> media = Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
                    android.net.Uri uri = (android.net.Uri) media.getMethod("getContentUri", String.class).invoke(null, mediaId);
                    // any new entry: a picture just taken is catalogued before it is marked as having its JPEG
                    // (v0.32.0 waited for the mark and never saw its pictures; the Shooting test never asked)
                    found = newer(catalogueNumbers(activity.getContentResolver(), uri), after);
                } catch (Throwable e) {
                    problem = Probe.why(e);
                }
                final int[] listed = found;
                final String why = problem;
                ui.post(new Runnable() {
                    public void run() {
                        catalogueBusy = false;
                        if (why != null) note("catalogue: " + why);
                        if (flushMs >= 0 && flushNotes++ < 20) note("told the catalogue to look (" + flushMs + " ms, " + (SystemClock.uptimeMillis() - lastAnswerAt) + " ms after the last shutter)");
                        boolean any = false;
                        for (int n : listed) {
                            if (n <= highestKnown) continue;
                            any = true;
                            highestKnown = n;
                            stored++;
                            if (BLACKOUT && stored >= shots) blackout(false);
                            if (stored > shots) {   // the shutter wasn't reported: it's an exposure all the same
                                shots = stored;
                                flashUntil = SystemClock.uptimeMillis() + FLASH_MS;
                                if (pendingPrint != null) { print = pendingPrint; pendingPrint = null; printAt = SystemClock.uptimeMillis(); }
                            }
                            File f = dcfFile(n / 10000, n % 10000);
                            if (stored <= 30) note(f.getName() + " in the catalogue " + (SystemClock.uptimeMillis() - storeSince) + " ms after the shutter");
                            host.shot(f, nextFilm());
                        }
                        storeBusy = storing();
                        if (any) { flushLogSoon(); host.changed(); feed(); }   // listed: the quiet may be over
                        if (stored >= shots && any && !release.shooting) watchUntil = Math.min(watchUntil, SystemClock.uptimeMillis() + 1500);
                        if (!closed) ui.postDelayed(findAgain, 300);   // never pressing the catalogue while the camera writes it
                    }
                });
            }
        });
    }

    // catalogue thread: whether the two-column answer can't be used this visit (checked at open), and its first noted
    private boolean numbersFailed, numbersNoted;

    /**
     * Every photo number in the catalogue (folder 100, file 4696: 1004696), catalogue thread. Asked for those
     * two columns only (v0.44 read every row's path too, every 300 ms after a shot, while the camera stored);
     * if the catalogue won't answer that, or answered it differently at open, the whole query for the visit.
     */
    private int[] catalogueNumbers(android.content.ContentResolver cr, android.net.Uri uri) {
        long t = SystemClock.uptimeMillis();
        int[] nums = null;
        if (!numbersFailed) {
            try {
                nums = Probe.numbers(cr, uri);
            } catch (Throwable e) {
                numbersFailed = true;
                note("catalogue: folder and number alone not answered (" + Probe.why(e) + "), asking for every column");
            }
        }
        boolean narrow = nums != null;
        if (nums == null) {
            List<Probe.Row> rows = Probe.query(cr, uri);
            nums = new int[rows.size()];
            for (int i = 0; i < nums.length; i++) nums[i] = rows.get(i).folder * 10000 + rows.get(i).number;
        }
        if (!numbersNoted) {
            numbersNoted = true;
            note("catalogue: " + nums.length + " photos in " + (SystemClock.uptimeMillis() - t) + " ms" + (narrow ? " (folder and number only)" : ""));
        }
        return nums;
    }

    /**
     * Catalogue thread, at open (nothing storing): the two-column answer checked against the whole one, so the
     * pictures are looked for by it only where it gives the same photos; otherwise as before, for the visit.
     */
    private void checkNumbers(android.content.ContentResolver cr, android.net.Uri uri, List<Probe.Row> rows) {
        int[] all = new int[rows.size()];
        for (int i = 0; i < all.length; i++) all[i] = rows.get(i).folder * 10000 + rows.get(i).number;
        java.util.Arrays.sort(all);
        String differs;
        try {
            int[] two = Probe.numbers(cr, uri);
            java.util.Arrays.sort(two);
            differs = java.util.Arrays.equals(all, two) ? null : two.length + " photos, not " + all.length;
        } catch (Throwable e) {
            differs = Probe.why(e);
        }
        if (differs != null) {
            numbersFailed = true;
            note("catalogue: asked for folder and number alone, it answered differently (" + differs + "): every column, as before");
        }
    }

    /** The numbers above 'after', in order: the pictures new since. */
    static int[] newer(int[] numbers, int after) {
        int n = 0;
        for (int v : numbers) if (v > after) n++;
        int[] out = new int[n];
        n = 0;
        for (int v : numbers) if (v > after) out[n++] = v;
        java.util.Arrays.sort(out);
        return out;
    }

    private final Runnable findAgain = new Runnable() { public void run() { findStored(); } };

    static final long FLUSH_AFTER_MS = 2500, FLUSH_EVERY_MS = 1500;
    private long lastAnswerAt, lastFlushAt;
    private int flushNotes, flushFails;

    /** Sony's catalogue told to look: loadMedia, Images.waitAndUpdateDatabase, waitLoadMediaComplete (Smart Remote's order). Catalogue thread. */
    private void flushCatalogue() {
        try {
            Class<?> store = Class.forName("com.sony.scalar.provider.AvindexStore");
            store.getMethod("loadMedia", String.class, int.class).invoke(null, mediaId, 1);
            Class.forName("com.sony.scalar.provider.AvindexStore$Images").getMethod("waitAndUpdateDatabase", android.content.ContentResolver.class, String.class)
                    .invoke(null, activity.getContentResolver(), mediaId);
            store.getMethod("waitLoadMediaComplete", String.class).invoke(null, mediaId);
        } catch (Throwable e) {
            if (flushFails++ < 3) note("catalogue flush: " + Probe.why(e));
        }
    }

    /** The film of the oldest picture not yet counted (taken in this order). */
    private String nextFilm() { return shotFilms.isEmpty() ? filmId : shotFilms.removeFirst(); }

    // Sony's catalogue lists a new picture only once told to look (AvindexStore.loadMedia, then
    // Images.waitAndUpdateDatabase and waitLoadMediaComplete, as Smart Remote does once each picture is
    // written). Never while the camera may still be writing one: v0.35.0 asked 0.3 s after the shutter and
    // its four pictures were listed but never written; Latent asks only once the camera is given back.

    /**
     * Before any picture: the highest photo number in the catalogue, so a picture is known by being
     * above it. The card's listing can lag the catalogue (prints the darkroom made, earlier pictures), so
     * the catalogue itself says where new ones start. Catalogue thread.
     */
    private final Runnable baseline = new Runnable() {
        public void run() {
            int max = 0;
            String problem = null;
            try {
                Class<?> media = Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
                android.net.Uri uri = (android.net.Uri) media.getMethod("getContentUri", String.class).invoke(null, mediaId);
                List<Probe.Row> rows = Probe.query(activity.getContentResolver(), uri);
                for (Probe.Row r : rows) max = Math.max(max, r.folder * 10000 + r.number);
                checkNumbers(activity.getContentResolver(), uri, rows);
            } catch (Throwable e) {
                problem = Probe.why(e);
                numbersFailed = true;
            }
            final int m = max;
            final String why = problem;
            ui.post(new Runnable() {
                public void run() {
                    if (why != null) note("catalogue: " + why);
                    else note("catalogue's highest photo " + m + ", the card's " + highestKnown);
                    highestKnown = Math.max(highestKnown, m);
                    baselineKnown = true;
                }
            });
        }
    };
    private boolean baselineKnown;
    private long storeSince;

    /** DCIM/100MSDCF/DSC04680.JPG for folder 100, number 4680 (the folder's suffix as on the card). */
    private File dcfFile(int folder, int number) {
        File dcim = new File(card, "DCIM"), dir = new File(dcim, folder + "MSDCF");
        File[] dirs = dcim.listFiles();
        if (dirs != null) for (File d : dirs) if (d.getName().startsWith(String.valueOf(folder))) dir = d;
        return new File(dir, String.format(Locale.US, "DSC%05d.JPG", number % 100000));
    }

    // ------------------------------------------------------------------------------ the screen

    /**
     * Animations (the print, the blink, the card, a note) move on at 10 a second. Frames
     * between go up under the state last given (presentAgain), so on its own surface each tick gives a fresh one;
     * through Latent's screen, not while frames come (each redraws it anyway). The first run after the last
     * moving one gives one more: that state is the one with the card or the note gone, whenever the run comes
     * (the UI thread is late by a few ms each run, and a still scene sends no frames to clear it).
     */
    private final Runnable tick = new Runnable() {
        public void run() {
            if (closed) return;
            long now = SystemClock.uptimeMillis();
            if (evEditing && now > evEditUntil) closeEv();
            boolean m = moving(now, evEditing, flashUntil, cardUntil, noteUntil, print != null ? printAt : -1);
            if (tickFresh(m, moved) && (onSurface() || now - lastShownAt > 200)) host.animate();
            moved = m;
            if (m) ui.postDelayed(this, 100);
        }
    };
    private boolean moved;   // the tick's last run found something moving

    /** The tick gives a fresh state while something moves, and once more on the first run after. */
    static boolean tickFresh(boolean moving, boolean movedBefore) { return moving || movedBefore; }

    /** What Latent says when a picture won't save, so the camera can't be given back: the one thing left to do. */
    static final String STUCK = "A photo isn't saving. Turn the camera off (battery out if it won't).";

    /** What the viewfinder shows, into s (the roll's own details are MainActivity's). */
    void fill(UiState s) {
        long now = SystemClock.uptimeMillis();
        s.mode = UiState.PHOTO;
        // frames stopped (the camera struggling), or out of date (back from MENU, the look just on): the camera's own
        // view; while a picture is written, the last frame holds
        boolean look = lookOn && hasFreshLook() && !sequenceStopped;
        boolean exact = look;
        s.liveImage = look ? shown16[front16] : null;
        s.liveExact = exact;
        s.liveThrough = previewing && !look;   // the camera's own live view shows through
        s.lookLive = look;
        s.afState = release.state;
        s.afTry = release.tries;
        s.afLamp = lamp;
        s.afNote = release.state == Release.NO_LOCK ? (lampThisTry ? "Too dark to focus" : "Can't focus") : "";
        s.afMode = afMode;
        s.afArea = afArea;
        s.shutter = shutter;
        s.aperture = aperture;
        s.iso = isoAuto && iso.length() == 0 ? "AUTO" : iso;
        s.isoAuto = isoAuto && iso.length() > 0;
        s.ev = ev;
        s.evEdit = evEditing;
        s.shotsLeft = shotsLeft < 0 ? -1 : Math.max(0, shotsLeft - shots);
        s.grid = display == D_GRID;
        s.hist = display == D_GRID && look ? hist : null;
        s.clean = display == D_CLEAN;
        s.flash = now < flashUntil;
        s.printImage = print;
        s.printProgress = print == null ? 0 : Math.min(1f, (now - printAt) / (float) PRINT_MS);
        s.developing = shots;
        s.lookShown = now < cardUntil;
        s.vfNote = now < noteUntil ? note : "";
        s.fatal = held ? STUCK : null;   // nothing else can happen until the camera is switched off
    }

    /** A picture won't save and the camera is held (not given back: that froze it every time). */
    boolean held() { return held; }

    // ------------------------------------------------------------------------------ closing

    private boolean closing, held;
    private long closingAt;
    static final long SAVE_WAIT_MS = 30000;
    private String closeWhy;

    /**
     * MENU, leaving Latent, or a failure: the camera goes back. Never while a picture is being taken
     * or stored (v0.31.1 released the camera with pictures unstored and it hung): "Saving" waits for
     * the catalogue, up to 10 s. Then direct shutter is stopped and the camera released, as Sony's
     * apps do. Safe to call more than once.
     */
    void close(String why) {
        if (closed || closing) return;
        closing = true;
        closingAt = SystemClock.uptimeMillis();
        closeWhy = why;
        if (why != null) note("closing: " + why);
        cancelFocus();
        if (storing()) { say("Saving\u2026"); note("closing: waiting for " + Math.max(1, shots - stored) + " picture(s) to be stored"); }
        flushLog();
        ui.post(closeStep);
    }

    private final Runnable closeStep = new Runnable() {
        public void run() {
            if (closed || held) return;
            ui.removeCallbacks(poll);   // no frames while closing: the camera's processor is for the pictures
            ui.removeCallbacks(feeder);
            if (storing() && cameraEx != null) {
                // never given back with a picture still in the camera's memory: every time that froze it
                if (SystemClock.uptimeMillis() - closingAt < SAVE_WAIT_MS) {
                    if (!catalogueBusy) findStored();   // keeps telling the catalogue to look
                    ui.postDelayed(this, 200);
                    return;
                }
                held = true;
                note("HOLDING the camera: " + (shots - stored) + " picture(s) not listed after " + SAVE_WAIT_MS / 1000 + " s; not given back (that froze it every time)");
                flushLog();
                host.stuck(STUCK);   // and fill() says it, full screen, from now on
                return;
            }
            stopSequence();
            if (cameraEx != null && direct) {
                stopDirect(new Runnable() { public void run() { finishClose(); } });
                return;
            }
            finishClose();
        }
    };

    /** Now, without waiting (Latent is closing down): the camera is given back at once. */
    void closeNow() {
        if (closed) return;
        if (!closing) { closing = true; note("closing at once"); }
        finishClose();
    }

    private void finishClose() {
        if (closed) return;
        closed = true;
        if (SHOOT_16BIT) Display.fullColour(true);
        if (blackedOut) { blackedOut = false; host.hud(true); }
        String why = closeWhy;
        releaseCamera();
        host.showLive(false);
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        long now = SystemClock.uptimeMillis();
        long liveMs = Math.max(1, (lastFrameAt > 0 ? lastFrameAt : now) - framesAskedAt);
        note("closed: " + shots + " picture(s), " + stored + " in the catalogue, " + frames + " live frames (" + String.format(Locale.US, "%.1f", frames * 1000.0 / liveMs)
                + " a second, " + (frames > 0 ? lookMsSum / frames : 0) + " ms each on " + lookers.length + " threads), Latent's focus " + locks + " locked / " + misses
                + " missed");
        int[] bat = battery(activity), was = openBattery;   // the visit's cost, so battery ideas can be measured
        if (openedAt > 0) note("visit: " + String.format(Locale.US, "%.1f", (now - openedAt) / 60000.0) + " min, battery " + (was != null ? batteryText(was) + " -> " : "")
                + batteryText(bat) + ", " + (slowMsSum + (pacer.slow ? now - pacer.slowSince : 0)) / 1000 + " s of it at the still pace");
        flushLog();
        catalogue.shutdown();
        for (final Looker k : lookers) {   // each composer freed on its own thread, after any frame it's making
            if (k == null) continue;
            k.exec.execute(new Runnable() { public void run() { if (k.ctx != 0) { FilmCore.frameFree(k.ctx); k.ctx = 0; } } });
            k.exec.shutdown();
        }
        overlayer.execute(new Runnable() { public void run() { FilmCore.setOverlay(null, 0, 0); } });
        overlayer.shutdown();
        if (warmer != null) warmer.shutdown();
        logger.shutdown();   // what it still holds is written; the rest of the visit's lines are written here, synchronously
        ui.removeCallbacksAndMessages(null);
        for (; stored < shots; stored++) host.shot(null, nextFilm());   // taken but not yet named: still exposures on the roll
        print = null;          // freed by the reaper with the pictures, once the presenter has stopped
        pendingPrint = null;
        reapBitmaps();
        host.closed(why);
    }

    private void fail(String why) { closeWhy = why; closing = true; note("failed: " + why); finishClose(); }

    /**
     * The screen bitmaps (and the prints) freed only once every look thread, the overlay's and the presenter
     * have stopped: a look thread may be writing one in native code (core 0's at background priority took up
     * to 1.3 s a frame), and freeing it under that write crashed Latent between the viewfinder and the
     * darkroom (v0.41.0). If they don't stop in time, the bitmaps are left to the collector (Latent restarts
     * to develop anyway).
     */
    private void reapBitmaps() {
        new Thread(new Runnable() {
            public void run() {
                boolean stopped = true;
                try {
                    long until = SystemClock.uptimeMillis() + 3000;
                    for (Looker k : lookers) if (k != null) stopped &= k.exec.awaitTermination(Math.max(1, until - SystemClock.uptimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
                    stopped &= overlayer.awaitTermination(Math.max(1, until - SystemClock.uptimeMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
                    Thread p = presenter;
                    if (p != null) { synchronized (presentLock) { presentLock.notifyAll(); } p.join(Math.max(1, until - SystemClock.uptimeMillis())); stopped &= !p.isAlive(); }
                } catch (InterruptedException e) {
                    stopped = false;
                }
                synchronized (shown16) {
                    for (int i = 0; i < shown16.length; i++) {
                        if (stopped && shown16[i] != null) shown16[i].recycle();
                        shown16[i] = null;
                    }
                    for (int i = 0; i < prints.length; i++) {
                        if (stopped && prints[i] != null) prints[i].recycle();
                        prints[i] = null;
                    }
                    front16 = -1;
                    posted16 = 0;
                }
            }
        }, "reaper").start();
    }
    /** cancel focus and capture, stop the frames and live view, then release: the order the verified apps use. */
    private void releaseCamera() {
        ui.removeCallbacks(poll);
        ui.removeCallbacks(feeder);
        stopSequence();
        if (cameraEx == null) return;
        try { if (camera != null) camera.cancelAutoFocus(); } catch (Throwable ignored) { }
        if (release.shooting) safely("cancelTakePicture");
        set("setShutterListener", null, null);
        set("setFocusLightStateListener", null, null);
        set("setAutoFocusDoneListener", null, null);
        set("setAutoFocusStartListener", null, null);
        set("setShutterSpeedChangeListener", null, null);
        set("setApertureChangeListener", null, null);
        set("setAutoISOSensitivityListener", null, null);
        set("setStoreImageCompleteListener", null, null);
        set("setCaptureStatusListener", null, null);
        set("setPreviewStartListener", null, null);
        try { if (camera != null && previewing) camera.stopPreview(); } catch (Throwable e) { note("stopPreview: " + Probe.why(e)); }
        // Sony's own apps release the Android camera before CameraEx (their ExecutorCreator.terminate)
        try { if (camera != null) camera.release(); } catch (Throwable e) { note("camera release: " + Probe.why(e)); }
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

    /** The live frames stop for good (the camera's own live view shows). */
    private void pauseFrames() {
        if (pictureHolder != null) host.pictureGone();
        ui.removeCallbacks(poll);
        ui.removeCallbacks(feeder);
        if (!sequenceStopped) { stopSequence(); sequenceStopped = true; }
        host.changed();
    }

    private int draws, drawMsSum, blitMsSum;
    // log thread: the last /proc readings, to tell each core's share and Latent's CPU since the line before
    private long[] coreBusy, coreTotal;
    private long statCpu = -1;
    // the visit's battery, for its summary
    private volatile int[] openBattery;
    private long openedAt;

    /** The screen drew the viewfinder (UI thread): how long it took, and of that the picture's copy, for the stats. */
    void drew(long ms, long blitMs) { draws++; drawMsSum += ms; blitMsSum += blitMs; }

    /** Each core's busy share since the last call, from /proc/stat ("0:12% 1:95% ..."), or "?". Log thread. */
    private String coreLoad() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/stat"));
            long[] busy = new long[8], total = new long[8];
            try {
                String l;
                while ((l = r.readLine()) != null) {
                    long[] c = cpuLine(l);
                    if (c == null || c[0] < 0 || c[0] >= 8) continue;
                    busy[(int) c[0]] = c[1];
                    total[(int) c[0]] = c[2];
                }
            } finally { r.close(); }
            StringBuilder b = new StringBuilder();
            for (int n = 0; n < 8; n++) {
                if (total[n] == 0) continue;
                long db = coreBusy == null ? 0 : busy[n] - coreBusy[n], dt = coreTotal == null ? 0 : total[n] - coreTotal[n];
                b.append(b.length() > 0 ? " " : "").append(n).append(':').append(dt > 0 ? db * 100 / dt + "%" : "?");
            }
            coreBusy = busy;
            coreTotal = total;
            return b.toString();
        } catch (Throwable e) {
            return "?";
        }
    }

    /**
     * One line of /proc/stat, "cpuN user nice system idle iowait irq softirq ...": {N, busy, total} in its ticks
     * (idle and iowait are the idle part; the first seven fields count), or null for any other line ("cpu " is
     * all the cores together). Read by hand: a regex split compiled its pattern for every line on Dalvik 2.3.
     */
    static long[] cpuLine(String l) {
        if (l == null || !l.startsWith("cpu")) return null;
        int i = 3, len = l.length(), digits = 0;
        long core = 0;
        while (i < len && l.charAt(i) >= '0' && l.charAt(i) <= '9') { core = core * 10 + (l.charAt(i) - '0'); i++; digits++; }
        if (digits == 0) return null;
        long[] v = new long[7];
        int k = 0;
        while (k < v.length) {
            while (i < len && (l.charAt(i) == ' ' || l.charAt(i) == '\t')) i++;
            if (i >= len) break;
            long x = 0;
            int d = 0;
            while (i < len && l.charAt(i) >= '0' && l.charAt(i) <= '9') { x = x * 10 + (l.charAt(i) - '0'); i++; d++; }
            if (d == 0) return null;
            v[k++] = x;
        }
        if (k < 4) return null;
        long idle = v[3] + (k > 4 ? v[4] : 0), sum = 0;
        for (int j = 0; j < k; j++) sum += v[j];
        return new long[] { core, sum - idle, sum };
    }

    /** The index-th field (from 0) of s from position 'from', fields split by spaces, as a number; -1 if it isn't one. */
    static long field(String s, int from, int index) {
        int i = Math.max(0, from), len = s.length();
        for (int f = 0; ; f++) {
            while (i < len && s.charAt(i) == ' ') i++;
            if (i >= len) return -1;
            int start = i;
            while (i < len && s.charAt(i) != ' ') i++;
            if (f == index) {
                try { return Long.parseLong(s.substring(start, i)); } catch (NumberFormatException e) { return -1; }
            }
        }
    }

    /**
     * The stats line's names for FilmCore's TM_* stages, as they measure since v0.45.0: the decode; the frame's
     * signature (and the sharpening, when it's built in); the look; the compose, and of it the one pass (blend,
     * fades, dither, packing) and the overlay's own pixels (the type). Until v0.45.0: level, blend, dither.
     */
    static final String[] STAGES = { "decode", "sig+sharpen", "look", "compose", "of it one pass", "type" };

    /**
     * Every 10 s while live: how the frames are keeping up (a camera slowing down shows here first). The counts
     * are taken here, on the UI thread; /proc, the battery and the card are read and written by the log thread
     * (v0.44 did it all here: a stall of the frames every 10 s). write false (hidden): counted from now, nothing
     * written.
     */
    private void stats(long now, boolean write) {
        if (statsAt > 0 && write) {
            int n = frames - statFrames, lm = lookMsSum - statLookMs;
            Runtime rt = Runtime.getRuntime();
            int look = n > 0 ? lm / n : 0, grab = grabs > 0 ? grabMsSum / grabs : 0;
            StringBuilder st = new StringBuilder();
            for (int i = 0; i < stageMs.length; i++) {
                st.append(i == 0 ? "" : ", ").append(STAGES[i]).append(' ').append(n > 0 ? (stageMs[i] - statStage[i]) / n : 0);
                statStage[i] = stageMs[i];
            }
            StringBuilder cores = new StringBuilder(), late = new StringBuilder();
            for (int i = 0; i < lookers.length; i++) {
                late.append(i == 0 ? "" : "+").append(lateBy[i] - statLateBy[i]);
                statLateBy[i] = lateBy[i];
                Looker k = lookers[i];
                if (k == null || k.pinned == null) continue;
                cores.append(cores.length() > 0 ? " + " : "").append(k.pinned).append(" (").append(framesBy[i] - statFramesBy[i]).append(')');
                statFramesBy[i] = framesBy[i];
            }
            long age;
            int ages;
            synchronized (shown16) { ages = ageN; age = ageN > 0 ? ageSum / ageN : 0; ageSum = 0; ageN = 0; }
            long slowTotal = slowMsSum + (pacer.slow ? now - pacer.slowSince : 0), slowMs = slowTotal - statSlowTotal;
            final String head = "live: " + n + " frames in " + (now - statsAt) / 1000 + " s, " + look + " ms each (" + st + ") on " + cores
                    + ", grab " + grab + " ms (CPU " + (grabs > 0 ? grabCpuNs / 1000000 / grabs : 0) + "), "
                    + draws + " draws of " + (draws > 0 ? drawMsSum / draws : 0) + " ms (picture " + (draws > 0 ? blitMsSum / draws : 0) + "), "
                    + (pictureHolder != null ? (presents - statPresents) + " put up on the surface, " + (presents - statPresents > 0 ? (presentMsSum - statPresentMs) / (presents - statPresents) : 0) + " ms each, " : "");
            final String mid = (overlaysMade - statOverlays) + " overlays of " + (overlaysMade > 0 ? overlayMsSum / overlaysMade : 0) + " ms";
            final String tail = "heap " + (rt.totalMemory() - rt.freeMemory()) / 1024 + " of " + rt.maxMemory() / 1024 + " KB"
                    + ", " + (ages > 0 ? age + " ms from the camera to the screen" : "no frame up") + ", late " + late
                    + (bitmapMisses > statMisses ? ", " + (bitmapMisses - statMisses) + " without a bitmap" : "")
                    + (pacing ? ", still pace " + Math.max(0, slowMs) / 1000 + " s (" + (stillChecks - statChecks) + " checks, " + (stillFrames - statStill) + " still)" : "")
                    + (drains > statDrains ? ", " + (drains - statDrains) + " drained" : "");
            final long span = now - statsAt;
            onLogger(new Runnable() {
                public void run() {
                    long cpu = cpuMs();
                    String load = coreLoad();
                    note(head + "cores " + load + ", " + mid + ", Latent's CPU " + (cpu >= 0 && statCpu >= 0 ? (cpu - statCpu) * 100 / Math.max(1, span) + "%" : "?")
                            + ", load " + loadAvg() + ", " + tail + ", battery " + batteryText(battery(activity)));
                    statCpu = cpu;
                    flushLog();
                }
            });
            // the camera struggling: give it its own live view back rather than push on
            if (lookOn && (look > 700 || grab > 150)) {
                if (++slowSpells >= 2) { lookOn = false; lookFailed = true; say("Film look paused: the camera is busy"); note("look off: the frames slowed down"); pauseFrames(); }
            } else slowSpells = 0;
        } else {   // the first, or hidden: the cores' and Latent's CPU counted from here
            onLogger(new Runnable() { public void run() { statCpu = cpuMs(); coreLoad(); } });
            synchronized (shown16) { ageSum = 0; ageN = 0; }
            for (int i = 0; i < lookers.length; i++) { statLateBy[i] = lateBy[i]; statFramesBy[i] = framesBy[i]; }
            System.arraycopy(stageMs, 0, statStage, 0, stageMs.length);
        }
        statsAt = now;
        statFrames = frames;
        statLookMs = lookMsSum;
        statOverlays = overlaysMade;
        statPresents = presents;
        statPresentMs = presentMsSum;
        statMisses = bitmapMisses;
        statChecks = stillChecks;
        statStill = stillFrames;
        statDrains = drains;
        statSlowTotal = slowMsSum + (pacer.slow ? now - pacer.slowSince : 0);
        grabs = 0;
        grabMsSum = 0;
        grabCpuNs = 0;
        draws = 0;
        drawMsSum = 0;
        blitMsSum = 0;
    }

    private int statMisses, statChecks, statStill, statDrains;
    private long statSlowTotal;

    /** Latent's own CPU time so far, in ms (/proc/self/stat), or -1. */
    private static long cpuMs() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/self/stat"));
            String st;
            try { st = r.readLine(); } finally { r.close(); }
            int at = st.lastIndexOf(')') + 2;
            long user = field(st, at, 11), sys = field(st, at, 12);   // utime + stime, in 1/100 s
            return user < 0 || sys < 0 ? -1 : (user + sys) * 10;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** The system's load average over the last minute (/proc/loadavg). */
    private static String loadAvg() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/loadavg"));
            try {
                String l = r.readLine();
                if (l == null) return "?";
                int sp = l.indexOf(' ');
                return sp < 0 ? l : l.substring(0, sp);
            } finally { r.close(); }
        } catch (Throwable e) {
            return "?";
        }
    }

    /** The battery as the system has it (its sticky broadcast): percent, millivolts, tenths of a degree; null if unread. */
    static int[] battery(android.content.Context c) {
        try {
            android.content.Intent i = c.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return null;
            int level = i.getIntExtra("level", -1), scale = i.getIntExtra("scale", 100);
            return new int[] { level < 0 || scale <= 0 ? -1 : level * 100 / scale, i.getIntExtra("voltage", -1), i.getIntExtra("temperature", Integer.MIN_VALUE) };
        } catch (Throwable e) {
            return null;
        }
    }

    static String batteryText(int[] b) {
        if (b == null || b.length < 3) return "?";
        return (b[0] >= 0 ? b[0] + "%" : "?%") + (b[1] > 0 ? " " + b[1] + " mV" : "")
                + (b[2] != Integer.MIN_VALUE ? String.format(Locale.US, " %.1f C", b[2] / 10f) : "");
    }

    /** /proc/cpuinfo's word on these cores (implementer, part, variant, revision, features, the board), read once a visit. */
    static String cpuInfo() {
        String impl = "?", part = "?", variant = "?", rev = "?", features = "?", hardware = "?";
        int cores = 0;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/cpuinfo"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    String key = line.substring(0, colon).trim(), value = line.substring(colon + 1).trim();
                    if (key.equals("processor")) cores++;
                    else if (key.equals("CPU implementer")) impl = value;
                    else if (key.equals("CPU part")) part = value;
                    else if (key.equals("CPU variant")) variant = value;
                    else if (key.equals("CPU revision")) rev = value;
                    else if (key.equals("Features")) features = value;
                    else if (key.equals("Hardware")) hardware = value;
                }
            } finally {
                r.close();
            }
        } catch (Throwable e) {
            return "? (" + Probe.why(e) + ")";
        }
        return cores + " listed, implementer " + impl + ", part " + part + cpuPartName(part) + ", variant " + variant + ", revision " + rev
                + ", features " + features + ", " + hardware;
    }

    /** ARM's names for the parts this could be (" (Cortex-A9)"), or "". */
    static String cpuPartName(String part) {
        String p = part == null ? "" : part.toLowerCase(Locale.US);
        if (p.equals("0xc05")) return " (Cortex-A5)";
        if (p.equals("0xc07")) return " (Cortex-A7)";
        if (p.equals("0xc08")) return " (Cortex-A8)";
        if (p.equals("0xc09")) return " (Cortex-A9)";
        if (p.equals("0xc0f")) return " (Cortex-A15)";
        return "";
    }

    // ---- the log: lines kept in memory and written to the card by a thread of its own (Latent's core 3, background
    // priority), except the breadcrumbs that must be on the card before the camera is asked something that could hang
    // it (the shutter, the camera opening and closing, a crash): those are written at once, on the asking thread.

    private final ExecutorService logger = Executors.newSingleThreadExecutor();
    private boolean loggerSet;            // log thread: placed and lowered
    private boolean flushPosted;          // UI thread: a write is on its way
    private final Object flushLock = new Object();

    /** r on the log thread (never the camera's core 0; behind the screen and the look). Nothing once closed. */
    private void onLogger(final Runnable r) {
        try {
            logger.execute(new Runnable() {
                public void run() {
                    if (!loggerSet) {
                        loggerSet = true;
                        try { FilmCore.pinCore(LOW_PRIORITY_CORE); } catch (Throwable ignored) { }
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    }
                    r.run();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // closed: the last lines were written as it closed
        }
    }

    /** UI thread: the log onto the card soon, by the log thread (at most about twice a second). */
    private void flushLogSoon() {
        if (flushPosted) return;
        flushPosted = true;
        ui.postDelayed(flushLater, 500);
    }

    private final Runnable flushLater = new Runnable() { public void run() { flushPosted = false; onLogger(flushNow); } };
    private final Runnable flushNow = new Runnable() { public void run() { flushLog(); } };

    private int flushed;   // how much of the log is on the card already

    /** The log so far onto the card (only what's new), so a camera that hangs still leaves it. Any thread, one at a time. */
    private void flushLog() {
        synchronized (flushLock) {
            String add;
            synchronized (log) {
                if (flushed >= log.length()) return;
                add = log.substring(flushed);
                flushed = log.length();
            }
            try {
                FileOutputStream o = new FileOutputStream(new File(Library.home(card), "VIEWFIND.TXT"), true);
                try { o.write(add.getBytes("UTF-8")); } finally { o.close(); }
            } catch (Exception ignored) {
                // the log is a convenience
            }
        }
    }

    private void writeLog() { flushLog(); }

    // ------------------------------------------------------------------------------ words for the screen

    static String shutterText(int n, int d) {
        if (n >= d) {   // a second or longer: 2", 1.3"
            if (n % d == 0) return n / d + "\"";
            return String.format(Locale.US, "%.1f\"", n / (double) d);
        }
        if (n == 1) return "1/" + d;
        return "1/" + Math.round(d / (double) n);
    }

    static String apertureText(int f100) {
        return f100 >= 1000 ? "F" + Math.round(f100 / 100f) : String.format(Locale.US, "F%.1f", f100 / 100f);
    }

    static String afModeText(String afMode, String focusMode) {
        if ("manual".equals(focusMode)) return "MF";
        if ("dmf".equals(focusMode)) return "DMF";
        if (afMode == null) return "AF";
        return afMode.toUpperCase(Locale.US);   // af-s -> AF-S
    }

    static String areaText(String area) {
        if (area == null) return "";
        if (area.equals("center")) return "Centre";
        if (area.equals("wide")) return "Wide";
        if (area.equals("flex-spot")) return "Spot";
        if (area.equals("multi")) return "Multi";
        if (area.equals("local")) return "Zone";
        if (area.equals("spot")) return "Spot";
        if (area.equals("tracking")) return "Tracking";
        return area;
    }

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

    private static int intField(Object o, String name) {
        try { return o.getClass().getField(name).getInt(o); } catch (Throwable e) { return -1; }
    }

    /** An info object's public fields, for the log (so their names are known on this camera). */
    private static String fields(Object o) {
        StringBuilder b = new StringBuilder();
        for (java.lang.reflect.Field f : o.getClass().getFields()) {
            try { b.append(b.length() > 0 ? ", " : "").append(f.getName()).append('=').append(f.get(o)); } catch (Throwable ignored) { }
        }
        return b.length() > 0 ? b.toString() : String.valueOf(o);
    }

    /** A proxy's own equals / hashCode / toString. */
    private static Object objectMethod(Object proxy, Method m, Object[] a) {
        String n = m.getName();
        if (n.equals("equals")) return a != null && a.length == 1 && proxy == a[0];
        if (n.equals("hashCode")) return System.identityHashCode(proxy);
        if (n.equals("toString")) return "Latent viewfinder";
        return null;
    }
}
