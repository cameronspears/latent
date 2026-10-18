package com.latent.camera;

import java.util.ArrayList;
import java.util.List;

/** Everything a screen shows, filled in by MainActivity (or by the Mac preview tool). Plain data. */
final class UiState implements Cloneable {
    // SHEET is Photos' contact sheet, VIEW its viewer (5 and 6: the Lab and the camera tests until v0.45.0)
    static final int BACK = 0, LOAD = 1, DARKROOM = 2, SHEET = 3, VIEW = 4, PHOTO = 7, SETTINGS = 8;

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
    String toast = "";                 // a short notice, shown near the top and faded out by the caller

    // camera back
    Film film;                         // stock of the loaded roll, null when no film is loaded
    int rollNumber, exposed, length;
    int developed = -1;                // frames developed so far when a develop was paused, else -1
    boolean endless;                   // a roll with no end: it develops as you shoot
    boolean developLater;              // the camera was opened in Latent: nothing develops until Latent opens again
    int waiting;                       // photos shot in Latent's viewfinder this time, still to develop

    // Photo mode: live view with a look on it
    Object liveImage;                  // a still standing in for live view (previews only)
    boolean liveExact;                 // liveImage is at the screen's own pixels (16-bit, dithered): drawn 1:1
    /**
     * The viewfinder in layers (v0.38.0): ALL draws everything; OVERLAY only Latent's type and fades over
     * the picture (drawn once when they change, then laid into every frame natively); SCREEN everything
     * else (the picture, already carrying the overlay, and what moves: focus, the print).
     */
    static final int LAYER_ALL = 0, LAYER_OVERLAY = 1, LAYER_SCREEN = 2;
    int vfLayer;
    /**
     * Where the viewfinder is drawn (v0.43.1): PART_ALL one screen; with the picture on a 16-bit surface of
     * its own, PART_WINDOW is Latent's screen (the side bars only: the surface covers the rest) and
     * PART_PICTURE the surface (what moves over the picture, and notes; the picture itself is put up first).
     */
    static final int PART_ALL = 0, PART_WINDOW = 1, PART_PICTURE = 2;
    int vfPart;

    /** A shallow copy (the lists shared: they're not changed once the state is built). */
    UiState copy() {
        try { return (UiState) clone(); } catch (CloneNotSupportedException e) { throw new AssertionError(e); }
    }
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
    boolean evEdit;                    // the exposure scale is open (DOWN): the wheel moves it
    int shotsLeft = -1;
    int[] hist;                        // live brightness, or null
    boolean grid, lookLive, flash;     // thirds; the look is on live view; the shutter just blinked
    Object printImage;                 // the last shot, developing
    float printProgress;               // 0 latent .. 1 developed
    boolean liveThrough;               // the camera's own live view shows in the picture (no look on it)
    boolean clean;                     // the clean display: the picture, focus and the counter only
    String vfNote = "";                // a short word under the counter ("Grid and histogram", "Roll finished")
    String cardNote = "";              // under the film's name on its card, in place of the tagline
    String fatal;                      // the viewfinder can't go on (a photo isn't saving): what to do, full screen; null normally
    boolean coach;                     // the first visit: the viewfinder's keys, once, for a few seconds

    // the menu
    List<String[]> rows = new ArrayList<String[]>();   // {label, value, why not}: value "›" opens, "" is a heading; why not "" when it can act now
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
    boolean devPreparing;              // the darkroom getting ready (the camera given back, Latent starting again)
    Object devPrint;                   // the frame being developed (its camera thumbnail), or null
    List<Object> devStrip = new ArrayList<Object>();   // developed frames so far, in order

    List<Object> thumbs = new ArrayList<Object>();   // camera back: an endless roll's newest prints

    // Photos (v0.45.0): every developed frame, newest first, on a contact sheet grouped by roll (Photos.Sheet)
    /** A frame on the sheet's page on show. */
    static final class Cell {
        Object thumb;                  // its thumbnail, or null while it loads (a plain cell)
        int number;                    // its number on its roll
        int row, col;                  // where it sits on the page
        Film roll;                     // on the first row of each roll on the page: its film (the canister marks the roll)
        int rollNumber;                // ... and the roll's number
    }
    boolean photosReady;               // the list is made: until then only the title (never a flash of the empty state)
    List<Cell> cells = new ArrayList<Cell>();   // the page on show; empty with photosReady: nothing developed yet
    int cellSel = -1;                  // the chosen frame's cell
    int frameNumber, frameOf;          // the chosen frame: its number on its roll (film and rollNumber above), of the roll's frames
    String photoDate = "";             // when it was taken ("12 Oct 2026"), or "" when its EXIF doesn't say
    String photoExposure = "";         // its exposure ("1/250  F5.6  ISO 400"), or ""
    int filingLeft;                    // the chosen frame's roll: originals still moving to its NEG folder
    // the viewer
    Object viewImage;                  // the picture on show (the frame's preview, its 100% crop, or its thumbnail standing in); null: a note
    boolean viewCrop;                  // viewImage is the 100% crop: drawn a photo pixel to a layout pixel
    String viewNote = "";              // with no picture: what's happening ("Developing preview…") or that there is none
    boolean viewCaption;               // the caption and the keys, for a moment: on arrival and on UP
    boolean viewZoom, viewMoves;       // at 100% (its picture may be on its way); and the arrows move it
    float[] viewMap;                   // at 100%: where the view lies in the photo {x, y, w, h} (0..1) and the photo's aspect; null: hidden
    boolean viewMapPending;            // ... the picture is still on its way there

    // ---- the screens (v0.45.0, the UI stream)
    int readyIn = -1;                  // camera back: a shoot waits for the camera to settle, seconds to go (0: a moment); -1 none
    int readyTotal;                    // ... of this many seconds in all
    long developMs = -1;               // camera back: about how long the roll (or what's left of it) takes to develop; -1 unknown

    // ---- the viewfinder's MENU (v0.45.0, the review's fixes)
    boolean menuDevelops;              // MENU restarts Latent and the pictures develop (an endless roll, or a roll they finished): "Develop", else "Back"
}
