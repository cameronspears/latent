package com.latent.camera;

import java.util.ArrayList;
import java.util.List;

/** Everything a screen shows, filled in by MainActivity (or by the Mac preview tool). Plain data. */
final class UiState {
    static final int BACK = 0, LOAD = 1, DARKROOM = 2, SHEET = 3, VIEW = 4, LAB = 5, PROBE = 6, PHOTO = 7, SETTINGS = 8;

    /** How a film stock is shown: names and its two canister colours plus a UI accent. */
    static final class Film {
        String title = "", iso = "", tagline = "";
        int body, band, accent;
        Object sample;                 // the latest photo rendered in this stock, or null while it renders
    }

    int mode = BACK;
    float width = 640;                 // logical width: 480 x the panel's real aspect (853 on a 16:9 panel)
    int battery = -1;                  // percent, -1 unknown
    boolean loading;                   // Load film: the chosen roll is being loaded
    boolean live;                      // the camera's live view is showing behind this screen
    String liveNote = "";              // a short line over it
    String toast = "";                 // a short notice, shown near the top and faded out by the caller

    // camera back
    Film film;                         // stock of the loaded roll, null when no film is loaded
    int rollNumber, exposed, length;
    int developed = -1;                // frames developed so far when a develop was paused, else -1
    boolean endless;                   // a roll with no end: it develops as you shoot

    // Photo mode: live view with a look on it
    Object liveImage;                  // a still standing in for live view (previews only)
    List<Film> looks = new ArrayList<Film>();
    int lookSel;
    boolean lookShown;                 // the look's name, large, just after it changed
    int developing;                    // photos still developing in the background
    String liveCaption = "";           // what the picture is (a preview on your last photo)

    // the viewfinder (in-app shooting)
    int afState;                       // 0 ready, 1 searching, 2 locked, 3 can't lock
    float[][] afAreas;                 // focused areas as {x, y, w, h}, 0..1 of the picture; null = the centre
    boolean afLamp;                    // the AF light is on
    int afTry;                         // which try (searching again in the dark)
    String afNote = "";                // why it can't lock
    String afMode = "", afArea = "";   // "AF-S", "Centre"
    String shutter = "", aperture = "", iso = "";
    boolean isoAuto;
    int ev;                            // exposure, in thirds of a stop from metered
    int shotsLeft = -1;
    int[] hist;                        // live brightness, or null
    boolean grid, lookLive, flash;     // thirds; the look is on live view; the shutter just blinked
    Object printImage;                 // the last shot, developing
    float printProgress;               // 0 latent .. 1 developed

    // Settings
    List<String[]> rows = new ArrayList<String[]>();   // {label, value}: value "on"/"off" draws a switch, "›" opens, "" is a heading
    int rowSel;
    String footer = "";

    // load film
    List<Film> films = new ArrayList<Film>();
    int filmSel, lengthSel;
    String[] lengthLabels = { "12", "24", "36", "\u221e" };
    boolean cancelable;                // choosing film with a roll still in the camera: MENU goes back to it

    // darkroom
    int devFrame, devTotal, devDone;
    float devProgress;                 // whole roll, 0..1, moves continuously
    float devFramePct;                 // the frame in hand, 0..1
    String devLeft = "";
    boolean paused;
    Object devPrint;                   // the frame being developed (its camera thumbnail), or null
    List<Object> devStrip = new ArrayList<Object>();   // developed frames so far, in order

    // contact sheet and viewer
    List<Object> thumbs = new ArrayList<Object>();
    int sheetSel, sheetTotal;
    boolean sheetUndeveloped;
    boolean sheetPhotos;               // a photo roll (Photo mode), not a film roll
    int filingLeft;                    // originals still moving to the roll's NEG folder
    Object viewImage;
    boolean viewCaption, viewFill;

    // camera tests
    String probeTitle = "";
    String[] probeSteps = new String[0], probePlan = new String[0];
    String probeFooter = "";
    int probeStage;                    // 0 = about to run, 1 = running, 2 = finished
    int[] probeStatus = new int[0];    // Probe.RUN / OK / FAIL / SKIP / INFO, -1 = not reached
    String[] probeDetail = new String[0];

    // lab
    Object labImage;
    String[] labRows = new String[0], labValues = new String[0];
    int labRow, labProgress = -1;
    String labCaption = "";
}
