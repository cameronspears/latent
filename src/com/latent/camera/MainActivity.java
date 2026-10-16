package com.latent.camera;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Film Lab: shoot a roll of film with the camera, then develop it.
 *
 *   Camera back   the loaded roll: stock canister and frame counter (photos taken since loading)
 *   Load film     pick a stock and a roll length
 *   Darkroom      develops every frame of the roll, screen dimmed; pausable and resumable
 *   Contact sheet developed rolls as film strips; open a frame full screen
 *   Lab           tweak one photo by hand (look, tone, grain) and save a copy to LATENT/OUT
 *
 * Originals in DCIM are never changed. Controls: UP/DOWN, LEFT/RIGHT or the wheel, centre, MENU.
 */
public class MainActivity extends Activity {
    private static final int BACK = UiState.BACK, LOAD = UiState.LOAD, DARKROOM = UiState.DARKROOM,
            SHEET = UiState.SHEET, VIEW = UiState.VIEW, LAB = UiState.LAB, PROBE = UiState.PROBE, SETTINGS = UiState.SETTINGS,
            PHOTO = UiState.PHOTO;
    private static final int[] LENGTHS = { 12, 24, 36, Roll.ENDLESS };

    // camera key scan codes (Sony ScalarInput)
    private static final int SC_UP = 103, SC_DOWN = 108, SC_LEFT = 105, SC_RIGHT = 106, SC_ENTER = 232,
            SC_MENU = 514, SC_SK1 = 229, SC_S1 = 516, SC_S2 = 518, SC_WHEEL_CW = 522, SC_WHEEL_CCW = 523, SC_DIAL_CW = 525, SC_DIAL_CCW = 526;
    private static final int K_UP = 1, K_DOWN = 2, K_LEFT = 3, K_RIGHT = 4, K_ENTER = 5;

    // one worker with a roomy native stack: Dalvik's default thread stack is small for libjpeg
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) { return new Thread(null, r, "latent", 1024 * 1024); }
    });
    // files negatives alongside the darkroom: copies while a frame develops, originals out once settled
    private final ExecutorService filer = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) { Thread t = new Thread(r, "filer"); t.setPriority(Thread.MIN_PRIORITY + 1); return t; }
    });
    private volatile boolean resumed;   // originals are only removed while Latent is on screen
    private final Handler ui = new Handler();
    private Screen screen;
    private SharedPreferences prefs;
    private PowerSave powerSave;

    private File card;
    private int mode = BACK;
    private String status = "";        // shown as a toast near the top; cleared after a few seconds
    private final Runnable clearToast = new Runnable() {
        public void run() { status = ""; screen.invalidate(); }
    };
    private boolean ready;

    // card state, refreshed on resume
    private List<File> photos = new ArrayList<File>();
    private List<Stock> stocks = new ArrayList<Stock>();
    private List<Roll> rolls = new ArrayList<Roll>();
    private Roll current;              // the loaded roll, if any
    private int exposed;               // photos on it so far

    private int battery = -1;          // percent
    private final Map<String, Bitmap> samples = new HashMap<String, Bitmap>();   // latest photo per stock
    private String samplesFor = "";
    private int stockSel, lengthSel = 1;   // load film

    private File reference;            // the newest neutral original: looks are previewed on it
    private boolean backFromCamera;    // set on resume: a finished roll develops by itself, a paused develop carries on

    // darkroom
    private volatile Roll developing;
    private volatile boolean devQuiet;   // an endless roll developing behind whatever is on screen
    private int quietLeft;               // its photos still to develop
    private boolean shootAfterFrame;     // the shutter was pressed during a quiet develop: shoot once the frame in hand is done
    private Roll developAfter;           // a film roll to develop once the quiet develop has stopped
    private boolean paused;
    private int devFrame, devPct, devDoneAtStart, devFailed;
    private long devStarted;
    private final Set<Integer> failed = new HashSet<Integer>();
    // the camera's own develop (worker thread only): null once it fails, and this visit goes on in software
    private HardwareDevelop hw;
    // per original (its path): its CRC once copied to NEG, and when that copy was written
    private final Map<String, Long> negCrc = new ConcurrentHashMap<String, Long>();
    private final ConcurrentHashMap<String, Long> negAt = new ConcurrentHashMap<String, Long>();
    /**
     * An original leaves the camera roll only once its negative has had this long to reach the card:
     * the card has no way to force a write (asking the kernel with sync hung the camera in v0.12.0),
     * and the kernel writes cached data out within about 30 s.
     */
    static final long SETTLE_MS = 30000;
    private volatile int filingLeft;      // originals still to file after a develop, shown on the contact sheet
    private float progressShown;          // the bar never goes backwards

    // contact sheet and viewer
    private Roll sheetRoll;
    private List<File> sheetFrames = new ArrayList<File>();
    private int sheetSel;
    private Bitmap viewBitmap;
    private final Map<String, Bitmap> thumbs = new HashMap<String, Bitmap>();

    // lab
    private int labRow, labPhoto, labStock, labHl, labSh, labGrain = 4, labSize = 1, labView;
    private Bitmap labPreview;
    private int previewGen;
    private boolean labBusy;
    private int labProgress = -1;

    // ------------------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        startAt = SystemClock.uptimeMillis();
        beforeUs = processAge();
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFormat(android.graphics.PixelFormat.RGBA_8888);   // 2.3 defaults to 16-bit RGB565
        screen = new Screen(this);   // loads the fonts
        setupMs = SystemClock.uptimeMillis() - startAt;
        // live view (the camera's own picture) sits behind Latent's drawing, hidden until it's used
        liveSurface = new android.view.SurfaceView(this);
        // Android 2.3: the camera only draws live view on a surface marked as fed by the camera itself
        liveSurface.getHolder().setType(android.view.SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        liveSurface.setVisibility(View.GONE);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.addView(liveSurface);
        root.addView(screen);
        setContentView(root);
        prefs = getPreferences(Context.MODE_PRIVATE);
        powerSave = new PowerSave(prefs);
        powerSave.release();       // undo a power-save change left behind by a crash
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            toast("No memory card");
            return;
        }
        card = Environment.getExternalStorageDirectory();
        if (getIntent().getLongExtra(Relaunch.EXTRA_AT, 0) > 0) {
            developNow = "develop".equals(getIntent().getStringExtra(Relaunch.EXTRA_WHY));
            if (developNow) {   // the same red darkroom Relaunch showed, until the develop starts
                preparing = true;
                prepared = getIntent();
                if (battery < 0) battery = prepared.getIntExtra(Relaunch.EXTRA_BATTERY, -1);   // until it's read again
                mode = DARKROOM;
                dim(true);
            }
            relaunched();
        }
    }

    /** Started again by Relaunch at the end of the Live look test: its screen, and whether its pictures are on the card. */
    private void lookTestBack(final long createdAt) {
        mode = PROBE;
        lookTest = true;
        fromSettings = true;
        probeStage = 1;
        probeStatus = new int[LiveLookTest.STEPS.length];
        probeDetail = new String[LiveLookTest.STEPS.length];
        java.util.Arrays.fill(probeStatus, -1);
        java.util.Arrays.fill(probeDetail, "");
        LiveLookTest.restoreSteps(prefs, probeStatus, probeDetail);
        final Probe.Listener listener = new Probe.Listener() {
            public void step(final int i, final int status, final String detail) {
                ui.post(new Runnable() { public void run() { probeStatus[i] = status; probeDetail[i] = detail; screen.invalidate(); } });
            }
        };
        worker.execute(new Runnable() {
            public void run() {
                LiveLookTest.checkCard(card, prefs, createdAt, listener);
                ui.post(new Runnable() { public void run() { probeStage = 2; screen.invalidate(); } });
            }
        });
    }

    /**
     * Started again by Relaunch (the Restart test): the test's screen, its steps so far, and the new
     * process's own steps (how long the restart took, whether the image memory is back), first thing on
     * the worker.
     */
    private void relaunched() {
        final long createdAt = startAt;
        if (prefs.getLong(LiveLookTest.P_AT, 0) > 0) { lookTestBack(createdAt); return; }
        if (prefs.getLong(RestartTest.P_AT, 0) == 0) return;
        mode = PROBE;
        restartTest = true;
        fromSettings = true;
        probeStage = 1;
        probeStatus = new int[RestartTest.STEPS.length];
        probeDetail = new String[RestartTest.STEPS.length];
        java.util.Arrays.fill(probeStatus, -1);
        java.util.Arrays.fill(probeDetail, "");
        RestartTest.restoreSteps(prefs, probeStatus, probeDetail);
        final Probe.Listener listener = new Probe.Listener() {
            public void step(final int i, final int status, final String detail) {
                ui.post(new Runnable() { public void run() { probeStatus[i] = status; probeDetail[i] = detail; screen.invalidate(); } });
            }
        };
        worker.execute(new Runnable() {
            public void run() {
                RestartTest.finish(MainActivity.this, card, prefs, createdAt, listener);
                ui.post(new Runnable() { public void run() { probeStage = 2; screen.invalidate(); } });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        backFromCamera = true;
        Display.fullColour(!(vf != null && Viewfinder.SHOOT_16BIT));   // the camera's own 16-bit plane while it's Latent's
        if (card != null && developing == null) refresh(null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(shootWhenSettled);   // left some other way: don't jump to shooting later
        shootPending = false;
        if (liveRun != null) liveRun.close();    // never keep the camera when Latent isn't on screen
        if (restartRun != null) restartRun.close(false);
        final Runnable keysLog = new Runnable() { public void run() { logKeys(); } };
        worker.execute(keysLog);
        Display.fullColour(false);
        if (developing != null) paused = true;   // finish the frame in hand, then stop; resumable later
        powerSave.release();
        // Once the camera has been opened here, Sony's image memory stays taken until this process ends.
        // Leaving a camera app only pauses it (v0.32.0: never stopped, never destroyed, and the next open
        // resumed this same process with nothing developing), so Latent closes itself now: the camera is
        // given back (after any picture still storing), then the process ends, and the next open develops.
        if (Viewfinder.opened) {
            devLog("leaving: the camera was opened, so Latent closes itself (its image memory comes back only then)");
            killWhenClosed = true;
            if (lookRun != null && lookRun.busy()) {   // the test ends first (never with a picture on its way); it ends Latent then
                lookRun.close();
            } else if (vf != null) {
                vf.close(null);
                ui.postDelayed(new Runnable() { public void run() { if (vf != null) vf.closeNow(); killNow(); } }, 12000);
            } else {
                ui.post(new Runnable() { public void run() { killNow(); } });
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
        filer.shutdown();
    }

    private boolean killWhenClosed;

    /** Latent ends here, so the next open is a fresh one (with the camera's image memory back). */
    private void killNow() {
        worker.shutdown();
        try { worker.awaitTermination(1500, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        filer.shutdownNow();
        finish();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /** Re-read the card (looks, rolls, photos), then run 'then' on the UI thread. */
    private int refreshing;            // card reads under way: the camera waits for them (one may set the camera neutral)

    private void refresh(final Runnable then) {
        refreshing++;
        worker.execute(new Runnable() {
            public void run() {
                String problem = null;
                long r00 = SystemClock.uptimeMillis();
                boolean cardThere = Library.home(card).isDirectory();   // the first touch of the card
                long r0 = SystemClock.uptimeMillis();
                try {
                    Library.installBundledLuts(getAssets(), card);
                } catch (Exception e) {
                    problem = "Looks not installed: " + e.getMessage();
                }
                final List<Stock> st = Stock.all(card);
                long r1 = SystemClock.uptimeMillis();
                final List<Roll> ro = Roll.list(card);
                Roll.upgrade(ro);
                long r2 = SystemClock.uptimeMillis();
                List<File> listed = Library.photos(card);
                final int highest = Roll.highest(listed);
                // test shots are never exposures; the viewfinder's are, even before the listing shows them
                // (pictures the listing doesn't show are checked against the catalogue: one never stored doesn't count)
                Set<Integer> catalogued = new File(Library.home(card), "SHOTS.TXT").exists() ? Probe.catalogued(getContentResolver()) : null;
                List<File> all = Library.withoutIgnored(card, Library.withShots(card, listed, catalogued));
                long r3 = SystemClock.uptimeMillis();
                try {
                    String tidied = Roll.tidy(ro, Roll.exposable(all, ro));
                    if (tidied != null) devLog("rolls: " + tidied);
                } catch (Exception e) {
                    devLog("rolls: couldn't tidy (" + Probe.why(e) + ")");
                }
                final List<File> ph = Roll.exposable(all, ro);
                // the card says which roll is in the camera; Latent's own note is only a shortcut (it can be lost)
                String note = prefs.getString("roll", "");
                final Roll inCamera = Roll.inCamera(ro, note);
                if (inCamera != null && !inCamera.dir.getName().equals(note)) {
                    boolean saved = prefs.edit().putString("roll", inCamera.dir.getName()).commit();
                    devLog("Latent's note of the loaded roll was " + (note.length() == 0 ? "missing" : note) + "; " + inCamera.label()
                            + " is open on the card, so that's the roll in the camera" + (saved ? "" : " (the note couldn't be saved)"));
                }
                Roll busy = developing;   // that roll's develop owns its ROLL.TXT until it stops
                if (inCamera != null && inCamera.endless() && Roll.LOADED.equals(inCamera.state) && (busy == null || !busy.dir.equals(inCamera.dir))) {   // what was shot since joins it
                    try {
                        int added = inCamera.collect(ph);
                        if (added > 0) devLog(inCamera.label() + ": " + added + " new photo(s) to develop");
                    } catch (Exception e) {
                        devLog(inCamera.label() + ": new photos not added (" + Probe.why(e) + ")");
                    }
                }
                final List<Roll> waiting = Roll.undevelopedEndless(ro);
                final List<File> prints = new ArrayList<File>();
                if (inCamera != null && inCamera.endless()) {
                    List<File> dev = inCamera.developed();
                    for (int i = dev.size() - 1; i >= 0 && prints.size() < 8; i--) { loadThumb(dev.get(i)); prints.add(dev.get(i)); }
                }
                final File ref = Roll.reference(ro, inCamera, ph);
                final String neutralMsg = reconcileNeutral(neutralWanted(inCamera != null));
                if (cardReadNote == null) cardReadNote = "card read: first touch " + (r0 - r00) + " ms" + (cardThere ? "" : " (no LATENT)") + ", looks " + (r1 - r0) + " ms, rolls " + (r2 - r1) + " ms, photos "
                        + (r3 - r2) + " ms (" + all.size() + "), the rest " + (SystemClock.uptimeMillis() - r3) + " ms";
                final String msg = problem;
                ui.post(new Runnable() {
                    public void run() {
                        refreshing--;
                        highestOnCard = Math.max(highestOnCard, highest);
                        stocks = st;
                        rolls = ro;
                        photos = ph;
                        current = inCamera;
                        reference = ref;
                        endlessWaiting = waiting;
                        endlessPrints = prints;
                        exposed = current == null ? 0 : current.frames.isEmpty() ? current.exposures(photos).size() : current.frames.size();
                        if (msg != null) toast(msg);
                        if (neutralMsg != null) {   // after any toast the caller shows (e.g. "Roll 8 developed")
                            ui.postDelayed(new Runnable() { public void run() { toast(neutralMsg); } }, 3500);
                        }
                        readBattery();
                        if (!ready) readyAt = SystemClock.uptimeMillis();
                        ready = true;
                        saveSnapshot();
                        if (mode == BACK && current == null) openLoad();   // nothing in the camera: choosing film is home
                        else if (mode == LOAD && !loading && current == null) renderHero();
                        filer.execute(new Runnable() { public void run() { fileBacklog(ro); } });
                        renderSamples();   // so choosing film opens with them ready (only when the reference changed)
                        if (then != null) then.run();
                        boolean back = backFromCamera;
                        backFromCamera = false;
                        // finished while you were out (or a develop was left part-way): straight to the darkroom
                        if (back && current != null && developing == null && (mode == BACK || preparing) && wantsDeveloping(current)) startDevelop(current);
                        else developWaiting();
                        if (preparing && developing == null && !restarting) unprepare();   // nothing to develop after all
                        screen.invalidate();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------------------ camera back

    /**
     * The camera back with a roll in it has one thing to do: shoot (the centre button or a half-press),
     * or develop once the roll is finished or a develop was paused. Everything else is behind MENU.
     */
    private void backKey(int k) {
        if (k != K_ENTER || !ready || current == null) return;
        if (Viewfinder.opened && current.endless() && (vfWaiting > 0 || (vf != null && vf.shots > 0))) restartToDevelop("photos to develop");
        else if (wantsDeveloping(current)) startDevelop(current);
        else goShoot();
    }

    /** A finished roll, or one part-way through developing. (An endless roll develops by itself.) */
    private boolean wantsDeveloping(Roll r) {
        return !r.endless() && (Roll.DEVELOPING.equals(r.state) || Math.min(exposed, r.length) >= r.length);
    }

    /** Choosing film: the home screen when the camera is empty, or Change film from the menu. */
    private void openLoad() {
        if (stocks.isEmpty()) { toast("No looks in LATENT/LUTS"); return; }
        if (mode != LOAD) {   // opens on the film and length you chose last
            String last = prefs.getString("lastStock", "");
            for (int i = 0; i < stocks.size(); i++) if (stocks.get(i).id.equals(last)) stockSel = i;
            lengthSel = Math.max(0, Math.min(LENGTHS.length - 1, prefs.getInt("lastLength", 1)));
        }
        stockSel = Math.min(stockSel, stocks.size() - 1);
        mode = LOAD;
        renderSamples();
        renderHero();
    }

    /** The home screen: the camera back with its roll, or choosing film when there is none. */
    private int home() { return current == null ? LOAD : BACK; }

    private void goHome() {
        if (home() == LOAD && !stocks.isEmpty()) openLoad();
        else mode = BACK;
        developWaiting();
    }

    // ------------------------------------------------------------------------------ the film under the wheel, full screen

    private Bitmap hero;               // the reference in the film under the wheel, full screen
    private String heroFor = "";
    private int heroGen;

    /** The reference in the chosen film at full screen size; until it's ready the small sample stands in. */
    private void renderHero() {
        if (reference == null || stocks.isEmpty()) return;
        final Stock st = stocks.get(Math.min(stockSel, stocks.size() - 1));
        final File ref = reference;
        final String key = ref.getPath() + "|" + st.id;
        if (key.equals(heroFor)) return;
        final int gen = ++heroGen;
        worker.execute(new Runnable() {
            public void run() {
                if (gen != heroGen) return;   // the wheel moved on
                int[] px = new int[720 * 480], size = new int[2];
                int[] p = params(ref, st.highlight, st.shadow, 0, 1);
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);   // never ahead of a press
                int rc;
                try { rc = FilmCore.preview(ref.getPath(), st.lut.getPath(), p, FilmCore.PREVIEW_FIT, px, 720, 480, size); }
                finally { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT); }
                if (rc != 0) return;
                final Bitmap b = Bitmap.createBitmap(px, size[0], size[1], Bitmap.Config.ARGB_8888);
                ui.post(new Runnable() {
                    public void run() {
                        if (gen != heroGen) { b.recycle(); return; }
                        Bitmap old = hero;
                        hero = b;
                        heroFor = key;
                        if (old != null) old.recycle();
                        screen.invalidate();
                    }
                });
            }
        });
    }

    private int neutralBusy;           // settings being changed right now (worker)

    // ------------------------------------------------------------------------------ settings

    private int settingsSel = 1;       // a row of settingsRows(); headings are passed over
    private boolean fromSettings;      // the test or Lab on screen was opened from Settings: MENU goes back there

    /** Each row: {label, value, action}. Value "on"/"off" is a switch, "›" opens something, "" is a heading. */
    private List<String[]> settingsRows() {
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[] { current != null ? "This roll" : "Latent", "", "" });
        if (current != null && Roll.LOADED.equals(current.state) && !current.endless() && exposed > 0 && exposed < current.length)
            rows.add(new String[] { "Develop now", "›", "develop" });
        if (current != null && !Roll.DEVELOPING.equals(current.state)) rows.add(new String[] { "Change film", "›", "change" });
        rows.add(new String[] { "Rolls", "›", "rolls" });
        rows.add(new String[] { "Shooting", "", "" });
        rows.add(new String[] { "Latent's viewfinder", viewfinderOn() ? "on" : "off", "viewfinder" });
        rows.add(new String[] { "Focus first", focusFirst() ? "on" : "off", "focusFirst" });
        rows.add(new String[] { "Film look on live view", liveLook() ? "on" : "off", "liveLook" });
        rows.add(new String[] { "Developing", "", "" });
        rows.add(new String[] { "Use three cores", developCores() > 1 ? "on" : "off", "cores" });
        rows.add(new String[] { "Shoot neutral while a roll is loaded", neutralOn() ? "on" : "off", "neutral" });
        rows.add(new String[] { "Looks", "", "" });
        rows.add(new String[] { "Try looks on a photo", "›", "lab" });
        rows.add(new String[] { "Camera", "", "" });
        rows.add(new String[] { "Settings check", "›", "settingsCheck" });
        rows.add(new String[] { "Speed test", "›", "speedTest" });
        rows.add(new String[] { "Live view test", "›", "liveTest" });
        rows.add(new String[] { "Shooting test", "›", "shootTest" });
        rows.add(new String[] { "Restart test", "›", "restartTest" });
        rows.add(new String[] { "Live look test", "›", "lookTest" });
        return rows;
    }

    /** What the chosen row does, in a line at the foot of Settings. */
    private String settingsFooter(String action) {
        if (action.equals("cores")) return prefs.getBoolean("multicoreOk", false) ? "Develops on cores 1 and 2 as well. Core 0 is left to the camera." : "Run the Speed test first: it checks the other cores.";
        if (action.equals("neutral")) return "Every look starts from the same picture. Your settings come back after.";
        if (action.equals("viewfinder")) return viewfinderOn() ? "Shoot stays in Latent. Photos develop the next time Latent opens." : "Shoot hands over to the camera's own shooting mode.";
        if (action.equals("liveLook")) return liveLook() ? "Live view shows the film, with the level. Turn it off for the camera's own view."
                : "The camera's own live view. Run the Live look test (Camera, below) to turn the film on.";
        if (action.equals("focusFirst")) return focusFirst() ? "A full press waits for focus to lock, as AF-S does." : "A full press takes the picture at once, in focus or not.";
        if (action.equals("shootTest")) return "Latent focuses and takes one photo itself. Changes no setting.";
        if (action.equals("lookTest")) return "The film look on live frames while two photos are taken. If both are stored, the look comes on.";
        if (action.equals("restartTest")) return "Latent gives the camera back and starts again by itself. Takes no photo.";
        if (action.equals("develop")) return "Ends the roll here and develops the " + exposed + " shot so far.";
        if (action.equals("change")) return current != null && current.endless() ? "This roll's photos keep developing." : "Keeps the frames shot so far for developing.";
        return "Latent " + versionName();
    }

    private boolean neutralOn() { return prefs.getBoolean("neutral", true); }
    private boolean viewfinderOn() { return prefs.getBoolean("viewfinder", true); }
    private boolean focusFirst() { return prefs.getBoolean("focusFirst", true); }
    /**
     * The film look on live view: off until the Live look test has found its pictures on the card, taken
     * with the look running (it turns it on). "liveLook2": v0.35.0's test turned "liveLook" on from
     * catalogue entries whose files were never written.
     */
    private boolean liveLook() { return prefs.getBoolean("liveLook2", false); }

    /** The camera shoots neutral while a roll is loaded: every look starts from a plain picture. */
    private boolean neutralWanted(boolean filmLoaded) { return neutralOn() && filmLoaded; }

    private void openSettings() {
        mode = SETTINGS;
        fromSettings = false;
        List<String[]> rows = settingsRows();
        settingsSel = Math.max(1, Math.min(settingsSel, rows.size() - 1));
        if (rows.get(settingsSel)[1].length() == 0) settingsSel++;
    }

    private void settingsKey(int k) {
        List<String[]> rows = settingsRows();
        int n = rows.size(), step = k == K_UP || k == K_LEFT ? -1 : k == K_DOWN || k == K_RIGHT ? 1 : 0;
        if (step != 0) {
            int i = settingsSel + step;
            while (i >= 0 && i < n && rows.get(i)[1].length() == 0) i += step;   // pass over headings
            if (i >= 0 && i < n) settingsSel = i;
        } else if (k == K_ENTER) {
            String action = rows.get(Math.min(settingsSel, n - 1))[2];
            if (action.equals("cores")) {   // develop on three cores, or on Latent's own only
                if (!prefs.getBoolean("multicoreOk", false)) toast("Run the Speed test first: it checks the other cores");
                else prefs.edit().putInt("cores", developCores() > 1 ? 1 : 3).commit();
            } else if (action.equals("neutral")) {
                setNeutral(!neutralOn());
            } else if (action.equals("viewfinder")) {
                prefs.edit().putBoolean("viewfinder", !viewfinderOn()).commit();
            } else if (action.equals("focusFirst")) {
                prefs.edit().putBoolean("focusFirst", !focusFirst()).commit();
            } else if (action.equals("liveLook")) {
                if (vf != null) toast("It changes the next time the viewfinder opens");
                prefs.edit().putBoolean("liveLook2", !liveLook()).commit();
            } else if (action.equals("lab")) {
                openLab();
                fromSettings = true;
            } else if (action.equals("develop")) {
                startDevelop(current);
            } else if (action.equals("change")) {
                openLoad();
            } else if (action.equals("rolls")) {
                Roll r = firstRollWithFrames();
                if (r == null) toast("No rolls yet");
                else openSheet(r);
            } else if (action.equals("settingsCheck") || action.equals("speedTest") || action.equals("liveTest") || action.equals("shootTest") || action.equals("restartTest") || action.equals("lookTest")) {
                mode = PROBE;
                probeStage = 0;
                hardwareTest = false;
                settingsCheck = action.equals("settingsCheck");
                speedTest = action.equals("speedTest");
                liveTest = action.equals("liveTest");
                shootTest = action.equals("shootTest");
                restartTest = action.equals("restartTest");
                lookTest = action.equals("lookTest");
                fromSettings = true;
            }
        }
        screen.invalidate();
    }

    /**
     * The switch: off, the camera keeps your own settings during a roll. Changing it mid-roll changes the
     * camera at once (and shooting waits the usual few seconds after, like loading a roll).
     */
    private void setNeutral(final boolean on) {
        prefs.edit().putBoolean("neutral", on).commit();
        matchNeutral();
    }

    /** Sets the camera neutral, or gives your settings back, to match what's wanted now. */
    private void matchNeutral() {
        final boolean on = neutralWanted(current != null);
        neutralBusy++;   // shooting waits for this (and then the usual settle time)
        worker.execute(new Runnable() {
            public void run() {
                try { match(); } finally { ui.post(new Runnable() { public void run() { neutralBusy--; } }); }
            }

            private void match() {
                StringBuilder nl = new StringBuilder();
                NeutralSettings ns = new NeutralSettings(card, nl);
                String msg = null;
                if (on && !ns.active()) {
                    String why = ns.apply();
                    msg = why == null ? "Camera set to neutral" + (current != null ? " for this roll" : "") : "Camera " + why;
                } else if (!on && ns.active()) {
                    String why = ns.restore();
                    msg = why == null ? "Your camera settings are back" : why;
                }
                devLogAll(nl);
                final String m = msg;
                if (m != null) ui.post(new Runnable() { public void run() { toast(m); } });
            }
        });
    }

    /** Threads for the look: 3 (Latent's core plus helpers on cores 1 and 2) once the Speed test has checked them, unless switched off. */
    private int developCores() {
        return prefs.getBoolean("multicoreOk", false) ? Math.max(1, Math.min(3, prefs.getInt("cores", 3))) : 1;
    }

    // camera roll test (phase 1)
    private int probeStage;
    private boolean hardwareTest;      // phase 2: develop one photo through the camera's hardware
    private boolean settingsCheck;     // read the camera's white balance and Creative Style (read only)
    private boolean speedTest;         // time the look's inner loops on this chip; keep the fastest accurate one
    private boolean liveTest;          // can live view be shown with a film look on it
    private boolean shootTest;         // can Latent focus and shoot itself, and get live frames
    private boolean restartTest;       // can Latent give the camera back and start again, with the image memory back
    private RestartTest restartRun;    // while it runs (it holds the camera)
    private boolean lookTest;          // can the camera store pictures with live frames running (Auto Review off)
    private LiveLookTest lookRun;      // while it runs (it holds the camera)
    private ShootingTest shootRun;     // while it runs (it holds the camera)
    private android.view.SurfaceView liveSurface;
    private LiveViewTest liveRun;      // while it runs (it holds the camera)
    private boolean liveOn;
    private String liveNote = "";
    private int[] probeStatus = new int[0];
    private String[] probeDetail = new String[0];

    private void runProbe() {
        final String[] steps = lookTest ? LiveLookTest.STEPS : restartTest ? RestartTest.STEPS : shootTest ? ShootingTest.STEPS : liveTest ? LiveViewTest.STEPS : speedTest ? SpeedTest.STEPS : settingsCheck ? SettingsCheck.STEPS : hardwareTest ? HardwareTest.STEPS : Probe.STEPS;
        probeStage = 1;
        probeStatus = new int[steps.length];
        probeDetail = new String[steps.length];
        for (int i = 0; i < steps.length; i++) { probeStatus[i] = -1; probeDetail[i] = ""; }
        final Probe.Listener listener = new Probe.Listener() {
            public void step(final int i, final int status, final String detail) {
                ui.post(new Runnable() { public void run() { probeStatus[i] = status; probeDetail[i] = detail; screen.invalidate(); } });
            }
        };
        Stock st = current != null ? Stock.byId(stocks, current.stock) : null;
        if (st == null && !stocks.isEmpty()) st = stocks.get(0);
        final Stock stock = st;
        if ((hardwareTest || speedTest) && stock == null) { toast("No film looks on the card"); probeStage = 0; return; }
        if ((shootTest || liveTest || restartTest || lookTest) && developing != null) {   // the darkroom and the camera never overlap (v0.33.0)
            paused = true;
            toast("Finishing a photo first. Try again in a moment");
            probeStage = 0;
            return;
        }
        if ((shootTest || liveTest || restartTest || lookTest) && vf != null) {   // the viewfinder holds the camera until Latent is left
            toast("The camera is in use. Leave Latent and open it again to run this test");
            probeStage = 0;
            return;
        }
        if (shootTest) {   // like the live view test: on this thread, which opens the camera
            shootRun = new ShootingTest(this, card, liveSurface, stock, listener, new LiveViewTest.Host() {
                public void showLive(boolean on) {
                    liveOn = on;
                    liveSurface.setVisibility(on ? View.VISIBLE : View.GONE);
                    screen.invalidate();
                }
                public void note(String text) { liveNote = text; screen.invalidate(); }
                public void finished() { shootRun = null; liveNote = ""; probeStage = 2; screen.invalidate(); }
            });
            shootRun.start();
            screen.invalidate();
            return;
        }
        if (lookTest) {   // the camera answers on the thread that opens it: this one
            lookRun = new LiveLookTest(this, card, liveSurface, prefs, stock, listener, new LiveViewTest.Host() {
                public void showLive(boolean on) {
                    liveOn = on;
                    liveSurface.setVisibility(on ? View.VISIBLE : View.GONE);
                    screen.invalidate();
                }
                public void note(String text) { liveNote = text; screen.invalidate(); }
                public void finished() {
                    lookRun = null; liveNote = ""; probeStage = 2; screen.invalidate();
                    if (killWhenClosed) killNow();   // left during the test: it ends now the camera is back
                }
            });
            lookRun.start();
            screen.invalidate();
            return;
        }
        if (restartTest) {   // the camera answers on the thread that opens it: this one
            restartRun = new RestartTest(this, card, liveSurface, prefs, listener, new LiveViewTest.Host() {
                public void showLive(boolean on) {
                    liveOn = on;
                    liveSurface.setVisibility(on ? View.VISIBLE : View.GONE);
                    screen.invalidate();
                }
                public void note(String text) { liveNote = text; screen.invalidate(); }
                public void finished() { restartRun = null; liveNote = ""; probeStage = 2; screen.invalidate(); }
            });
            restartRun.start();
            screen.invalidate();
            return;
        }
        if (liveTest) {   // the camera answers on the thread that opens it: this one
            liveRun = new LiveViewTest(this, card, liveSurface, listener, new LiveViewTest.Host() {
                public void showLive(boolean on) {
                    liveOn = on;
                    liveSurface.setVisibility(on ? View.VISIBLE : View.GONE);
                    screen.invalidate();
                }
                public void note(String text) { liveNote = text; screen.invalidate(); }
                public void finished() { liveRun = null; liveNote = ""; probeStage = 2; screen.invalidate(); }
            });
            liveRun.start();
            screen.invalidate();
            return;
        }
        dim(hardwareTest || speedTest);
        if (speedTest) powerSave.hold();
        worker.execute(new Runnable() {
            public void run() {
                if (speedTest) {
                    new SpeedTest(card, stock, prefs, listener).run();
                } else if (settingsCheck) {
                    new SettingsCheck(card, listener).run();
                } else if (hardwareTest) {
                    new HardwareTest(MainActivity.this, card, stock, listener, new FilmCore.Progress() {
                        public void onProgress(final int pct) {
                            ui.post(new Runnable() { public void run() { if (probeStatus[4] == Probe.RUN) { probeDetail[4] = "developing… " + pct + "%"; screen.invalidate(); } } });
                        }
                    }).run();
                } else {
                    new Probe(MainActivity.this, card, ui, listener).run();
                }
                ui.post(new Runnable() { public void run() { probeStage = 2; dim(false); if (speedTest) powerSave.release(); screen.invalidate(); } });
            }
        });
        screen.invalidate();
    }

    /** The loaded roll as last seen, so the camera back appears instantly on the next launch. */
    private void saveSnapshot() {
        SharedPreferences.Editor e = prefs.edit();
        Stock st = current == null ? null : Stock.byId(stocks, current.stock);
        if (st == null) e.remove("snap");
        else e.putString("snap", st.title + "|" + st.iso + "|" + st.body + "|" + st.band + "|" + st.accent + "|"
                + current.number + "|" + exposed + "|" + current.length + "|" + current.state);
        e.commit();
    }

    /** Fills the camera back from the snapshot until the card has been read. */
    private boolean snapshot(UiState s) {
        String[] v = prefs.getString("snap", "").split("\\|");
        if (v.length < 9) return false;
        try {
            UiState.Film f = new UiState.Film();
            f.title = v[0]; f.iso = v[1];
            f.body = Integer.parseInt(v[2]); f.band = Integer.parseInt(v[3]); f.accent = Integer.parseInt(v[4]);
            s.film = f;
            s.rollNumber = Integer.parseInt(v[5]);
            s.exposed = Integer.parseInt(v[6]);
            s.length = Integer.parseInt(v[7]);
            s.endless = s.length >= Roll.ENDLESS;   // no ticks: a tick per frame of 9999 took the first frame over a second
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Straight to the camera's shooting mode (not back through the menus). */
    /** Going to shoot this soon after Latent changed a camera setting restarts the A5100: wait at least this long. */
    static final long SHOOT_SETTLE_MS = 6000;
    private boolean shootPending;
    private final Runnable shootWhenSettled = new Runnable() { public void run() { goShoot(); } };

    private void goShoot() {
        if (developing != null && devQuiet) {   // the photo in hand finishes first (a frame half-written is wasted)
            paused = true;
            shootAfterFrame = true;
            toast("Finishing a photo…");
            return;
        }
        if (neutralBusy > 0 || refreshing > 0 || filing) {   // never start shooting while settings are being written (or the card read, or an original filed): check again shortly
            if (!shootPending) { shootPending = true; toast("Setting up the camera…"); }
            ui.removeCallbacks(shootWhenSettled);
            ui.postDelayed(shootWhenSettled, 300);
            return;
        }
        long since = NeutralSettings.sinceLastWrite();
        if (loading || (since >= 0 && since < SHOOT_SETTLE_MS)) {   // one press is enough: it goes as soon as it's safe
            if (!shootPending) { shootPending = true; toast("Setting up the camera…"); }
            ui.removeCallbacks(shootWhenSettled);
            ui.postDelayed(shootWhenSettled, loading ? 300 : SHOOT_SETTLE_MS - since);
            return;
        }
        shootPending = false;
        if (viewfinderOn() && current != null && Roll.LOADED.equals(current.state)) { openViewfinder(); return; }
        // a breadcrumb: if the camera restarts on the way to shooting, the next start shows it came right after this
        devLog("shoot: handing over to the camera" + (current != null ? " (" + current.label() + ", " + exposed + " exposed)" : " (no roll)")
                + (since >= 0 ? ", settings last changed " + since / 1000 + " s before" : ""));
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME)
                    .setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            // no home activity: just leave
        }
        finish();
    }

    // ------------------------------------------------------------------------------ the viewfinder

    private Viewfinder vf;             // while it's open (it holds the camera)
    private int vfBase;                // the roll's exposures when it opened
    private int vfWaiting;             // pictures taken in it since Latent opened: they develop next time
    private int highestOnCard;         // the highest photo number the card listed
    private int vfHighestAtOpen;       // ... when the viewfinder opened: its pictures are numbered above it
    private volatile boolean filing;   // an original is being removed with Sony's delete: the camera waits
    private boolean s1Held;            // the shutter button is half-pressed now

    /**
     * Latent's own viewfinder in place of the camera's shooting mode: live view in the roll's film,
     * focus and the picture from the shutter button, the level read from the picture. Pictures are
     * the roll's exposures as the camera stores them; they develop the next time Latent opens.
     */
    private void openViewfinder() {
        if (current == null) return;
        if (vf != null) {   // opened earlier this visit and kept open: the camera is never reopened in one visit
            if (!current.endless() && exposed >= current.length) { toast("Roll finished"); return; }
            mode = PHOTO;
            readBattery();
            vf.show();
            if (s1Held) vf.halfDown();
            screen.invalidate();
            return;
        }
        if (!current.endless() && exposed >= current.length) { toast("Roll finished"); return; }
        Stock st = Stock.byId(stocks, current.stock);
        mode = PHOTO;
        vfBase = exposed;
        vfHighestAtOpen = highestOnCard;
        readBattery();
        devLog("viewfinder: opening (" + current.label() + ", " + exposed + " exposed)");
        vf = new Viewfinder(this, card, liveSurface, worker, st, focusFirst(), highestOnCard, liveLook(), new Viewfinder.Host() {
            public void showLive(boolean on) {
                liveSurface.setVisibility(on ? View.VISIBLE : View.GONE);
                screen.invalidate();
            }
            public void changed() { screen.invalidate(); }
            public void frame() {   // a new live frame: only the picture's area
                int w = screen.getWidth(), side = Math.round((logicalWidth - 720) / 2 * w / logicalWidth);
                if (w <= 0 || side <= 0) screen.invalidate(); else screen.invalidate(side - 1, 0, w - side + 1, screen.getHeight());
            }
            public void live() { if (s1Held && vf != null) vf.halfDown(); }   // still half-pressed from the camera back: focus now
            public void shot(File photo, String film) { vfShot(photo, film); }
            public void hud(boolean on) { screen.setVisibility(on ? View.VISIBLE : View.INVISIBLE); }
            public void stuck(String what) {
                screen.setVisibility(View.VISIBLE);
                devLog("viewfinder: " + what);
                restarting = false;
                relaunchWhenClosed = false;
                unprepare();
                status = what;   // stays up: nothing else can happen until the camera is switched off
                ui.removeCallbacks(clearToast);
                screen.invalidate();
            }
            public void closed(String why) {
                vf = null;
                screen.setVisibility(View.VISIBLE);
                if (relaunchWhenClosed) { relaunch(); return; }   // the camera is back: Latent starts again and develops
                if (killWhenClosed) { killNow(); return; }
                ui.removeCallbacks(closeWhenFinished);
                if (mode == PHOTO) mode = BACK;
                devLog("viewfinder: closed" + (why != null ? " (" + why + ")" : "") + ", " + exposed + " exposed");
                if (why != null) toast(why);
                screen.invalidate();
            }
        });
        // the picture's area on screen in real pixels: the 3:2 area (720 x 480 of the design space)
        if (screen.getWidth() > 0) vf.setLiveSize(Math.round(720f * screen.getWidth() / logicalWidth), screen.getHeight());
        vf.open();
        screen.invalidate();
    }

    /** One picture stored (photo is its file, or null when the catalogue didn't name it). */
    private void vfShot(final File photo, final String shotFilm) {
        vfWaiting++;
        exposed++;
        final Roll r = current;
        final String film = r != null && r.endless() ? (shotFilm != null && shotFilm.length() > 0 ? shotFilm : r.stock) : null;   // an endless roll: this frame's own film (decision A)
        if (photo != null) highestOnCard = Math.max(highestOnCard, Roll.number(photo));
        final int after = vfHighestAtOpen;
        Runnable note = new Runnable() {
            public void run() {
                if (photo != null) try { Library.recordShot(card, photo); } catch (Exception e) { devLog("viewfinder: " + photo.getName() + " not noted (" + Probe.why(e) + ")"); }
                if (film != null) {
                    if (photo != null) r.lookFor(photo, film);
                    else r.shotFilm(after, film);   // named once the card shows it (after the camera is back)
                    try { r.save(); } catch (Exception e) { devLog("viewfinder: a picture's film not noted (" + Probe.why(e) + ")"); }
                }
            }
        };
        try { worker.execute(note); } catch (java.util.concurrent.RejectedExecutionException e) { note.run(); }   // Latent closing down
        if (current != null && !current.endless() && exposed >= current.length && vf != null) {   // the last frame: back to the camera back
            vf.lockRoll();   // the button takes no more pictures
            vf.say("Roll finished");
            ui.removeCallbacks(closeWhenFinished);
            ui.postDelayed(closeWhenFinished, 1800);
        }
        readBattery();
        saveSnapshot();
        screen.invalidate();
    }

    private final Runnable closeWhenFinished = new Runnable() { public void run() { hideViewfinder(); } };

    private boolean restarting, relaunchWhenClosed;
    /**
     * The darkroom getting ready: from MENU in the viewfinder, across the restart (Relaunch draws the same),
     * until the develop starts in the new process. One red screen throughout, as a darkroom door closing.
     */
    private boolean preparing;
    private Intent prepared;   // what it shows: the film and how many frames (Relaunch's extras)

    /**
     * Goal 6: pictures shot here need the darkroom, and the darkroom needs Sony's image memory, which
     * this process holds until it ends (v0.23-0.24). So the camera goes back (after every picture is
     * stored), and Latent starts again behind a screen of its own (Relaunch): back in ~3.3 s with the
     * memory free (v0.33.1), where the darkroom takes the new pictures as usual.
     */
    private void restartToDevelop(String why) {
        if (restarting) return;
        restarting = true;
        devLog("restarting to develop: " + why);
        prepared = new Intent();
        Stock st = current == null ? null : Stock.byId(stocks, current.stock);
        if (st != null) {
            prepared.putExtra(Relaunch.EXTRA_FILM, st.title).putExtra(Relaunch.EXTRA_ISO, st.iso).putExtra(Relaunch.EXTRA_ACCENT, st.accent)
                    .putExtra(Relaunch.EXTRA_BODY, st.body).putExtra(Relaunch.EXTRA_BAND, st.band);
        }
        prepared.putExtra(Relaunch.EXTRA_TOTAL, Math.max(1, Math.max(vfWaiting, vf != null ? vf.shots : 0)));
        prepared.putExtra(Relaunch.EXTRA_BATTERY, battery);
        preparing = true;
        toast("");
        mode = DARKROOM;
        dim(true);
        screen.invalidate();
        if (vf != null) { relaunchWhenClosed = true; vf.close(null); }
        else relaunch();
    }

    /** The darkroom wasn't reached after all (the restart failed, or nothing was left to develop): the camera back. */
    private void unprepare() {
        if (!preparing) return;
        preparing = false;
        if (mode == DARKROOM && developing == null) { mode = BACK; dim(false); }
        screen.invalidate();
    }

    /** Relaunch in front; this process then ends in onPause (the camera was opened here). */
    private void relaunch() {
        try {
            Intent i = new Intent(this, Relaunch.class);
            i.putExtra(Relaunch.EXTRA_PID, android.os.Process.myPid());
            i.putExtra(Relaunch.EXTRA_AT, SystemClock.uptimeMillis());
            i.putExtra(Relaunch.EXTRA_WHY, "develop");
            if (prepared != null) i.putExtras(prepared);
            startActivity(i);
            overridePendingTransition(0, 0);
        } catch (Throwable e) {   // stay put: the pictures develop the next time Latent opens
            restarting = false;
            relaunchWhenClosed = false;
            unprepare();
            devLog("restart: " + Probe.why(e));
        }
    }

    /**
     * MENU in the viewfinder: back to the camera back, with the camera kept open behind it. Opening it
     * again in the same visit froze the camera twice (v0.31.2, v0.32.0), so it is given back only when
     * Latent is left.
     */
    private void hideViewfinder() {
        if (vf == null) return;
        ui.removeCallbacks(closeWhenFinished);
        int taken = Math.max(vfWaiting, vf.shots);   // listed or not yet: the hand-back waits for them
        if (taken > 0) { restartToDevelop(taken + " picture(s) to develop"); return; }
        vf.hide();
        if (mode == PHOTO) mode = BACK;
        screen.invalidate();
    }

    /** Nothing shot on this roll yet: its film can still change. */
    private boolean filmFresh() {
        return current != null && Roll.LOADED.equals(current.state) && current.frames.isEmpty() && vfBase + (vf != null ? vf.shots : 0) == 0;
    }

    /**
     * The wheel in the viewfinder, as the X Half's film simulation (decision A): on an endless roll it
     * changes the film for this frame and the ones after (each frame keeps its own, in ROLL.TXT); a roll of
     * 12, 24 or 36 is film camera mode, one film, changeable only until its first frame. Else it shows the
     * film's card.
     */
    private void vfFilm(int step) {
        if (vf == null) return;
        if (current != null && (current.endless() || filmFresh()) && stocks.size() > 1) {
            Stock now = Stock.byId(stocks, current.stock);
            int i = Math.max(0, stocks.indexOf(now));
            final Stock next = stocks.get((i + step + stocks.size()) % stocks.size());
            if (current.endless()) {   // what's shot already keeps the film it was shot in
                for (String f : current.frames) if (!current.looks.containsKey(Roll.number(new File(f)))) current.lookFor(new File(f), current.stock);
                for (File f : current.exposures(photos)) if (!current.looks.containsKey(Roll.number(f))) current.lookFor(f, current.stock);
            }
            current.stock = next.id;
            final Roll r = current;
            prefs.edit().putString("lastStock", next.id).commit();
            worker.execute(new Runnable() {
                public void run() {
                    try { r.save(); } catch (Exception e) { devLog(r.label() + ": film not changed on the card (" + Probe.why(e) + ")"); }
                }
            });
            vf.setFilm(next);
            saveSnapshot();
        }
        vf.showCard();
    }

    /** Keys while the viewfinder is open. */
    private void vfKey(int keyCode, KeyEvent e, boolean down) {
        int sc = e.getScanCode();
        boolean half = sc == SC_S1 || keyCode == KeyEvent.KEYCODE_FOCUS, full = sc == SC_S2 || keyCode == KeyEvent.KEYCODE_CAMERA;
        if (half) {
            if (down && e.getRepeatCount() == 0) vf.halfDown();
            else if (!down) vf.halfUp();
            return;
        }
        if (full) {
            if (down && e.getRepeatCount() == 0) {
                if (current != null && !current.endless() && vfBase + vf.shots >= current.length) vf.lockRoll();
                vf.fullDown();
            }
            return;
        }
        if (!down) {
            if (sc == SC_MENU || sc == SC_SK1 || keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BACK) {
                if (vf.evEditing) vf.closeEv(); else hideViewfinder();
            }
            return;
        }
        int k = key(keyCode, sc);
        boolean cw = sc == SC_WHEEL_CW || sc == SC_DIAL_CW, ccw = sc == SC_WHEEL_CCW || sc == SC_DIAL_CCW;
        if (vf.evEditing) {   // the exposure scale is open: the wheel, left and right move it
            if (cw || (k == K_RIGHT && !ccw)) vf.evBy(1);
            else if (ccw || k == K_LEFT) vf.evBy(-1);
            else if ((k == K_DOWN || k == K_ENTER) && e.getRepeatCount() == 0) vf.toggleEv();
            else if (k == K_UP) { vf.closeEv(); vf.cycleDisplay(1); }
            return;
        }
        if (cw) { vfFilm(1); return; }
        if (ccw) { vfFilm(-1); return; }
        if (k == K_LEFT) vfFilm(-1);
        else if (k == K_RIGHT) vfFilm(1);
        else if (k == K_UP) vf.cycleDisplay(1);                                     // DISP
        else if (k == K_DOWN && e.getRepeatCount() == 0) vf.toggleEv();             // the ± key: exposure
        else if (k == K_ENTER && e.getRepeatCount() == 0) vf.toggleLook();
    }

    private void toast(String msg) {
        status = msg == null ? "" : msg;
        ui.removeCallbacks(clearToast);
        if (status.length() > 0) ui.postDelayed(clearToast, 3500);
        screen.invalidate();
    }

    // ------------------------------------------------------------------------------ load film

    private volatile boolean loading;   // a roll is being loaded: one press is enough, the rest wait

    private void loadKey(int k) {
        if (loading || !ready || stocks.isEmpty()) return;
        if (k == K_ENTER && developing != null && devQuiet) { paused = true; loadAfter = true; toast("Finishing a photo…"); return; }
        if (k == K_LEFT) stockSel = (stockSel + stocks.size() - 1) % stocks.size();
        else if (k == K_RIGHT) stockSel = (stockSel + 1) % stocks.size();
        else if (k == K_UP) lengthSel = Math.min(LENGTHS.length - 1, lengthSel + 1);   // up = more exposures (∞ last)
        else if (k == K_DOWN) lengthSel = Math.max(0, lengthSel - 1);
        else if (k == K_ENTER) loadRoll();
        if (k == K_LEFT || k == K_RIGHT) renderHero();
        screen.invalidate();
    }

    /**
     * Loads the chosen film: answers at once ("Loading…" in place of Load, further presses ignored), stops sample
     * pictures still waiting to render so nothing queues ahead of it, and opens the camera back on
     * the new roll as soon as it exists and the camera is neutral; the card is re-read afterwards.
     */
    private void loadRoll() {
        final Stock stock = stocks.get(stockSel);
        prefs.edit().putString("lastStock", stock.id).putInt("lastLength", lengthSel).commit();
        final Roll previous = current;
        final List<File> ph = photos;
        final int length = LENGTHS[lengthSel];
        final long after = ph.isEmpty() ? 0 : ph.get(0).lastModified();   // newest photo on the card
        loading = true;
        samplesGen++;          // samples not yet rendered: skip them, the load goes first
        samplesFor = "";
        screen.invalidate();   // the Load film screen says "Loading…" until the camera back opens
        worker.execute(new Runnable() {
            public void run() {
                String msg;
                Roll made = null;
                long t0 = System.currentTimeMillis(), t1 = t0, t2 = t0, t3;
                try {
                    // every roll still open on the card keeps exactly the frames it has (or goes, if it has none)
                    if (previous != null) previous.close(ph);
                    for (Roll o : Roll.list(card)) if (Roll.LOADED.equals(o.state) && o.isOpen()) o.close(ph);
                    t1 = System.currentTimeMillis();
                    made = Roll.create(card, stock.id, length, after, Roll.highest(Library.photos(card)));
                    if (!prefs.edit().putString("roll", made.dir.getName()).commit())
                        devLog("load film: Latent's note of " + made.label() + " couldn't be saved (the card still says it's loaded)");
                    t2 = System.currentTimeMillis();
                    // the camera shoots neutral while a roll is loaded; yours come back after developing
                    StringBuilder nl = new StringBuilder();
                    String why = neutralOn() ? new NeutralSettings(card, nl).apply() : null;   // a film roll: wanted whenever the switch is on
                    neutralTried = true;
                    devLogAll(nl);
                    msg = stock.name + " loaded" + (!neutralOn() ? "" : why == null ? "  ·  camera set to neutral" : ". Camera " + why);
                } catch (Exception e) {
                    msg = "Could not load film: " + e.getMessage();
                }
                t3 = System.currentTimeMillis();
                devLog("load film: waited " + (t0 - pressed) + " ms, old roll " + (t1 - t0) + " ms, new roll " + (t2 - t1)
                        + " ms, neutral " + (t3 - t2) + " ms");
                final String m = msg;
                final Roll r = made;
                ui.post(new Runnable() {
                    public void run() {
                        loading = false;
                        if (r != null) { current = r; exposed = 0; }   // the camera back shows it now; the card is re-read behind it
                        mode = BACK;
                        toast(m);
                        refresh(null);
                    }
                });
            }
            private final long pressed = System.currentTimeMillis();
        });
    }

    private volatile boolean neutralTried;   // set neutral at most once per run for a roll loaded before v0.13

    /**
     * The camera shoots neutral exactly while a roll is loaded (and the switch in Settings is on): once
     * none is (developed, or the app finds none), your settings go back. Worker thread. Returns a message for the screen, or null.
     */
    private String reconcileNeutral(boolean loaded) {
        StringBuilder nl = new StringBuilder();
        NeutralSettings ns = new NeutralSettings(card, nl);
        String msg = null;
        if (!loaded && ns.active()) {
            String yours = ns.yoursSummary(), why = ns.restore();
            msg = why == null ? "Your camera settings are back" + (yours != null && !yours.equals("already neutral") ? ": " + yours : "") : why;
        } else if (loaded && !ns.active() && !neutralTried) {   // a roll loaded before Latent did this
            neutralTried = true;
            String why = ns.apply();
            msg = why == null ? "Camera set to neutral for this roll" : "Camera " + why;
        }
        devLogAll(nl);
        return msg;
    }

    private void devLogAll(StringBuilder lines) {
        for (String l : lines.toString().split("\r\n")) if (l.length() > 0) devLog(l);
    }

    // ------------------------------------------------------------------------------ darkroom

    private void startDevelop(Roll r) { startDevelop(r, false); }

    /**
     * Quiet: a photo roll, developed behind the Photo screen (or wherever you are, short of the Lab, a
     * test or the darkroom, which pause it). Otherwise the darkroom, with the roll on screen.
     */
    private void startDevelop(Roll r, boolean quiet) {
        if (r == null) return;
        if (Viewfinder.opened) {   // the camera has the image memory until this process ends: Latent restarts to develop
            if (!quiet) restartToDevelop(r.label() + " to develop");
            return;
        }
        if (developing != null) {
            if (devQuiet && !quiet) {   // your photos give way to the film: after the frame in hand
                paused = true;
                developAfter = r;
                toast("Finishing a photo first…");
            }
            return;
        }
        if (r.frames.isEmpty()) for (File f : r.exposures(photos)) r.frames.add(f.getPath());
        if (r.frames.isEmpty()) { toast("No exposures on this roll yet. Go shoot."); return; }
        if (!quiet && !r.endless()) r.state = Roll.DEVELOPING;   // an endless roll stays loaded while its photos develop
        try { r.save(); } catch (Exception e) { toast("Could not write the roll: " + e.getMessage()); return; }
        r.cleanTemp();
        developing = r;
        paused = false;
        devFailed = 0;
        failed.clear();
        devDoneAtStart = r.developedCount();
        devStarted = System.currentTimeMillis();
        etaShown = -1;
        progressShown = 0;
        devQuiet = quiet;
        quietLeft = r.frames.size() - devDoneAtStart;
        powerSave.hold();
        preparing = false;
        if (!quiet) {
            mode = DARKROOM;
            toast("");
            dim(true);
        }
        worker.execute(new Runnable() {
            public void run() {
                // developing into the camera roll needs the catalogue and a verified memory path
                FilmCore.setVariant(prefs.getInt("lookVariant", 0));   // the inner loop the speed test chose
                StringBuilder hl = new StringBuilder();
                HardwareDevelop h = new HardwareDevelop(MainActivity.this, card, hl, null);
                try {
                    h.prepare(false);
                    hw = h;
                } catch (Throwable e) {
                    hw = null;
                    devLog("camera roll develop unavailable (" + Probe.why(e) + "); developing in software. "
                            + hl.toString().trim().replace("\r\n", "; "));
                }
                // the strip shows frames developed on an earlier, paused visit too
                for (int i = 0; i < r.frames.size(); i++) if (r.developed(i) != null) loadThumb(r.developed(i));
            }
        });
        developNext();
    }

    // time estimate: per-frame time learned from earlier develops, so it's right from the first second
    private long etaShown = -1;

    private long msPerFrame() { return prefs.getLong("msPerFrame", 37000); }

    private void learnFrameTime(int ms) {
        if (ms <= 0) return;
        prefs.edit().putLong("msPerFrame", (msPerFrame() * 6 + ms * 4) / 10).commit();
    }

    /**
     * "About 4 min left": from the remaining work and the learned frame time. It only counts down
     * (a small rise is held, so the text never climbs), and says minutes until the last minute.
     */
    private String timeLeft(long leftMs) {
        long left = leftMs / 1000;
        if (etaShown < 0 || left < etaShown || left > etaShown * 5 / 4 + 10) etaShown = left;
        long t = etaShown;
        if (t >= 90) return "About " + (t + 30) / 60 + " min left";
        if (t >= 45) return "About a minute left";
        if (t >= 12) return "Less than a minute left";
        return "Almost done";
    }

    private void developNext() {
        final Roll r = developing;
        if (devQuiet && !quietAllowed()) paused = true;
        if (paused) { stopDevelop(devQuiet ? null : "Paused. Press the centre button to carry on."); return; }
        int next = -1;
        for (int i = 0; i < r.frames.size() && next < 0; i++) if (!failed.contains(i) && r.developed(i) == null) next = i;
        if (next < 0) { finishDevelop(); return; }   // the last originals are filed in the background (refresh)
        final int frame = next;
        final File src = r.source(frame), out = r.output(frame), prv = r.preview(out);
        final String film = r.stockFor(frame);   // on an endless roll, the film this frame was shot in
        final Stock stock = Stock.byId(stocks, film);
        devFrame = frame;
        devPct = 0;
        powerSave.touch();
        readBattery();
        final int[] p = stock == null ? params(src, 0, 0, 4, 1)
                : params(src, stock.highlight, stock.shadow, stock.grain, stock.grainSize);
        p[FilmCore.SEED] = (int) (src.lastModified() ^ new File(r.frames.get(frame)).getName().hashCode());
        final String lut = stock == null ? null : stock.lut.getPath();
        screen.invalidate();
        final boolean behind = devQuiet;   // developing behind the screen: the screen always comes first
        worker.execute(new Runnable() {
            public void run() {
                if (behind) android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                long t0 = System.currentTimeMillis();
                boolean exists = src.exists();
                if (exists) {   // the print in the tray: the camera's own small thumbnail of this frame
                    loadThumb(src);
                    ui.post(new Runnable() { public void run() { screen.invalidate(); } });
                }
                FilmCore.Progress progress = new FilmCore.Progress() {
                    public void onProgress(final int pct) {
                        ui.post(new Runnable() { public void run() { devPct = pct; screen.invalidate(); } });
                    }
                };
                File made = null;
                String err = null;
                final int[] t = new int[FilmCore.T_COUNT];
                // into the camera roll, while the original is still there and the hardware keeps working
                final HardwareDevelop h = hw;
                if (exists && h != null && !HardwareDevelop.hung() && src.getPath().equals(r.frames.get(frame))) {
                    // the negative is copied alongside, so it has settled on the card by the time the original goes
                    final File neg = r.negative(frame);
                    filer.execute(new Runnable() { public void run() { copyNegative(src, neg); } });
                    try {
                        int[] hp = p.clone();
                        hp[FilmCore.THREADS] = developCores();   // the look's threads (the software fallback keeps its own)
                        HardwareDevelop.Print print = h.develop(src, lut, hp, progress);
                        made = print.file;
                        r.setPrint(frame, print.file);
                        try { r.save(); } catch (Exception e) { devLog("roll not saved after " + print.file.getName() + ": " + Probe.why(e)); }
                        if (!print.catalogued) {
                            hw = null;   // something about the camera roll isn't right: no more until next time
                            devLog(print.file.getName() + " is not in the catalogue; developing the rest in software");
                        }
                        // earlier frames whose negatives have settled leave the camera roll, alongside the next frame
                        filer.execute(new Runnable() { public void run() { fileSettled(r, false); } });
                        logHardware(src, film, print, h.ms, " (read " + h.look[FilmCore.T_READ] + " look " + h.look[FilmCore.T_LOOK]
                                + " write " + h.look[FilmCore.T_WRITE] + ") " + (HardwareDevelop.extraFine() ? "xfine" : "fine")
                                + " loop=" + FilmCore.VARIANTS[Math.max(0, Math.min(FilmCore.VARIANTS.length - 1, prefs.getInt("lookVariant", 0)))]
                                + " rows=" + (SonyMemory.lastDirect() ? "direct" : "gathered")
                                + " threads=" + h.look[FilmCore.T_THREADS] + (FilmCore.helperNote().length() > 0 ? " (" + FilmCore.helperNote() + ")" : ""), System.currentTimeMillis() - t0);
                    } catch (Throwable e) {
                        hw = null;
                        devLog(src.getName() + ": camera roll develop failed (" + Probe.why(e) + "); developing in software");
                        filer.execute(new Runnable() {   // the original stays in the camera roll: no negative needed
                            public void run() {
                                if (r.print(frame) == null && src.exists()) {
                                    neg.delete();
                                    negCrc.remove(src.getPath());
                                    negAt.remove(src.getPath());
                                }
                            }
                        });
                        ui.post(new Runnable() { public void run() { devPct = 0; } });
                    }
                }
                if (made == null) {
                    prv.getParentFile().mkdirs();
                    CpuGate.busy(true);
                    int rc;
                    try {
                        rc = exists ? FilmCore.process(src.getPath(), out.getPath(), lut, prv.getPath(), p, progress) : -1;
                    } finally {
                        CpuGate.busy(false);
                    }
                    if (rc == 0) {
                        FilmCore.timing(t);
                        logTiming(src, film, p, t);
                        made = out;
                    } else {
                        err = exists ? FilmCore.lastError() : "photo no longer on the card";
                    }
                }
                if (behind) android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
                if (made != null) loadThumb(made);
                final File print = made;
                final String error = err;
                final long frameMs = System.currentTimeMillis() - t0;
                ui.post(new Runnable() {
                    public void run() {
                        if (error != null) { devFailed++; failed.add(frame); toast((devQuiet ? "Photo " : "Frame ") + (frame + 1) + ": " + error); }
                        else {
                            if (devQuiet) {
                                quietLeft = Math.max(0, quietLeft - 1);
                                if (r == current && print != null) endlessPrints.add(0, print);
                            }
                            if (t[FilmCore.T_TOTAL] > 0) adaptThreads(t, p[FilmCore.THREADS]);
                            learnFrameTime((int) frameMs);
                        }
                        developNext();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------------------ negatives (filer thread)

    /** Copies an original into its roll's NEG folder and checks it; remembers when. Filer thread. */
    private void copyNegative(File original, File neg) {
        String key = original.getPath();
        if (negCrc.containsKey(key) || !original.exists()) return;
        try {
            negCrc.put(key, HardwareDevelop.copyNegative(original, neg));
            negAt.put(key, SystemClock.elapsedRealtime());
        } catch (Exception e) {
            devLog(original.getName() + ": negative not copied (" + Probe.why(e) + "); trying again when filing");
        }
    }

    /** Frames developed into the camera roll whose original is still there. */
    private static List<Integer> unfiled(Roll r) {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < r.frames.size(); i++) {
            File print = r.print(i);
            if (print != null && print.exists() && new File(r.frames.get(i)).exists()) out.add(i);
        }
        return out;
    }

    /**
     * Moves originals out of the camera roll for frames whose negative has settled on the card (with
     * wait, waits for that). Each negative is read back and checked against its original, and only
     * then does Sony's delete remove the original, and only while Latent is on screen (never while
     * the camera is shooting or playing back). Anything that doesn't check out stays where it is.
     * Returns how many originals were filed. Filer thread.
     */
    private int fileSettled(Roll r, boolean wait) {
        if (HardwareDevelop.hung() || Viewfinder.opened) return 0;   // never alongside the camera
        List<Integer> todo = unfiled(r);
        if (todo.isEmpty()) return 0;
        HardwareDevelop sony = new HardwareDevelop(this, card, new StringBuilder(), null);
        try {
            sony.openCatalogue();
        } catch (Throwable e) {
            devLog("negatives not filed: " + Probe.why(e));
            return 0;
        }
        long now = SystemClock.elapsedRealtime();
        for (int i : todo) negAt.putIfAbsent(r.frames.get(i), now);   // can't tell their age: the full time, all together
        int filed = 0, remaining = todo.size();
        if (wait) showFiling(remaining);
        for (int i : todo) {
            File print = r.print(i), original = new File(r.frames.get(i)), neg = r.negative(i);
            String key = original.getPath();
            if (!negCrc.containsKey(key)) {   // copied on an earlier visit (or not yet): check it, copy it if need be
                try {
                    boolean had = neg.exists();
                    negCrc.put(key, HardwareDevelop.copyNegative(original, neg));
                    if (!had) negAt.put(key, SystemClock.elapsedRealtime());
                } catch (Exception e) {
                    devLog(original.getName() + " stays in the camera roll: " + Probe.why(e));
                    continue;
                }
            }
            long left = negAt.get(key) + SETTLE_MS - SystemClock.elapsedRealtime();
            if (left > 0 && !wait) continue;
            try {
                while (left > 0 || !resumed || mode == PHOTO || shootPending) {
                    if (Viewfinder.opened) return filed;
                    Thread.sleep(left > 0 ? Math.min(left, 500) : 500);
                    left = negAt.get(key) + SETTLE_MS - SystemClock.elapsedRealtime();
                }
            } catch (InterruptedException e) {
                return filed;
            }
            String w;
            filing = true;   // the camera doesn't open meanwhile
            try { w = Viewfinder.opened ? "the camera is open" : sony.removeOriginal(original, print, neg, negCrc.get(key)); }
            finally { filing = false; }
            if (wait) showFiling(--remaining);
            if (w == null) filed++;
            else devLog(original.getName() + " stays in the camera roll: " + w);
            if (HardwareDevelop.NEG_MISMATCH.equals(w)) {   // a bad copy: drop it, so the next pass copies afresh
                neg.delete();
                negCrc.remove(key);
                negAt.remove(key);
            }
            if (HardwareDevelop.hung()) break;
        }
        if (wait) showFiling(0);
        if (filed > 0) {
            devLog(r.label() + ": " + filed + " original(s) filed as negatives");
            if (wait) ui.post(new Runnable() { public void run() { toast("Negatives filed"); } });
            ui.post(new Runnable() { public void run() { if (mode == SHEET || mode == VIEW) screen.invalidate(); } });
        }
        return filed;
    }

    private void showFiling(int n) {
        filingLeft = Math.max(0, n);
        ui.post(new Runnable() { public void run() { screen.invalidate(); } });
    }

    /** Every roll's originals still waiting to be filed (after a develop, or from an earlier visit). Filer thread. */
    private void fileBacklog(List<Roll> rolls) {
        long now = SystemClock.elapsedRealtime();
        for (Roll r : rolls) for (int i : unfiled(r)) negAt.putIfAbsent(r.frames.get(i), now);
        for (Roll r : rolls) {
            Roll d = developing;   // that roll's own develop files its frames as it goes
            if (d == null || !d.dir.equals(r.dir)) fileSettled(r, true);
        }
    }

    private void finishDevelop() {
        final Roll r = developing;
        if (devFailed == 0 && !(r.endless() && r.isOpen())) {   // an endless roll in the camera is never finished
            r.state = Roll.DEVELOPED;
            if (r.dir.getName().equals(prefs.getString("roll", ""))) prefs.edit().remove("roll").commit();
        }
        try { r.save(); } catch (Exception e) { /* the frames are on the card either way */ }
        if (devQuiet) {   // nothing to announce: the photos are in the camera roll, the next trip's develop next
            final int n = r.frames.size();
            endDarkroom();
            refresh(new Runnable() { public void run() { afterQuiet(); } });
            return;
        }
        long secs = (System.currentTimeMillis() - devStarted) / 1000;
        if (r.endless()) {   // the photos just shot are in the camera roll: back to the camera back, ready to shoot
            final int n = r.developedCount() - devDoneAtStart;
            final String m = devFailed == 0 ? n + (n == 1 ? " photo" : " photos") + " developed" : devFailed + " photo(s) could not be developed";
            endDarkroom();
            refresh(new Runnable() {
                public void run() {
                    if (shootAfterFrame) { shootAfterFrame = false; goShoot(); return; }
                    mode = BACK;
                    toast(m);
                }
            });
            return;
        }
        final String msg = devFailed == 0 ? r.label() + " developed in " + secs / 60 + " min " + secs % 60 + " s"
                : devFailed + " frame(s) could not be developed";
        endDarkroom();
        refresh(new Runnable() {
            public void run() {
                if (shootAfterFrame) { shootAfterFrame = false; goShoot(); return; }   // the shutter was pressed on the last frame
                openSheet(r);
                toast(msg);
            }
        });
    }

    private void stopDevelop(final String msg) {
        boolean quiet = devQuiet;
        endDarkroom();
        if (quiet) { refresh(new Runnable() { public void run() { afterQuiet(); } }); return; }
        refresh(new Runnable() {
            public void run() {
                mode = BACK;
                if (shootAfterFrame) { shootAfterFrame = false; goShoot(); }   // the shutter paused it
                else toast(msg);
            }
        });
    }

    private boolean loadAfter, shootAfterLoad;   // Load was pressed during a quiet develop: load once the frame in hand is done

    /** A quiet develop has stopped or finished: what was waiting for it goes now. */
    private void afterQuiet() {
        if (loadAfter) {
            loadAfter = false;
            if (mode == LOAD && !loading) { loadRoll(); if (shootAfterLoad) goShoot(); }
            shootAfterLoad = false;
            return;
        }
        if (shootAfterFrame) { shootAfterFrame = false; goShoot(); return; }
        if (developAfter != null) { Roll f = developAfter; developAfter = null; startDevelop(f, false); }
    }

    /** Photos develop quietly on these screens; the Lab, the tests, Load film and the darkroom want the camera to themselves. */
    private boolean quietAllowed() {
        return resumed && !loading && (preparing || mode == BACK || mode == LOAD || mode == SHEET || mode == VIEW || mode == SETTINGS);
    }

    private List<File> endlessPrints = new ArrayList<File>();   // the endless roll in the camera: its prints, newest first
    private List<Roll> endlessWaiting = new ArrayList<Roll>();   // endless rolls with frames to develop (from the last card read)

    /** An endless roll's new photos start developing as soon as nothing else needs the camera. */
    private void developWaiting() {
        if (developing != null || restarting || shootPending || shootAfterFrame || !ready || !quietAllowed() || endlessWaiting.isEmpty()) return;
        // an endless roll develops when you're not about to shoot: a half-press right after opening Latent
        // shoots at once, rather than waiting for a frame in the darkroom (v0.35.0: "finishing up some frames")
        long idle = SystemClock.uptimeMillis() - Math.max(lastPressAt, readyAt);
        if (s1Held || (idle < IDLE_BEFORE_DEVELOP_MS && !developNow)) {
            ui.removeCallbacks(developWhenIdle);
            ui.postDelayed(developWhenIdle, Math.max(250, IDLE_BEFORE_DEVELOP_MS - idle));
            return;
        }
        developNow = false;
        startDevelop(endlessWaiting.get(0), false);   // the red darkroom, as a roll of film develops: you can watch it
    }

    static final long IDLE_BEFORE_DEVELOP_MS = 4000;
    private long lastPressAt;
    private boolean developNow;   // started again to develop (Relaunch): at once
    private final Runnable developWhenIdle = new Runnable() { public void run() { developWaiting(); } };

    private void endDarkroom() {
        devQuiet = false;
        quietLeft = 0;
        developing = null;
        dim(false);
        powerSave.release();
    }

    /** If the threads did not really run in parallel on this camera, stop paying for them. */
    private void adaptThreads(int[] t, int threads) {
        if (threads > 1 && t[FilmCore.T_TOTAL] > 0 && t[FilmCore.T_CPU] < t[FilmCore.T_TOTAL] * 13 / 10) {
            prefs.edit().putInt("threads", 1).commit();
        }
    }

    // ------------------------------------------------------------------------------ contact sheet

    private Roll firstRollWithFrames() {
        for (Roll r : rolls) if (!r.developed().isEmpty() || !r.frames.isEmpty()) return r;
        return rolls.isEmpty() ? null : rolls.get(0);
    }

    private void openSheet(final Roll r) {
        mode = SHEET;
        sheetRoll = r;
        sheetSel = 0;
        sheetFrames = new ArrayList<File>();
        if (r != null) {
            worker.execute(new Runnable() {
                public void run() {
                    final List<File> frames = r.developed();
                    for (File f : frames) loadThumb(f);
                    ui.post(new Runnable() {
                        public void run() {
                            if (sheetRoll == r) sheetFrames = frames;
                            screen.invalidate();
                        }
                    });
                }
            });
        }
        screen.invalidate();
    }

    private void sheetKey(int k) {
        if (sheetRoll == null) return;
        int idx = rolls.indexOf(sheetRoll);
        if (k == K_LEFT && sheetSel > 0) sheetSel--;
        else if (k == K_RIGHT && sheetSel < sheetFrames.size() - 1) sheetSel++;
        else if (k == K_DOWN && idx > 0) openSheet(rolls.get(idx - 1));
        else if (k == K_UP && idx >= 0 && idx < rolls.size() - 1) openSheet(rolls.get(idx + 1));
        else if (k == K_ENTER) {
            if (!sheetFrames.isEmpty()) openView(sheetSel);
            else if (!Roll.DEVELOPED.equals(sheetRoll.state)) startDevelop(sheetRoll);
        }
        screen.invalidate();
    }

    private boolean viewCaption, viewFill;
    private final Runnable hideCaption = new Runnable() {
        public void run() { viewCaption = false; screen.invalidate(); }
    };

    /** Shows a developed frame: its screen-size preview (made once, if it predates previews). */
    private void openView(final int index) {
        mode = VIEW;
        sheetSel = index;
        viewCaption = true;
        ui.removeCallbacks(hideCaption);
        ui.postDelayed(hideCaption, 1800);
        if (viewBitmap != null) { viewBitmap.recycle(); viewBitmap = null; }
        final File f = sheetFrames.get(index), prv = sheetRoll.preview(f);
        worker.execute(new Runnable() {
            public void run() {
                if (!prv.exists()) {
                    prv.getParentFile().mkdirs();
                    FilmCore.makePreview(f.getPath(), prv.getPath());
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                o.inDither = false;
                Bitmap d = BitmapFactory.decodeFile(prv.getPath(), o);
                if (d != null && d.getHeight() < 470) {   // made before previews filled the panel's height
                    d.recycle();
                    FilmCore.makePreview(f.getPath(), prv.getPath());
                    d = BitmapFactory.decodeFile(prv.getPath(), o);
                }
                final Bitmap b = d;
                ui.post(new Runnable() {
                    public void run() {
                        if (mode == VIEW && sheetSel == index) { viewBitmap = b; screen.invalidate(); }
                        else if (b != null) b.recycle();
                    }
                });
            }
        });
        screen.invalidate();
    }

    private void viewKey(int k) {
        if (k == K_LEFT && sheetSel > 0) openView(sheetSel - 1);
        else if (k == K_RIGHT && sheetSel < sheetFrames.size() - 1) openView(sheetSel + 1);
        else if (k == K_ENTER) {   // fit the whole photo, or fill the screen
            viewFill = !viewFill;
            viewCaption = true;
            ui.removeCallbacks(hideCaption);
            ui.postDelayed(hideCaption, 1500);
        }
        screen.invalidate();
    }

    /** The EXIF thumbnail (developed files carry one with the look applied); cached. Worker thread. */
    private void loadThumb(File f) {
        synchronized (thumbs) { if (thumbs.containsKey(f.getPath())) return; }
        Bitmap b = null;
        try {
            byte[] data = new ExifInterface(f.getPath()).getThumbnail();
            if (data != null) b = BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Exception e) {
            // fall back to a heavily subsampled decode below
        }
        if (b == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 32;
            b = BitmapFactory.decodeFile(f.getPath(), o);
        }
        synchronized (thumbs) { thumbs.put(f.getPath(), b); }
    }

    private Bitmap thumb(File f) {
        synchronized (thumbs) { return thumbs.get(f.getPath()); }
    }

    // ------------------------------------------------------------------------------ lab

    private static final String[] LAB_ROWS = { "Photo", "Film", "Highlight", "Shadow", "Grain", "Grain size", "Preview", "Save copy" };

    private void openLab() {
        mode = LAB;
        labRow = 1;
        labPhoto = 0;
        toast(photos.isEmpty() ? "No JPEGs in DCIM" : "");
        requestLabPreview(0);
    }

    private String labValue(int r) {
        switch (r) {
            case 0: return photos.isEmpty() ? "-" : (labPhoto + 1) + " of " + photos.size();
            case 1: return labStock == 0 || labStock > stocks.size() ? "None" : stocks.get(labStock - 1).name;
            case 2: return signed(labHl);
            case 3: return signed(labSh);
            case 4: return labGrain == 0 ? "Off" : String.valueOf(labGrain);
            case 5: return labSize == 1 ? "Fine" : labSize == 2 ? "Medium" : "Large";
            case 6: return labView == 0 ? "Whole" : "100%";
            default: return "";
        }
    }

    private void labKey(int k) {
        if (labBusy) return;
        int n = LAB_ROWS.length;
        if (k == K_UP) labRow = (labRow + n - 1) % n;
        else if (k == K_DOWN) labRow = (labRow + 1) % n;
        else if (k == K_ENTER) { if (labRow == 7) labProcess(); else if (labRow == 6) labChange(1); else labRow = 7; }
        else if (k == K_LEFT) labChange(-1);
        else if (k == K_RIGHT) labChange(1);
        screen.invalidate();
    }

    private void labChange(int dir) {
        switch (labRow) {
            case 0: if (photos.isEmpty()) return; labPhoto = (labPhoto + dir + photos.size()) % photos.size(); break;
            case 1:
                labStock = (labStock + dir + stocks.size() + 1) % (stocks.size() + 1);
                if (labStock > 0) {   // a stock brings its own tone and grain
                    Stock s = stocks.get(labStock - 1);
                    labHl = s.highlight; labSh = s.shadow; labGrain = s.grain; labSize = s.grainSize;
                }
                break;
            case 2: labHl = clamp(labHl + dir, -4, 4); break;
            case 3: labSh = clamp(labSh + dir, -4, 4); break;
            case 4: labGrain = clamp(labGrain + dir, 0, 10); break;
            case 5: labSize = clamp(labSize + dir, 1, 3); break;
            case 6: labView = labView == 0 ? 1 : 0; break;
            default: return;
        }
        // grain only shows in the 100% view
        if ((labRow == 4 || labRow == 5) && labView == 0) return;
        requestLabPreview(labRow == 0 ? 150 : 350);
    }

    /** Render a preview after a short pause, so turning the wheel quickly renders only once. */
    private void requestLabPreview(int delayMs) {
        final int gen = ++previewGen;
        ui.postDelayed(new Runnable() {
            public void run() {
                if (gen != previewGen || photos.isEmpty() || labBusy || mode != LAB) return;
                final File src = photos.get(labPhoto);
                final String lut = labStock > 0 ? stocks.get(labStock - 1).lut.getPath() : null;
                final int[] p = params(src, labHl, labSh, labGrain, labSize);
                final int pm = labView == 0 ? FilmCore.PREVIEW_FIT : FilmCore.PREVIEW_CROP;
                worker.execute(new Runnable() {
                    public void run() {
                        if (gen != previewGen) return;
                        int[] px = new int[420 * 280], size = new int[2];
                        int rc = FilmCore.preview(src.getPath(), lut, p, pm, px, 420, 280, size);
                        final Bitmap bmp = rc == 0 ? Bitmap.createBitmap(px, size[0], size[1], Bitmap.Config.ARGB_8888) : null;
                        final String err = rc == 0 ? null : FilmCore.lastError();
                        ui.post(new Runnable() {
                            public void run() {
                                if (gen != previewGen) { if (bmp != null) bmp.recycle(); return; }
                                if (labPreview != null) labPreview.recycle();
                                labPreview = bmp;
                                if (err != null) toast("Preview: " + err);
                                screen.invalidate();
                            }
                        });
                    }
                });
            }
        }, delayMs);
    }

    private void labProcess() {
        if (photos.isEmpty()) return;
        final File src = photos.get(labPhoto);
        final File dir = Library.outDir(card);
        if (!dir.isDirectory() && !dir.mkdirs()) { toast("Cannot create " + dir.getPath()); return; }
        final File out = Library.outputFor(card, src);
        if (out == null) { toast("Too many copies of " + src.getName()); return; }
        final String lut = labStock > 0 ? stocks.get(labStock - 1).lut.getPath() : null;
        final String lookName = labStock > 0 ? stocks.get(labStock - 1).id : "none";
        final int[] p = params(src, labHl, labSh, labGrain, labSize);
        labBusy = true;
        labProgress = 0;
        powerSave.hold();
        toast("Saving " + src.getName());
        dim(true);
        worker.execute(new Runnable() {
            public void run() {
                int rc = FilmCore.process(src.getPath(), out.getPath(), lut, null, p, new FilmCore.Progress() {
                    public void onProgress(final int pct) {
                        ui.post(new Runnable() { public void run() { labProgress = pct; screen.invalidate(); } });
                    }
                });
                final int[] t = new int[FilmCore.T_COUNT];
                final String msg;
                if (rc == 0) {
                    FilmCore.timing(t);
                    logTiming(src, lookName, p, t);
                    msg = "Saved " + out.getName() + " in " + secs(t[FilmCore.T_TOTAL]) + " s";
                } else {
                    msg = "Failed: " + FilmCore.lastError();
                }
                ui.post(new Runnable() {
                    public void run() {
                        labBusy = false;
                        labProgress = -1;
                        toast(msg);
                        if (t[FilmCore.T_TOTAL] > 0) adaptThreads(t, p[FilmCore.THREADS]);
                        powerSave.release();
                        dim(false);
                        screen.invalidate();
                    }
                });
            }
        });
    }

    /** Renders the newest photo on the card in every stock, for the Load film screen. Cached. */
    private volatile int samplesGen;   // a load bumps it: samples still waiting are skipped

    // ------------------------------------------------------------------------------ responsiveness, measured

    private long startAt, setupMs, beforeUs, firstFrameAt, readyAt, keyAt;
    private volatile String cardReadNote;   // the first card read's steps, for the start line
    private boolean startLogged;
    private final List<Long> keyLags = new ArrayList<Long>();   // a press until the screen showed it, this visit
    private String slowestScreen = "";

    /** How long the process existed before onCreate (the camera starting Latent), from /proc/self/stat. */
    private static long processAge() {
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/self/stat"));
            String s;
            try { s = r.readLine(); } finally { r.close(); }
            String[] f = s.substring(s.lastIndexOf(')') + 2).split(" ");
            long startTicks = Long.parseLong(f[19]);   // field 22: start time, in 1/100 s since boot
            return Math.max(0, SystemClock.elapsedRealtime() - startTicks * 10);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Called after each frame is drawn. */
    private void drawn() {
        long now = SystemClock.uptimeMillis();
        if (firstFrameAt == 0) firstFrameAt = now;
        if (keyAt > 0) {
            long lag = now - keyAt;
            keyAt = 0;
            synchronized (keyLags) { keyLags.add(lag); }
            if (lag >= 150) slowestScreen = modeName();
        }
        if (!startLogged && readyAt > 0) {
            startLogged = true;
            devLog("start: camera on for " + SystemClock.elapsedRealtime() / 1000 + " s, " + (beforeUs >= 0 ? beforeUs + " ms" : "?") + " before Latent ran, fonts and screen " + setupMs
                    + " ms, first frame at " + (firstFrameAt - startAt) + " ms, controls ready at " + (readyAt - startAt) + " ms"
                    + (cardReadNote != null ? "; " + cardReadNote : ""));
        }
    }

    private String modeName() {
        switch (mode) {
            case BACK: return "camera back"; case LOAD: return "Load film"; case DARKROOM: return "darkroom"; case SHEET: return "contact sheet";
            case VIEW: return "viewer"; case LAB: return "Lab"; case SETTINGS: return "menu"; default: return "tests";
        }
    }

    /** One line per visit: how quickly the screen answered presses. */
    private void logKeys() {
        List<Long> l;
        synchronized (keyLags) { l = new ArrayList<Long>(keyLags); keyLags.clear(); }
        if (l.isEmpty()) return;
        java.util.Collections.sort(l);
        devLog("keys: " + l.size() + " presses, screen answered in " + l.get(l.size() / 2) + " ms typically, slowest " + l.get(l.size() - 1) + " ms"
                + (slowestScreen.length() > 0 ? " (" + slowestScreen + ")" : ""));
        slowestScreen = "";
    }

    private void renderSamples() {
        // the newest neutral original; failing that (nothing shot with Latent yet), the newest photo
        final File latest = reference != null ? reference : photos.isEmpty() ? null : photos.get(0);
        if (latest == null) return;
        if (mode == LOAD) renderHero();
        if (latest.getPath().equals(samplesFor) && samples.size() == stocks.size()) return;
        samplesFor = latest.getPath();
        final List<Stock> st = stocks;
        final int gen = samplesGen;
        worker.execute(new Runnable() {
            public void run() {
                // the samples are a nicety: they must never slow the screen or your presses (one core, shared)
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                try { render(); } finally { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT); }
            }

            private void render() {
                for (final Stock s : st) {
                    if (gen != samplesGen) return;
                    int[] px = new int[420 * 280], size = new int[2];
                    int[] p = params(latest, s.highlight, s.shadow, 0, 1);
                    if (FilmCore.preview(latest.getPath(), s.lut.getPath(), p, FilmCore.PREVIEW_FIT, px, 420, 280, size) != 0) continue;
                    final Bitmap b = Bitmap.createBitmap(px, size[0], size[1], Bitmap.Config.ARGB_8888);
                    ui.post(new Runnable() {
                        public void run() {
                            Bitmap old = samples.put(s.id, b);
                            if (old != null && old != b) old.recycle();
                            screen.invalidate();
                        }
                    });
                }
            }
        });
    }

    /** Battery level from the system's sticky broadcast (no receiver to keep registered). */
    private void readBattery() {
        try {
            android.content.Intent i = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return;
            int level = i.getIntExtra("level", -1), scale = i.getIntExtra("scale", 100);
            battery = level < 0 || scale <= 0 ? -1 : level * 100 / scale;
        } catch (Exception e) {
            battery = -1;
        }
    }

    // ------------------------------------------------------------------------------ shared

    private int[] params(File source, int hl, int sh, int grain, int grainSize) {
        int[] p = new int[FilmCore.COUNT];
        p[FilmCore.HIGHLIGHT] = hl;
        p[FilmCore.SHADOW] = sh;
        p[FilmCore.GRAIN] = grain;
        p[FilmCore.GRAIN_SIZE] = grainSize;
        p[FilmCore.QUALITY] = 95;
        p[FilmCore.SEED] = (int) (source.lastModified() ^ source.getName().hashCode());
        p[FilmCore.THREADS] = prefs.getInt("threads", Runtime.getRuntime().availableProcessors());
        return p;
    }

    /** The backlight is a large share of the camera's power draw: turn it right down while developing. */
    private void dim(boolean on) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = on ? 0.05f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }
    private static String signed(int v) { return v > 0 ? "+" + v : String.valueOf(v); }
    private static String secs(int ms) { return String.valueOf((ms + 500) / 1000); }

    /** Appends one line per developed frame to /LATENT/TIMING.TXT. Worker thread. */
    private void logTiming(File src, String look, int[] p, int[] t) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                + " v" + versionName() + " abi=" + Build.CPU_ABI + " " + src.getName() + " look=" + look
                + " grain=" + p[FilmCore.GRAIN] + "/" + p[FilmCore.GRAIN_SIZE] + " threads=" + p[FilmCore.THREADS]
                + " ms: read=" + t[FilmCore.T_READ] + " look=" + t[FilmCore.T_LOOK] + " write=" + t[FilmCore.T_WRITE]
                + " finish=" + t[FilmCore.T_FINISH] + " total=" + t[FilmCore.T_TOTAL] + " cpu=" + t[FilmCore.T_CPU]
                + " " + cpuInfo() + " screen=" + screenSize + "\r\n";
        try {
            FileOutputStream out = new FileOutputStream(new File(Library.home(card), "TIMING.TXT"), true);
            try { out.write(line.getBytes("US-ASCII")); } finally { out.close(); }
        } catch (Exception e) {
            // the log is a convenience; never fail a develop over it
        }
    }

    /** One line per hardware-developed frame in TIMING.TXT. */
    private void logHardware(File src, String look, HardwareDevelop.Print print, long[] m, String extra, long total) {
        appendLog("TIMING.TXT", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                + " v" + versionName() + " " + src.getName() + " -> " + print.file.getName() + " look=" + look + " hardware ms:"
                + " decode=" + m[HardwareDevelop.T_DECODE] + " image=" + m[HardwareDevelop.T_IMAGE] + " develop=" + m[HardwareDevelop.T_DEVELOP]
                + " save=" + m[HardwareDevelop.T_SAVE] + " find=" + m[HardwareDevelop.T_FIND] + " orient=" + m[HardwareDevelop.T_ORIENT]
                + extra + " total=" + total + " (" + print.orientation + ")");
    }

    /** What the darkroom decided and why, in LATENT/DEVELOP.TXT. */
    private void devLog(String what) {
        appendLog("DEVELOP.TXT", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " v" + versionName() + " " + what);
    }

    private void appendLog(String name, String line) {
        try {
            FileOutputStream out = new FileOutputStream(new File(Library.home(card), name), true);
            try { out.write((line + "\r\n").getBytes("UTF-8")); } finally { out.close(); }
        } catch (Exception e) {
            // the log is a convenience; never fail a develop over it
        }
    }

    private volatile String screenSize = "?";
    private float logicalWidth = 640;  // 480 x the panel's real aspect (853 on a 16:9 panel)

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    /** Cores, the cores this process may run on, CPU part and features, for the timing log. */
    private static String cpuInfo() {
        String part = "?", features = "?", allowed = "?";
        int cores = 0;
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/cpuinfo"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    String key = line.substring(0, colon).trim(), value = line.substring(colon + 1).trim();
                    if (key.equals("processor")) cores++;
                    else if (key.equals("CPU part")) part = value;
                    else if (key.equals("Features")) features = value.replace(' ', ',');
                }
            } finally {
                r.close();
            }
            r = new BufferedReader(new FileReader("/proc/self/status"));
            try {
                String line;
                while ((line = r.readLine()) != null) if (line.startsWith("Cpus_allowed_list:")) allowed = line.substring(18).trim();
            } finally {
                r.close();
            }
        } catch (Exception e) {
            // keep the defaults
        }
        return "cores=" + Math.max(cores, Runtime.getRuntime().availableProcessors()) + " allowed=" + allowed
                + " part=" + part + " features=" + features;
    }

    // ------------------------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // route every key here, so no focused view swallows the camera's buttons
        return e.getAction() == KeyEvent.ACTION_DOWN ? onKeyDown(e.getKeyCode(), e) : onKeyUp(e.getKeyCode(), e);
    }

    private static int key(int keyCode, int sc) {
        if (sc == SC_UP || keyCode == KeyEvent.KEYCODE_DPAD_UP) return K_UP;
        if (sc == SC_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return K_DOWN;
        if (sc == SC_LEFT || sc == SC_WHEEL_CCW || sc == SC_DIAL_CCW || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return K_LEFT;
        if (sc == SC_RIGHT || sc == SC_WHEEL_CW || sc == SC_DIAL_CW || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return K_RIGHT;
        if (sc == SC_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) return K_ENTER;
        return 0;
    }

    private boolean shutterDown;   // pressed here: going to shoot once it is released

    private static boolean shutterKey(int sc, int keyCode) {
        return sc == SC_S1 || sc == SC_S2 || keyCode == KeyEvent.KEYCODE_FOCUS || keyCode == KeyEvent.KEYCODE_CAMERA;
    }

    private boolean shootAllowed() {
        return !labBusy && !(mode == PROBE && probeStage == 1);   // in the darkroom it pauses after the frame, then shoots
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        int sc = e.getScanCode();
        if (sc == SC_S1 || keyCode == KeyEvent.KEYCODE_FOCUS) s1Held = true;
        if (mode == PHOTO && vf != null) { vfKey(keyCode, e, true); return true; }
        if (shutterKey(sc, keyCode) && e.getRepeatCount() == 0 && mode == BACK && ready && current != null && !wantsDeveloping(current)
                && viewfinderOn() && shootAllowed() && !Roll.DEVELOPING.equals(current.state)) {
            goShoot();   // the viewfinder opens on the press itself (the camera's own mode had to wait for the release)
            return true;
        }
        if (shutterKey(sc, keyCode)) {
            // half-press the shutter, like a camera: you're shooting. It goes when the button is released, so the
            // camera's shooting mode starts with the shutter up (started mid-press, the A5100 wouldn't focus)
            if (shootAllowed()) shutterDown = true;
            return true;
        }
        int k = key(keyCode, sc);
        if (k == 0 || (k == K_ENTER && e.getRepeatCount() > 0)) return true;
        if (e.getRepeatCount() == 0) keyAt = SystemClock.uptimeMillis();
        lastPressAt = SystemClock.uptimeMillis();
        switch (mode) {
            case BACK: backKey(k); break;
            case LOAD: loadKey(k); break;
            case SHEET: sheetKey(k); break;
            case VIEW: viewKey(k); break;
            case LAB: labKey(k); break;
            case SETTINGS: settingsKey(k); break;
            case PROBE: if (k == K_ENTER && probeStage == 0) runProbe(); break;
            default: break;   // darkroom: only MENU (pause)
        }
        return true;   // swallow everything else (PLAY, trash ...) so the camera stays in the app
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        int sc = e.getScanCode();
        if (sc == SC_S1 || keyCode == KeyEvent.KEYCODE_FOCUS) s1Held = false;
        if (mode == PHOTO && vf != null) { vfKey(keyCode, e, false); return true; }
        if (shutterKey(sc, keyCode)) {
            if (shutterDown && shootAllowed()) {
                // choosing film, a half-press loads it and goes to shoot: one press from an empty camera to shooting
                if (mode == LOAD && !loading && ready && !stocks.isEmpty()) {
                    if (developing != null && devQuiet) { paused = true; loadAfter = true; shootAfterLoad = true; toast("Finishing a photo…"); }
                    else { loadRoll(); goShoot(); }   // mid-load, it goes once the camera is ready
                } else if (mode == DARKROOM) {
                    if (preparing) { /* a moment: the darkroom is getting ready */ }
                    else if (!shootAfterFrame) { paused = true; shootAfterFrame = true; toast("Pausing after this frame, then shooting"); }
                } else goShoot();
            }
            shutterDown = false;
            return true;
        }
        boolean menu = sc == SC_MENU || sc == SC_SK1 || keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BACK;
        if (!menu) return true;
        switch (mode) {
            case BACK: openSettings(); break;
            case LOAD: if (loading) break; if (current != null) mode = BACK; else openSettings(); break;   // MENU: back to the roll, or the menu
            case SETTINGS: goHome(); break;
            case DARKROOM: if (!preparing) { paused = true; toast("Pausing after this frame"); } break;
            case VIEW: mode = SHEET; break;
            case LAB: if (!labBusy) { if (fromSettings) mode = SETTINGS; else goHome(); developWaiting(); } break;
            case PROBE:
                if (liveRun != null) liveRun.close();   // MENU stops the live view test at once
                else if (shootRun != null) shootRun.close();
                else if (restartRun != null) restartRun.close(false);
                else if (lookRun != null) lookRun.close();   // never with a picture on its way (it waits)
                else if (probeStage != 1) { if (fromSettings) mode = SETTINGS; else goHome(); developWaiting(); }
                break;
            default: goHome(); break;
        }
        screen.invalidate();
        return true;
    }

    // ------------------------------------------------------------------------------ drawing

    /** Everything on screen, gathered for Ui.draw (which the Mac preview tool runs too). */
    private UiState state() {
        UiState s = new UiState();
        s.mode = mode;
        s.battery = battery;
        s.toast = status;
        s.width = logicalWidth;
        Stock cur = current == null ? null : Stock.byId(stocks, current.stock);
        switch (mode) {
            case BACK:
                if (!ready) {   // the card is still being read: show the roll as last seen, no controls yet
                    snapshot(s);
                    break;
                }
                s.film = cur == null ? null : film(cur);
                if (current != null) {
                    s.rollNumber = current.number;
                    s.length = current.length;
                    s.exposed = exposed;
                    if (Roll.DEVELOPING.equals(current.state)) s.developed = current.developedCount();
                }
                if (current != null && current.endless()) {
                    s.endless = true;
                    for (int i = 0; i < endlessPrints.size() && i < 8; i++) s.thumbs.add(thumb(endlessPrints.get(i)));
                }
                s.developing = devQuiet && developing != null ? quietLeft : 0;
                s.developLater = Viewfinder.opened;
                s.waiting = vfWaiting;
                break;
            case PHOTO: {
                if (vf != null) vf.fill(s);
                for (Stock st : stocks) s.looks.add(film(st));
                s.lookSel = Math.max(0, stocks.indexOf(cur));
                s.exposed = vfBase + (vf != null ? vf.shots : 0);
                if (current != null) { s.length = current.length; s.endless = current.endless(); }
                s.cardNote = current != null && current.endless() ? "From this frame on" : filmFresh() ? "" : "Loaded for the whole roll";
                break;
            }
            case SETTINGS: {
                List<String[]> rows = settingsRows();
                int sel = Math.min(settingsSel, rows.size() - 1);
                for (String[] r : rows) s.rows.add(new String[] { r[0], r[1] });
                s.rowSel = sel;
                s.footer = settingsFooter(rows.get(sel)[2]);
                break;
            }
            case LOAD:
                for (Stock st : stocks) s.films.add(film(st));
                s.filmSel = stockSel;
                s.lengthSel = lengthSel;
                s.loading = loading;
                s.cancelable = current != null;
                if (!stocks.isEmpty() && reference != null) {   // the full-size picture once it's this film; meanwhile its sample
                    Stock st = stocks.get(Math.min(stockSel, stocks.size() - 1));
                    s.liveImage = heroFor.equals(reference.getPath() + "|" + st.id) ? hero : null;
                }
                s.liveCaption = reference != null || !photos.isEmpty() ? "on" : "";   // there is a photo to show looks on
                break;
            case DARKROOM: {
                Roll r = developing;
                if (r == null && preparing && prepared != null) {   // getting ready: the film and the frames to come
                    s.devPreparing = true;
                    s.devTotal = Math.max(1, prepared.getIntExtra(Relaunch.EXTRA_TOTAL, 1));
                    String title = prepared.getStringExtra(Relaunch.EXTRA_FILM);
                    if (title != null) {
                        s.film = new UiState.Film();
                        s.film.title = title;
                        s.film.iso = prepared.getStringExtra(Relaunch.EXTRA_ISO) == null ? "" : prepared.getStringExtra(Relaunch.EXTRA_ISO);
                        s.film.accent = prepared.getIntExtra(Relaunch.EXTRA_ACCENT, Ui.RED);
                        s.film.body = prepared.getIntExtra(Relaunch.EXTRA_BODY, 0);
                        s.film.band = prepared.getIntExtra(Relaunch.EXTRA_BAND, 0);
                    }
                    break;
                }
                if (r == null) break;
                Stock st = Stock.byId(stocks, r.stock);
                s.film = st == null ? null : film(st);
                s.devFrame = devFrame;
                s.devTotal = r.frames.size();
                s.devDone = r.developedCount();
                s.devFramePct = devPct / 100f;
                long per = msPerFrame();
                // frames already developed, plus how far the one in hand is (counted once: when it has just
                // finished it is both "developed" and "at 100%" until the next frame starts)
                boolean inHandDone = devFrame < s.devTotal && r.developed(devFrame) != null;
                float done = Math.min(s.devDone - (inHandDone ? 1 : 0) + (inHandDone ? 1f : s.devFramePct), s.devTotal);
                progressShown = Math.max(progressShown, done / Math.max(1, s.devTotal));   // never goes backwards
                s.devProgress = progressShown;
                s.paused = paused;
                s.devLeft = timeLeft((long) ((s.devTotal - done) * per));
                s.devPrint = devFrame < r.frames.size() ? thumb(r.source(devFrame)) : null;
                for (int i = 0; i < s.devTotal; i++) {
                    File d = r.developed(i);
                    s.devStrip.add(d != null ? thumb(d) : null);
                }
                break;
            }
            case SHEET:
            case VIEW: {
                Stock st = sheetRoll == null ? null : Stock.byId(stocks, sheetRoll.stock);
                if (sheetRoll != null) {
                    s.film = film(st);
                    if (s.film == null) { s.film = new UiState.Film(); s.film.title = sheetRoll.stock; s.film.accent = Ui.INK; }
                    s.rollNumber = sheetRoll.number;
                    s.sheetUndeveloped = !Roll.DEVELOPED.equals(sheetRoll.state);
                    for (File f : sheetFrames) s.thumbs.add(thumb(f));
                }
                s.sheetSel = sheetSel;
                s.sheetTotal = sheetFrames.size();
                s.filingLeft = filingLeft;
                s.viewImage = viewBitmap;
                s.viewCaption = viewCaption;
                s.viewFill = viewFill;
                break;
            }
            case PROBE:
                s.live = liveOn;
                s.liveNote = liveNote;
                s.probeTitle = lookTest ? "Live look test" : restartTest ? "Restart test" : shootTest ? "Shooting test" : liveTest ? "Live view test" : speedTest ? "Speed test" : settingsCheck ? "Camera settings check" : hardwareTest ? "Hardware develop test" : "Camera roll test";
                s.probeSteps = lookTest ? LiveLookTest.STEPS : restartTest ? RestartTest.STEPS : shootTest ? ShootingTest.STEPS : liveTest ? LiveViewTest.STEPS : speedTest ? SpeedTest.STEPS : settingsCheck ? SettingsCheck.STEPS : hardwareTest ? HardwareTest.STEPS : Probe.STEPS;
                s.probePlan = lookTest ? Ui.LOOK_PLAN : restartTest ? Ui.RESTART_PLAN : shootTest ? Ui.SHOOT_PLAN : liveTest ? Ui.LIVE_PLAN : speedTest ? Ui.SPEED_PLAN : settingsCheck ? Ui.SETTINGS_PLAN : hardwareTest ? Ui.HARDWARE_PLAN : Ui.PROBE_PLAN;
                s.probeFooter = lookTest ? Ui.LOOK_FOOTER : restartTest ? Ui.RESTART_FOOTER : shootTest ? Ui.SHOOT_FOOTER : liveTest ? Ui.LIVE_FOOTER : speedTest ? Ui.SPEED_FOOTER : settingsCheck ? Ui.SETTINGS_FOOTER : hardwareTest ? Ui.HARDWARE_FOOTER : Ui.PROBE_FOOTER;
                s.probeStage = probeStage;
                s.probeStatus = probeStatus.clone();
                s.probeDetail = probeDetail.clone();
                break;
            case LAB:
                s.labImage = labPreview;
                s.labCaption = photos.isEmpty() ? "" : photos.get(labPhoto).getName();
                s.labRows = LAB_ROWS;
                s.labValues = new String[LAB_ROWS.length];
                for (int i = 0; i < LAB_ROWS.length; i++) s.labValues[i] = labValue(i);
                s.labRow = labRow;
                s.labProgress = labProgress;
                break;
            default:
                break;
        }
        return s;
    }

    private UiState.Film film(Stock st) {
        if (st == null) return null;
        UiState.Film f = new UiState.Film();
        f.title = st.title;
        f.iso = st.iso;
        f.tagline = st.tagline;
        f.body = st.body;
        f.band = st.band;
        f.accent = st.accent;
        f.sample = samples.get(st.id);
        return f;
    }

    private final class Screen extends View {
        private final AndroidGfx gfx;

        Screen(Context c) {
            super(c);
            setFocusable(true);
            gfx = new AndroidGfx(c.getAssets());
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            float aspect = Display.aspect();
            logicalWidth = 480f * (aspect > 0 ? aspect : (float) w / Math.max(1, h));
            android.util.DisplayMetrics m = getResources().getDisplayMetrics();
            screenSize = w + "x" + h + "/display=" + m.widthPixels + "x" + m.heightPixels + "/panel=" + aspect;
        }

        @Override
        protected void onDraw(Canvas c) {
            long t = SystemClock.uptimeMillis();
            c.save();
            c.scale(getWidth() / logicalWidth, getHeight() / 480f);
            gfx.begin(c);
            UiState s = state();
            if (mode == PHOTO && vf != null) vf.layer(s);   // the type over the picture comes in the frames themselves
            Ui.draw(gfx, s);
            c.restore();
            drawn();
            if (mode == PHOTO && vf != null) vf.drew(SystemClock.uptimeMillis() - t);
        }
    }
}
