package com.latent.camera;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Renders every Latent screen with realistic data to PNGs, through the same Ui code the camera
 * runs, at the panel's true shape (853x480 for the A5100's 16:9 panel). tools/preview.sh drives it.
 *
 *   RenderScreens FONTDIR SAMPLEDIR OUTDIR
 */
public class RenderScreens {
    static final float W = 480f * 16 / 9;

    public static void main(String[] a) throws Exception {
        File fonts = new File(a[0]), samples = new File(a[1]), out = new File(a[2]);
        out.mkdirs();
        UiState.Film cuban = film("Cuban Neg", "400", "Warm, punchy street colour with deep teal shadows.", 0xFF17675F, 0xFFEC6A2C, 0xFFF0743A);
        UiState.Film portra = film("Portra", "400", "Soft contrast, warm creamy highlights and golden skin.", 0xFFC79A3E, 0xFFF3E7C9, 0xFFE8B653);
        cuban.sample = img(samples, "STREET-CUBANNEG.png");
        portra.sample = img(samples, "STREET-PORTRA4W.png");
        List<Object> thumbs = new ArrayList<Object>();
        String[] names = { "STREET", "HOUSE", "PEOPLE" };
        for (int i = 0; i < 24; i++) thumbs.add(img(samples, names[i % 3] + "-CUBANNEG.png"));

        UiState s;
        UiState.Film natural = film("Natural", "100", "", 0xFF8C9096, 0xFFE9E5DC, 0xFFD9D2C3);
        UiState.Film classic = film("Classic Neg", "400", "", 0xFF1F6E52, 0xFFE8E2D3, 0xFF6CC29E);
        UiState.Film trix = film("Tri-X", "400", "", 0xFF1D1C1A, 0xFFF2C230, 0xFFF4F1EA);
        UiState.Film party = film("Afterparty", "800", "", 0xFF4B2470, 0xFFE8458B, 0xFFF0629F);
        List<UiState.Film> six = Arrays.asList(natural, portra, classic, cuban, trix, party);

        // ---- home: choosing film when the camera is empty; the frame counter with a roll in; MENU holds the rest
        s = state();
        s.mode = UiState.LOAD; s.films = six; s.filmSel = 2; s.lengthSel = 1; s.liveCaption = "on";
        classic.tagline = "Fuji's Classic Negative: muted, hard, nostalgic.";
        classic.sample = img(samples, "STREET-CLASSNEG.png");
        render(s, fonts, out, "1-home-choose-classic");
        trix.tagline = "Deep blacks and a gorgeous coarse grain."; trix.sample = img(samples, "PEOPLE-TRIX400.png");
        s.filmSel = 4; s.lengthSel = 3;
        render(s, fonts, out, "1b-home-choose-trix-endless");
        s.cancelable = true; s.filmSel = 5; party.tagline = "Flash at a dark party: hot skin, deep night."; party.sample = img(samples, "PEOPLE-PARTY800.png");
        render(s, fonts, out, "1c-change-film-party");
        s.loading = true;
        render(s, fonts, out, "1d-loading");
        s = state();
        s.mode = UiState.LOAD; s.films = six; s.filmSel = 0; s.lengthSel = 1; s.liveCaption = "";
        render(s, fonts, out, "1e-home-first-run");

        s = state();
        s.film = portra; s.rollNumber = 5; s.exposed = 0; s.length = 12; s.toast = "Portra 400 loaded";
        render(s, fonts, out, "2-home-fresh-toast");
        s = state();
        s.film = classic; s.rollNumber = 12; s.exposed = 9; s.length = 24;
        render(s, fonts, out, "3-home-roll");
        s = state();
        s.film = trix; s.rollNumber = 13; s.exposed = 23; s.endless = true; s.developing = 2;
        for (int i = 0; i < 6; i++) s.thumbs.add(img(samples, names[i % 3] + "-TRIX400.png"));
        render(s, fonts, out, "4-home-endless");
        s = state();
        s.film = trix; s.rollNumber = 13; s.exposed = 0; s.endless = true;
        render(s, fonts, out, "4b-home-endless-fresh");
        s = state();
        s.film = portra; s.rollNumber = 4; s.exposed = 12; s.length = 12; s.battery = 17;
        render(s, fonts, out, "5-home-finished-lowbattery");

        s = state();
        s.mode = UiState.SETTINGS;
        s.rows = new ArrayList<String[]>();
        s.rows.add(new String[] { "This roll", "" });
        s.rows.add(new String[] { "Develop now", "\u203a" });
        s.rows.add(new String[] { "Change film", "\u203a" });
        s.rows.add(new String[] { "Rolls", "\u203a" });
        s.rows.add(new String[] { "Developing", "" });
        s.rows.add(new String[] { "Use three cores", "on" });
        s.rows.add(new String[] { "Shoot neutral while a roll is loaded", "on" });
        s.rows.add(new String[] { "Looks", "" });
        s.rows.add(new String[] { "Try looks on a photo", "\u203a" });
        s.rows.add(new String[] { "Camera", "" });
        s.rows.add(new String[] { "Settings check", "\u203a" });
        s.rows.add(new String[] { "Speed test", "\u203a" });
        s.rows.add(new String[] { "Live view test", "\u203a" });
        s.rows.add(new String[] { "Shooting test", "\u203a" });
        s.footer = "Ends the roll here and develops the 9 shot so far.";
        s.rowSel = 1;
        render(s, fonts, out, "6-menu");
        s.rowSel = 13; s.footer = "Latent 0.19.0";
        render(s, fonts, out, "6b-menu-scrolled");

        // ---- the viewfinder (in-app shooting): every focus state, a look change, a shot developing
        classic.tagline = "Muted, hard, nostalgic.";
        int[] hist = new int[64];
        for (int i = 0; i < 64; i++) hist[i] = (int) (30 + 100 * Math.exp(-Math.pow((i - 22) / 9.0, 2)) + 60 * Math.exp(-Math.pow((i - 47) / 6.0, 2)));
        s = vf(six, 2, img(samples, "STREET-CLASSNEG.png"));
        render(s, fonts, out, "8-vf-ready");
        s.afState = 1; s.afLamp = true; s.shutter = "1/60"; s.aperture = "F3.5"; s.iso = "3200"; s.isoAuto = true;
        render(s, fonts, out, "8b-vf-focusing");
        s.afState = 2; s.afLamp = false;
        s.afAreas = new float[][] { { 0.44f, 0.40f, 0.05f, 0.075f }, { 0.50f, 0.40f, 0.05f, 0.075f }, { 0.44f, 0.48f, 0.05f, 0.075f }, { 0.56f, 0.52f, 0.05f, 0.075f } };
        render(s, fonts, out, "8c-vf-locked");
        s = vf(six, 2, img(samples, "PEOPLE-CLASSNEG.png"));
        s.afState = 3; s.afNote = "Too dark to focus"; s.shutter = "1/30"; s.aperture = "F3.5"; s.iso = "6400"; s.isoAuto = true; s.ev = -2; s.afLamp = true;
        render(s, fonts, out, "8d-vf-cant-focus");
        s = vf(six, 4, img(samples, "HOUSE-TRIX400.png"));
        s.lookShown = true;
        render(s, fonts, out, "8e-vf-look-trix");
        s = vf(six, 4, img(samples, "HOUSE-TRIX400.png"));
        s.exposed = 10; s.printImage = img(samples, "PEOPLE-TRIX400.png"); s.printProgress = 0.45f; s.developing = 2; s.grid = true; s.hist = hist;
        render(s, fonts, out, "8f-vf-print-developing");
        s = vf(six, 5, img(samples, "PEOPLE-PARTY800.png"));
        s.endless = true; s.exposed = 37; s.flash = true; s.printImage = img(samples, "STREET-PARTY800.png"); s.printProgress = 0.1f; s.developing = 1;
        render(s, fonts, out, "8g-vf-endless-shot");

        s = state();
        s.mode = UiState.DARKROOM; s.film = cuban; s.devFrame = 4; s.devTotal = 12; s.devDone = 4;
        s.devFramePct = 0.55f; s.devProgress = (4 + 0.55f) / 12; s.devLeft = "About 5 min left";
        s.devPrint = img(samples, "HOUSE-CUBANNEG.png");
        for (int i = 0; i < 4; i++) s.devStrip.add(thumbs.get(i));
        render(s, fonts, out, "8-darkroom");

        s = state();
        s.mode = UiState.SHEET; s.film = cuban; s.rollNumber = 3; s.thumbs = thumbs; s.sheetSel = 6;
        render(s, fonts, out, "9-sheet");
        s.filingLeft = 2;
        render(s, fonts, out, "9b-sheet-filing");
        s.filingLeft = 0;

        s = state();
        s.mode = UiState.VIEW; s.film = cuban; s.viewImage = img(samples, "HOUSE-PRV.png"); s.sheetSel = 6; s.sheetTotal = 24; s.viewCaption = true;
        render(s, fonts, out, "10-view-fit");
        s.viewFill = true; s.viewCaption = false;
        render(s, fonts, out, "11-view-fill");

        s = state();
        s.mode = UiState.PROBE; s.probeStage = 0; s.probeTitle = "Hardware develop test"; s.probeSteps = HardwareTest.STEPS;
        s.probePlan = Ui.HARDWARE_PLAN; s.probeFooter = Ui.HARDWARE_FOOTER;
        render(s, fonts, out, "13-probe-intro");
        s = state();
        s.mode = UiState.PROBE; s.probeStage = 0; s.probeTitle = "Shooting test"; s.probeSteps = ShootingTest.STEPS;
        s.probePlan = Ui.SHOOT_PLAN; s.probeFooter = Ui.SHOOT_FOOTER;
        render(s, fonts, out, "13b-shooting-intro");
        s.probeStage = 2;
        s.probeStatus = new int[] { Probe.OK, Probe.OK, Probe.OK, Probe.OK, Probe.OK, Probe.OK, Probe.OK };
        s.probeDetail = new String[] { "opened in 560 ms", "on screen", "locked in 840 ms on try 2, AF lamp on 1900 ms in all",
                "DSC04671.JPG on the card: shutter 410 ms, saved 1200 ms later", "41 in 3.0 s (13.7 a second), 640x424, 31 KB each",
                "Tri-X: 95 ms a frame, decode included (640x424, one core)", "live view stopped, camera released" };
        render(s, fonts, out, "13c-shooting-results");
        s.probeStage = 2; s.probeTitle = "Camera roll test"; s.probeSteps = Probe.STEPS;
        s.probeStatus = new int[] { Probe.INFO, Probe.OK, Probe.OK, Probe.OK, Probe.OK, Probe.FAIL, Probe.OK };
        s.probeDetail = new String[] { "ILCE-5100, Android 2.3.7, build 3.10", "all present: catalog, catalog query, catalog delete, hardware decoder",
                "4 of 4 found (not loaded, not called)", "212 photos catalogued on card 1", "6000x4000 in 1012 ms (our software decode: about 10,000 ms)",
                "the camera did not catalogue it (212 -> 212 photos; no catalog events)", "removed (it was never catalogued)" };
        render(s, fonts, out, "14-probe-results");
        s.probeStage = 0; s.probeTitle = "Camera settings check"; s.probeSteps = SettingsCheck.STEPS;
        s.probePlan = Ui.SETTINGS_PLAN; s.probeFooter = Ui.SETTINGS_FOOTER;
        render(s, fonts, out, "15-settings-intro");
        s.probeStage = 2;
        s.probeStatus = new int[] { Probe.OK, Probe.OK, Probe.OK, Probe.OK, Probe.OK, Probe.INFO, Probe.OK };
        s.probeDetail = new String[] { "15 of 15 settings read; nothing written", "Auto", "menu A2 / G1;  Auto applies A2 / G1", "Standard",
                "contrast 0, saturation +1, sharpness 0", "Picture Effect Off, DRO Auto, exposure 0/3", "yes: none of them is write-protected (nothing was written)" };
        render(s, fonts, out, "16-settings-results");
        s.probeStage = 2; s.probeTitle = "Speed test"; s.probeSteps = SpeedTest.STEPS;
        s.probeStatus = new int[] { Probe.OK, Probe.OK, Probe.INFO, Probe.INFO, Probe.INFO, Probe.OK, Probe.OK };
        s.probeDetail = new String[] { "DSC04625.JPG, 3000x2000; through RGB 2874 ms", "1395 ms at half size, off by 0.33 on average",
                "none clearly faster (colour reuse 2171, packed table 1378, smaller table 1395 ms)", "present 0-3, on 0-3, Latent allowed 3",
                "cpu0 4% cpu1 0% cpu2 1% cpu3 38%", "1.9x (1395 -> 731 ms), same picture; main cpu 3, helper cpu 1; asked 0xf, got 0xf",
                "per pair, one core (two cores not used yet)" };
        render(s, fonts, out, "17-speed-results");

        s = state();
        s.mode = UiState.LAB; s.labImage = img(samples, "PEOPLE-PORTRA4W.png"); s.labCaption = "DSC04547.JPG";
        s.labRows = new String[] { "Photo", "Film", "Highlight", "Shadow", "Grain", "Grain size", "Preview", "Save copy" };
        s.labValues = new String[] { "3 of 212", "Portra 400", "-1", "-1", "4", "Fine", "Whole", "" };
        s.labRow = 1;
        render(s, fonts, out, "12-lab");
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

    static UiState.Film film(String title, String iso, String tagline, int body, int band, int accent) {
        UiState.Film f = new UiState.Film();
        f.title = title; f.iso = iso; f.tagline = tagline; f.body = body; f.band = band; f.accent = accent;
        return f;
    }

    static Object img(File dir, String name) throws Exception {
        File f = new File(dir, name);
        return f.exists() ? ImageIO.read(f) : null;
    }

    static void render(UiState s, File fonts, File out, String name) throws Exception {
        BufferedImage img = new BufferedImage(Math.round(s.width), 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        Ui.draw(new HostGfx(g, fonts), s);
        g.dispose();
        ImageIO.write(img, "png", new File(out, name + ".png"));
    }
}
