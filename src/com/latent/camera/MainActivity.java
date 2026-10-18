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

    private volatile int battery = -1;   // percent
    // the rest of the battery's report, for the logs: volts in mV and temperature in tenths of a degree (as
    // the system gives them; -1 / MIN_VALUE unknown), and whether it is on a charger
    private volatile int batteryMv = -1, batteryTemp = Integer.MIN_VALUE;
    private volatile boolean batteryPlugged;
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
    private long devStarted, devStartedUp;   // when the develop started: the clock (for "developed in"), and uptime
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
    private volatile Roll sheetRoll;
    private List<File> sheetFrames = new ArrayList<File>();
    private volatile int sheetSel;   // (the viewer's decodes read it on the worker)
    private Bitmap viewBitmap;
    /**
     * Thumbnails by path (160x120, ~77 KB each), the least recently drawn dropped past THUMBS_MAX: v0.44's
     * map only grew (a 36-frame develop added ~5.5 MB). A dropped one is never recycled: a screen being
     * drawn may still hold it; the collector frees it. Guarded by itself.
     */
    private static final int THUMBS_MAX = 64;
    private final Map<String, Bitmap> thumbs = Slots.recent(THUMBS_MAX);

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
        // no window background: every screen paints its own, and on each live frame the theme's black was
        // filled under the picture only to be covered (v0.38.2)
        getWindow().setBackgroundDrawable(null);
        screen = new Screen(this);   // loads the fonts
        light = new Backlight(SystemClock.uptimeMillis());
        setupMs = SystemClock.uptimeMillis() - startAt;
        // live view (the camera's own picture) sits behind Latent's drawing, hidden until it's used
        liveSurface = new android.view.SurfaceView(this);
        // Android 2.3: the camera only draws live view on a surface marked as fed by the camera itself
        liveSurface.getHolder().setType(android.view.SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        liveSurface.setVisibility(View.GONE);
        root = new android.widget.FrameLayout(this);
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
                work(true);
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
        light.shown(SystemClock.uptimeMillis());   // back on screen: lit (the darkroom keeps its own light)
        applyLight();
        if (card != null && developing == null) refresh(null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(shootWhenSettled);   // left some other way: don't jump to shooting later
        shootPending = false;
        if (liveRun != null) liveRun.close();    // never keep the camera when Latent isn't on screen
        if (screenRun != null) screenRun.stop();
        if (restartRun != null) restartRun.close(false);
        final String census = unmappedLine();   // the buttons pressed this visit that Latent doesn't use
        final Runnable keysLog = new Runnable() { public void run() { logKeys(); if (census != null) devLog(census); } };
        worker.execute(keysLog);
        Display.fullColour(false);
        if (developing != null) paused = true;   // finish the frame in hand, then stop; resumable later
        holdAfter = false;   // a hold kept for filing ends here too
        ui.removeCallbacks(releaseAfter);
        powerSave.release();
        // Once the camera has been opened here, Sony's image memory stays taken until this process ends.
        // Leaving a camera app only pauses it (v0.32.0: never stopped, never destroyed, and the next open
        // resumed this same process with nothing developing), so Latent closes itself now: the camera is
        // given back (after any picture still storing), then the process ends, and the next open develops.
        if (Viewfinder.opened) {
            // how long since the last press: a long quiet means the camera's own power-save timer sent Latent away
            long quiet = anyKeyAt > 0 ? (SystemClock.uptimeMillis() - anyKeyAt) / 1000 : -1;
            readBattery();
            devLog("leaving: the camera was opened, so Latent closes itself (its image memory comes back only then); "
                    + (quiet >= 0 ? quiet + " s since the last press" : "no press this visit") + ", " + batteryNote());
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
        // written now: a commit waits for every write left behind (apply) too, which the process ending would lose
        try { saveSnapshot(true); } catch (Throwable ignored) { }
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
                final List<File> prints = new ArrayList<File>();   // their thumbnails load once the controls are ready (loadPrints)
                if (inCamera != null && inCamera.endless()) {
                    List<File> dev = inCamera.developed();
                    for (int i = dev.size() - 1; i >= 0 && prints.size() < 8; i--) prints.add(dev.get(i));
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
                        if (mode == BACK && current == null) openLoad(false);   // nothing in the camera: choosing film is home
                        final long filingFrom = SystemClock.uptimeMillis();
                        filer.execute(new Runnable() {
                            public void run() {
                                fileBacklog(ro);
                                // filed (or it gave way to shooting): a power-save hold kept for it goes
                                ui.post(new Runnable() { public void run() { filedSince(filingFrom); } });
                            }
                        });
                        if (then != null) then.run();
                        boolean back = backFromCamera;
                        backFromCamera = false;
                        // finished while you were out (or a develop was left part-way): straight to the darkroom
                        if (back && current != null && developing == null && (mode == BACK || preparing) && wantsDeveloping(current)) startDevelop(current);
                        else developWaiting();
                        if (preparing && developing == null && !restarting) unprepare();   // nothing to develop after all
                        // the pictures go to the worker last, so nothing goes ahead of a develop starting now
                        // (the darkroom covers them; the next card read, after it, asks again)
                        if (developing == null) {
                            if (mode == LOAD && !loading) { renderHero(); renderSamples(); }
                            loadPrints(prints);
                        }
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
    private void openLoad() { openLoad(true); }

    /** render: its pictures now (the card read leaves them until it knows no develop starts first). */
    private void openLoad(boolean render) {
        if (stocks.isEmpty()) { toast("No looks in LATENT/LUTS"); return; }
        if (mode != LOAD) {   // opens on the film and length you chose last
            String last = prefs.getString("lastStock", "");
            for (int i = 0; i < stocks.size(); i++) if (stocks.get(i).id.equals(last)) stockSel = i;
            lengthSel = Math.max(0, Math.min(LENGTHS.length - 1, prefs.getInt("lastLength", 1)));
        }
        stockSel = Math.min(stockSel, stocks.size() - 1);
        mode = LOAD;
        if (render) {
            renderHero();      // the full-size picture first: it's what the screen shows
            renderSamples();   // then the small stand-ins, this film's and its neighbours'
        }
    }

    /**
     * The endless roll's newest prints on the camera back: their thumbnails, after the controls are ready
     * (v0.44 loaded eight, two EXIF reads each, before a half-press could shoot).
     */
    private void loadPrints(final List<File> prints) {
        if (prints.isEmpty()) return;
        worker.execute(new Runnable() {
            public void run() {
                boolean added = false;
                for (File f : prints) added |= loadThumb(f);
                if (added) ui.post(new Runnable() { public void run() { if (mode == BACK && endlessPrints == prints) screen.invalidate(); } });
            }
        });
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
                        if (mode == LOAD) screen.invalidate();   // only Choose film shows it
                    }
                });
            }
        });
    }

    private int neutralBusy;           // settings being changed right now (worker)

    // ------------------------------------------------------------------------------ settings

    private int settingsSel = 1;       // a row of settingsRows(); headings are passed over
    private int settingsPage;          // Ui.PAGE_MENU, or Ui.PAGE_CHECKS (the camera tests, one row on the first page)
    private boolean fromSettings;      // the test or Lab on screen was opened from Settings: MENU goes back there

    /** The menu's rows on this page: {label, value, action, why not} (Ui.menuRows, which the Mac previews show too). */
    private List<String[]> settingsRows() { return Ui.menuRows(settingsPage, menuFacts()); }

    /** What the menu's rows depend on, now. */
    private Ui.Menu menuFacts() {
        Ui.Menu m = new Ui.Menu();
        m.roll = current != null;
        m.developNow = current != null && Roll.LOADED.equals(current.state) && !current.endless() && exposed > 0 && exposed < current.length;
        m.changeFilm = current != null && !Roll.DEVELOPING.equals(current.state);
        m.endless = current != null && current.endless();
        m.exposed = exposed;
        m.rolls = !rolls.isEmpty();   // (from the last card read: asking each roll for its prints would read the card every redraw)
        m.viewfinder = viewfinderOn();
        m.focusFirst = focusFirst();
        m.liveLook = liveLook();
        m.threeCores = developCores() > 1;
        m.threeCoresOk = prefs.getBoolean("multicoreOk", false);
        m.neutral = neutralOn();
        m.looks = !stocks.isEmpty();
        m.cameraBusy = vf != null;   // the viewfinder holds the camera until Latent is left
        m.developing = developing != null;
        m.version = versionName();   // asked of the system once, then kept
        return m;
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
        settingsPage = Ui.PAGE_MENU;
        List<String[]> rows = settingsRows();
        settingsSel = Math.max(1, Math.min(settingsSel, rows.size() - 1));
        if (rows.get(settingsSel)[1].length() == 0) settingsSel++;
    }

    /** A page of the menu, on its first row (or on 'action' when it's there). */
    private void settingsPage(int page, String action) {
        settingsPage = page;
        List<String[]> rows = settingsRows();
        settingsSel = 1;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i)[2].equals(action)) settingsSel = i;
        while (settingsSel < rows.size() - 1 && rows.get(settingsSel)[1].length() == 0) settingsSel++;
    }

    /** MENU in the menu: from Camera checks back to the first page, from there home. */
    private void settingsBack() {
        if (settingsPage != Ui.PAGE_MENU) settingsPage(Ui.PAGE_MENU, "checks");
        else goHome();
    }

    private void settingsKey(int k) {
        List<String[]> rows = settingsRows();
        int n = rows.size(), step = k == K_UP || k == K_LEFT ? -1 : k == K_DOWN || k == K_RIGHT ? 1 : 0;
        if (step != 0) {
            int i = settingsSel + step;
            while (i >= 0 && i < n && rows.get(i)[1].length() == 0) i += step;   // pass over headings
            if (i >= 0 && i < n) settingsSel = i;
        } else if (k == K_ENTER) {
            String[] row = rows.get(Math.min(settingsSel, n - 1));
            String action = row[2];
            if (row[3].length() > 0) {   // grey: it can't act now, and the footer already says why
                toast(row[3]);
            } else if (action.equals("cores")) {   // develop on three cores, or on Latent's own only
                prefs.edit().putInt("cores", developCores() > 1 ? 1 : 3).apply();   // apply: written behind, not on this thread
            } else if (action.equals("neutral")) {
                setNeutral(!neutralOn());
            } else if (action.equals("viewfinder")) {
                prefs.edit().putBoolean("viewfinder", !viewfinderOn()).apply();
            } else if (action.equals("focusFirst")) {
                prefs.edit().putBoolean("focusFirst", !focusFirst()).apply();
            } else if (action.equals("liveLook")) {
                if (vf != null) toast("It changes the next time the viewfinder opens");
                prefs.edit().putBoolean("liveLook2", !liveLook()).apply();
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
                else { openSheet(r); sheetFrom = SETTINGS; }
            } else if (action.equals("checks")) {
                settingsPage(Ui.PAGE_CHECKS, "");
            } else if (action.equals("settingsCheck") || action.equals("speedTest") || action.equals("devTest") || action.equals("liveTest") || action.equals("shootTest") || action.equals("restartTest") || action.equals("lookTest") || action.equals("screenTest")) {
                mode = PROBE;
                probeStage = 0;
                hardwareTest = false;
                settingsCheck = action.equals("settingsCheck");
                speedTest = action.equals("speedTest");
                devTest = action.equals("devTest");
                liveTest = action.equals("liveTest");
                shootTest = action.equals("shootTest");
                restartTest = action.equals("restartTest");
                lookTest = action.equals("lookTest");
                screenTest = action.equals("screenTest");
                fromSettings = true;
            }
        }
        redrawKey();
    }

    /**
     * The switch: off, the camera keeps your own settings during a roll. Changing it mid-roll changes the
     * camera at once (and shooting waits the usual few seconds after, like loading a roll).
     */
    private void setNeutral(final boolean on) {
        prefs.edit().putBoolean("neutral", on).apply();   // the switch only: the camera's own state is on the card (NeutralSettings)
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
        return prefs.getBoolean("multicoreOk", false) ? Math.max(1, Math.min(3, prefs.getInt("cores", 3))) : 1;   // never core 0: the camera's (v0.42.2)
    }

    // camera roll test (phase 1)
    private int probeStage;
    private boolean hardwareTest;      // phase 2: develop one photo through the camera's hardware
    private boolean settingsCheck;     // read the camera's white balance and Creative Style (read only)
    private boolean speedTest;         // time the look's inner loops on this chip; keep the fastest accurate one
    private boolean devTest;           // time the darkroom's develop one idea at a time; keep a faster way only if the picture is identical
    private boolean liveTest;          // can live view be shown with a film look on it
    private boolean shootTest;         // can Latent focus and shoot itself, and get live frames
    private boolean restartTest;       // can Latent give the camera back and start again, with the image memory back
    private RestartTest restartRun;    // while it runs (it holds the camera)
    private boolean lookTest;          // can the camera store pictures with live frames running (Auto Review off)
    private LiveLookTest lookRun;      // while it runs (it holds the camera)
    private ShootingTest shootRun;     // while it runs (it holds the camera)
    private android.view.SurfaceView liveSurface;
    private LiveViewTest liveRun;      // while it runs (it holds the camera)
    private android.widget.FrameLayout root;
    private boolean screenTest;        // how fast the screen takes the viewfinder's picture, four ways (no camera)
    private ScreenTest screenRun;      // while it runs
    private volatile boolean screenHook;
    private boolean liveOn;
    private String liveNote = "";
    private int[] probeStatus = new int[0];
    private String[] probeDetail = new String[0];

    private void runProbe() {
        final String[] steps = screenTest ? ScreenTest.STEPS : lookTest ? LiveLookTest.STEPS : restartTest ? RestartTest.STEPS : shootTest ? ShootingTest.STEPS : liveTest ? LiveViewTest.STEPS : devTest ? DevelopTest.STEPS : speedTest ? SpeedTest.STEPS : settingsCheck ? SettingsCheck.STEPS : hardwareTest ? HardwareTest.STEPS : Probe.STEPS;
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
        if ((hardwareTest || speedTest || devTest) && stock == null) { toast("No film looks on the card"); probeStage = 0; return; }
        if (devTest && Viewfinder.opened) {   // the camera has Sony's image memory until Latent closes
            toast("The camera has the image memory. Leave Latent and open it again to run this test");
            probeStage = 0;
            return;
        }
        if (devTest && (developing != null || filing || HardwareDevelop.hung())) {   // one user of Sony's image memory at a time
            toast(HardwareDevelop.hung() ? "A camera call is still busy. Restart Latent first" : "Finishing a photo first. Try again in a moment");
            probeStage = 0;
            return;
        }
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
        if (screenTest) {   // the screen only, on this thread, in its 16-bit mode as while shooting
            if (vf == null) Display.fullColour(false);
            screenRun = new ScreenTest(this, card, prefs, listener, new ScreenTest.Host() {
                public android.widget.FrameLayout root() { return root; }
                public View screen() { return screen; }
                public void windowFormat(int format) { getWindow().setFormat(format); }
                public void hook(boolean on) { screenHook = on; }
                public void finished() {
                    screenRun = null;
                    screenHook = false;
                    if (vf == null) Display.fullColour(true);
                    probeStage = 2;
                    screen.invalidate();
                }
            });
            screenRun.start();
            screen.invalidate();
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
        if (hardwareTest || speedTest) work(true);
        if (speedTest || devTest) holdPower();
        worker.execute(new Runnable() {
            public void run() {
                if (devTest) {
                    new DevelopTest(MainActivity.this, card, stock, prefs, developCores(), versionName(), listener).run();
                } else if (speedTest) {
                    new SpeedTest(card, stock, prefs, listener).run();
                } else if (settingsCheck) {
                    new SettingsCheck(card, listener).run();
                } else if (hardwareTest) {
                    // its progress redraws the screen at most twice a second, not once per percent
                    new HardwareTest(MainActivity.this, card, stock, listener, new Throttled(500) {
                        void show(int pct) { if (probeStatus.length > 4 && probeStatus[4] == Probe.RUN) { probeDetail[4] = "developing… " + pct + "%"; screen.invalidate(); } }
                    }).run();
                } else {
                    new Probe(MainActivity.this, card, ui, listener).run();
                }
                ui.post(new Runnable() { public void run() { probeStage = 2; work(false); if (speedTest || devTest) powerSave.release(); screen.invalidate(); } });
            }
        });
        screen.invalidate();
    }

    /** The loaded roll as last seen, so the camera back appears instantly on the next launch. */
    private void saveSnapshot() { saveSnapshot(false); }

    /**
     * now: written before this returns (just before the process ends itself). Otherwise written behind
     * (apply): it's a hint for the next start, and a synchronous write with its fsync on the UI thread
     * stalled the viewfinder's frame grabbing around each picture and wheel click. Android 2.3 finishes
     * writes left behind when Latent is paused; killNow commits first.
     */
    private void saveSnapshot(boolean now) {
        SharedPreferences.Editor e = prefs.edit();
        Stock st = current == null ? null : Stock.byId(stocks, current.stock);
        if (st == null) e.remove("snap");
        else e.putString("snap", st.title + "|" + st.iso + "|" + st.body + "|" + st.band + "|" + st.accent + "|"
                + current.number + "|" + exposed + "|" + current.length + "|" + current.state);
        if (now) e.commit(); else e.apply();
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

    /**
     * While a shoot waits (shootPending), the camera back counts the wait down ("Shooting in 4 s"): a redraw as each
     * second turns. Only reads the time since the last settings write; goShoot's own timing is untouched.
     */
    private final Runnable settleTick = new Runnable() {
        public void run() {
            if (!shootPending) return;
            screen.invalidate();
            long since = NeutralSettings.sinceLastWrite(), left = since < 0 ? 0 : SHOOT_SETTLE_MS - since;
            ui.postDelayed(this, left > 0 ? left % 1000 + 20 : 300);
        }
    };

    /** Seconds until a waiting shoot can go (0: a moment more, the camera or the card busy), or -1 when none waits. */
    private int readyIn() {
        if (!shootPending) return -1;
        long since = NeutralSettings.sinceLastWrite();
        if (loading || neutralBusy > 0 || refreshing > 0 || filing || since < 0 || since >= SHOOT_SETTLE_MS) return 0;
        return (int) ((SHOOT_SETTLE_MS - since + 999) / 1000);
    }

    private void goShoot() {
        if (developing != null && devQuiet) {   // the photo in hand finishes first (a frame half-written is wasted)
            paused = true;
            shootAfterFrame = true;
            toast("Finishing a photo…");
            return;
        }
        if (neutralBusy > 0 || refreshing > 0 || filing) {   // never start shooting while settings are being written (or the card read, or an original filed): check again shortly
            if (!shootPending) { shootPending = true; ui.removeCallbacks(settleTick); ui.post(settleTick); }   // the camera back counts it down
            ui.removeCallbacks(shootWhenSettled);
            ui.postDelayed(shootWhenSettled, 300);
            return;
        }
        long since = NeutralSettings.sinceLastWrite();
        if (loading || (since >= 0 && since < SHOOT_SETTLE_MS)) {   // one press is enough: it goes as soon as it's safe
            if (!shootPending) { shootPending = true; ui.removeCallbacks(settleTick); ui.post(settleTick); }   // the camera back counts it down
            ui.removeCallbacks(shootWhenSettled);
            ui.postDelayed(shootWhenSettled, loading ? 300 : SHOOT_SETTLE_MS - since);
            return;
        }
        // the power-save timer: yours goes back before the camera is Latent's (a hold kept for filing ends here),
        // and that write gets a moment before shooting, as the card read after a develop always gave it
        if (holdAfter) releaseHold();
        long power = PowerSave.sinceLastWrite();
        if (power >= 0 && power < POWER_SETTLE_MS) {
            if (!shootPending) { shootPending = true; toast("Setting up the camera…"); }
            ui.removeCallbacks(shootWhenSettled);
            ui.postDelayed(shootWhenSettled, POWER_SETTLE_MS - power);
            return;
        }
        shootPending = false;
        if (viewfinderOn() && current != null && Roll.LOADED.equals(current.state)) {
            openViewfinder();
            light.shown(SystemClock.uptimeMillis());
            applyLight();   // the viewfinder's light (the camera's own) now, before the camera opens: never while it's Latent's
            return;
        }
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
            if (!current.endless() && exposed >= current.length) { toast("Roll finished. Press \u2299 to develop."); return; }
            mode = PHOTO;
            readBattery();
            vf.show();   // frames again at once; its surface back on the screen with the first of them
            if (s1Held) vf.halfDown();
            screen.invalidate();
            return;
        }
        if (!current.endless() && exposed >= current.length) { toast("Roll finished. Press \u2299 to develop."); return; }
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
            public void changed() { vfChanged(); }
            public void frame() {   // a new live frame: only the picture's area (or its own surface, under the last state)
                if (mode != PHOTO || vf == null) return;   // hidden behind MENU: nothing of it is on screen
                if (vf.onSurface()) { presentPicture(false); return; }
                invalidatePicture();
            }
            public void animate() {   // what's over the picture moves: a fresh state over it
                if (mode != PHOTO || vf == null) return;
                if (vf.onSurface()) { presentPicture(true); return; }
                invalidatePicture();
            }
            public void pictureGone() { dropPictureSurface(); screen.invalidate(); }
            public void picturePlace(boolean on) { placePicture(on); }
            public void live() { if (s1Held && vf != null) vf.halfDown(); }   // still half-pressed from the camera back: focus now
            public void shot(File photo, String film) { vfShot(photo, film); }
            public void hud(boolean on) { screen.setVisibility(on ? View.VISIBLE : View.INVISIBLE); }
            public void stuck(String what) {
                screen.setVisibility(View.VISIBLE);
                devLog("viewfinder: " + what);
                restarting = false;
                relaunchWhenClosed = false;
                unprepare();
                // nothing else can happen until the camera is switched off: the viewfinder says so, full screen
                // (UiState.fatal; it was a toast that never cleared), and MENU no longer leaves it
                mode = PHOTO;
                status = "";
                ui.removeCallbacks(clearToast);
                screen.invalidate();
                if (vf != null && vf.onSurface()) presentPicture(true);
            }
            public void closed(String why) {
                flushFilmSave();   // the wheel's last film onto the card first
                vf = null;
                dropPictureSurface();   // the camera given back first
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
        // the roll's film and the wheel's next ones read ahead into the image core's film cache
        int i = Math.max(0, stocks.indexOf(st)), n = stocks.size();
        if (st != null && n > 0) vf.prewarm(st.lut.getPath(), stocks.get((i + 1) % n).lut.getPath(), stocks.get((i + n - 1) % n).lut.getPath());
        // the picture's area on screen in real pixels: the 3:2 area (720 x 480 of the design space)
        final int liveW = Math.round(720f * screen.getWidth() / logicalWidth);
        if (screen.getWidth() > 0) vf.setLiveSize(liveW, screen.getHeight());
        final Viewfinder opening = vf;
        final Runnable open = new Runnable() {
            public void run() {
                if (vf != opening || vf.opened()) return;
                vf.open();
                screen.invalidate();
            }
        };
        // The picture on a 16-bit surface of its own is the proven way (the Screen test chose it, 31.5 frames a second
        // against 20.9; on the camera since v0.43.1), so it is the default in code, not only the Screen test's pref:
        // that only overrides it with Latent's own screen (0, or 1: a 16-bit window, which does nothing on this body).
        // Its quarter-size surface (4) isn't a way the viewfinder has, so it gets the whole surface too.
        int path = prefs.getInt(ScreenTest.P_PATH, 2);
        if (path != 0 && path != 1 && liveLook() && liveW > 0) {
            // made before the camera opens, which waits for it (at most 2 s, then the picture goes through Latent's screen)
            try {
                final android.view.SurfaceView pv = new android.view.SurfaceView(this);
                pv.setZOrderOnTop(true);
                pv.getHolder().setFormat(android.graphics.PixelFormat.RGB_565);
                pv.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                    public void surfaceCreated(android.view.SurfaceHolder holder) {
                        if (vf == opening && pictureView == pv) vf.pictureSurface(holder);
                        ui.removeCallbacks(open);
                        ui.post(open);
                    }
                    public void surfaceChanged(android.view.SurfaceHolder holder, int format, int w, int h) { }
                    public void surfaceDestroyed(android.view.SurfaceHolder holder) { if (vf != null) vf.pictureSurface(null); }
                });
                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(liveW, screen.getHeight());
                pictureLeft = (screen.getWidth() - liveW) / 2;
                lp.leftMargin = pictureLeft;
                lp.gravity = android.view.Gravity.LEFT | android.view.Gravity.TOP;
                pictureView = pv;
                pictureOnScreen = true;
                root.addView(pv, lp);
                ui.postDelayed(open, 2000);
                return;
            } catch (Throwable e) {
                devLog("viewfinder: no picture surface (" + Probe.why(e) + ")");
                dropPictureSurface();
            }
        }
        screen.post(open);
    }

    private android.view.SurfaceView pictureView;   // the viewfinder's picture surface, while there is one
    private int pictureLeft;                        // its place on the screen
    private boolean pictureOnScreen;                // there, or moved off it (hidden, the look off)
    private final Runnable dropSurface = new Runnable() {
        public void run() {
            if (mode == PHOTO) return;
            if (vf != null) placePicture(false);   // the viewfinder kept open behind: moved off the screen, kept
            else dropPictureSurface();
            screen.invalidate();
        }
    };

    private void dropPictureSurface() {
        if (pictureView == null) return;
        if (vf != null) vf.pictureSurface(null);
        try { root.removeView(pictureView); } catch (Throwable ignored) { }
        pictureView = null;
        pictureOnScreen = false;
    }

    /**
     * The picture's surface on the screen, or moved off it to the right (a SurfaceView's window may lie outside
     * the screen, FLAG_LAYOUT_NO_LIMITS; nothing of it is then shown or composed), so it is kept, not made again,
     * with the camera open. Its own scroll is nudged too: a SurfaceView moves its window when the view tree
     * reports a scroll (as one in a scrolling list follows it), so the move doesn't wait on it being drawn, which a
     * view outside its parent may not be.
     */
    private void placePicture(boolean on) {
        on &= mode == PHOTO;
        if (pictureView == null || on == pictureOnScreen) return;
        try {
            android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) pictureView.getLayoutParams();
            lp.leftMargin = on ? pictureLeft : screen.getWidth() + 64;
            pictureView.setLayoutParams(lp);
            pictureView.scrollTo(on ? 0 : 1, 0);   // its content isn't drawn by the view, so this only reports the move
            pictureOnScreen = on;
        } catch (Throwable e) {   // it can't move: away with it (the picture then goes through Latent's screen)
            devLog("viewfinder: picture surface not moved (" + Probe.why(e) + ")");
            dropPictureSurface();
        }
    }

    /** The picture's area of Latent's screen drawn again (the picture through it, not on its own surface). */
    private void invalidatePicture() {
        int w = screen.getWidth(), side = Math.round((logicalWidth - 720) / 2 * w / logicalWidth);
        if (w <= 0 || side <= 0) screen.invalidate(); else screen.invalidate(side - 1, 0, w - side + 1, screen.getHeight());
    }

    /**
     * Something in the viewfinder changed (UI thread). On its own surface, a fresh state goes to the presenter,
     * and Latent's screen (the side bars) is drawn again only when what it shows changed (Ui.windowKey: focus
     * events, the lamp, notes and shots mostly leave the bars as they were); else the whole screen.
     */
    private void vfChanged() {
        if (mode != PHOTO || vf == null) return;   // hidden behind MENU: nothing of it is on screen
        if (!vf.onSurface()) { screen.invalidate(); return; }
        UiState s = presentPicture(true);
        if (!Ui.windowKey(s).equals(windowDrawn)) screen.invalidate();
    }

    private String windowDrawn;   // Ui.windowKey of what Latent's screen last drew of the viewfinder (null: something else)
    private UiState presented;    // the state the presenter has

    /**
     * The viewfinder's state to its picture surface: what moves over the newest picture. UI thread. Without
     * rebuild, the newest picture goes up under the state it has, unless that no longer fits (v0.44 built a
     * whole state for every frame). The state handed over is never changed afterwards.
     */
    private UiState presentPicture(boolean rebuild) {
        if (!rebuild && presented != null && vf.presentAgain()) return presented;
        UiState s = state();
        vf.layer(s);
        vf.present(s);
        presented = s;
        return s;
    }

    /** One picture stored (photo is its file, or null when the catalogue didn't name it). */
    private void vfShot(final File photo, final String shotFilm) {
        flushFilmSave();   // a film the wheel chose is the roll's on the card before this frame is noted
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
        if (mode == PHOTO && vf != null) vfChanged(); else screen.invalidate();   // the counter: in the type over the picture
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
        work(true);
        screen.invalidate();
        if (vf != null) { relaunchWhenClosed = true; vf.close(null); }
        else relaunch();
    }

    /** The darkroom wasn't reached after all (the restart failed, or nothing was left to develop): the camera back. */
    private void unprepare() {
        if (!preparing) return;
        preparing = false;
        if (mode == DARKROOM && developing == null) { mode = BACK; work(false); }
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
        if (vf == null || vf.held()) return;   // a picture that won't save: its screen stays until the camera is off
        ui.removeCallbacks(closeWhenFinished);
        flushFilmSave();   // the wheel's last film onto the card (the darkroom reads it there)
        int taken = Math.max(vfWaiting, vf.shots);   // listed or not yet: the hand-back waits for them
        if (taken > 0) {
            dropPictureSurface();   // it's on top of everything: the darkroom shows at once
            restartToDevelop(taken + " picture(s) to develop");
            return;
        }
        if (mode == PHOTO) mode = BACK;
        vf.hide();   // its surface moved off the screen (kept for the return), the camera back shows at once
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
            prefs.edit().putString("lastStock", next.id).apply();   // written behind (API 9), not on this thread
            filmToSave = current;   // the roll's film onto the card once the wheel settles (and at the next frame, MENU, closing)
            ui.removeCallbacks(saveFilm);
            ui.postDelayed(saveFilm, FILM_SAVE_MS);
            vf.setFilm(next);
            int j = Math.max(0, stocks.indexOf(next)), n = stocks.size();   // and the films either side of it read ahead
            vf.prewarm(stocks.get((j + 1) % n).lut.getPath(), stocks.get((j + n - 1) % n).lut.getPath());
        }
        vf.showCard();
    }

    /** After the wheel stops: one card write for a turn through several films (v0.44 wrote ROLL.TXT at every click). */
    static final long FILM_SAVE_MS = 1000;
    private Roll filmToSave;   // the roll whose film the wheel changed, not yet on the card
    private final Runnable saveFilm = new Runnable() { public void run() { flushFilmSave(); } };

    /** The wheel's film onto the card now (ROLL.TXT, on the worker, as before) and into the snapshot, if one waits. */
    private void flushFilmSave() {
        ui.removeCallbacks(saveFilm);
        final Roll r = filmToSave;
        if (r == null) return;
        filmToSave = null;
        Runnable save = new Runnable() {
            public void run() {
                try { r.save(); } catch (Exception e) { devLog(r.label() + ": film not changed on the card (" + Probe.why(e) + ")"); }
            }
        };
        try { worker.execute(save); } catch (java.util.concurrent.RejectedExecutionException e) { save.run(); }   // Latent closing down
        saveSnapshot();
    }

    /** Keys while the viewfinder is open. */
    private void vfKey(int keyCode, KeyEvent e, boolean down) {
        vf.touched();   // any key: live view back to its full rate at once
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
        if (k == K_LEFT || k == K_RIGHT) { renderHero(); renderSamples(); }   // the next film either side gets its stand-in
        redrawKey();
    }

    /**
     * Loads the chosen film: answers at once ("Loading…" in place of Load, further presses ignored), stops sample
     * pictures still waiting to render so nothing queues ahead of it, and opens the camera back on
     * the new roll as soon as it exists and the camera is neutral; the card is re-read afterwards.
     */
    private void loadRoll() {
        final Stock stock = stocks.get(stockSel);
        prefs.edit().putString("lastStock", stock.id).putInt("lastLength", lengthSel).apply();   // a hint for next time
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
        readBattery();
        if (batteryLow()) { toast(BATTERY_LOW); return; }   // it waits, as it is, until the camera is charged
        if (!quiet && !r.endless()) r.state = Roll.DEVELOPING;   // an endless roll stays loaded while its photos develop
        try { r.save(); } catch (Exception e) { toast("Could not write the roll: " + e.getMessage()); return; }
        r.cleanTemp();
        // nothing of Choose film's goes ahead of the develop on the one worker: samples still waiting are dropped
        // (they render again when Choose film is next opened), and its full-size picture too unless it's on show
        samplesGen++;
        samplesFor = "";
        if (!quiet) heroGen++;
        devViewOf = null;   // the roll asked about afresh (frames may have developed on an earlier visit)
        developing = r;
        paused = false;
        devFailed = 0;
        failed.clear();
        devDoneAtStart = r.developedCount();
        devStarted = System.currentTimeMillis();
        devStartedUp = SystemClock.uptimeMillis();   // a press after this means someone is watching the roll develop
        etaShown = -1;
        progressShown = 0;
        devQuiet = quiet;
        quietLeft = r.frames.size() - devDoneAtStart;
        final String held = holdPower();   // logged once per develop: does Sony's power-save API work here, and to what
        preparing = false;
        if (!quiet) {
            mode = DARKROOM;
            toast("");
            work(true);
        }
        worker.execute(new Runnable() {
            public void run() {
                devLog(r.label() + ": developing " + (r.frames.size() - devDoneAtStart) + " frame(s); " + held + "; " + batteryNote());
                // developing into the camera roll needs the catalogue and a verified memory path
                FilmCore.setVariant(prefs.getInt("lookVariant", 0));   // the inner loop the speed test chose
                FilmCore.setFastCube(true);   // film files read Latent's way: identical on the camera, row for row, for every film (v0.39.1)
                FilmCore.setPairMemo(prefs.getInt(DevelopTest.P_MEMO, DevelopTest.MEMO_DEFAULT));   // lookups remembered (1K unless the Develop test says otherwise)
                StringBuilder hl = new StringBuilder();
                HardwareDevelop h = new HardwareDevelop(MainActivity.this, card, hl, null);
                try {
                    h.prepare(false, prefs.getInt(DevelopTest.P_BLOCK, HardwareDevelop.DARKROOM_BLOCK));   // the Develop test may have chosen another size
                    hw = h;
                } catch (Throwable e) {
                    hw = null;
                    devLog("camera roll develop unavailable (" + Probe.why(e) + "); developing in software. "
                            + hl.toString().trim().replace("\r\n", "; "));
                }
                // the strip shows frames developed on an earlier, paused visit too (an endless roll's strip is
                // this batch only: its earlier prints aren't loaded, as state() draws the strip from 'base')
                int base = r.endless() ? Math.max(0, Math.min(devDoneAtStart, r.frames.size())) : 0;
                for (int i = base; i < r.frames.size(); i++) {
                    File d = r.developed(i);
                    if (d != null) loadThumb(d);
                }
            }
        });
        developNext();
    }

    // time estimate: per-frame time learned from earlier develops, so it's right from the first second
    private long etaShown = -1;

    // until a frame has been timed here: a darkroom frame takes ~6-7 s since v0.44 (37 s was v0.1x's software develop)
    private long msPerFrame() { return prefs.getLong("msPerFrame", 7000); }

    private void learnFrameTime(int ms) {
        if (ms <= 0) return;
        prefs.edit().putLong("msPerFrame", (msPerFrame() * 6 + ms * 4) / 10).apply();   // an estimate: written behind
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

    /**
     * The darkroom's view of the roll, worked out once per frame in developNext: each redraw used to ask the
     * card about every frame of the roll (developedCount, the strip, the tray's source), ~400 ms a draw on
     * the UI thread, which shares core 3 with the develop's reads and writes (v0.39.1: 5.3-5.9 s of drawing
     * in each ~8 s frame).
     */
    private volatile File[] devView = new File[0];   // per frame: its developed file, or null
    private volatile Roll devViewOf;
    private volatile File devSource;                // the photo of the frame in the tray

    /**
     * Progress redraws during a develop: at most one per DEV_REDRAW_MS, and in the darkroom only of what
     * changed: the progress band, and the tray when the print steps up (Ui.trayStep). v0.41.0 still spent
     * ~1.7 s a frame redrawing the whole screen (~100 ms each, 17-19 a frame) on core 3.
     */
    private static final long DEV_REDRAW_MS = 1000;
    private long devDrawnAt;
    private boolean devRedrawPending;
    private int devTrayShown;   // the tray as last drawn: step 0 is the empty tray (the print still under a full veil)
    private final Runnable devRedraw = new Runnable() {
        public void run() {
            devRedrawPending = false;
            devDrawnAt = SystemClock.uptimeMillis();
            if (mode != DARKROOM) return;   // no other screen shows the frame's progress (v0.44: a full redraw a second for nothing)
            invalidateLogical(0, Ui.BAND_TOP, logicalWidth, Ui.BAND_BOTTOM - Ui.BAND_TOP);
            int step = Ui.trayStep(devPct / 100f);
            if (step != devTrayShown) {
                devTrayShown = step;
                invalidateLogical(Ui.trayX(logicalWidth) - 3, Ui.TRAY_Y - 3, Ui.TRAY_W + 6, Ui.TRAY_H + 6);
            }
        }
    };

    private UiState drawnState;   // what the screen last drew (Screen.onDraw)

    /**
     * After a key on Choose film, the menu, the contact sheet or the Lab: only what it changed is redrawn when
     * Ui.dirty can say what that is (RenderScreens checks every changed pixel lies inside), else everything.
     */
    private void redrawKey() {
        float[] r = Ui.dirty(drawnState, state());
        if (r == null) screen.invalidate();
        else invalidateLogical(r[0], r[1], r[2], r[3]);
    }

    /** Redraws only this part of the screen (in Ui's logical pixels). */
    private void invalidateLogical(float x, float y, float w, float h) {
        float sx = screen.getWidth() / logicalWidth, sy = screen.getHeight() / 480f;
        screen.invalidate((int) Math.floor(x * sx) - 1, (int) Math.floor(y * sy) - 1, (int) Math.ceil((x + w) * sx) + 1, (int) Math.ceil((y + h) * sy) + 1);
    }

    private void redrawSoon() {
        if (devRedrawPending) return;
        devRedrawPending = true;
        ui.postDelayed(devRedraw, Math.max(0, devDrawnAt + DEV_REDRAW_MS - SystemClock.uptimeMillis()));
    }

    /**
     * A worker's progress, handed to the UI thread at most once per 'every' ms, the newest value winning.
     * A post per percent was ~100 UI wake-ups a darkroom frame on core 3, which the develop's reads and
     * writes share, for a band that redraws at most once a second anyway.
     */
    private abstract class Throttled implements FilmCore.Progress, Runnable {
        private final long every;
        private volatile int pct;
        private volatile long shownAt;
        private final java.util.concurrent.atomic.AtomicBoolean posted = new java.util.concurrent.atomic.AtomicBoolean();

        Throttled(long every) { this.every = every; }

        public final void onProgress(int p) {   // any thread
            pct = p;
            if (posted.compareAndSet(false, true)) ui.postDelayed(this, Math.max(0, shownAt + every - SystemClock.uptimeMillis()));
        }

        public final void run() {   // UI thread
            posted.set(false);
            shownAt = SystemClock.uptimeMillis();
            show(pct);
        }

        /** No more of this progress (the job ended): a post still waiting is dropped. UI thread. */
        final void cancel() { ui.removeCallbacks(this); }

        abstract void show(int pct);   // UI thread
    }

    private Throttled devProgress;   // the frame in hand's (UI thread): an older frame's late post changes nothing

    private void developNext() {
        final Roll r = developing;
        File[] view = devView;
        if (devViewOf != r || view.length != r.frames.size()) {
            // the card asked about every frame once, as the develop starts (or carries on); after that each
            // finished frame updates its own entry, rather than two stats per frame of the roll every frame
            view = new File[r.frames.size()];
            for (int i = 0; i < view.length; i++) view[i] = r.developed(i);
            devView = view;
            devViewOf = r;
        }
        if (devQuiet && !quietAllowed()) paused = true;
        readBattery();
        if (batteryLow()) {   // never a frame begun on the last of the battery: the roll stops here, safe, and carries on later
            devLog(r.label() + ": paused, the battery is low (" + batteryNote() + ")");
            stopDevelop(devQuiet ? null : BATTERY_LOW);
            return;
        }
        if (paused) { stopDevelop(devQuiet ? null : "Paused. Press the centre button to carry on."); return; }
        int next = -1;
        for (int i = 0; i < view.length && next < 0; i++) if (!failed.contains(i) && view[i] == null) next = i;
        if (next < 0) { finishDevelop(); return; }   // the last originals are filed in the background (refresh)
        final int frame = next;
        final File src = r.source(frame), out = r.output(frame), prv = r.preview(out);
        devSource = src;
        // the frame after this one: its film is made ready while Sony saves this one (FilmCore.prewarm)
        int after = -1;
        for (int i = 0; i < view.length && after < 0; i++) if (i != frame && !failed.contains(i) && view[i] == null) after = i;
        Stock nextStock = after < 0 ? null : Stock.byId(stocks, r.stockFor(after));
        final String nextLut = after < 0 || nextStock == null ? null : nextStock.lut.getPath();
        final int[] nextParams = new int[FilmCore.COUNT];
        if (nextStock != null) {
            nextParams[FilmCore.HIGHLIGHT] = nextStock.highlight; nextParams[FilmCore.SHADOW] = nextStock.shadow;
            nextParams[FilmCore.GRAIN] = nextStock.grain; nextParams[FilmCore.GRAIN_SIZE] = nextStock.grainSize;
        } else {
            nextParams[FilmCore.GRAIN] = 4; nextParams[FilmCore.GRAIN_SIZE] = 1;   // as params() gives a frame with no film
        }
        final boolean prepareNext = after >= 0;
        final String film = r.stockFor(frame);   // on an endless roll, the film this frame was shot in
        final Stock stock = Stock.byId(stocks, film);
        devFrame = frame;
        devPct = 0;
        devTrayShown = 0;   // the full redraw below draws the tray at step 0 (empty): no tray redraw until step 1
        // (no power-save write per frame any more: hold() already set the longest timer for the develop, and
        // the per-frame re-write of v0.2x was an experiment nothing ever read back)
        if (devProgress != null) devProgress.cancel();
        final Throttled progress = new Throttled(250) {
            void show(int pct) { if (devProgress == this) { devPct = pct; redrawSoon(); } }
        };
        devProgress = progress;
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
                // the print in the tray: the camera's own small thumbnail of this frame. No redraw for it: the
                // tray shows the print only from its first step up (devRedraw redraws the tray then)
                if (exists) loadThumb(src);
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
                        final long draws0 = drawCount, drawMs0 = drawMs, stateMs0 = drawStateMs;
                        h.beforeSave = !prepareNext ? null : new Runnable() { public void run() { FilmCore.prewarm(nextLut, nextParams); } };
                        HardwareDevelop.Print print = h.develop(src, lut, hp, progress);
                        h.beforeSave = null;
                        final String draws = " setup=" + h.look[FilmCore.T_SETUP] + (h.look[FilmCore.T_WARM] != 0 ? " (ready)" : "") + " draws=" + (drawCount - draws0) + "/" + (drawMs - drawMs0) + "ms"
                                + " (state " + (drawStateMs - stateMs0) + "ms)";
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
                                + " rows=" + (SonyMemory.lastDirect() ? "direct" : "gathered") + " block=" + HardwareDevelop.block() / 1024 + "K"
                                + " threads=" + h.look[FilmCore.T_THREADS] + (FilmCore.helperNote().length() > 0 ? " (" + FilmCore.helperNote() + ")" : "")
                                + draws + " memo=" + prefs.getInt(DevelopTest.P_MEMO, DevelopTest.MEMO_DEFAULT), System.currentTimeMillis() - t0);
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
                        progress.cancel();
                        if (error != null) { devFailed++; failed.add(frame); toast((devQuiet ? "Photo " : "Frame ") + (frame + 1) + ": " + error); }
                        else {
                            File[] v = devView;   // this frame is developed now: its entry, without asking the card again
                            if (devViewOf == r && frame < v.length && print != null) { v = v.clone(); v[frame] = print; devView = v; }
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
            endDarkroom(true);
            refresh(new Runnable() { public void run() { afterQuiet(); } });
            return;
        }
        long secs = (System.currentTimeMillis() - devStarted) / 1000;
        if (r.endless()) {   // the photos just shot are in the camera roll: back to the camera back, ready to shoot
            final int n = r.developedCount() - devDoneAtStart;
            final String m = devFailed == 0 ? n + (n == 1 ? " photo" : " photos") + " developed" : devFailed + " photo(s) could not be developed";
            endDarkroom(true);
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
        endDarkroom(true);
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
        endDarkroom(false);
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
        if (batteryLow()) {   // (as last read) they wait for a charge rather than start and stop again
            if (developNow) { developNow = false; toast(BATTERY_LOW); }   // started again to develop: say why it doesn't
            return;
        }
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

    /** The develop is over: finished (its last frame done), or stopped part-way. */
    private void endDarkroom(boolean finished) {
        boolean darkroom = !devQuiet;
        devQuiet = false;
        quietLeft = 0;
        developing = null;
        if (devProgress != null) { devProgress.cancel(); devProgress = null; }
        // nobody pressed anything while it developed (the camera set down): what it ends on stays at the
        // darkroom's light, and the first press only wakes the screen. Otherwise it lights up, as before.
        boolean rests = darkroom && light.developEnded(SystemClock.uptimeMillis(), devStartedUp);
        applyLight();
        // a finished roll nobody is watching keeps the power-save hold until its last originals are filed
        // (they wait SETTLE_MS for their negatives to reach the card), so the roll completes in this visit
        if (finished && rests && !shootAfterFrame) holdUntilFiled();
        else powerSave.release();
    }

    /** After a roll, the camera's power-save stays held for its filing at most this long. */
    static final long HOLD_AFTER_MS = SETTLE_MS + 30000;
    /** A write of the power-save timer is given this long before the camera opens (as ~1 s always passed before v0.45). */
    static final long POWER_SETTLE_MS = 1000;
    private boolean holdAfter;         // the hold is kept for the last roll's filing (UI thread)
    private long holdAfterSince;
    private final Runnable releaseAfter = new Runnable() { public void run() { releaseHold(); } };

    private void holdUntilFiled() {
        holdAfter = true;
        holdAfterSince = SystemClock.uptimeMillis();
        ui.removeCallbacks(releaseAfter);
        ui.postDelayed(releaseAfter, HOLD_AFTER_MS);
    }

    /** The filing queued at 'queuedAt' is done: a hold kept for it (from before then) is given back. UI thread. */
    private void filedSince(long queuedAt) {
        if (holdAfter && queuedAt >= holdAfterSince) releaseHold();
    }

    /** The power-save timer back to yours, if it was kept for filing. UI thread. */
    private void releaseHold() {
        ui.removeCallbacks(releaseAfter);
        if (!holdAfter) return;
        holdAfter = false;
        powerSave.release();
    }

    /** The power-save timer raised for long work (a hold kept for filing becomes this one's). Returns hold()'s note. */
    private String holdPower() {
        holdAfter = false;
        ui.removeCallbacks(releaseAfter);
        return powerSave.hold();
    }

    /** If the threads did not really run in parallel on this camera, stop paying for them. */
    private void adaptThreads(int[] t, int threads) {
        if (threads > 1 && t[FilmCore.T_TOTAL] > 0 && t[FilmCore.T_CPU] < t[FilmCore.T_TOTAL] * 13 / 10) {
            prefs.edit().putInt("threads", 1).apply();
        }
    }

    // ------------------------------------------------------------------------------ contact sheet

    private Roll firstRollWithFrames() {
        for (Roll r : rolls) if (!r.developed().isEmpty() || !r.frames.isEmpty()) return r;
        return rolls.isEmpty() ? null : rolls.get(0);
    }

    private void openSheet(final Roll r) {
        if (mode != SHEET && mode != VIEW) sheetFrom = -1;   // a new visit to the sheet: whoever opened it says where MENU goes
        mode = SHEET;
        sheetRoll = r;
        sheetSel = 0;
        sheetFrames = new ArrayList<File>();
        thumbsPage = -1;
        dropViewer();
        if (r != null) {
            worker.execute(new Runnable() {
                public void run() {
                    final List<File> frames = r.developed();
                    // the sheet at once, its thumbnails filling in page by page (v0.44 read every frame's first)
                    ui.post(new Runnable() {
                        public void run() {
                            if (sheetRoll != r) return;
                            sheetFrames = frames;
                            sheetThumbs();
                            screen.invalidate();
                        }
                    });
                }
            });
        }
        screen.invalidate();
    }

    /** Ui.sheet's page of thumbnails (5 x 3). */
    static final int SHEET_PAGE = 15;

    /**
     * The contact sheet's thumbnails: the page on show first, then the pages either side (where LEFT and
     * RIGHT go next). Three pages at most, so the cache keeps them however long the roll. UI thread.
     */
    private void sheetThumbs() {
        final Roll r = sheetRoll;
        final List<File> frames = sheetFrames;
        if (r == null || frames.isEmpty()) return;
        final int page = Math.min(sheetSel, frames.size() - 1) / SHEET_PAGE, pages = (frames.size() + SHEET_PAGE - 1) / SHEET_PAGE;
        if (page == thumbsPage && r == thumbsRoll) return;   // asked for already
        thumbsPage = page;
        thumbsRoll = r;
        worker.execute(new Runnable() {
            public void run() {
                for (int p : new int[] { page, page + 1, page - 1 }) {
                    if (p < 0 || p >= pages || sheetRoll != r) continue;
                    boolean added = false;
                    for (int i = p * SHEET_PAGE; i < Math.min(frames.size(), (p + 1) * SHEET_PAGE); i++) added |= loadThumb(frames.get(i));
                    if (added && p == page) ui.post(new Runnable() { public void run() { if ((mode == SHEET || mode == VIEW) && sheetRoll == r) screen.invalidate(); } });
                }
            }
        });
    }

    private int thumbsPage = -1;
    private Roll thumbsRoll;

    private void sheetKey(int k) {
        if (sheetRoll == null) return;
        int idx = rolls.indexOf(sheetRoll);
        if (k == K_LEFT && sheetSel > 0) { sheetSel--; sheetThumbs(); }
        else if (k == K_RIGHT && sheetSel < sheetFrames.size() - 1) { sheetSel++; sheetThumbs(); }
        else if (k == K_DOWN && idx > 0) openSheet(rolls.get(idx - 1));
        else if (k == K_UP && idx >= 0 && idx < rolls.size() - 1) openSheet(rolls.get(idx + 1));
        else if (k == K_ENTER) {
            if (!sheetFrames.isEmpty()) openView(sheetSel);
            else if (!Roll.DEVELOPED.equals(sheetRoll.state)) startDevelop(sheetRoll);
        }
        redrawKey();
    }

    private boolean viewCaption, viewFill;
    private final Runnable hideCaption = new Runnable() {
        public void run() { viewCaption = false; screen.invalidate(); }
    };

    // The viewer keeps the frame on show and the ones either side decoded (three previews of ~1.4 MB,
    // and the picture on show while the next loads), so LEFT and RIGHT show the next frame at once, as
    // swiping does in Apple's Photos. Until a frame's own preview is there, its sheet thumbnail stands in
    // (else the picture on show stays): never a black screen between frames. Bitmaps are recycled only
    // here on the UI thread, never the one on show, and all of them when the viewer is left. UI thread.
    private final Map<Integer, Bitmap> viewSlots = new HashMap<Integer, Bitmap>();   // sheet index -> its preview (viewBitmap among them)
    private final Set<Integer> viewQueued = new HashSet<Integer>();   // decodes on their way
    private int viewShown = -1;        // the frame viewBitmap is
    private int viewGen;               // leaving the viewer: decodes still on their way are dropped
    private Bitmap viewStandIn;        // the frame's thumbnail (cut to the picture's shape) while its preview loads
    private boolean viewStandInOwned;  // a cut of the thumbnail, the viewer's to recycle (the thumbnail itself is the cache's)
    private int viewStandInFor = -1;

    /** What the viewer shows: the frame's preview; until it's there its thumbnail; failing that the picture on show. */
    private Bitmap viewImage() {
        if (viewBitmap != null && viewShown == sheetSel) return viewBitmap;
        if (viewStandIn != null && viewStandInFor == sheetSel) return viewStandIn;
        return viewBitmap;
    }

    /** Shows a developed frame: its screen-size preview (made once, if it predates previews). */
    private void openView(final int index) {
        mode = VIEW;
        sheetSel = index;
        viewCaption = true;
        ui.removeCallbacks(hideCaption);
        ui.postDelayed(hideCaption, 1800);
        Bitmap ready = viewSlots.get(index);
        if (ready != null) { viewBitmap = ready; viewShown = index; }
        else standIn(index);
        trimView();
        prefetchView(index);   // this frame first if it isn't here, then the one after, then the one before
        screen.invalidate();
    }

    /** The frame's sheet thumbnail, its letterbox cut to the shape of the picture on show (3:2 at first). */
    private void standIn(int index) {
        if (viewStandInFor == index) return;
        dropStandIn();
        Bitmap t = index < sheetFrames.size() ? thumb(sheetFrames.get(index)) : null;
        if (t == null || t.isRecycled()) return;
        float aspect = viewBitmap != null ? viewBitmap.getWidth() / (float) viewBitmap.getHeight() : 1.5f;
        int w = t.getWidth(), h = t.getHeight(), ch = Math.min(h, Math.round(w / aspect)), cw = Math.min(w, Math.round(h * aspect));
        if (ch < h) cw = w; else ch = h;
        viewStandInFor = index;
        if (cw == w && ch == h) { viewStandIn = t; return; }   // nothing to cut: the thumbnail as it is
        try {
            viewStandIn = Bitmap.createBitmap(t, (w - cw) / 2, (h - ch) / 2, cw, ch);
            viewStandInOwned = viewStandIn != t;
        } catch (Throwable e) {
            viewStandIn = t;
        }
    }

    private void dropStandIn() {
        if (viewStandIn != null && viewStandInOwned) viewStandIn.recycle();
        viewStandIn = null;
        viewStandInOwned = false;
        viewStandInFor = -1;
    }

    /** Previews out of reach of the frame on show are recycled (never the picture on show: it goes once replaced). */
    private void trimView() {
        java.util.Iterator<Map.Entry<Integer, Bitmap>> it = viewSlots.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Bitmap> e = it.next();
            if (Slots.kept(e.getKey(), sheetSel) || e.getValue() == viewBitmap) continue;
            e.getValue().recycle();
            it.remove();
        }
        if (viewStandIn != null && (viewStandInFor != sheetSel || viewShown == sheetSel)) dropStandIn();   // its frame is up, or gone
    }

    private void prefetchView(int index) {
        for (int i : Slots.wanted(index, sheetFrames.size())) if (!viewSlots.containsKey(i) && viewQueued.add(i)) decodeView(i);
    }

    /** Frame i's preview from the card (made first if it's missing or predates previews), on the worker. */
    private void decodeView(final int i) {
        final Roll r = sheetRoll;
        final File f = sheetFrames.get(i), prv = r.preview(f);
        final int gen = viewGen;
        worker.execute(new Runnable() {
            public void run() {
                if (gen != viewGen) return;   // the viewer was left
                Bitmap d = null;
                // only the frame on show goes at the usual priority: its neighbours are a nicety
                boolean ahead = i != sheetSel;
                if (ahead) android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                try {
                    if (Slots.kept(i, sheetSel)) d = readPreview(f, prv);   // (moved on far since: skipped)
                } finally {
                    if (ahead) android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
                }
                final Bitmap b = d;
                ui.post(new Runnable() {
                    public void run() {
                        if (gen == viewGen) viewQueued.remove(i);
                        if (b == null) return;
                        if (gen != viewGen || sheetRoll != r || !Slots.kept(i, sheetSel) || viewSlots.containsKey(i)) { b.recycle(); return; }
                        viewSlots.put(i, b);
                        if (i != sheetSel || mode != VIEW) return;
                        viewBitmap = b;   // the swap: the picture before is recycled only now, if it's out of reach
                        viewShown = i;
                        trimView();
                        screen.invalidate();
                    }
                });
            }
        });
    }

    /** A preview file decoded (read in one go: the card answers large reads far faster than many small ones). Worker thread. */
    private static Bitmap readPreview(File f, File prv) {
        if (!prv.exists()) {
            prv.getParentFile().mkdirs();
            FilmCore.makePreview(f.getPath(), prv.getPath());
        }
        Bitmap d = decodeWhole(prv);
        if (d != null && d.getHeight() < 470) {   // made before previews filled the panel's height
            d.recycle();
            FilmCore.makePreview(f.getPath(), prv.getPath());
            d = decodeWhole(prv);
        }
        return d;
    }

    private static Bitmap decodeWhole(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        o.inDither = false;
        try {
            long n = f.length();
            if (n <= 0 || n > 8 * 1024 * 1024) return BitmapFactory.decodeFile(f.getPath(), o);
            byte[] data = new byte[(int) n];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int got = 0;
            try { for (int k; got < data.length && (k = in.read(data, got, data.length - got)) > 0; ) got += k; } finally { in.close(); }
            return BitmapFactory.decodeByteArray(data, 0, got, o);
        } catch (Throwable e) {
            return BitmapFactory.decodeFile(f.getPath(), o);
        }
    }

    /** Leaving the viewer: every preview it holds is recycled (UI thread; nothing on screen holds them now). */
    private void dropViewer() {
        viewGen++;
        viewQueued.clear();
        for (Bitmap b : viewSlots.values()) if (b != viewBitmap) b.recycle();
        viewSlots.clear();
        if (viewBitmap != null) viewBitmap.recycle();
        viewBitmap = null;
        viewShown = -1;
        dropStandIn();
    }

    private void viewKey(int k) {
        if (k == K_LEFT && sheetSel > 0) { openView(sheetSel - 1); sheetThumbs(); }
        else if (k == K_RIGHT && sheetSel < sheetFrames.size() - 1) { openView(sheetSel + 1); sheetThumbs(); }
        else if (k == K_ENTER) {   // fit the whole photo, or fill the screen
            viewFill = !viewFill;
            viewCaption = true;
            ui.removeCallbacks(hideCaption);
            ui.postDelayed(hideCaption, 1500);
        }
        screen.invalidate();
    }

    /** The EXIF thumbnail (developed files carry one with the look applied); cached. Worker thread. False if it was already. */
    private boolean loadThumb(File f) {
        synchronized (thumbs) { if (thumbs.containsKey(f.getPath())) return false; }
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
        return true;
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
        redrawKey();
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
        holdPower();
        toast("Saving " + src.getName());
        work(true);
        // its bar redraws the screen at most once a second (a redraw per percent was ~100 full Lab redraws a save,
        // on the core the save shares)
        final Throttled progress = new Throttled(1000) {
            void show(int pct) { if (labBusy) { labProgress = pct; screen.invalidate(); } }
        };
        worker.execute(new Runnable() {
            public void run() {
                int rc = FilmCore.process(src.getPath(), out.getPath(), lut, null, p, progress);
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
                        progress.cancel();
                        labBusy = false;
                        labProgress = -1;
                        toast(msg);
                        if (t[FilmCore.T_TOTAL] > 0) adaptThreads(t, p[FilmCore.THREADS]);
                        powerSave.release();
                        work(false);
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
    /** Screen redraws so far and the ms they took (the UI thread shares core 3 with a develop's reads and writes). */
    private volatile long drawCount, drawMs, drawStateMs;   // drawStateMs: of drawMs, working out what to draw (state())

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

    /**
     * Choose film's small samples, each the stand-in for the full-size picture while that renders: the film
     * under the wheel (queued after its full-size picture, renderHero), then the films either side of it,
     * where the wheel goes next; the rest only as the wheel reaches them. v0.44 rendered all six on every card
     * read where the photo had changed, whatever was on screen, ahead of the darkroom's first frame on the one
     * worker. UI thread.
     */
    private void renderSamples() {
        if (mode != LOAD || stocks.isEmpty()) return;
        // the newest neutral original; failing that (nothing shot with Latent yet), the newest photo
        final File latest = reference != null ? reference : photos.isEmpty() ? null : photos.get(0);
        if (latest == null) return;
        if (!latest.getPath().equals(samplesFor)) { samplesFor = latest.getPath(); samplesQueued.clear(); }
        int n = stocks.size(), sel = Math.min(stockSel, n - 1);
        renderSample(stocks.get(sel), latest);
        renderSample(stocks.get((sel + 1) % n), latest);
        renderSample(stocks.get((sel + n - 1) % n), latest);
    }

    private final Set<String> samplesQueued = new HashSet<String>();   // stocks rendered, or on their way, on samplesFor

    private void renderSample(final Stock s, final File latest) {
        if (!samplesQueued.add(s.id)) return;
        final int gen = samplesGen;
        final String on = latest.getPath();
        worker.execute(new Runnable() {
            public void run() {
                if (gen != samplesGen) return;   // a load or a develop goes first: this one renders again later
                Bitmap made = null;
                // the samples are a nicety: they must never slow the screen or your presses (one core, shared)
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                try {
                    int[] px = new int[420 * 280], size = new int[2];
                    int[] p = params(latest, s.highlight, s.shadow, 0, 1);
                    if (FilmCore.preview(on, s.lut.getPath(), p, FilmCore.PREVIEW_FIT, px, 420, 280, size) == 0)
                        made = Bitmap.createBitmap(px, size[0], size[1], Bitmap.Config.ARGB_8888);
                } finally {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
                }
                if (made == null) return;
                final Bitmap b = made;
                ui.post(new Runnable() {
                    public void run() {
                        if (gen != samplesGen || !on.equals(samplesFor)) { b.recycle(); return; }
                        Bitmap old = samples.put(s.id, b);
                        if (old != null && old != b) old.recycle();
                        // redrawn only where it shows: Choose film on this film, while its full-size picture isn't up
                        if (mode == LOAD && !stocks.isEmpty() && s.id.equals(stocks.get(Math.min(stockSel, stocks.size() - 1)).id) && !heroShown())
                            screen.invalidate();
                    }
                });
            }
        });
    }

    /** Choose film shows the full-size picture of the film under the wheel (not its sample). */
    private boolean heroShown() {
        if (hero == null || reference == null || stocks.isEmpty()) return false;
        return heroFor.equals(reference.getPath() + "|" + stocks.get(Math.min(stockSel, stocks.size() - 1)).id);
    }

    /** Battery level from the system's sticky broadcast (no receiver to keep registered); its volts and temperature too. */
    private void readBattery() {
        try {
            android.content.Intent i = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return;
            int level = i.getIntExtra("level", -1), scale = i.getIntExtra("scale", 100);
            battery = level < 0 || scale <= 0 ? -1 : level * 100 / scale;
            batteryMv = i.getIntExtra("voltage", -1);
            batteryTemp = i.getIntExtra("temperature", Integer.MIN_VALUE);
            batteryPlugged = i.getIntExtra("plugged", 0) != 0;
        } catch (Exception e) {
            battery = -1;
        }
    }

    /**
     * At this much battery or less (and not on a charger) no darkroom frame starts: the frame in hand
     * finishes, then the roll pauses, so the camera never dies between Sony's save and Latent noting it
     * (that frame would develop twice). Resuming carries on where it stopped.
     */
    static final int BATTERY_GUARD = 5;
    static final String BATTERY_LOW = "Battery low. This roll is safe; charge to carry on.";

    private boolean batteryLow() {
        int b = battery;
        return b >= 0 && b <= BATTERY_GUARD && !batteryPlugged;
    }

    /** "battery=54% 3812mV 31.5C" (as last read), for the timing log: what a roll costs, frame by frame. */
    private String batteryNote() {
        int t = batteryTemp;
        return "battery=" + (battery >= 0 ? battery + "%" : "?") + (batteryMv > 0 ? " " + batteryMv + "mV" : "")
                + (t != Integer.MIN_VALUE ? " " + (t < 0 ? "-" : "") + Math.abs(t) / 10 + "." + Math.abs(t) % 10 + "C" : "")
                + (batteryPlugged ? " plugged" : "");
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

    // ------------------------------------------------------------------------------ the backlight

    private Backlight light;           // what level the screen wants (the rules are there, tested on the Mac)
    private float lit = Float.NaN;     // the level the window has now
    private int lightMode = -1;        // the screen the light last saw (onDraw notices a change and asks again)
    private boolean lightStill;
    private final Runnable lightCheck = new Runnable() { public void run() { applyLight(); } };

    /** The backlight as Backlight wants it now; asks again when that changes by time alone. UI thread. */
    private void applyLight() {
        long now = SystemClock.uptimeMillis();
        light.still(stillScreen(), now);
        dim(light.level(now));
        ui.removeCallbacks(lightCheck);
        long at = light.next(now);
        if (at > 0) ui.postAtTime(lightCheck, at);   // one timer, moved on by each press: no polling
    }

    /** Long work with the screen down (the darkroom, a Lab save, the Speed and Hardware tests) starts or ends. */
    private void work(boolean on) {
        light.work(on, SystemClock.uptimeMillis());
        applyLight();
    }

    /**
     * Screens that wait for you, which may rest after a while untouched. Never the viewfinder or the
     * viewer, the darkroom (its own light), or anything while the camera is Latent's.
     */
    private boolean stillScreen() {
        if (vf != null || liveRun != null || shootRun != null || restartRun != null || lookRun != null || screenRun != null) return false;
        return mode == BACK || mode == LOAD || mode == SETTINGS || mode == SHEET || mode == LAB || mode == PROBE;
    }

    /** The wheel or an arrow: in the darkroom, a press that brightens the screen for a moment. */
    static boolean arrowKey(int keyCode, int sc) {
        int k = key(keyCode, sc);
        return k == K_UP || k == K_DOWN || k == K_LEFT || k == K_RIGHT;
    }

    /**
     * The backlight is a large share of the camera's power draw: the darkroom turns it right down, an idle
     * screen rests lower. level is a fraction of full, or Backlight.FULL for the camera's own brightness.
     */
    private void dim(float level) {
        if (level == lit) return;
        lit = level;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = level < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : level;
        getWindow().setAttributes(lp);
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }
    private static String signed(int v) { return v > 0 ? "+" + v : v < 0 ? "\u2013" + -v : "0"; }   // the en dash: the fonts have no minus sign
    private static String secs(int ms) { return String.valueOf((ms + 500) / 1000); }

    /** Appends one line per developed frame to /LATENT/TIMING.TXT. Worker thread. */
    private void logTiming(File src, String look, int[] p, int[] t) {
        String line = stamp()
                + " v" + versionName() + " abi=" + Build.CPU_ABI + " " + src.getName() + " look=" + look
                + " grain=" + p[FilmCore.GRAIN] + "/" + p[FilmCore.GRAIN_SIZE] + " threads=" + p[FilmCore.THREADS]
                + " ms: read=" + t[FilmCore.T_READ] + " look=" + t[FilmCore.T_LOOK] + " write=" + t[FilmCore.T_WRITE]
                + " finish=" + t[FilmCore.T_FINISH] + " total=" + t[FilmCore.T_TOTAL] + " cpu=" + t[FilmCore.T_CPU]
                + " " + cpuInfo() + " screen=" + screenSize + " " + batteryNote() + "\r\n";
        try {
            FileOutputStream out = new FileOutputStream(new File(Library.home(card), "TIMING.TXT"), true);
            try { out.write(line.getBytes("US-ASCII")); } finally { out.close(); }
        } catch (Exception e) {
            // the log is a convenience; never fail a develop over it
        }
    }

    /** One line per hardware-developed frame in TIMING.TXT. */
    private void logHardware(File src, String look, HardwareDevelop.Print print, long[] m, String extra, long total) {
        appendLog("TIMING.TXT", stamp()
                + " v" + versionName() + " " + src.getName() + " -> " + print.file.getName() + " look=" + look + " hardware ms:"
                + " decode=" + m[HardwareDevelop.T_DECODE] + " image=" + m[HardwareDevelop.T_IMAGE] + " develop=" + m[HardwareDevelop.T_DEVELOP]
                + " save=" + m[HardwareDevelop.T_SAVE] + " find=" + m[HardwareDevelop.T_FIND] + " (settle " + m[HardwareDevelop.T_SETTLE] + ")"
                + " orient=" + m[HardwareDevelop.T_ORIENT]
                + extra + " total=" + total + " " + batteryNote() + " (" + print.orientation + ")");
    }

    /** What the darkroom decided and why, in LATENT/DEVELOP.TXT. */
    private void devLog(String what) {
        appendLog("DEVELOP.TXT", stamp() + " v" + versionName() + " " + what);
    }

    // one date format for every log line (it isn't thread-safe: the worker, the filer and the UI thread all log)
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private static String stamp() {
        synchronized (STAMP) { return STAMP.format(new Date()); }
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

    private static volatile String version;   // asked of the package manager once: it's a call into another process

    private String versionName() {
        String v = version;
        if (v != null) return v;
        try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
        if (v == null) return "?";
        version = v;
        return v;
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

    private long anyKeyAt;   // the last press of any button (uptime), the shutter and MENU too
    private static final int NO_KEY = Integer.MIN_VALUE;
    private int wokeBy = NO_KEY;   // the button whose press only woke the screen: the rest of that press is ignored

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, first = down && e.getRepeatCount() == 0;
        int sc = e.getScanCode(), id = sc != 0 ? sc : -e.getKeyCode();   // the button: by its scan code where it has one
        if (first) {
            long now = SystemClock.uptimeMillis();
            anyKeyAt = now;
            if (id == wokeBy) wokeBy = NO_KEY;   // pressed again: a new press
            int kind = shutterKey(sc, e.getKeyCode()) ? Backlight.SHUTTER
                    : mode == DARKROOM && vf == null && arrowKey(e.getKeyCode(), sc) ? Backlight.PEEK : Backlight.PRESS;
            if (light.press(now, kind)) {   // the screen was down: this press only wakes it (MENU acts on its release: that too)
                wokeBy = id;
                applyLight();
                return true;
            }
        }
        if (wokeBy != NO_KEY && id == wokeBy) {
            if (!down) wokeBy = NO_KEY;
            return true;
        }
        // route every key here, so no focused view swallows the camera's buttons
        boolean r = down ? onKeyDown(e.getKeyCode(), e) : onKeyUp(e.getKeyCode(), e);
        if (first) applyLight();   // a peek, or a press that changed the screen
        return r;
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

    // ---- the camera's other buttons

    /**
     * Sony's own names for its buttons' scan codes (ScalarInput's ISV_KEY_* constants), read once by reflection:
     * static fields only, nothing called on the camera. Empty where the class isn't (the Mac, another body).
     */
    private static Map<Integer, String> scanNames;
    private static int playScan = -2;

    private static synchronized Map<Integer, String> scanNames() {
        if (scanNames != null) return scanNames;
        Map<Integer, String> m = new HashMap<Integer, String>();
        try {
            for (java.lang.reflect.Field f : Class.forName("com.sony.scalar.sysutil.ScalarInput").getFields()) {
                if (f.getType() != int.class || !java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !f.getName().startsWith("ISV_KEY_")) continue;
                int v = f.getInt(null);
                if (!m.containsKey(v)) m.put(v, f.getName());
            }
        } catch (Throwable e) {
            // not a Sony camera, or no such class: no names, and no Playback button
        }
        scanNames = m;
        return m;
    }

    /** The Playback button's scan code (ISV_KEY_PLAY), or -1 when the camera doesn't say. */
    private static synchronized int playScan() {
        if (playScan != -2) return playScan;
        playScan = -1;
        for (Map.Entry<Integer, String> en : scanNames().entrySet()) if (en.getValue().equals("ISV_KEY_PLAY")) playScan = en.getKey();
        return playScan;
    }

    private int sheetFrom = -1;        // the screen the contact sheet was opened from (MENU and Playback go back there); -1 home
    private final Set<String> unmapped = new HashSet<String>();   // buttons pressed this visit that Latent doesn't use

    /**
     * Playback, as on the camera itself: the roll's contact sheet (the loaded roll's once it has frames, else the
     * newest roll with any), and pressed again, back where it was. Only from the camera back, Choose film and the
     * menu: never in the viewfinder or the darkroom (nor mid-test, nor in the Lab).
     */
    private void playback() {
        if (!ready) return;
        if (mode == SHEET || mode == VIEW) { leaveSheet(); screen.invalidate(); return; }
        if (mode != BACK && mode != LOAD && mode != SETTINGS) return;
        if (mode == LOAD && loading) return;
        Roll r = current != null && exposed > 0 ? current : firstRollWithFrames();
        if (r == null) { toast("No rolls yet"); return; }
        int from = mode;
        openSheet(r);
        sheetFrom = from;
    }

    /** Off the contact sheet: back to the menu or Choose film when it was opened there, else home. */
    private void leaveSheet() {
        if (sheetFrom == SETTINGS) mode = SETTINGS;
        else if (sheetFrom == LOAD && current == null) openLoad();
        else goHome();
        sheetFrom = -1;
    }

    /** A button Latent doesn't use: noted (scan code / key code, Sony's name), for one line a visit in DEVELOP.TXT. */
    private void unmapped(int keyCode, int sc) {
        if (sc == SC_MENU || sc == SC_SK1 || keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BACK) return;   // MENU acts on release
        if (unmapped.size() >= 30) return;
        String name = scanNames().get(sc);
        unmapped.add(sc + "/" + keyCode + (name != null ? " " + name : ""));
    }

    /** The visit's census of unused buttons, as a log line (null when none was pressed); clears it. */
    private String unmappedLine() {
        if (unmapped.isEmpty()) return null;
        String line = "keys: not used here " + unmapped + "; Playback is " + (playScan() >= 0 ? "scan code " + playScan() : "not named by the camera");
        unmapped.clear();
        return line;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        int sc = e.getScanCode();
        if (sc == SC_S1 || keyCode == KeyEvent.KEYCODE_FOCUS) s1Held = true;
        if (mode == PHOTO && coachUntil > 0) endCoach();   // the first key: the keys are learnt
        if (mode == PHOTO && vf != null) { vfKey(keyCode, e, true); return true; }
        if (sc >= 0 && sc == playScan()) {   // the camera's Playback button: the roll's contact sheet, and back
            if (e.getRepeatCount() == 0) playback();
            return true;
        }
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
        if (k == 0 && e.getRepeatCount() == 0) unmapped(keyCode, sc);
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
            case SETTINGS: settingsBack(); break;
            case DARKROOM: if (!preparing) { paused = true; toast("Pausing after this frame"); } break;
            case SHEET: leaveSheet(); break;
            case VIEW: mode = SHEET; dropViewer(); sheetThumbs(); break;   // its previews go; the sheet's page may have moved
            case LAB: if (!labBusy) { if (fromSettings) mode = SETTINGS; else goHome(); developWaiting(); } break;
            case PROBE:
                if (screenRun != null) screenRun.stop();
                else if (liveRun != null) liveRun.close();   // MENU stops the live view test at once
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
                s.readyIn = readyIn();
                s.readyTotal = (int) (SHOOT_SETTLE_MS / 1000);
                if (current != null && !current.endless()) {   // finished or paused: about how long the develop takes
                    int shown = Math.min(exposed, current.length);
                    if (s.developed >= 0) s.developMs = (shown - s.developed) * msPerFrame();
                    else if (shown >= current.length) s.developMs = shown * msPerFrame();
                }
                break;
            case PHOTO: {
                if (vf != null) vf.fill(s);
                s.looks = vfLooks();   // made once, not for every frame (Ui only reads it)
                s.lookSel = Math.max(0, stocks.indexOf(cur));
                s.exposed = vfBase + (vf != null ? vf.shots : 0);
                if (current != null) { s.length = current.length; s.endless = current.endless(); }
                s.cardNote = current != null && current.endless() ? "From this frame on" : filmFresh() ? "" : "Loaded for the whole roll";
                break;
            }
            case SETTINGS:
                Ui.menuState(s, settingsPage, menuFacts(), settingsSel);   // the rows the Mac previews show too
                s.film = cur == null ? null : film(cur);                     // its colour marks the chosen row
                break;
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
                // the film of the frame in the tray: on an endless roll each frame has its own (the wheel, per frame)
                Stock st = Stock.byId(stocks, devFrame < r.frames.size() ? r.stockFor(devFrame) : r.stock);
                if (st == null) st = Stock.byId(stocks, r.stock);
                s.film = st == null ? null : film(st);
                // an endless roll develops in batches: this batch counts from its own first frame ("01 of 3"), not
                // from the roll's first (frame 29 of 31 read as 90% done before anything had developed)
                int base = r.endless() ? Math.max(0, Math.min(devDoneAtStart, r.frames.size())) : 0;
                File[] dv = devView, view = devViewOf == r && dv.length == r.frames.size() ? dv : null;   // the card asked once per frame
                int developed;
                if (view == null) developed = r.developedCount();
                else { developed = 0; for (File d : view) if (d != null) developed++; }
                s.devFrame = Math.max(0, devFrame - base);
                s.devTotal = r.frames.size() - base;
                s.devDone = Math.max(0, developed - base);
                s.devFramePct = devPct / 100f;
                long per = msPerFrame();
                // frames already developed, plus how far the one in hand is (counted once: when it has just
                // finished it is both "developed" and "at 100%" until the next frame starts)
                boolean inHandDone = devFrame < r.frames.size() && (view != null ? view[devFrame] : r.developed(devFrame)) != null;
                float done = Math.max(0, Math.min(s.devDone - (inHandDone ? 1 : 0) + (inHandDone ? 1f : s.devFramePct), s.devTotal));
                progressShown = Math.max(progressShown, done / Math.max(1, s.devTotal));   // never goes backwards
                s.devProgress = progressShown;
                s.paused = paused;
                s.devLeft = timeLeft((long) ((s.devTotal - done) * per));
                File tray = devSource;
                s.devPrint = devFrame < r.frames.size() ? thumb(view != null && tray != null ? tray : r.source(devFrame)) : null;
                for (int i = base; i < r.frames.size(); i++) {
                    File d = view != null ? view[i] : r.developed(i);
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
                    // the page on show only (Ui.sheet draws no other): the cache then keeps what's drawn freshest
                    int first = Math.max(0, sheetSel) / SHEET_PAGE * SHEET_PAGE;
                    for (int i = 0; i < sheetFrames.size(); i++) s.thumbs.add(i >= first && i < first + SHEET_PAGE ? thumb(sheetFrames.get(i)) : null);
                }
                s.sheetSel = sheetSel;
                s.sheetTotal = sheetFrames.size();
                s.filingLeft = filingLeft;
                s.viewImage = viewImage();
                s.viewCaption = viewCaption;
                s.viewFill = viewFill;
                break;
            }
            case PROBE:
                s.live = liveOn;
                s.liveNote = liveNote;
                s.probeTitle = screenTest ? "Screen test" : lookTest ? "Live look test" : restartTest ? "Restart test" : shootTest ? "Shooting test" : liveTest ? "Live view test" : devTest ? "Develop test" : speedTest ? "Speed test" : settingsCheck ? "Camera settings check" : hardwareTest ? "Hardware develop test" : "Camera roll test";
                s.probeSteps = screenTest ? ScreenTest.STEPS : lookTest ? LiveLookTest.STEPS : restartTest ? RestartTest.STEPS : shootTest ? ShootingTest.STEPS : liveTest ? LiveViewTest.STEPS : devTest ? DevelopTest.STEPS : speedTest ? SpeedTest.STEPS : settingsCheck ? SettingsCheck.STEPS : hardwareTest ? HardwareTest.STEPS : Probe.STEPS;
                s.probePlan = screenTest ? Ui.SCREEN_PLAN : lookTest ? Ui.LOOK_PLAN : restartTest ? Ui.RESTART_PLAN : shootTest ? Ui.SHOOT_PLAN : liveTest ? Ui.LIVE_PLAN : devTest ? Ui.DEVTEST_PLAN : speedTest ? Ui.SPEED_PLAN : settingsCheck ? Ui.SETTINGS_PLAN : hardwareTest ? Ui.HARDWARE_PLAN : Ui.PROBE_PLAN;
                s.probeFooter = screenTest ? Ui.SCREEN_FOOTER : lookTest ? Ui.LOOK_FOOTER : restartTest ? Ui.RESTART_FOOTER : shootTest ? Ui.SHOOT_FOOTER : liveTest ? Ui.LIVE_FOOTER : devTest ? Ui.DEVTEST_FOOTER : speedTest ? Ui.SPEED_FOOTER : settingsCheck ? Ui.SETTINGS_FOOTER : hardwareTest ? Ui.HARDWARE_FOOTER : Ui.PROBE_FOOTER;
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
        if (mode == PHOTO) s.coach = coach();
        return s;
    }

    private List<UiState.Film> looksMade;   // the viewfinder's films, as made from looksOf
    private List<Stock> looksOf;

    /** The films as the viewfinder shows them, made again only when the films do (a new list from the card). */
    private List<UiState.Film> vfLooks() {
        if (looksMade == null || looksOf != stocks) {
            List<UiState.Film> l = new ArrayList<UiState.Film>();
            for (Stock st : stocks) l.add(film(st));
            looksMade = l;
            looksOf = stocks;
        }
        return looksMade;
    }

    /** How a stock is shown (Ui.film, as the previews show it), with its latest photo rendered in it. */
    private UiState.Film film(Stock st) {
        UiState.Film f = Ui.film(st);
        if (f != null) f.sample = samples.get(st.id);
        return f;
    }

    // ---- the viewfinder's keys, once ever (UiState.coach)

    static final long COACH_MS = 5000;
    private long coachUntil;           // 0 not asked yet this run, > 0 shown until then, -1 done (or shown on an earlier visit)
    private final Runnable coachEnd = new Runnable() { public void run() { endCoach(); } };

    /** The first viewfinder visit ever: its keys for a few seconds, or until the first key. */
    private boolean coach() {
        if (coachUntil == 0) {
            if (prefs.getBoolean("coached", false)) coachUntil = -1;
            else {
                prefs.edit().putBoolean("coached", true).apply();
                coachUntil = SystemClock.uptimeMillis() + COACH_MS;
                ui.postDelayed(coachEnd, COACH_MS);
            }
        }
        return coachUntil > 0 && SystemClock.uptimeMillis() < coachUntil;
    }

    private void endCoach() {
        if (coachUntil <= 0) return;
        coachUntil = -1;
        ui.removeCallbacks(coachEnd);
        if (mode != PHOTO) return;
        screen.invalidate();
        if (vf != null && vf.onSurface()) presentPicture(true);   // gone from the picture's surface too, frames or none
    }

    private final class Screen extends View {
        private final AndroidGfx gfx;
        private final android.graphics.Rect clip = new android.graphics.Rect();

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
            ScreenTest st = screenRun;
            if (screenHook && st != null) { st.draw(c); return; }   // the screen test draws its frames here
            long t = SystemClock.uptimeMillis();
            boolean whole = c.getClipBounds(clip) && clip.left <= 0 && clip.top <= 0 && clip.right >= getWidth() && clip.bottom >= getHeight();   // not a partial redraw
            c.save();
            c.scale(getWidth() / logicalWidth, getHeight() / 480f);
            gfx.begin(c);
            long ts = SystemClock.uptimeMillis();
            UiState s = state();
            drawStateMs += SystemClock.uptimeMillis() - ts;
            if (mode == PHOTO && vf != null) {
                vf.layer(s);   // the type over the picture comes in the frames themselves
                if (vf.onSurface()) s.vfPart = UiState.PART_WINDOW;   // the picture and what's over it are on its own surface
            }
            windowDrawn = mode == PHOTO && s.vfPart == UiState.PART_WINDOW ? Ui.windowKey(s) : null;
            if (pictureOnScreen && mode != PHOTO) ui.post(dropSurface);   // it's on top: off the screen, away from the viewfinder
            Ui.draw(gfx, s);
            // a key's partial redraw (Ui.dirty): if something else changed since the last drawing, all of it next
            if (!whole && drawnState != null && Ui.keyPartial(s.mode) && Ui.dirty(drawnState, s) == null) screen.invalidate();
            drawnState = s;
            c.restore();
            drawCount++;
            drawMs += SystemClock.uptimeMillis() - t;
            drawn();
            boolean still = stillScreen();   // another screen (without a press): the light looks again, after this draw
            if (mode != lightMode || still != lightStill) { lightMode = mode; lightStill = still; ui.post(lightCheck); }
            if (mode == PHOTO && vf != null) vf.drew(SystemClock.uptimeMillis() - t, gfx.takeExactMs());
        }
    }
}
