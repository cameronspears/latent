package com.filmlab.camera;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Renders every Film Lab screen with realistic data to PNGs, through the same Ui code the camera
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

        UiState s = state();
        s.nav = Arrays.asList("Load film", "Contact sheets", "Lab");
        render(s, fonts, out, "1-back-empty");

        s = state();
        s.film = portra; s.rollNumber = 5; s.exposed = 0; s.length = 12; s.toast = "Portra 400 loaded";
        s.nav = Arrays.asList("Shoot", "More");
        render(s, fonts, out, "2-back-fresh-toast");

        s = state();
        s.film = cuban; s.rollNumber = 3; s.exposed = 14; s.length = 24;
        s.nav = Arrays.asList("Shoot", "Develop", "More");
        render(s, fonts, out, "3-back-midroll");

        s.navMore = true; s.nav = Arrays.asList("Contact sheets", "Lab", "Unload roll", "Settings check", "Speed test", "3 cores"); s.navSel = 0;
        render(s, fonts, out, "4-back-more");
        s.navSel = 5;
        render(s, fonts, out, "4b-back-more-end");

        s = state();
        s.film = portra; s.rollNumber = 4; s.exposed = 36; s.length = 36; s.battery = 17;
        s.nav = Arrays.asList("Develop", "More");
        render(s, fonts, out, "5-back-full-lowbattery");

        s = state();
        s.mode = UiState.LOAD; s.films = Arrays.asList(cuban, portra); s.filmSel = 0; s.lengthSel = 1;
        render(s, fonts, out, "6-load-cuban");
        s.loading = true;
        render(s, fonts, out, "6b-load-loading");
        s.loading = false;
        UiState.Film pending = film("Portra", "400", "Soft contrast, warm creamy highlights and golden skin.", 0xFFC79A3E, 0xFFF3E7C9, 0xFFE8B653);
        s.films = Arrays.asList(cuban, pending); s.filmSel = 1;
        render(s, fonts, out, "7-load-sample-rendering");

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
