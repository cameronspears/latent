package com.latent.camera;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Renders every Latent screen with realistic data to PNGs, through the same Ui code the camera
 * runs, at the panel's true shape (853x480 for the A5100's 16:9 panel), and again as the camera
 * really shows them (CameraTrue: 640 dots, 4 bits a channel) in camera/. tools/preview.sh drives it.
 *
 * The data is the camera's own where it can be: the films are Stock's built-ins, the menu is
 * Ui.menuRows (what MainActivity shows). Then it checks what the eye would catch late:
 * - the viewfinder's layers and parts add up to the whole (8w, 8x);
 * - the overlay draws nothing outside its two bands (Ui.OVL_TOP_END, OVL_BOTTOM_START) unless the grid is on;
 * - two viewfinder states with one Ui.windowKey draw the same window (the window is redrawn only on a new key);
 * - every text is at least 13 px (sentences 15), reads at 4.5:1 on its background (3:1 from 18 px), and
 *   overlaps no other text; every side-bar value Sony can report fits its bar;
 * - the palette and the films' colours are on the 4-bit grid.
 * Any failure fails the run.
 *
 *   RenderScreens FONTDIR SAMPLEDIR OUTDIR [BASEDIR]
 *
 * With BASEDIR (an earlier version's output, camera/ inside it), compare/ pairs its camera renders with these.
 */
public class RenderScreens {
    static final float W = 480f * 16 / 9;
    static File fonts, out;
    static boolean sun;
    static final List<String> problems = new ArrayList<String>();
    static final List<String> rendered = new ArrayList<String>();

    public static void main(String[] a) throws Exception {
        fonts = new File(a[0]);
        File samples = new File(a[1]);
        out = new File(a[2]);
        out.mkdirs();
        new File(out, "camera").mkdirs();
        sun = System.getProperty("latent.sun") != null;
        if (sun) new File(out, "camera-sun").mkdirs();
        tokens();

        // the films as the camera shows them: Stock's built-ins
        List<UiState.Film> six = new ArrayList<UiState.Film>();
        for (Stock st : Stock.builtIn()) six.add(Ui.film(st));
        UiState.Film natural = six.get(0), portra = six.get(1), classic = six.get(2), cuban = six.get(3), trix = six.get(4), party = six.get(5);
        cuban.sample = img(samples, "STREET-CUBANNEG.png");
        portra.sample = img(samples, "STREET-PORTRA4W.png");
        classic.sample = img(samples, "STREET-CLASSNEG.png");
        trix.sample = img(samples, "PEOPLE-TRIX400.png");
        party.sample = img(samples, "PEOPLE-PARTY800.png");
        natural.sample = img(samples, "HOUSE-NATURAL.png");
        List<Object> thumbs = new ArrayList<Object>();
        String[] names = { "STREET", "HOUSE", "PEOPLE" };
        for (int i = 0; i < 24; i++) thumbs.add(img(samples, names[i % 3] + "-CUBANNEG.png"));

        UiState s;
        // ---- home: choosing film when the camera is empty; the frame counter with a roll in; MENU holds the rest
        s = state();
        s.mode = UiState.LOAD; s.films = six; s.filmSel = 2; s.lengthSel = 1; s.liveCaption = "on";
        render(s, "1-home-choose-classic");
        s.filmSel = 4; s.lengthSel = 3;
        render(s, "1b-home-choose-trix-endless");
        s.cancelable = true; s.filmSel = 5;
        render(s, "1c-change-film-party");
        s.loading = true;
        render(s, "1d-loading");
        s = state();
        s.mode = UiState.LOAD; s.filmSel = 0; s.lengthSel = 1; s.liveCaption = "";   // nothing shot yet: no picture in any film
        for (Stock st : Stock.builtIn()) s.films.add(Ui.film(st));
        render(s, "1e-home-first-run");
        s = state();
        s.mode = UiState.LOAD; s.films = new ArrayList<UiState.Film>(); s.liveCaption = "";
        render(s, "1f-home-no-looks");
        for (int f = 0; f < six.size(); f++) for (int l = 0; l < 4; l++) {   // every film at every length: nothing collides
            s = state();
            s.mode = UiState.LOAD; s.films = six; s.filmSel = f; s.lengthSel = l; s.liveCaption = "on";
            audit(s, "1-home-choose " + six.get(f).title + " length " + l);
        }

        s = state();
        s.film = portra; s.rollNumber = 5; s.exposed = 0; s.length = 12; s.toast = "Portra 400 loaded  \u00b7  camera set to neutral";
        render(s, "2-home-fresh-toast");
        s.toast = ""; s.readyIn = 4; s.readyTotal = 6;
        render(s, "2b-home-settling");
        s.readyIn = 0;
        render(s, "2c-home-getting-ready");
        s = state();
        s.film = classic; s.rollNumber = 12; s.exposed = 9; s.length = 24;
        render(s, "3-home-roll");
        UiState s10 = s.copy();
        s10.exposed = 10;
        render(s10, "3b-home-roll-10");
        same(s, s10, "the camera back's 'of 24' at frames 9 and 10", 600, 230, 853, 310);
        s = state();
        s.film = classic; s.rollNumber = 12; s.exposed = 22; s.length = 24;
        render(s, "3c-home-roll-end");
        s = state();
        s.film = trix; s.rollNumber = 13; s.exposed = 23; s.endless = true; s.developing = 2;
        for (int i = 0; i < 6; i++) s.thumbs.add(img(samples, names[i % 3] + "-TRIX400.png"));
        render(s, "4-home-endless");
        s = state();
        s.film = trix; s.rollNumber = 13; s.exposed = 0; s.endless = true;
        render(s, "4b-home-endless-fresh");
        s = state();
        s.film = portra; s.rollNumber = 4; s.exposed = 12; s.length = 12; s.battery = 17; s.developMs = 12 * 7000;
        render(s, "5-home-finished-lowbattery");
        // after Latent's viewfinder, with the camera still open (Latent restarts to develop on MENU or Develop)
        s = state();
        s.film = portra; s.rollNumber = 4; s.exposed = 12; s.length = 12; s.developLater = true; s.waiting = 3; s.developMs = 12 * 7000;
        render(s, "5b-home-finished-develop-later");
        s = state();
        s.film = classic; s.rollNumber = 12; s.exposed = 24; s.length = 24; s.developed = 9; s.developMs = 15 * 7000;
        render(s, "5c-home-paused");
        s = state();
        s.film = trix; s.rollNumber = 13; s.exposed = 29; s.endless = true; s.developLater = true; s.waiting = 6;
        for (int i = 0; i < 6; i++) s.thumbs.add(img(samples, names[i % 3] + "-TRIX400.png"));
        render(s, "4c-home-endless-develop-later");

        // ---- the menu, its rows from Ui.menuRows (as MainActivity builds them): a roll loaded, 9 shot
        Ui.Menu m = new Ui.Menu();
        m.roll = true; m.developNow = true; m.changeFilm = true; m.exposed = 9; m.rolls = true; m.viewfinder = true; m.focusFirst = true;
        m.liveLook = true; m.threeCores = true; m.threeCoresOk = true; m.neutral = true; m.looks = true; m.version = "0.45.0";
        s = menu(m, Ui.PAGE_MENU, 1, classic);
        render(s, "6-menu");
        s = menu(m, Ui.PAGE_MENU, 5, classic);
        render(s, "6c-menu-viewfinder");
        s = menu(m, Ui.PAGE_MENU, 99, classic);
        render(s, "6b-menu-scrolled");
        s = menu(m, Ui.PAGE_CHECKS, 1, classic);
        render(s, "6d-menu-camera-checks");
        Ui.Menu busy = new Ui.Menu();
        busy.rolls = false; busy.viewfinder = true; busy.looks = true; busy.cameraBusy = true; busy.version = "0.45.0";
        s = menu(busy, Ui.PAGE_CHECKS, 5, null);
        render(s, "6f-menu-checks-camera-busy");
        s = menu(busy, Ui.PAGE_MENU, 8, null);
        render(s, "6e-menu-cores-unavailable");

        // ---- the viewfinder (in-app shooting): every focus state, a look change, a shot developing
        int[] hist = new int[64];
        for (int i = 0; i < 64; i++) hist[i] = (int) (30 + 100 * Math.exp(-Math.pow((i - 22) / 9.0, 2)) + 60 * Math.exp(-Math.pow((i - 47) / 6.0, 2)));
        s = vf(six, 2, img(samples, "STREET-CLASSNEG.png"));
        render(s, "8-vf-ready");
        UiState coach = s.copy();
        coach.coach = true;
        render(coach, "8y-vf-coach");
        UiState stuck = s.copy();
        stuck.fatal = "A photo isn't saving. Turn the camera off (battery out if it won't).";
        render(stuck, "8z-vf-fatal");
        s.afState = 1; s.afLamp = true; s.shutter = "1/60"; s.aperture = "F3.5"; s.iso = "3200"; s.isoAuto = true;
        render(s, "8b-vf-focusing");
        s.afState = 2; s.afLamp = false;
        s.afAreas = new float[][] { { 0.44f, 0.40f, 0.05f, 0.075f }, { 0.50f, 0.40f, 0.05f, 0.075f }, { 0.44f, 0.48f, 0.05f, 0.075f }, { 0.56f, 0.52f, 0.05f, 0.075f } };
        render(s, "8c-vf-locked");
        s = vf(six, 2, img(samples, "PEOPLE-CLASSNEG.png"));
        s.afState = 3; s.afNote = "Too dark to focus"; s.shutter = "1/30"; s.aperture = "F3.5"; s.iso = "6400"; s.isoAuto = true; s.ev = -2; s.afLamp = true;
        render(s, "8d-vf-cant-focus");
        s = vf(six, 4, img(samples, "HOUSE-TRIX400.png"));
        s.lookShown = true;
        render(s, "8e-vf-look-trix");
        s = vf(six, 4, img(samples, "HOUSE-TRIX400.png"));
        s.exposed = 10; s.printImage = img(samples, "PEOPLE-TRIX400.png"); s.printProgress = 0.45f; s.developing = 2; s.grid = true; s.hist = hist;
        render(s, "8f-vf-print-developing");
        s = vf(six, 5, img(samples, "PEOPLE-PARTY800.png"));
        s.endless = true; s.exposed = 37; s.flash = true; s.printImage = img(samples, "STREET-PARTY800.png"); s.printProgress = 0.1f; s.developing = 1;
        render(s, "8g-vf-endless-shot");
        // the level, read from the picture: off level, then level
        s = vf(six, 2, img(samples, "HOUSE-CLASSNEG.png"));
        s.levelShown = true; s.levelAngle = 2.6f;
        render(s, "8h-vf-level-tilted");
        s.levelAngle = 0.2f; s.levelOk = true; s.afState = 2; s.shutter = "1/250"; s.aperture = "F8";
        render(s, "8i-vf-level-locked");
        s = vf(six, 2, img(samples, "HOUSE-CLASSNEG.png"));
        s.clean = true; s.vfNote = "Clean";
        render(s, "8j-vf-clean");
        // centre: the camera's own live view (drawn here under the screen, as the camera's video plane is)
        s = vf(six, 2, null);
        s.liveThrough = true; s.lookLive = false; s.vfNote = "Film look off  \u00b7  the camera's own view"; s.levelShown = true; s.levelAngle = -1.4f;
        renderOver(s, "8k-vf-camera-view", img(samples, "HOUSE-NATURAL.png"));
        s = vf(six, 0, img(samples, "PEOPLE-NATURAL.png"));
        s.exposed = 12; s.length = 12; s.vfNote = "Roll finished"; s.printImage = img(samples, "PEOPLE-NATURAL.png"); s.printProgress = 0.6f; s.developing = 3;
        render(s, "8l-vf-roll-finished");
        s = vf(six, 0, img(samples, "PEOPLE-NATURAL.png"));
        s.exposed = 22; s.length = 24; s.developing = 4; s.printImage = img(samples, "HOUSE-NATURAL.png"); s.printProgress = 1;
        render(s, "8la-vf-two-left");
        s = vf(six, 4, img(samples, "HOUSE-TRIX400.png"));
        s.lookShown = true; s.cardNote = "Loaded for the whole roll";
        render(s, "8m-vf-card-locked");
        // v0.32.0 on the camera: its own live view with Latent's drawing over it, prints waiting for the darkroom
        s = vf(six, 2, null);
        s.liveThrough = true; s.lookLive = false; s.exposed = 11; s.developing = 2; s.afState = 2;
        renderOver(s, "8o-vf-camera-live-view", img(samples, "STREET-NATURAL.png"));
        s.afState = 1; s.afTry = 2; s.afLamp = true; s.vfNote = "";
        renderOver(s, "8p-vf-camera-live-view-trying", img(samples, "PEOPLE-NATURAL.png"));
        s = vf(six, 2, img(samples, "PEOPLE-CLASSNEG.png"));
        s.afState = 1; s.afTry = 3; s.afLamp = true; s.shutter = "1/30"; s.aperture = "F3.5"; s.iso = "6400"; s.isoAuto = true;
        render(s, "8n-vf-trying-again");

        // v0.35: the look on live view again; the wheel on an endless roll; DOWN opens the exposure scale
        s = vf(six, 3, img(samples, "STREET-CUBANNEG.png"));
        s.endless = true; s.exposed = 41; s.lookShown = true; s.cardNote = "From this frame on";
        render(s, "8q-vf-endless-film-card");
        s = vf(six, 2, img(samples, "HOUSE-CLASSNEG.png"));
        s.evEdit = true; s.ev = 0; s.shutter = "1/125"; s.aperture = "F5.6"; s.iso = "200";
        render(s, "8r-vf-exposure-open");
        s.ev = 1;
        render(s, "8s-vf-exposure-plus-third");
        UiState e2 = s.copy();
        e2.ev = 2;
        if (!Ui.overlayKey(s).equals(Ui.overlayKey(e2))) problems.add("overlayKey changes with the exposure while its scale is open (it's drawn in the side bar)");
        s = vf(six, 2, img(samples, "PEOPLE-CLASSNEG.png"));
        s.evEdit = true; s.ev = -5; s.levelShown = true; s.levelAngle = 0.3f; s.levelOk = true;
        render(s, "8t-vf-exposure-minus-level");
        s = vf(six, 2, img(samples, "STREET-CLASSNEG.png"));
        s.ev = -2; s.exposed = 7; s.printImage = img(samples, "HOUSE-CLASSNEG.png"); s.printProgress = 0.7f; s.levelShown = true; s.levelAngle = 3.1f;
        s.shutter = "1/250"; s.aperture = "F8"; s.iso = "400"; s.isoAuto = true;
        render(s, "8u-vf-live-look-print-level");
        layers(s, "8w-vf-layers");
        s = vf(six, 2, img(samples, "STREET-CLASSNEG.png"));
        s.evEdit = true; s.ev = 4; s.afState = 2; s.levelShown = true; s.levelAngle = -0.2f; s.levelOk = true; s.grid = true; s.hist = new int[64];
        for (int i = 0; i < 64; i++) s.hist[i] = 20 + (i * 37 % 50);
        layers(s, "8x-vf-layers-ev-grid");
        windowKeys(six, img(samples, "STREET-CLASSNEG.png"));
        sideBarText();

        s = state();
        s.mode = UiState.DARKROOM; s.film = cuban; s.devFrame = 4; s.devTotal = 12; s.devDone = 4;
        s.devFramePct = 0.55f; s.devProgress = (4 + 0.55f) / 12; s.devLeft = "About 5 min left";
        s.devPrint = img(samples, "HOUSE-CUBANNEG.png");
        for (int i = 0; i < 4; i++) s.devStrip.add(thumbs.get(i));
        render(s, "8-darkroom");

        s = new UiState();   // MENU in the viewfinder: the darkroom getting ready across the restart (v0.37.0), built as Relaunch builds it
        s.width = W; s.battery = 82;
        s.mode = UiState.DARKROOM; s.devTotal = 3; s.devPreparing = true;
        s.film = new UiState.Film(); s.film.title = cuban.title; s.film.iso = cuban.iso; s.film.accent = cuban.accent; s.film.body = cuban.body; s.film.band = cuban.band;
        render(s, "8v-darkroom-getting-ready");

        s = state();
        s.mode = UiState.SHEET; s.film = cuban; s.rollNumber = 3; s.thumbs = thumbs; s.sheetSel = 6;
        render(s, "9-sheet");
        s.filingLeft = 2;
        render(s, "9b-sheet-filing");
        s.filingLeft = 0;
        s = state();
        s.mode = UiState.SHEET;
        render(s, "9c-sheet-no-rolls");
        s = state();
        s.mode = UiState.SHEET; s.film = portra; s.rollNumber = 4; s.sheetUndeveloped = true;
        render(s, "9d-sheet-undeveloped");

        s = state();
        s.mode = UiState.VIEW; s.film = cuban; s.viewImage = img(samples, "HOUSE-PRV.png"); s.sheetSel = 6; s.sheetTotal = 24; s.viewCaption = true;
        render(s, "10-view-fit");
        s.viewFill = true; s.viewCaption = false;
        render(s, "11-view-fill");
        BufferedImage prv = (BufferedImage) img(samples, "HOUSE-PRV.png");
        s.viewFill = false; s.viewCaption = false;
        s.viewImage = prv.getSubimage(0, 0, Math.min(prv.getWidth(), 317), prv.getHeight());   // upright, and an odd width: edges off whole dots
        render(s, "10b-view-fit-upright");

        s = state();
        s.mode = UiState.PROBE; s.probeStage = 0; s.probeTitle = "Hardware develop test"; s.probeSteps = HardwareTest.STEPS;
        s.probePlan = Ui.HARDWARE_PLAN; s.probeFooter = Ui.HARDWARE_FOOTER;
        render(s, "13-probe-intro");
        s = state();
        s.mode = UiState.PROBE; s.probeStage = 0; s.probeTitle = "Screen test"; s.probeSteps = ScreenTest.STEPS;
        s.probePlan = Ui.SCREEN_PLAN; s.probeFooter = Ui.SCREEN_FOOTER;
        render(s, "13s-screen-test-intro");
        s = state();
        s.mode = UiState.PROBE; s.probeStage = 0; s.probeTitle = "Shooting test"; s.probeSteps = ShootingTest.STEPS;
        s.probePlan = Ui.SHOOT_PLAN; s.probeFooter = Ui.SHOOT_FOOTER;
        render(s, "13b-shooting-intro");
        s.probeStage = 2;
        s.probeStatus = new int[] { Probe.OK, Probe.OK, Probe.OK, Probe.INFO, Probe.OK, Probe.OK, Probe.OK, Probe.OK, Probe.OK, Probe.OK };
        s.probeDetail = new String[] { "opened in 662 ms", "on screen", "af-s, center, faces [off, on]", "no sensor readings; display roll 0",
                "locked in 1049 ms", "DSC04681.JPG stored: shutter 31 ms, in the catalogue 79 ms later", "29 in 3.0 s (9.6 a second), 640x424, 31 KB each",
                "Tri-X: 640x424 on 1 core 419 ms, 640x424 on 3 cores 412 ms, 320x212 on 3 cores 142 ms", "Tri-X: DSC04682.JPG in 7.9 s",
                "live view stopped, camera released" };
        render(s, "13c-shooting-results");
        s.probeStage = 2; s.probeTitle = "Camera roll test"; s.probeSteps = Probe.STEPS;
        s.probeStatus = new int[] { Probe.INFO, Probe.OK, Probe.OK, Probe.OK, Probe.OK, Probe.FAIL, Probe.OK };
        s.probeDetail = new String[] { "ILCE-5100, Android 2.3.7, build 3.10", "all present: catalog, catalog query, catalog delete, hardware decoder",
                "4 of 4 found (not loaded, not called)", "212 photos catalogued on card 1", "6000x4000 in 1012 ms (our software decode: about 10,000 ms)",
                "the camera did not catalogue it (212 -> 212 photos; no catalog events)", "removed (it was never catalogued)" };
        render(s, "14-probe-results");
        s.probeStage = 0; s.probeTitle = "Camera settings check"; s.probeSteps = SettingsCheck.STEPS;
        s.probePlan = Ui.SETTINGS_PLAN; s.probeFooter = Ui.SETTINGS_FOOTER;
        render(s, "15-settings-intro");
        s.probeStage = 2;
        s.probeStatus = new int[] { Probe.OK, Probe.OK, Probe.OK, Probe.OK, Probe.OK, Probe.INFO, Probe.OK };
        s.probeDetail = new String[] { "15 of 15 settings read; nothing written", "Auto", "menu A2 / G1;  Auto applies A2 / G1", "Standard",
                "contrast 0, saturation +1, sharpness 0", "Picture Effect Off, DRO Auto, exposure 0/3", "yes: none of them is write-protected (nothing was written)" };
        render(s, "16-settings-results");
        s.probeStage = 2; s.probeTitle = "Speed test"; s.probeSteps = SpeedTest.STEPS;
        s.probeStatus = new int[] { Probe.OK, Probe.OK, Probe.INFO, Probe.INFO, Probe.INFO, Probe.OK, Probe.OK };
        s.probeDetail = new String[] { "DSC04625.JPG, 3000x2000; through RGB 2874 ms", "1395 ms at half size, off by 0.33 on average",
                "none clearly faster (colour reuse 2171, packed table 1378, smaller table 1395 ms)", "present 0-3, on 0-3, Latent allowed 3",
                "cpu0 4% cpu1 0% cpu2 1% cpu3 38%", "1.9x (1395 -> 731 ms), same picture; main cpu 3, helper cpu 1; asked 0xf, got 0xf",
                "per pair, one core (two cores not used yet)" };
        render(s, "17-speed-results");
        s.probeStage = 0; s.probeTitle = "Develop test"; s.probeSteps = DevelopTest.STEPS;
        s.probePlan = Ui.DEVTEST_PLAN; s.probeFooter = Ui.DEVTEST_FOOTER;
        render(s, "17b-develop-intro");
        s.probeStage = 2;
        s.probeStatus = new int[] { Probe.OK, Probe.OK, Probe.OK, Probe.OK, Probe.OK, Probe.OK, Probe.OK, Probe.OK };
        s.probeDetail = new String[] { "DSC04811.JPG, then DSC04810.JPG", "verified up to 2048 KB per transfer", "decode 576, image 445, develop 2240 ms",
                "yes: decode 560, image 440 ms", "1750 ms, DSC04812.JPG", "both in 1880 ms, one after the other 2750",
                "identical, byte for byte", "yes: about 870 ms a frame to gain" };
        render(s, "17c-develop-results");

        s = state();
        s.mode = UiState.LAB; s.labImage = img(samples, "PEOPLE-PORTRA4W.png"); s.labCaption = "DSC04547.JPG";
        s.labRows = new String[] { "Photo", "Film", "Highlight", "Shadow", "Grain", "Grain size", "Preview", "Save copy" };
        s.labValues = new String[] { "3 of 212", "Portra 400", "\u20131", "\u20131", "4", "Fine", "Whole", "" };
        s.labRow = 1;
        render(s, "12-lab");
        partials(six, m, thumbs, s.labImage);

        s = state();
        s.film = classic; s.rollNumber = 12; s.exposed = 9; s.length = 24;
        s.toast = "Could not load film: the card is write-protected, or full. Check the switch on its side, or make room on it.";
        render(s, "18-toast-long");

        if (a.length > 3) compare(new File(a[3]));
        System.out.println(rendered.size() + " screens rendered; camera-true in " + new File(out, "camera"));
        if (!problems.isEmpty()) {
            for (String p : problems) System.out.println("PROBLEM: " + p);
            throw new IllegalStateException(problems.size() + " problem(s) in the screens");
        }
        System.out.println("checks: layers, overlay bands, window key, text sizes, contrast, overlaps, side-bar fit, palette: all pass");
    }

    /** The menu on this page with this row chosen, as MainActivity builds it. */
    static UiState menu(Ui.Menu m, int page, int sel, UiState.Film film) {
        UiState s = state();
        s.mode = UiState.SETTINGS;
        s.film = film;
        Ui.menuState(s, page, m, sel);
        while (s.rowSel < s.rows.size() - 1 && s.rows.get(s.rowSel)[1].length() == 0) Ui.menuState(s, page, m, s.rowSel + 1);   // headings are passed over
        return s;
    }

    /** A viewfinder: these looks, this one chosen, live view standing in as a still. */
    static UiState vf(List<UiState.Film> looks, int sel, Object live) {
        UiState s = state();
        s.mode = UiState.PHOTO; s.looks = looks; s.lookSel = sel; s.liveImage = live; s.lookLive = true;
        s.exposed = 9; s.length = 24; s.battery = 74; s.shotsLeft = 1204; s.afMode = "AF-S"; s.afArea = "Centre";
        s.shutter = "1/125"; s.aperture = "F5.6"; s.iso = "400"; s.isoAuto = true;
        return s;
    }

    static UiState state() {
        UiState s = new UiState();
        s.width = W;
        s.battery = 82;
        return s;
    }

    static Object img(File dir, String name) throws Exception {
        File f = new File(dir, name);
        return f.exists() ? ImageIO.read(f) : null;
    }

    static void render(UiState s, String name) throws Exception { renderOver(s, name, null); }

    /** With 'video' drawn first where the camera's live view would be (3:2, full height, centred). */
    static void renderOver(UiState s, String name, Object video) throws Exception {
        BufferedImage img = new BufferedImage(Math.round(s.width), 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        if (video != null) {
            BufferedImage v = (BufferedImage) video;
            g.drawImage(v, Math.round((s.width - 720) / 2), 0, 720, 480, null);
        }
        Audit au = new Audit(new HostGfx(g, fonts), img, s, name);
        Ui.draw(au, s);
        g.dispose();
        au.finish();
        ImageIO.write(img, "png", new File(out, name + ".png"));
        camera(s, name, (BufferedImage) video);
        if (s.mode == UiState.PHOTO) bands(s, name);
        else if (!s.live) covered(s, name);
        rendered.add(name);
    }

    /** The camera's own rendering of the screen: camera/NAME.png (and camera-sun/ with -sun). */
    static void camera(UiState s, String name, BufferedImage video) throws Exception {
        BufferedImage v = CameraTrue.view(CameraTrue.dots(s, fonts, video), s.width);
        ImageIO.write(v, "png", new File(out, "camera/" + name + ".png"));
        if (sun) ImageIO.write(CameraTrue.sun(v), "png", new File(out, "camera-sun/" + name + ".png"));
    }

    /** Only the checks (not written out): a state drawn through the audit. */
    static void audit(UiState s, String name) throws Exception {
        BufferedImage img = new BufferedImage(Math.round(s.width), 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        Audit au = new Audit(new HostGfx(g, fonts), img, s, name);
        Ui.draw(au, s);
        g.dispose();
        au.finish();
    }

    /**
     * Every pixel of the camera's screen is drawn: Choose film and the viewer don't fill the screen black under their
     * pictures, and Latent's window keeps whatever it held where nothing is drawn. Drawn at the camera's 640 dots
     * over magenta, none may be left.
     */
    static void covered(UiState s, String name) throws Exception {
        BufferedImage img = new BufferedImage(CameraTrue.DOTS, 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0xFF00FF));
        g.fillRect(0, 0, CameraTrue.DOTS, 480);
        g.scale(CameraTrue.DOTS / s.width, 1);
        Ui.draw(new HostGfx(g, fonts), s);
        g.dispose();
        int left = 0;
        for (int y = 0; y < 480; y++) for (int x = 0; x < CameraTrue.DOTS; x++) {
            int p = img.getRGB(x, y);
            if (((p >> 16) & 255) > 200 && (p & 255) > 200 && ((p >> 8) & 255) < 60) left++;
        }
        if (left > 0) problems.add(name + ": " + left + " pixels of the screen left undrawn (the window would show stale pixels there)");
    }

    static int partials;
    static double partialShare;

    /**
     * A key's partial redraw is enough: every pixel 'b' draws differently from 'a' lies inside Ui.dirty(a, b) (the
     * camera widens it by a dot each side; here by a design pixel).
     */
    static void partial(UiState a, UiState b, String what) throws Exception {
        float[] r = Ui.dirty(a, b);
        if (r == null) { problems.add(what + ": no partial redraw (Ui.dirty gave the whole screen)"); return; }
        BufferedImage ia = whole(a), ib = whole(b);
        int outside = 0, changed = 0;
        for (int y = 0; y < 480; y++) for (int x = 0; x < ia.getWidth(); x++) {
            if (ia.getRGB(x, y) == ib.getRGB(x, y)) continue;
            changed++;
            if (x < r[0] - 1 || x >= r[0] + r[2] + 1 || y < r[1] - 1 || y >= r[1] + r[3] + 1) outside++;
        }
        if (outside > 0) problems.add(what + ": " + outside + " of " + changed + " changed pixels lie outside Ui.dirty's rect");
        partials++;
        partialShare += Math.min(1, r[2] * r[3] / (b.width * 480));
    }

    /** The keys that redraw only part of the screen, each checked (Choose film, the menu, the contact sheet, the Lab). */
    static void partials(List<UiState.Film> six, Ui.Menu m, List<Object> thumbs, Object labImage) throws Exception {
        for (int f : new int[] { 2, 4 }) for (int l = 0; l < 3; l++) {   // UP/DOWN on Choose film
            UiState a = state();
            a.mode = UiState.LOAD; a.films = six; a.filmSel = f; a.lengthSel = l; a.liveCaption = "on";
            UiState b = a.copy();
            b.lengthSel = l + 1;
            partial(a, b, "Choose film, length " + l + " to " + (l + 1));
            partial(b, a, "Choose film, length " + (l + 1) + " to " + l);
        }
        for (int page = Ui.PAGE_MENU; page <= Ui.PAGE_CHECKS; page++) {   // the menu, a row at a time
            List<String[]> rows = Ui.menuRows(page, m);
            int prev = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i)[1].length() == 0) continue;
                if (prev >= 0) {
                    UiState a = menu(m, page, prev, null), b = menu(m, page, i, null);
                    if (Ui.dirty(a, b) != null) { partial(a, b, "menu " + prev + " to " + i); partial(b, a, "menu " + i + " to " + prev); }
                }
                prev = i;
            }
        }
        Ui.Menu off = new Ui.Menu();   // a switch turned off where it was on
        off.roll = m.roll; off.developNow = m.developNow; off.changeFilm = m.changeFilm; off.exposed = m.exposed; off.rolls = m.rolls;
        off.viewfinder = false; off.focusFirst = m.focusFirst; off.liveLook = m.liveLook; off.threeCores = m.threeCores; off.threeCoresOk = m.threeCoresOk;
        off.neutral = m.neutral; off.looks = m.looks; off.version = m.version;
        partial(menu(m, Ui.PAGE_MENU, 5, null), menu(off, Ui.PAGE_MENU, 5, null), "menu, a switch");
        for (int[] t : new int[][] { { 6, 7 }, { 7, 6 }, { 4, 5 }, { 0, 1 }, { 13, 14 } }) {   // the contact sheet, a frame along
            UiState a = state();
            a.mode = UiState.SHEET; a.film = six.get(3); a.rollNumber = 3; a.thumbs = thumbs; a.sheetSel = t[0];
            UiState b = a.copy();
            b.sheetSel = t[1];
            partial(a, b, "contact sheet, frame " + t[0] + " to " + t[1]);
        }
        UiState a = state();   // the Lab: a row, then a value
        a.mode = UiState.LAB; a.labImage = labImage; a.labCaption = "DSC04547.JPG";
        a.labRows = new String[] { "Photo", "Film", "Highlight", "Shadow", "Grain", "Grain size", "Preview", "Save copy" };
        a.labValues = new String[] { "3 of 212", "Portra 400", "–1", "–1", "4", "Fine", "Whole", "" };
        a.labRow = 1;
        UiState b = a.copy();
        b.labRow = 2;
        partial(a, b, "Lab, a row");
        UiState c = b.copy();
        c.labValues = a.labValues.clone();
        c.labValues[2] = "0";
        partial(b, c, "Lab, a value");
        UiState d = b.copy();
        d.labRow = 7;
        partial(b, d, "Lab, to Save copy");
        System.out.println("partial redraws: " + partials + " key changes checked, " + Math.round(partialShare * 100 / Math.max(1, partials)) + "% of the screen redrawn on average");
    }

    /** Two states draw the same pixels in this box (x0, y0, x1, y1). */
    static void same(UiState a, UiState b, String what, int x0, int y0, int x1, int y1) throws Exception {
        BufferedImage ia = whole(a), ib = whole(b);
        for (int y = y0; y < y1; y++) for (int x = x0; x < Math.min(x1, ia.getWidth()); x++) {
            if (ia.getRGB(x, y) != ib.getRGB(x, y)) { problems.add(what + ": differ at " + x + "," + y); return; }
        }
    }

    static BufferedImage whole(UiState s) throws Exception {
        BufferedImage img = new BufferedImage(Math.round(s.width), 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        Ui.draw(new HostGfx(g, fonts), s);
        g.dispose();
        return img;
    }

    /** The viewfinder's overlay draws nothing outside its two bands (unless the grid is on): the overlay thread reads back only those. */
    static void bands(UiState s, String name) throws Exception {
        if (s.grid) return;
        BufferedImage o = overlay(s);
        for (int y = Ui.OVL_TOP_END; y < Ui.OVL_BOTTOM_START; y++) for (int x = 0; x < o.getWidth(); x++) {
            if ((o.getRGB(x, y) >>> 24) != 0) { problems.add(name + ": the overlay draws at row " + y + ", outside its bands"); return; }
        }
    }

    /** The overlay alone, at the picture's 720 x 480 of the layout, on transparent. */
    static BufferedImage overlay(UiState s) throws Exception {
        BufferedImage o = new BufferedImage(720, 480, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = o.createGraphics();
        g.translate(-(s.width - 720) / 2, 0);
        UiState c = s.copy();
        c.vfLayer = UiState.LAYER_OVERLAY;
        c.vfPart = UiState.PART_ALL;
        Ui.draw(new HostGfx(g, fonts), c);
        g.dispose();
        return o;
    }

    /** Latent's window alone in the viewfinder (PART_WINDOW), as its pixels. */
    static int[] window(UiState s) throws Exception {
        BufferedImage w = new BufferedImage(Math.round(s.width), 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = w.createGraphics();
        UiState c = s.copy();
        c.vfLayer = UiState.LAYER_SCREEN;
        c.vfPart = UiState.PART_WINDOW;
        c.liveImage = null;
        Ui.draw(new HostGfx(g, fonts), c);
        g.dispose();
        return w.getRGB(0, 0, w.getWidth(), 480, null, 0, w.getWidth());
    }

    /**
     * Ui.windowKey is complete: change any one field of a viewfinder state, and if the key stays the same the
     * window must draw the same pixels (else a side bar would go stale, as the window is redrawn only on a new key).
     */
    static void windowKeys(List<UiState.Film> looks, Object live) throws Exception {
        List<UiState> bases = new ArrayList<UiState>();
        UiState b = vf(looks, 2, live);
        bases.add(b);
        UiState e = b.copy(); e.evEdit = true; e.ev = 2; bases.add(e);
        UiState l = b.copy(); l.afLamp = true; l.afState = 1; l.developing = 2; bases.add(l);
        UiState c = b.copy(); c.clean = true; bases.add(c);
        int checked = 0;
        for (UiState base : bases) {
            String key = Ui.windowKey(base);
            int[] px = window(base);
            for (Field f : UiState.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                for (Object v : variants(f, f.get(base), looks)) {
                    UiState t = base.copy();
                    f.set(t, v);
                    if (!Ui.windowKey(t).equals(key)) continue;
                    checked++;
                    if (!Arrays.equals(px, window(t))) problems.add("windowKey misses " + f.getName() + " (" + v + "): the window changes but the key doesn't");
                }
            }
        }
        System.out.println("window key: " + checked + " changes that keep the key draw the same window");
    }

    /** Other values for a field, to see whether the window notices them. */
    static List<Object> variants(Field f, Object v, List<UiState.Film> looks) {
        List<Object> out = new ArrayList<Object>();
        Class<?> t = f.getType();
        if (t == boolean.class) out.add(!(Boolean) v);
        else if (t == int.class) { int i = (Integer) v; for (int d : new int[] { -1, 1, 2, 3, 5, 13 }) out.add(i + d); out.add(0); }
        else if (t == long.class) out.add((Long) v + 60000);
        else if (t == float.class) { out.add((Float) v + 0.5f); out.add(0f); }
        else if (t == String.class) { out.add(""); out.add("Wide"); out.add("AF-C"); out.add(v == null ? "x" : v + "x"); }
        else if (t == List.class && f.getName().equals("looks")) {
            List<UiState.Film> other = new ArrayList<UiState.Film>();
            for (UiState.Film x : looks) { UiState.Film y = new UiState.Film(); y.title = x.title + "!"; y.accent = 0xFF55DD88; y.body = x.body; y.band = x.band; other.add(y); }
            out.add(other);
            out.add(new ArrayList<UiState.Film>());
        } else if (!t.isPrimitive()) out.add(null);
        return out;
    }

    /** Every value a side bar can show (Sony's AF modes and areas, photos left, the keys, the exposure scale) fits it. */
    static void sideBarText() throws Exception {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        HostGfx h = new HostGfx(g, fonts);
        String[] values = { "MF", "DMF", "AF", "AF-S", "AF-C", "AF-A", "AF-D", "Centre", "Wide", "Spot", "Multi", "Zone", "Tracking",
                "0", "99999", "Develop", "Back", "Look", "Done" };
        for (String v : values) {
            String shown = Ui.barValue(h, v);
            if (!shown.equals(v) && !v.equals("Tracking")) problems.add("side bar: '" + v + "' doesn't fit even at " + Ui.FLOOR + " px");
            float size = Ui.fit(h, shown, Gfx.MEDIUM, Ui.T_SECOND, Ui.FLOOR, Ui.BAR_TEXT, 0);
            if (h.textWidth(shown, Gfx.MEDIUM, size, 0) > Ui.BAR_TEXT) problems.add("side bar: '" + shown + "' is wider than the bar");
        }
        for (int ev = -9; ev <= 9; ev++) {
            String t = Ui.evText(ev);
            float size = Ui.fit(h, t, Gfx.MEDIUM, Ui.T_READOUT, Ui.FLOOR, Ui.BAR_TEXT, 0);
            if (h.textWidth(t, Gfx.MEDIUM, size, 0) > Ui.BAR_TEXT) problems.add("exposure track: '" + t + "' is wider than the bar");
        }
        for (String label : new String[] { "FOCUS", "AREA", "LEFT", "EV", "AF", "LIGHT" })
            if (h.textWidth(label, Gfx.MEDIUM, Ui.T_LABEL, Ui.LABEL_TRACK) > Ui.BAR_TEXT) problems.add("side bar: the label " + label + " is wider than the bar");
        if (Ui.glyphWidth(h, "menu") > Ui.BAR_TEXT) problems.add("side bar: the MENU key is wider than the bar");
        g.dispose();
    }

    /** The palette and the films' colours: on the 4-bit grid, and text colours above their floors. */
    static void tokens() {
        int[] grid = { Ui.BG, Ui.INK, Ui.INK2, Ui.INK3, Ui.DIM, Ui.LINE, Ui.SURFACE, Ui.SELECT, Ui.GREEN, Ui.AMBER, Ui.RED, Ui.RED2, Ui.RED3, Ui.RED4, Ui.PLATE };
        for (int c : grid) if (!onGrid(c)) problems.add(String.format("palette: %08X is off the 4-bit grid", c));
        for (Stock st : Stock.builtIn()) {
            for (int c : new int[] { st.body, st.band, st.accent }) if (!onGrid(c)) problems.add(String.format("%s: %08X is off the 4-bit grid", st.name, c));
            if (contrast(st.accent, Ui.BG) < 4.5) problems.add(st.name + ": its accent reads at " + contrast(st.accent, Ui.BG) + ":1 on black");
            if (contrast(st.accent, Ui.PLATE) < 3) problems.add(st.name + ": its accent reads at " + contrast(st.accent, Ui.PLATE) + ":1 on a plate");
        }
        check("INK2 on black", Ui.INK2, Ui.BG, 4.5);
        check("INK3 on black", Ui.INK3, Ui.BG, 4.5);
        check("INK on a chosen row", Ui.INK, Ui.SELECT, 4.5);
        check("INK2 on a chosen row (20 px)", Ui.INK2, Ui.SELECT, 3);
        check("RED on black", Ui.RED, Ui.BG, 4.5);
        check("RED2 on black (18 px and up)", Ui.RED2, Ui.BG, 3);
        check("GREEN on black", Ui.GREEN, Ui.BG, 4.5);
        check("AMBER on a plate", Ui.AMBER, Ui.PLATE, 4.5);
        check("INK on a plate", Ui.INK, Ui.PLATE, 4.5);
        check("INK2 on a plate", Ui.INK2, Ui.PLATE, 4.5);
    }

    static void check(String what, int fg, int bg, double min) {
        double c = contrast(fg, bg);
        if (c < min) problems.add(String.format("%s: %.2f:1, under %.1f:1", what, c, min));
    }

    static boolean onGrid(int c) {
        for (int sh = 0; sh <= 16; sh += 8) if (((c >> sh) & 255) % 17 != 0) return false;
        return true;
    }

    static double lum(int c) {
        double[] v = new double[3];
        for (int i = 0; i < 3; i++) {
            double x = ((c >> (16 - 8 * i)) & 255) / 255.0;
            v[i] = x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    static double contrast(int a, int b) {
        double la = lum(a), lb = lum(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /**
     * Gfx that draws through HostGfx and checks each text as it goes: its size against the floors, its contrast
     * against what is under it (where that is plain: over a photo it can't be judged this way), and that it
     * overlaps no other text.
     */
    static final class Audit implements Gfx {
        final HostGfx g;
        final BufferedImage img;
        final UiState s;
        final String name;
        final List<float[]> boxes = new ArrayList<float[]>();
        final List<String> texts = new ArrayList<String>();

        Audit(HostGfx g, BufferedImage img, UiState s, String name) { this.g = g; this.img = img; this.s = s; this.name = name; }

        public void rect(float x, float y, float w, float h, int c) { g.rect(x, y, w, h, c); }
        public void roundRect(float x, float y, float w, float h, float r, int c) { g.roundRect(x, y, w, h, r, c); }
        public void strokeRoundRect(float x, float y, float w, float h, float r, float width, int c) { g.strokeRoundRect(x, y, w, h, r, width, c); }
        public void circle(float cx, float cy, float r, int c) { g.circle(cx, cy, r, c); }
        public void strokeCircle(float cx, float cy, float r, float width, int c) { g.strokeCircle(cx, cy, r, width, c); }
        public void line(float x1, float y1, float x2, float y2, float width, int c) { g.line(x1, y1, x2, y2, width, c); }
        public float textWidth(String t, int font, float size, int tracking) { return g.textWidth(t, font, size, tracking); }
        public void image(Object i, float x, float y, float w, float h, boolean red) { g.image(i, x, y, w, h, red); }
        public void imageExact(Object i, float x, float y, float w, float h) { g.imageExact(i, x, y, w, h); }
        public int imageWidth(Object i) { return g.imageWidth(i); }
        public int imageHeight(Object i) { return g.imageHeight(i); }

        public void text(String t, float x, float y, int font, float size, int color, int align, int tracking) {
            if (t.trim().length() > 0) check(t, x, y, font, size, color, align, tracking);
            g.text(t, x, y, font, size, color, align, tracking);
        }

        private void check(String t, float x, float y, int font, float size, int color, int align, int tracking) {
            boolean key = t.equals("MENU") && size < Ui.FLOOR;   // the MENU key's own label: part of its glyph
            if (!key && size < Ui.FLOOR - 0.01f) problems.add(name + ": '" + t + "' at " + size + " px (the floor is " + Ui.FLOOR + ")");
            boolean caps = t.equals(t.toUpperCase(java.util.Locale.US));   // a label, however many words
            if (!caps && t.split(" ").length >= 3 && size < Ui.FLOOR_SENTENCE) problems.add(name + ": the sentence '" + t + "' at " + size + " px (the floor is " + Ui.FLOOR_SENTENCE + ")");
            float w = g.textWidth(t, font, size, tracking), x0 = align == CENTER ? x - w / 2 : align == RIGHT ? x - w : x;
            float[] box = { x0, y - size * 0.72f, x0 + w, y };
            if (box[0] < -0.5f || box[2] > s.width + 0.5f) problems.add(name + ": '" + t + "' runs off the screen");
            // contrast against a plain background
            int bx0 = Math.max(0, (int) box[0]), bx1 = Math.min(img.getWidth() - 1, (int) box[2]), by0 = Math.max(0, (int) box[1]), by1 = Math.min(479, (int) box[3]);
            if (bx1 > bx0 && by1 > by0) {
                double lo = 1, hi = 0;
                long r = 0, gg = 0, b = 0, n = 0;
                for (int yy = by0; yy <= by1; yy++) for (int xx = bx0; xx <= bx1; xx++) {
                    int p = img.getRGB(xx, yy);
                    double l = lum(p);
                    lo = Math.min(lo, l); hi = Math.max(hi, l);
                    r += (p >> 16) & 255; gg += (p >> 8) & 255; b += p & 255; n++;
                }
                if (hi - lo < 0.02) {   // plain: a colour, not a photo
                    int bg = 0xFF000000 | (int) (r / n) << 16 | (int) (gg / n) << 8 | (int) (b / n);
                    int fg = over(color, bg);
                    double need = size >= 18 ? 3 : 4.5, c = contrast(fg, bg);
                    if (c < need - 0.005) problems.add(String.format("%s: '%s' (%.0f px) reads at %.2f:1 on %06X, under %.1f:1", name, t, size, c, bg & 0xFFFFFF, need));
                }
            }
            // no text over another (in the viewfinder, only its side bars: over the picture the card and the keys sit on top by design)
            boolean picture = s.mode == UiState.PHOTO && box[2] > (s.width - 720) / 2 && box[0] < (s.width + 720) / 2;
            if (picture) return;
            for (int i = 0; i < boxes.size(); i++) {
                float[] o = boxes.get(i);
                if (Math.min(o[2], box[2]) - Math.max(o[0], box[0]) > 1 && Math.min(o[3], box[3]) - Math.max(o[1], box[1]) > 1)
                    problems.add(name + ": '" + t + "' overlaps '" + texts.get(i) + "'");
            }
            boxes.add(box);
            texts.add(t);
        }

        void finish() { }

        static int over(int fg, int bg) {
            int a = fg >>> 24;
            int out = 0xFF000000;
            for (int sh = 0; sh <= 16; sh += 8) out |= ((((fg >> sh) & 255) * a + ((bg >> sh) & 255) * (255 - a)) / 255) << sh;
            return out;
        }
    }

    /**
     * The viewfinder in layers (v0.38.0), as the camera builds it: the picture with the overlay layer laid in
     * (the composer's job), then the screen layer over it. Written beside the whole drawing, with the share of
     * pixels that differ, which must stay small (only the stacking order of type and focus marks may differ).
     */
    static void layers(UiState s, String name) throws Exception {
        int w = Math.round(s.width);
        BufferedImage base = new BufferedImage(720, 480, BufferedImage.TYPE_INT_RGB);   // the picture at the layout's own size
        Graphics2D bg = base.createGraphics();
        bg.drawImage((BufferedImage) s.liveImage, 0, 0, 720, 480, null);
        bg.dispose();
        s.liveImage = base;
        BufferedImage all = new BufferedImage(w, 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = all.createGraphics();
        s.vfLayer = UiState.LAYER_ALL;
        Ui.draw(new HostGfx(g, fonts), s);
        g.dispose();
        // the composer's picture: the live image, then the overlay drawn alone over it
        BufferedImage pic = new BufferedImage(720, 480, BufferedImage.TYPE_INT_RGB);
        g = pic.createGraphics();
        g.drawImage(base, 0, 0, null);
        g.translate(-(s.width - 720) / 2, 0);
        UiState o = s.copy();
        o.vfLayer = UiState.LAYER_OVERLAY;
        Ui.draw(new HostGfx(g, fonts), o);
        g.dispose();
        BufferedImage lay = new BufferedImage(w, 480, BufferedImage.TYPE_INT_RGB);
        g = lay.createGraphics();
        UiState sc = s.copy();
        sc.vfLayer = UiState.LAYER_SCREEN;
        sc.liveImage = pic;
        Ui.draw(new HostGfx(g, fonts), sc);
        g.dispose();
        int diff = 0;
        for (int y = 0; y < 480; y++) for (int x = 0; x < w; x++) {
            int p = all.getRGB(x, y), q = lay.getRGB(x, y);
            if (Math.abs((p >> 16 & 255) - (q >> 16 & 255)) + Math.abs((p >> 8 & 255) - (q >> 8 & 255)) + Math.abs((p & 255) - (q & 255)) > 24) diff++;
        }
        ImageIO.write(lay, "png", new File(out, name + ".png"));
        camera(s, name, null);
        BufferedImage dm = new BufferedImage(w, 480, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 480; y++) for (int x = 0; x < w; x++) {
            int p = all.getRGB(x, y), q = lay.getRGB(x, y);
            int d = Math.abs((p >> 16 & 255) - (q >> 16 & 255)) + Math.abs((p >> 8 & 255) - (q >> 8 & 255)) + Math.abs((p & 255) - (q & 255));
            dm.setRGB(x, y, d > 24 ? 0xFF0000 : (p & 0xFEFEFE) >> 1);
        }
        ImageIO.write(dm, "png", new File(out, name + "-diff.png"));
        System.out.println(name + ": layered differs from whole on " + String.format("%.2f", diff * 100.0 / (w * 480)) + "% of pixels");
        if (diff * 100.0 / (w * 480) > 1.0) problems.add(name + ": the layers don't add up to the viewfinder");

        // and in parts (v0.43.1): Latent's screen draws the side bars; the picture's own surface, on top, gets
        // the composed picture and then what moves over it
        BufferedImage parts = new BufferedImage(w, 480, BufferedImage.TYPE_INT_RGB);
        g = parts.createGraphics();
        UiState win = s.copy();
        win.vfLayer = UiState.LAYER_SCREEN; win.vfPart = UiState.PART_WINDOW; win.liveImage = null;
        Ui.draw(new HostGfx(g, fonts), win);
        g.dispose();
        BufferedImage surf = new BufferedImage(720, 480, BufferedImage.TYPE_INT_RGB);
        g = surf.createGraphics();
        g.drawImage(pic, 0, 0, null);
        g.translate(-(s.width - 720) / 2, 0);
        UiState sp = s.copy();
        sp.vfLayer = UiState.LAYER_SCREEN; sp.vfPart = UiState.PART_PICTURE; sp.liveImage = null;
        Ui.draw(new HostGfx(g, fonts), sp);
        g.dispose();
        g = parts.createGraphics();
        g.drawImage(surf, Math.round((s.width - 720) / 2), 0, null);
        g.dispose();
        int pdiff = 0;
        for (int y = 0; y < 480; y++) for (int x = 0; x < w; x++) {
            int p = all.getRGB(x, y), q = parts.getRGB(x, y);
            if (Math.abs((p >> 16 & 255) - (q >> 16 & 255)) + Math.abs((p >> 8 & 255) - (q >> 8 & 255)) + Math.abs((p & 255) - (q & 255)) > 24) pdiff++;
        }
        ImageIO.write(parts, "png", new File(out, name + "-parts.png"));
        System.out.println(name + ": in parts differs from whole on " + String.format("%.2f", pdiff * 100.0 / (w * 480)) + "% of pixels");
        if (pdiff * 100.0 / (w * 480) > 1.0) problems.add(name + ": the parts don't add up to the viewfinder");
        if (!s.grid) bands(s, name);
        rendered.add(name);
    }

    /** The screens to judge side by side: an earlier version's camera render (left) and this one's (right). */
    static final String[] COMPARE = { "1-home-choose-classic", "1b-home-choose-trix-endless", "1e-home-first-run", "3-home-roll", "2b-home-settling",
            "5-home-finished-lowbattery", "6-menu", "6d-menu-camera-checks", "8-vf-ready", "8c-vf-locked", "8d-vf-cant-focus", "8s-vf-exposure-plus-third",
            "8q-vf-endless-film-card", "8y-vf-coach", "8z-vf-fatal", "8l-vf-roll-finished", "8-darkroom", "9-sheet", "10-view-fit", "12-lab", "13-probe-intro" };

    /** compare/NAME.png: before | after, labelled, at the camera's look. */
    static void compare(File base) throws Exception {
        File dir = new File(out, "compare");
        dir.mkdirs();
        int n = 0;
        for (String name : COMPARE) {
            File b = new File(base, "camera/" + name + ".png"), a = new File(out, "camera/" + name + ".png");
            if (!b.exists() || !a.exists()) continue;
            BufferedImage bi = ImageIO.read(b), ai = ImageIO.read(a);
            int gap = 16, top = 34;
            BufferedImage c = new BufferedImage(bi.getWidth() + gap + ai.getWidth(), top + 480, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = c.createGraphics();
            g.setColor(new Color(0x2A2A2A));
            g.fillRect(0, 0, c.getWidth(), c.getHeight());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(Font.createFont(Font.TRUETYPE_FONT, new File(fonts, "inter-medium.ttf")).deriveFont(16f));
            g.setColor(new Color(0xBBBBBB));
            g.drawString("before (v0.44.1)  \u00b7  " + name, 10, 23);
            g.drawString("after (v0.45.0)", bi.getWidth() + gap + 10, 23);
            g.drawImage(bi, 0, top, null);
            g.drawImage(ai, bi.getWidth() + gap, top, null);
            g.dispose();
            ImageIO.write(c, "png", new File(dir, name + ".png"));
            n++;
        }
        System.out.println(n + " before/after pairs in " + dir);
    }
}
