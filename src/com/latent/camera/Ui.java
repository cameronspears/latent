package com.latent.camera;

import java.util.ArrayList;
import java.util.List;

/**
 * Latent's screens, drawn through Gfx so the camera and the Mac preview share one implementation.
 *
 * Design rules: true black, warm white type, one accent (the loaded stock's colour), one hero per
 * screen. Inter Display Light for the big numbers, Inter for everything else. Calm as a Hasselblad
 * (grey means it can't be done now, and the footer says why), only the essentials as on a Leica
 * (status only when it matters, solid plates), the film as the hero as on a Fuji, and the picture
 * first as on an iPhone. Every screen says how to leave it, in one hint bar on one baseline.
 *
 * Geometry: the design space is 480 high and as wide as the panel really is (s.width: 853 on the
 * A5100's 16:9 panel). The camera's 640x480 frame buffer is stretched onto that panel, so the
 * Canvas is scaled to undo the stretch and everything here keeps its true proportions. Rows are the
 * panel's own; across, one design px is 0.75 of a dot, so vertical strokes sit on whole dots (vx, vw).
 */
final class Ui {
    private Ui() {}

    // palette, on the 4-bit grid (each channel a multiple of 0x11): Latent's 32-bit screen, the 16-bit plane it
    // becomes while the camera is open and the viewfinder's dithered overlay all show the same colour.
    // Contrast on black in brackets; text under 18 px needs 4.5:1 on its background, 18 px and up 3:1.
    static final int BG = 0xFF000000,
            INK = 0xFFFFFFEE,       // 20:1
            INK2 = 0xFF999988,      // 7.3:1
            INK3 = 0xFF777766,      // 4.6:1: the lowest colour any text may use
            DIM = 0xFF444433,       // decoration only: empty ticks, the exposure scale's minor marks
            LINE = 0xFF333322,
            SURFACE = 0xFF222222,
            SELECT = 0xFF333333;    // the chosen row's plate
    static final int GREEN = 0xFF55DD88,   // the one green: focus locked
            AMBER = 0xFFFFAA33;            // can't focus
    // the darkroom's safelight: RED for text (5.5:1), RED2 for text of 18 px and up (3.2:1), RED3 and RED4 decoration only
    static final int RED = 0xFFEE4433, RED2 = 0xFFAA3322, RED3 = 0xFF331111, RED4 = 0xFF552211;
    /**
     * Two scrims on Latent's window, and one plate for anything drawn over the live picture: its 16-bit surface
     * turns translucency into flat bands (no dither there), so what the presenter lays over the picture is opaque.
     */
    static final int GLASS = 0x99000000, CARD = 0xC4000000, PLATE = 0xFF111111;

    // the type scale (design px at 480 tall): one size per role
    static final float T_HERO = 230, T_HERO_DARK = 190,   // the camera back's and the darkroom's counters (unchanged)
            T_DISPLAY = 50,     // film names (Display Light)
            T_TITLE = 34,       // titles over pictures and empty states (Medium; Light only from 40 px up)
            T_READOUT = 22,     // the viewfinder's numbers (Medium, tabular)
            T_BODY = 20,        // rows, lines, headers
            T_SECOND = 17,      // hints, footers, notes
            T_LABEL = 13;       // caps labels, tracked
    static final int LABEL_TRACK = 120;
    /** Floors: no text under 13 px, no sentence under 15. */
    static final float FLOOR = 13, FLOOR_SENTENCE = 15;

    static final float L = 56, TOP = 50, FOOT = 444, H = 480;
    /** Every screen's hint bar sits on this baseline. */
    static final float HINT_Y = 452;
    /** Over a picture, Latent's type keeps this far inside its edges (the viewfinder, Choose film). */
    static final float INSET = 24;
    /** The panel's dots across (its frame buffer is 640 wide whatever the panel's shape). */
    static final int DOTS = 640;
    /**
     * The viewfinder's overlay (photoType) draws only in rows [0, OVL_TOP_END) and [OVL_BOTTOM_START, H) unless
     * the grid is on, so the overlay thread renders, clears and reads back just those two bands.
     */
    static final int OVL_TOP_END = 64, OVL_BOTTOM_START = 384;

    static void draw(Gfx g, UiState s) {
        boolean vf = s.mode == UiState.PHOTO;
        if (vf && s.vfLayer == UiState.LAYER_OVERLAY) { photo(g, s); return; }   // the type over the picture, alone
        if (s.fatal != null) {   // the camera can't go on: nothing else, in every part
            g.rect(0, 0, s.width, H, BG);
            fatal(g, s);
            return;
        }
        if (vf && s.vfPart == UiState.PART_PICTURE) { photo(g, s); toast(g, s); return; }   // over the picture, on its surface
        if (vf && (s.liveThrough || s.liveImage != null || s.vfPart == UiState.PART_WINDOW)) {   // the picture fills its area: black bars only
            float iw = H * 1.5f, ix = (s.width - iw) / 2;
            g.rect(0, 0, ix, H, BG);
            g.rect(ix + iw, 0, s.width - ix - iw, H, BG);
        } else if (s.mode == UiState.LOAD && loadPicture(s)) {   // Choose film: its picture covers the middle (a pixel's overlap, under it)
            float iw = H * 1.5f, ix = (s.width - iw) / 2;
            g.rect(0, 0, ix + 1, H, BG);
            g.rect(ix + iw - 1, 0, s.width - ix - iw + 1, H, BG);
        } else if (!(s.mode == UiState.VIEW && s.viewImage != null)) {   // the viewer fills only what its picture leaves
            g.rect(0, 0, s.width, H, BG);
        }
        switch (s.mode) {
            case UiState.BACK: back(g, s); break;
            case UiState.LOAD: load(g, s); break;
            case UiState.DARKROOM: darkroom(g, s); break;
            case UiState.SHEET: sheet(g, s); break;
            case UiState.VIEW: view(g, s); break;
            case UiState.PHOTO: photo(g, s); break;
            case UiState.SETTINGS: settings(g, s); break;
            default: break;
        }
        if (!(vf && s.vfPart == UiState.PART_WINDOW)) toast(g, s);   // the window's middle is under the picture's surface
    }

    private static float right(UiState s) { return s.width - L; }

    /** x on a whole panel dot: vertical strokes stay crisp and even after the 640-dot squeeze. */
    static float vx(UiState s, float x) { return Math.round(x * DOTS / s.width) * s.width / DOTS; }

    /** A vertical stroke's width in whole dots, never under one (4/3 design px on a 16:9 panel). */
    static float vw(UiState s, float w) { return Math.max(1, Math.round(w * DOTS / s.width)) * s.width / DOTS; }

    /** The largest size from 'size' down to 'floor' at which the text fits in maxw. */
    static float fit(Gfx g, String t, int font, float size, float floor, float maxw, int tracking) {
        while (size > floor && g.textWidth(t, font, size, tracking) > maxw) size -= 0.5f;
        return size;
    }

    // ------------------------------------------------------------------------------ the fatal screen

    /**
     * The viewfinder can't go on (a photo isn't saving): one calm screen, nothing else, until the camera is
     * switched off. The first sentence large, what to do under it, and that the other photos are safe.
     */
    private static void fatal(Gfx g, UiState s) {
        String t = s.fatal;
        int dot = t.indexOf(". ");
        String head = dot > 0 ? t.substring(0, dot) : t, rest = dot > 0 ? t.substring(dot + 2) : "";
        float x = (s.width - H * 1.5f) / 2 + 2 * INSET, w = H * 1.5f - 4 * INSET;   // inside the picture's area: all on its surface too
        g.text(head, x, 196, Gfx.MEDIUM, T_TITLE, INK, Gfx.LEFT, 0);
        float y = 196 + 40;
        if (rest.length() > 0) y = wrap(g, rest, x, y, w, Gfx.REGULAR, T_BODY, INK2, 27, 3) + 27;
        g.text("Your other photos are safe.", x, y, Gfx.REGULAR, T_BODY, INK2, Gfx.LEFT, 0);
    }

    // ------------------------------------------------------------------------------ camera back

    /**
     * The camera back with a roll in it: the frame counter is the hero, as in a film camera's window.
     * One thing to do (half-press, or the centre button, to shoot); everything else is behind MENU.
     */
    private static void back(Gfx g, UiState s) {
        float R = right(s);
        battery(g, s.battery, R, INK2, INK3);
        UiState.Film f = s.film;
        if (f == null) return;   // no roll: the film chooser is the home screen
        title(g, f, "Roll " + s.rollNumber, INK);

        int shown = s.endless ? s.exposed : Math.min(s.exposed, s.length);
        String num = s.endless ? String.valueOf(shown) : (shown < 10 ? "0" : "") + shown;   // "09 of 24": "of 24" never moves
        g.text(num, L - 10, 300, Gfx.DISPLAY, T_HERO, INK, Gfx.LEFT, -30);
        float nw = g.textWidth(num, Gfx.DISPLAY, T_HERO, -30);
        g.text(s.endless ? (shown == 1 ? "photo" : "photos") : "of " + s.length, L - 10 + nw + 16, 300, Gfx.REGULAR, 26, INK2, Gfx.LEFT, 0);

        String note;
        if (s.endless) {   // develops as you go: the newest prints, small, in a row
            float tw = 84, th = 56, x = L;
            for (int i = 0; i < s.thumbs.size() && x + tw <= R; i++, x += tw + 10) {
                Object t = s.thumbs.get(i);
                if (t != null) g.image(t, x, 322, tw, th, false);
                else g.rect(x, 322, tw, th, SURFACE);
            }
            note = s.developLater && s.waiting > 0 ? s.waiting + (s.waiting == 1 ? " new photo. Press \u2299 to develop it." : " new photos. Press \u2299 to develop them.")
                    : s.developing > 0 ? "" : shown == 0 ? "No end to this roll. Photos develop each time you come back." : "";
            if (note.length() > 0) keyText(g, note, L, s.thumbs.isEmpty() ? 356 : 410, Gfx.REGULAR, T_BODY, INK2, Gfx.LEFT);
        } else {
            // the roll's last three frames in the film's colour, as a counter's window warns of the end
            ticks(g, s, s.length, shown, L, R, 338, INK, DIM, f.accent, shade(f.accent, 0.5f));
            String est = s.developMs > 0 ? developEstimate(s.developMs) : "";
            note = s.developed >= 0 ? s.developed + " of " + shown + " developed" + (est.length() > 0 ? "  \u00b7  " + est + " left" : "")
                    : shown >= s.length ? "Roll finished" + (est.length() > 0 ? "  \u00b7  " + est + " to develop" : "")
                    : shown == 0 ? "Fresh roll. Half-press the shutter to shoot."
                    : "";   // mid-roll: the counter says it all
            if (note.length() > 0) keyText(g, note, L, 384, Gfx.REGULAR, T_BODY, INK2, Gfx.LEFT);
        }
        boolean develop = s.developed >= 0 || (!s.endless && shown >= s.length) || (s.endless && s.developLater && s.waiting > 0);
        float rx;
        if (s.readyIn >= 0 && !develop) {   // a shoot waiting for the camera to settle: say so, and count it down
            rx = hintBar(g, s, INK2, null, new String[] { "menu", "Menu" });
            String t = s.readyIn > 0 ? "Shooting in " + s.readyIn + " s" : "Getting ready\u2026";
            float tw = g.textWidth(t, Gfx.MEDIUM, T_SECOND, 0), tx = rx - 26 - tw;
            g.text(t, tx, HINT_Y, Gfx.MEDIUM, T_SECOND, INK, Gfx.LEFT, 0);
            if (s.readyIn > 0 && s.readyTotal > 0) {
                float k = Math.max(0, Math.min(1, (s.readyTotal - s.readyIn) / (float) s.readyTotal));
                g.rect(tx, HINT_Y + 9, tw, 2, LINE);
                g.rect(tx, HINT_Y + 9, tw * k, 2, f.accent);
            }
        } else {
            hintBar(g, s, INK2, null, new String[] { "centre", develop ? "Develop" : "Shoot", "menu", "Menu" });
        }
        if (s.developing > 0) developingPill(g, s, L, f.accent);
    }

    /** "about 2 min", "about 90 s", "under a minute": how long a develop of this many ms takes. */
    static String developEstimate(long ms) {
        long sec = (ms + 500) / 1000;
        if (sec < 50) return "under a minute";
        if (sec < 90) return "about " + (sec + 5) / 10 * 10 + " s";
        return "about " + (sec + 30) / 60 + " min";
    }

    /** ∞, drawn: two loops side by side. */
    private static void infinity(Gfx g, float cx, float cy, int color) {
        g.strokeCircle(cx - 5.2f, cy, 5f, 2f, color);
        g.strokeCircle(cx + 5.2f, cy, 5f, 2f, color);
    }

    /** "2 developing": a quiet pill on the hint bar's baseline, from x. */
    private static void developingPill(Gfx g, UiState s, float x, int accent) {
        String d = s.developing + " developing";
        float dw = g.textWidth(d, Gfx.MEDIUM, T_SECOND, 0) + 36;
        g.roundRect(x - 4, HINT_Y - 20, dw, 28, 14, SURFACE);
        g.circle(x + 11, HINT_Y - 6, 3.5f, accent);
        g.text(d, x + 22, HINT_Y - 1, Gfx.MEDIUM, T_SECOND, INK, Gfx.LEFT, 0);
    }

    // ------------------------------------------------------------------------------ the viewfinder

    /**
     * The viewfinder: Latent's own live view, with a film look on it, and the camera's information drawn
     * the way a Fuji or a Leica shows it: quiet until it matters. The picture is 3:2, full height, so the
     * 16:9 panel leaves a black bar each side; the steady details live there, out of the picture, and so
     * do the keys: centre (the look) on the left, MENU (develop, or back) on the right, and the exposure
     * scale while it is open, next to the wheel that moves it.
     *
     * Focus is the bracket in the middle. White while ready; tightening while it searches (with the AF
     * light noted when it's on); green and snapped tight on a lock, with the focused areas marked and a
     * green dot by the exposure (as Fuji confirms focus); amber when it can't lock, saying why.
     * After a shot the print develops in the corner, from latent to finished.
     */
    private static void photo(Gfx g, UiState s) {
        float iw = H * 1.5f, ix = (s.width - iw) / 2, ir = ix + iw;
        UiState.Film f = look(s);
        int accent = f != null ? f.accent : INK;
        // layers: 'over' is Latent's type and fades over the picture (in the frames themselves when the screen
        // draws SCREEN), 'under' the picture and what moves with it
        boolean over = s.vfLayer != UiState.LAYER_SCREEN && s.vfPart != UiState.PART_WINDOW, under = s.vfLayer != UiState.LAYER_OVERLAY;
        if (under && s.vfPart == UiState.PART_ALL) {
            if (s.liveImage != null && s.liveExact) g.imageExact(s.liveImage, ix, 0, iw, H);
            else if (s.liveImage != null) g.image(s.liveImage, ix, 0, iw, H, false);
            else if (!s.liveThrough) g.rect(ix, 0, iw, H, PLATE);
        }
        if (over) photoType(g, s, f, accent, ix, iw, ir);
        if (under && s.vfPart != UiState.PART_WINDOW) photoMovingPicture(g, s, f, ix, iw, ir, accent);
        if (under && s.vfPart != UiState.PART_PICTURE) sideBars(g, s, ix, ir, accent);
    }

    /** The film the viewfinder shows (its look), or null. */
    private static UiState.Film look(UiState s) {
        return s.looks.isEmpty() ? null : s.looks.get(Math.min(s.lookSel, s.looks.size() - 1));
    }

    /** A fade's colour: black at 'peak' alpha eased by t (0..1, smooth at both ends). */
    private static int fade(int peak, float t) {
        t = Math.max(0, Math.min(1, t));
        return ((int) (peak * t * t * (3 - 2 * t))) << 24;
    }

    /**
     * Over the picture and still: the fades, the film, the counter, the battery, exposure (the viewfinder's
     * overlay). Only inside the two bands (OVL_TOP_END, OVL_BOTTOM_START) unless the grid is on: the
     * composer blends just those rows, and the rest of the picture is the look alone.
     */
    private static void photoType(Gfx g, UiState s, UiState.Film f, int accent, float ix, float iw, float ir) {
        // soft fades top and bottom, within the bands, so the type reads over any picture
        for (int y = 0; y < OVL_TOP_END; y += 2) g.rect(ix, y, iw, 2, fade(0x78, 1 - (y + 1) / (float) OVL_TOP_END));
        for (int y = OVL_BOTTOM_START; y < H; y += 2) g.rect(ix, y, iw, 2, fade(0xB8, (y + 1 - OVL_BOTTOM_START) / (float) (H - OVL_BOTTOM_START)));
        if (s.grid) {   // thirds (vertical lines on whole dots)
            for (int i = 1; i < 3; i++) {
                g.rect(vx(s, ix + iw * i / 3), 0, vw(s, 1), H, 0x38FFFFFF);
                g.rect(ix, H * i / 3, iw, 1, 0x38FFFFFF);
            }
        }

        // ---- top: the film, the frame counter, the battery
        float x0 = ix + INSET, x1 = ir - INSET;
        if (f != null) {
            canister(g, f, x0, 19, 1, true);
            g.text(f.title, x0 + 26, 36, Gfx.MEDIUM, T_SECOND, INK, Gfx.LEFT, 0);
            if (s.liveThrough) {   // the camera's own view: the look is the normal state, so only its absence is labelled
                float x = x0 + 26 + g.textWidth(f.title, Gfx.MEDIUM, T_SECOND, 0) + 10, w = g.textWidth("LOOK OFF", Gfx.MEDIUM, T_LABEL, LABEL_TRACK) + 18;
                g.roundRect(x, 21, w, 20, 10, GLASS);
                g.text("LOOK OFF", x + w / 2, 36, Gfx.MEDIUM, T_LABEL, INK, Gfx.CENTER, LABEL_TRACK);
            }
        }
        frameWindow(g, s, s.width / 2, 36, accent);
        if (s.battery >= 0) battery2(g, s.battery, x1, 36);

        // ---- bottom: exposure in fixed slots, and the exposure scale when it isn't at 0 (still: nothing here
        // changes with focus, so the overlay isn't redrawn on every half-press; the focus dot is drawn with what moves)
        if (!s.clean) {
            float by = 448;
            exposure(g, ix + INSET + 16, by, s);
            if (!s.evEdit && s.ev != 0) meter(g, s, s.width / 2 + 60, by - 6, 150, s.ev, INK, accent);
        }
    }

    /**
     * What moves, drawn over the picture every frame (on its own surface, by the presenter): the histogram,
     * focus, its dot, the print, a note, the film's card and the first visit's keys. Notes, the card and the
     * keys sit on opaque plates: the 16-bit surface would band anything translucent.
     */
    private static void photoMovingPicture(Gfx g, UiState s, UiState.Film f, float ix, float iw, float ir, int accent) {
        float x1 = ir - INSET;
        if (s.hist != null) histogram(g, s, s.hist, x1 - 112, 76, 112, 40);
        // ---- focus (out of the way while the look card is up)
        if (!s.lookShown) afBrackets(g, s, ix, iw);
        if (!s.evEdit && (s.afState == 2 || s.afState == 3)) g.circle(ix + 26, 442, 4.5f, s.afState == 2 ? GREEN : AMBER);   // focus: Fuji's dot
        print(g, s, x1, 448, accent);
        if (s.vfNote.length() > 0) {   // a word under the counter, for a moment
            float tw = g.textWidth(s.vfNote, Gfx.MEDIUM, T_SECOND, 0) + 32;
            g.roundRect(s.width / 2 - tw / 2, 56, tw, 30, 15, PLATE);
            g.text(s.vfNote, s.width / 2, 77, Gfx.MEDIUM, T_SECOND, INK, Gfx.CENTER, 0);
        }
        // ---- the look, large, for a moment after the wheel turns (a Fuji film simulation answering its dial)
        if (s.lookShown && f != null) lookCard(g, s, f);
        if (s.coach && !s.lookShown) coach(g, s);
    }

    /**
     * The side bars (Latent's own window): focus on the left, photos left on the card on the right, and the
     * keys at the foot of each, centre (the look) on the left, MENU on the right. While the exposure scale is
     * open the right bar is its track, beside the wheel that moves it.
     */
    private static void sideBars(Gfx g, UiState s, float ix, float ir, int accent) {
        float lc = ix / 2, rc = ir + ix / 2;
        if (!s.clean) {
            sideLabel(g, lc, 214, s.afMode, "FOCUS");
            sideLabel(g, lc, 268, s.afArea, "AREA");
        }
        sideHint(g, lc, "centre", s.evEdit ? "Done" : "Look");
        if (s.evEdit) { evTrack(g, s, rc, accent); return; }
        if (!s.clean) sideLabel(g, rc, 214, s.shotsLeft >= 0 ? String.valueOf(s.shotsLeft) : "", "LEFT");
        if (s.afLamp) lamp(g, rc, 268, s.afState == 1 ? INK : INK2);
        sideHint(g, rc, "menu", s.developing > 0 ? "Develop" : "Back");
    }

    /**
     * What the viewfinder's overlay depends on (photoType): when this changes, it's drawn again. Everything
     * photoType reads, and nothing it doesn't, so the overlay is redrawn only when it would look different
     * (the exposure scale's value only while it's closed: open, it's drawn in the side bar).
     */
    static String overlayKey(UiState s) {
        UiState.Film f = look(s);
        StringBuilder k = new StringBuilder(96);
        k.append(s.width).append('|').append(f == null ? "-" : f.title + "/" + f.accent + "/" + f.body + "/" + f.band).append('|')
                .append(s.liveThrough).append(s.grid).append(s.clean).append(s.endless).append('|')
                .append(s.exposed).append('/').append(s.length).append('|').append(s.battery).append('|')
                .append(s.evEdit).append(s.evEdit ? 0 : s.ev).append('|').append(s.shutter).append('|').append(s.aperture).append('|')
                .append(s.iso).append(s.isoAuto);
        return k.toString();
    }

    /**
     * What Latent's own window shows in the viewfinder (PART_WINDOW: the side bars, and what is drawn in
     * them): when this is unchanged, the window isn't drawn again. Every input sideBars reads (RenderScreens
     * checks that two states with one key draw the same window).
     */
    static String windowKey(UiState s) {
        UiState.Film f = look(s);
        StringBuilder k = new StringBuilder(64);
        k.append(s.mode).append('|').append(s.width).append('|').append(s.clean).append('|')
                .append(s.afMode).append('|').append(s.afArea).append('|').append(s.shotsLeft).append('|').append(s.afLamp)
                .append(s.afState).append('|').append(s.evEdit).append(s.ev).append('|').append(f == null ? INK : f.accent).append('|')
                .append(s.developing > 0).append('|').append(s.fatal);
        return k.toString();
    }

    /** The frame counter as a film camera's window: "09 / 24", or "∞ 23" on an endless roll; the end of a roll in the film's colour. */
    private static void frameWindow(Gfx g, UiState s, float cx, float y, int accent) {
        String n = s.endless ? String.valueOf(s.exposed) : (s.exposed < 10 ? "0" : "") + s.exposed;
        String of = s.endless ? "" : " / " + s.length;
        boolean end = !s.endless && s.length - s.exposed <= 3;
        float nw = g.textWidth(n, Gfx.MEDIUM, T_READOUT, 0), ow = g.textWidth(of, Gfx.REGULAR, 16, 0);
        float w = (s.endless ? 22 : 0) + nw + ow + 32, x = cx - w / 2;
        g.roundRect(x, y - 24, w, 32, 16, CARD);
        g.strokeRoundRect(x, y - 24, w, 32, 16, vw(s, 1.4f), end ? accent : 0x40FFFFEE);
        float tx = x + 16;
        if (s.endless) { infinity(g, tx + 8, y - 7, INK2); tx += 22; }
        g.text(n, tx, y, Gfx.MEDIUM, T_READOUT, INK, Gfx.LEFT, 0);
        if (of.length() > 0) g.text(of, tx + nw, y, Gfx.REGULAR, 16, end ? accent : INK2, Gfx.LEFT, 0);
    }

    /** Battery for the viewfinder: the outline and its percent, right-aligned at R. */
    private static void battery2(Gfx g, int pct, float R, float y) {
        boolean low = pct <= 20;
        String t = pct + "%";
        float tw = g.textWidth(t, Gfx.MEDIUM, 15, 0);
        g.text(t, R, y, Gfx.MEDIUM, 15, low ? RED : INK, Gfx.RIGHT, 0);
        float bx = R - tw - 32, by = y - 12;
        g.strokeRoundRect(bx, by, 24, 12, 3, 1.4f, 0xCCFFFFEE);
        g.roundRect(bx + 25, by + 4, 2, 4, 1, 0xCCFFFFEE);
        g.roundRect(bx + 2.5f, by + 2.5f, 19 * Math.max(0.06f, pct / 100f), 7, 1.5f, low ? RED : INK);
    }

    /** Usable width in a side bar: 66 px less a margin each side. */
    static final float BAR_TEXT = 54;

    /** A small value with its label above, centred in a side bar; the value shrinks to fit, then is shortened. */
    private static void sideLabel(Gfx g, float cx, float y, String value, String label) {
        if (value == null || value.length() == 0) return;
        g.text(label, cx, y - 22, Gfx.MEDIUM, T_LABEL, INK3, Gfx.CENTER, LABEL_TRACK);
        String v = barValue(g, value);
        g.text(v, cx, y, Gfx.MEDIUM, fit(g, v, Gfx.MEDIUM, T_SECOND, FLOOR, BAR_TEXT, 0), INK2, Gfx.CENTER, 0);
    }

    /** A side-bar value as it's shown: shortened when even the smallest size can't fit it. */
    static String barValue(Gfx g, String v) {
        if (g.textWidth(v, Gfx.MEDIUM, FLOOR, 0) <= BAR_TEXT) return v;
        if (v.equals("Tracking")) return "Track";
        while (v.length() > 2 && g.textWidth(v + "\u2026", Gfx.MEDIUM, FLOOR, 0) > BAR_TEXT) v = v.substring(0, v.length() - 1);
        return v + "\u2026";
    }

    /** A key at the foot of a side bar: its glyph over what it does. */
    private static void sideHint(Gfx g, float cx, String glyph, String label) {
        glyph(g, glyph, cx - glyphWidth(g, glyph) / 2, HINT_Y - 27, INK2);
        g.text(label, cx, HINT_Y, Gfx.REGULAR, fit(g, label, Gfx.REGULAR, T_SECOND, FLOOR, BAR_TEXT, 0), INK2, Gfx.CENTER, 0);
    }

    /** The AF illuminator: a small lamp with rays, and what it is. */
    private static void lamp(Gfx g, float cx, float cy, int color) {
        g.strokeCircle(cx, cy - 6, 6, 1.6f, color);
        g.rect(cx - 3, cy + 2, 6, 2, color);
        for (int i = -1; i <= 1; i++) g.line(cx + i * 9, cy - 18 + Math.abs(i) * 3, cx + i * 11, cy - 21 + Math.abs(i) * 3, 1.4f, color);
        g.text("AF", cx, cy + 22, Gfx.MEDIUM, T_LABEL, INK3, Gfx.CENTER, LABEL_TRACK);
        g.text("LIGHT", cx, cy + 38, Gfx.MEDIUM, T_LABEL, INK3, Gfx.CENTER, LABEL_TRACK);
    }

    // the widest each exposure value can be: its slot (the digits are tabular)
    static final String[] SHUTTERS = { "1/4000", "1/8000", "1.3\"", "30\"" }, APERTURES = { "F5.6", "F22" }, ISOS = { "25600", "AUTO" };

    private static float widest(Gfx g, String[] vs) {
        float w = 0;
        for (String v : vs) w = Math.max(w, g.textWidth(v, Gfx.MEDIUM, T_READOUT, 0));
        return w;
    }

    /**
     * The exposure line in fixed slots, as a Leica's or a Fuji's: each value right-aligned in a slot as wide
     * as its widest, so 1/125 turning 1/60 moves nothing else (ISO, last, after its label's widest). Returns
     * where the line can end.
     */
    static float exposure(Gfx g, float x, float y, UiState s) {
        float sw = widest(g, SHUTTERS), aw = widest(g, APERTURES), iw = widest(g, ISOS), lw = g.textWidth("ISO A", Gfx.MEDIUM, T_LABEL, 80) + 6;
        if (s.shutter.length() > 0) g.text(s.shutter, x + sw, y, Gfx.MEDIUM, T_READOUT, INK, Gfx.RIGHT, 0);
        x += sw + 22;
        if (s.aperture.length() > 0) g.text(s.aperture, x + aw, y, Gfx.MEDIUM, T_READOUT, INK, Gfx.RIGHT, 0);
        x += aw + 22;
        if (s.iso.length() > 0) {
            String label = s.isoAuto ? "ISO A" : "ISO";
            g.text(label, x + lw - 6, y - 1, Gfx.MEDIUM, T_LABEL, INK2, Gfx.RIGHT, 80);
            g.text(s.iso, x + lw, y, Gfx.MEDIUM, T_READOUT, INK, Gfx.LEFT, 0);
        }
        return x + lw + iw;
    }

    /** Exposure compensation in words: "0", "+1/3", "–1 2/3", "+2" (the en dash: Latent's fonts have no minus sign). */
    static String evText(int ev) {
        if (ev == 0) return "0";
        int a = Math.abs(ev), whole = a / 3, third = a % 3;
        return (ev > 0 ? "+" : "\u2013") + (whole > 0 ? String.valueOf(whole) : "") + (third == 0 ? "" : (whole > 0 ? " " : "") + third + "/3");
    }

    /**
     * The exposure scale, open (DOWN, the camera's ± key), in the right side bar beside the wheel that moves it
     * (as the iPhone's Camera Control shows its scale at the button, and Fuji's large indicators use the side
     * columns): +3 at the top, -3 at the foot, a tick a third, the caret where it sits and the value under it.
     * The picture stays clear. The wheel moves it a third at a time; DOWN, centre or a half-press closes it.
     */
    private static void evTrack(Gfx g, UiState s, float cx, int accent) {
        g.text("EV", cx, 124, Gfx.MEDIUM, T_LABEL, INK3, Gfx.CENTER, LABEL_TRACK);
        float top = 152, step = 10;
        for (int i = 0; i <= 18; i++) {   // horizontal ticks: rows are whole pixels already; their ends on whole dots
            int ev = 9 - i;
            boolean major = ev % 3 == 0, zero = ev == 0;
            float w = zero ? 24 : major ? 16 : 8, a = vx(s, cx - w / 2), b = vx(s, cx + w / 2);
            g.rect(a, top + i * step - 1, b - a, 2, zero ? INK : major ? INK2 : DIM);
        }
        g.text("+", cx - 21, top + 5, Gfx.MEDIUM, T_LABEL, INK3, Gfx.CENTER, 0);
        g.text("\u2013", cx - 21, top + 18 * step + 5, Gfx.MEDIUM, T_LABEL, INK3, Gfx.CENTER, 0);
        int e = Math.max(-9, Math.min(9, s.ev));
        float y = top + (9 - e) * step;
        int c = s.ev == 0 ? INK : accent;
        g.line(cx + 22, y - 6, cx + 15, y, 2, c);   // the caret, pointing at the scale from the wheel's side
        g.line(cx + 15, y, cx + 22, y + 6, 2, c);
        if (e != 0) { float a = vx(s, cx - 12), b = vx(s, cx + 12); g.rect(a, y - 1.5f, b - a, 3, c); }
        String v = evText(s.ev);
        g.text(v, cx, top + 18 * step + 44, Gfx.MEDIUM, fit(g, v, Gfx.MEDIUM, T_READOUT, FLOOR, BAR_TEXT, 0), c, Gfx.CENTER, 0);
    }

    /** The exposure scale in the overlay: -3 to +3 in thirds, a mark where exposure sits (ev in thirds, 0 = metered). */
    private static void meter(Gfx g, UiState s, float cx, float y, float w, int ev, int ink, int accent) {
        float x0 = cx - w / 2, step = w / 18, tw = vw(s, 1.4f);
        for (int i = 0; i <= 18; i++) {   // vertical ticks on whole dots, each one dot wide
            boolean major = i % 3 == 0, zero = i == 9;
            float h = zero ? 10 : major ? 7 : 4;
            g.rect(vx(s, x0 + i * step - tw / 2), y - h / 2, tw, h, zero ? ink : (ink & 0x00FFFFFF) | (major ? 0xAA000000 : 0x66000000));
        }
        g.text("\u2013", x0 - 10, y + 5, Gfx.MEDIUM, T_LABEL, INK2, Gfx.CENTER, 0);
        g.text("+", x0 + w + 10, y + 5, Gfx.MEDIUM, T_LABEL, INK2, Gfx.CENTER, 0);
        int e = Math.max(-9, Math.min(9, ev));
        float mx = cx + e * step;
        g.line(mx - 5, y + 12, mx, y + 7, 1.6f, ev == 0 ? ink : accent);
        g.line(mx, y + 7, mx + 5, y + 12, 1.6f, ev == 0 ? ink : accent);
        if (ev != 0) g.text(evText(ev), mx, y - 12, Gfx.MEDIUM, T_LABEL, accent, Gfx.CENTER, 0);
    }

    /** The last shot as a small print developing in the corner; with photos queued, how many. */
    private static void print(Gfx g, UiState s, float R, float y, int accent) {
        if (s.printImage == null && s.developing <= 0) return;
        float w = 66, h = 44, x = R - w, top = y - h + 6;
        g.roundRect(x - 3, top - 3, w + 6, h + 6, 4, 0xFFEEEEDD);   // a white border, like a print
        if (s.printImage == null) {   // nothing to show yet: a print still latent, waiting for the darkroom
            g.rect(x, top, w, h, 0xFF222211);
            g.rect(x, top + h - 2, w, 2, (accent & 0x00FFFFFF) | 0xCC000000);
        } else {
            g.image(s.printImage, x, top, w, h, false);
            float left = 1 - Math.max(0, Math.min(1, s.printProgress));
            if (left > 0) {   // still latent: a ghost of the picture, developing up from the bottom
                g.rect(x, top, w, h * left, 0xB8222211);
                g.rect(x, top + h * left - 1, w, 2, (accent & 0x00FFFFFF) | 0xCC000000);
            }
        }
        if (s.developing > 0) {
            String d = String.valueOf(s.developing);
            float dw = Math.max(22, g.textWidth(d, Gfx.MEDIUM, T_LABEL, 0) + 12);
            g.roundRect(x - dw / 2, top - 11, dw, 22, 11, accent);
            g.text(d, x, top + 4.5f, Gfx.MEDIUM, T_LABEL, BG, Gfx.CENTER, 0);
        }
    }

    /** The focus brackets and what they say. */
    private static void afBrackets(Gfx g, UiState s, float ix, float iw) {
        float cx = ix + iw / 2, cy = H / 2, w = 150, h = 100;
        int c;
        float k, stroke;
        switch (s.afState) {
            case 1: c = INK; k = 0.86f; stroke = 2f; break;          // searching: tightening
            case 2: c = GREEN; k = 0.8f; stroke = 2.4f; break;       // locked: snapped tight
            case 3: c = AMBER; k = 1f; stroke = 2f; break;           // no lock
            default: c = 0xE6FFFFEE; k = 1f; stroke = 1.6f; break;   // ready
        }
        if (s.afState == 2 && s.afAreas != null) {   // the areas the camera focused on
            for (float[] a : s.afAreas) {
                float ax = ix + a[0] * iw, ay = a[1] * H, aw = a[2] * iw, ah = a[3] * H;
                g.strokeRoundRect(ax + 0.8f, ay + 0.8f, aw, ah, 3, 3.2f, 0x55000000);
                g.strokeRoundRect(ax, ay, aw, ah, 3, 2f, GREEN);
            }
        } else {
            corners(g, cx - w * k / 2, cy - h * k / 2, w * k, h * k, 16, stroke, c);
            if (s.afState == 1) corners(g, cx - w / 2, cy - h / 2, w, h, 16, vw(s, 1), 0x40FFFFEE);   // where it started
        }
        if (s.afState == 2 && s.afAreas == null) g.circle(cx, cy, 2.5f, GREEN);
        String say = s.afState == 3 ? (s.afNote.length() > 0 ? s.afNote : "Can't focus") : s.afState == 1 && s.afTry > 1 ? "Trying again \u00b7 " + s.afTry : "";
        if (say.length() > 0) {
            float tw = g.textWidth(say, Gfx.MEDIUM, 15, 0) + 28, ty = cy + h / 2 + 18;
            g.roundRect(cx - tw / 2, ty, tw, 28, 14, PLATE);
            g.text(say, cx, ty + 19, Gfx.MEDIUM, 15, s.afState == 3 ? AMBER : INK, Gfx.CENTER, 0);
        }
    }

    /** Four corner marks around a rectangle, over a soft shadow so they read on any picture. */
    private static void corners(Gfx g, float x, float y, float w, float h, float len, float stroke, int c) {
        if ((c >>> 24) > 0x60) corners1(g, x + 0.8f, y + 0.8f, w, h, len, stroke + 1.6f, 0x55000000);
        corners1(g, x, y, w, h, len, stroke, c);
    }

    private static void corners1(Gfx g, float x, float y, float w, float h, float len, float stroke, int c) {
        float r = x + w, b = y + h;
        g.line(x, y, x + len, y, stroke, c); g.line(x, y, x, y + len, stroke, c);
        g.line(r, y, r - len, y, stroke, c); g.line(r, y, r, y + len, stroke, c);
        g.line(x, b, x + len, b, stroke, c); g.line(x, b, x, b - len, stroke, c);
        g.line(r, b, r - len, b, stroke, c); g.line(r, b, r, b - len, stroke, c);
    }

    /** A tiny histogram of live view's brightness, on its own plate. */
    private static void histogram(Gfx g, UiState s, int[] hist, float x, float y, float w, float h) {
        g.roundRect(x - 6, y - 6, w + 12, h + 12, 6, PLATE);
        int max = 1;
        for (int v : hist) max = Math.max(max, v);
        float bw = w / hist.length;
        for (int i = 0; i < hist.length; i++) {
            float bh = h * hist[i] / max;
            if (bh >= 0.5f) g.rect(x + i * bw, y + h - bh, bw + 0.3f, bh, INK2);
        }
        g.rect(x, y + h, w, 1, LINE);
    }

    /** The film simulation card: the film's name large, every film's canister in a row, this one raised. */
    private static void lookCard(Gfx g, UiState s, UiState.Film f) {
        String tag = s.cardNote.length() > 0 ? s.cardNote : f.tagline;
        String sub = (f.iso.length() > 0 ? "ISO " + f.iso : "") + (tag.length() > 0 ? (f.iso.length() > 0 ? "  \u00b7  " : "") + tag : "");
        float tw = Math.max(Math.max(g.textWidth(f.title, Gfx.DISPLAY, T_DISPLAY, -10), g.textWidth(sub, Gfx.REGULAR, T_SECOND, 0)), s.looks.size() * 34f) + 72;
        tw = Math.min(tw, H * 1.5f - 2 * INSET);
        float x = s.width / 2 - tw / 2, y = 150;
        g.roundRect(x, y, tw, 168, 24, PLATE);
        g.text(f.title, s.width / 2, y + 72, Gfx.DISPLAY, T_DISPLAY, INK, Gfx.CENTER, -10);
        if (sub.length() > 0) g.text(sub, s.width / 2, y + 102, Gfx.REGULAR, T_SECOND, INK2, Gfx.CENTER, 0);
        canisterRow(g, s.looks, s.lookSel, s.width / 2, y + 128);
    }

    /** Every film's canister in a row, centred at cx, the chosen one raised and underlined in its colour. */
    private static void canisterRow(Gfx g, List<UiState.Film> films, int sel, float cx, float y) {
        float cw = 34, cx0 = cx - (films.size() - 1) * cw / 2;
        for (int i = 0; i < films.size(); i++) {
            UiState.Film o = films.get(i);
            boolean on = i == sel;
            float sw = on ? 18 : 13, sh = on ? 21 : 15, x = cx0 + i * cw - sw / 2, yy = y - (on ? 4 : 0);
            g.roundRect(x, yy, sw, sh, 3, on ? o.body : (o.body & 0x00FFFFFF) | 0x99000000);
            g.rect(x, yy + sh / 3, sw, sh / 3, on ? o.band : (o.band & 0x00FFFFFF) | 0x99000000);
            if (on) g.roundRect(cx0 + i * cw - 8, yy + sh + 6, 16, 3, 1.5f, o.accent);
        }
    }

    /**
     * The first visit: the viewfinder's keys, once, for a few seconds, at the foot of the picture where the
     * exposure line is (Apple's sparing onboarding). Gone at the first key.
     */
    private static void coach(Gfx g, UiState s) {
        String[] k = { "wheel", "Film", "up", "Display", "down", "Exposure", "centre", "Look", "menu", s.developing > 0 ? "Develop" : "Back" };
        float w = 0;
        for (int i = 0; i < k.length; i += 2) w += glyphWidth(g, k[i]) + 8 + g.textWidth(k[i + 1], Gfx.REGULAR, T_SECOND, 0) + (i > 0 ? 24 : 0);
        float ix = (s.width - H * 1.5f) / 2, x = s.width / 2 - w / 2, y = 444;
        g.roundRect(ix + INSET - 8, y - 28, H * 1.5f - 2 * INSET + 16, 44, 22, PLATE);   // over the whole exposure line
        hintPairs(g, k, x, y, INK, false);
    }

    // ------------------------------------------------------------------------------ settings

    /** What the menu's rows depend on: MainActivity fills it from the roll and the card, the Mac preview by hand. */
    static final class Menu {
        boolean roll;                    // a roll is loaded
        boolean developNow;              // a film roll part-way through: it can be ended and developed now
        boolean changeFilm;              // its film can change (it isn't developing)
        boolean endless;
        int exposed;
        boolean photos;                  // the card has developed photos to look at
        String version = "";
    }

    /**
     * The menu's rows, the same on the camera and in the previews: {label, value, action, why not}. Value "›"
     * opens something, "" is a heading. "Why not" is empty when the row can act now; otherwise the row is grey
     * and the footer says why (Hasselblad). The roll's two actions when they apply, then the photos: nothing to
     * set (v0.45.0: what the settings and the camera checks settled is how Latent works now).
     */
    static List<String[]> menuRows(Menu m) {
        List<String[]> rows = new ArrayList<String[]>();
        rows.add(new String[] { m.roll ? "This roll" : "Latent", "", "", "" });
        if (m.roll && m.developNow) rows.add(new String[] { "Develop now", "\u203a", "develop", "" });
        if (m.roll && m.changeFilm) rows.add(new String[] { "Change film", "\u203a", "change", "" });
        rows.add(new String[] { "Photos", "\u203a", "photos", m.photos ? "" : "No photos yet. Developed photos appear here." });
        return rows;
    }

    /**
     * The menu into s, as MainActivity and the previews both show it: its rows ({label, value, why not}), the
     * chosen one (sel, an index of menuRows), and its footer.
     */
    static void menuState(UiState s, Menu m, int sel) {
        List<String[]> rows = menuRows(m);
        sel = Math.max(0, Math.min(sel, rows.size() - 1));
        s.rows = new ArrayList<String[]>(rows.size());
        for (String[] r : rows) s.rows.add(new String[] { r[0], r[1], r[3] });
        s.rowSel = sel;
        s.footer = menuFooter(rows.get(sel), m);
    }

    /** How a stock is shown (its sample, the picture in it, is the caller's). */
    static UiState.Film film(Stock st) {
        if (st == null) return null;
        UiState.Film f = new UiState.Film();
        f.title = st.title;
        f.iso = st.iso;
        f.tagline = st.tagline;
        f.body = st.body;
        f.band = st.band;
        f.accent = st.accent;
        return f;
    }

    /**
     * What the chosen row does, in a line or two at the foot of the menu (or why it can't now). Photos, the last
     * row, ends with Latent's version: the menu's last row has always shown it.
     */
    static String menuFooter(String[] row, Menu m) {
        String a = row.length > 2 ? row[2] : "", why = row.length > 3 ? row[3] : "";
        if (a.equals("photos")) return (why.length() > 0 ? why : "Every developed photo, newest first, roll by roll.") + " Latent " + m.version;
        if (why.length() > 0) return why;
        if (a.equals("develop")) return "Ends the roll here and develops the " + m.exposed + " shot so far.";
        if (a.equals("change")) return m.endless ? "This roll's photos keep developing." : "Keeps the frames shot so far for developing.";
        return "Latent " + m.version;
    }

    /** The menu's row pitch, and a heading's. */
    static final float ROW = 44, HEAD = 32;

    /**
     * The menu: one plain list, the way the Settings app does it. Headings, switches, and rows that
     * open. It scrolls to keep the chosen row in view, under a fixed title and footer; a row is drawn
     * whole or not at all.
     */
    /** The menu's list: from MENU_TOP to MENU_BOTTOM, above a two-line footer. */
    static final float MENU_TOP = 72, MENU_BOTTOM = 412;

    /** Each row's top in the list, unscrolled (a heading's with the gap above it). */
    private static float[] menuYs(UiState s) {
        float[] ys = new float[s.rows.size()];
        float y = 0;
        for (int i = 0; i < s.rows.size(); i++) {
            if (s.rows.get(i)[1].length() == 0) { ys[i] = y + (i == 0 ? 0 : 10); y += HEAD + (i == 0 ? 0 : 10); }
            else { ys[i] = y; y += ROW; }
        }
        return ys;
    }

    /** How far the list is scrolled to show the chosen row: a whole row or heading at its top. */
    private static float menuScroll(UiState s, float[] ys) {
        if (s.rowSel <= 1 || s.rowSel >= ys.length) return 0;   // the first row: the list from its top, heading and all
        float need = MENU_TOP + ys[s.rowSel] + ROW - MENU_BOTTOM;   // how far up the list must move to show the chosen row
        if (need > 0) for (int i = 0; i <= s.rowSel; i++) if (ys[i] >= need) return ys[i];
        return 0;
    }

    private static void settings(Gfx g, UiState s) {
        float R = right(s), top = MENU_TOP, bottom = MENU_BOTTOM;
        int accent = s.film != null ? s.film.accent : INK;
        float[] ys = menuYs(s);
        float scroll = menuScroll(s, ys);
        for (int i = 0; i < s.rows.size(); i++) {
            String[] r = s.rows.get(i);
            boolean head = r[1].length() == 0;
            float ry = top + ys[i] - scroll;
            if (ry < top - 0.5f || ry + (head ? HEAD : ROW) > bottom + 0.5f) continue;   // whole rows only
            if (head) {   // a heading
                g.text(r[0].toUpperCase(java.util.Locale.US), L, ry + 22, Gfx.MEDIUM, T_LABEL, INK3, Gfx.LEFT, LABEL_TRACK);
                continue;
            }
            boolean on = i == s.rowSel, can = r.length < 3 || r[2].length() == 0;
            if (on) {
                g.roundRect(L - 16, ry, R - L + 32, ROW - 4, 10, SELECT);
                g.rect(vx(s, L - 16), ry + 9, vw(s, 4), ROW - 22, can ? accent : INK3);   // the chosen row's bar
            }
            g.text(r[0], L, ry + 27, Gfx.REGULAR, T_BODY, on ? (can ? INK : INK2) : can ? INK2 : INK3, Gfx.LEFT, 0);
            g.line(R - 8, ry + 14, R - 2, ry + 20, 2, on ? INK2 : INK3);   // it opens something
            g.line(R - 2, ry + 20, R - 8, ry + 26, 2, on ? INK2 : INK3);
        }
        if (scroll > 0) g.rect(L - 16, top - 1, R - L + 32, 1, LINE);
        g.text("Menu", L, TOP, Gfx.MEDIUM, T_BODY, INK, Gfx.LEFT, 0);
        battery(g, s.battery, R, INK2, INK3);
        float rx = hintBar(g, s, INK2, null, new String[] { "menu", "Back" });
        if (s.footer.length() > 0) footer(g, s.footer, L, rx - 32 - L, INK2);
    }

    /** A footer on the hint bar's left: up to two lines, the last on the hint baseline. */
    private static void footer(Gfx g, String t, float x, float w, int color) {
        List<String> lines = lines(g, t, w, Gfx.REGULAR, T_SECOND, 2);
        float y = HINT_Y - (lines.size() - 1) * 22;
        for (String l : lines) { keyText(g, l, x, y, Gfx.REGULAR, T_SECOND, color, Gfx.LEFT); y += 22; }
    }

    /** A short notice, centred under the top bar, wrapped to two lines. Never on top of the controls. */
    private static void toast(Gfx g, UiState s) {
        if (s.toast == null || s.toast.length() == 0) return;
        boolean dark = s.mode == UiState.DARKROOM;
        List<String> lines = lines(g, s.toast, right(s) - L - 40, Gfx.MEDIUM, T_SECOND, 2);
        float w = 0;
        for (String l : lines) w = Math.max(w, keyWidth(g, l, Gfx.MEDIUM, T_SECOND));
        w += 40;
        float x = (s.width - w) / 2, y = 72, h = 34 + (lines.size() - 1) * 22;
        g.roundRect(x, y, w, h, 17, dark ? 0xFF220000 : SURFACE);
        float ty = y + 23;
        for (String l : lines) { keyText(g, l, s.width / 2, ty, Gfx.MEDIUM, T_SECOND, dark ? RED : INK, Gfx.CENTER); ty += 22; }
    }

    /**
     * A row of marks, one per frame, every sixth a little taller: the scale on a film counter. Shot frames in
     * 'on', the newest in 'last'; the roll's last three, not yet shot, in 'end' (its end, coming).
     */
    private static void ticks(Gfx g, UiState s, int n, int filled, float x0, float x1, float y, int on, int off, int last, int end) {
        if (n <= 0 || n > 72) return;   // a film counter has at most 72 frames (a half-frame 36)
        float tw = vw(s, 4), step = (x1 - x0 - tw) / Math.max(1, n - 1);
        for (int i = 0; i < n; i++) {
            float x = vx(s, x0 + i * step);
            int c = i < filled ? (i == filled - 1 ? last : on) : n - i <= 3 ? end : off;
            boolean major = (i + 1) % 6 == 0;
            g.roundRect(x, major ? y - 4 : y, tw, major ? 20 : 16, 2, c);
        }
    }

    /** A colour toward black by k (0..1), opaque and on the 4-bit grid. */
    static int shade(int c, float k) {
        int out = 0xFF000000;
        for (int sh = 16; sh >= 0; sh -= 8) out |= Math.round(((c >> sh) & 255) * k / 17f) * 17 << sh;
        return out;
    }

    /**
     * A film canister: the stock's two colours, with its spool on top (nub). One drawing everywhere: the
     * headers, the chooser, the viewfinder.
     */
    private static void canister(Gfx g, UiState.Film f, float x, float y, float k, boolean nub) {
        if (nub) g.roundRect(x + 5 * k, y - 2 * k, 6 * k, 3 * k, k, INK3);
        g.roundRect(x, y, 16 * k, 18 * k, 3 * k, f.body);
        g.rect(x, y + 6 * k, 16 * k, 6 * k, f.band);
    }

    /** A screen's title, film first: "[canister] Cuban Neg 400 · Roll 3". */
    private static void title(Gfx g, UiState.Film f, String after, int color) {
        canister(g, f, L, TOP - 16, 1, true);
        String t = f.title + (f.iso.length() > 0 ? " " + f.iso : "") + (after.length() > 0 ? "  \u00b7  " + after : "");
        g.text(t, L + 26, TOP, Gfx.MEDIUM, T_BODY, color, Gfx.LEFT, 0);
    }

    private static void battery(Gfx g, int pct, float R, int text, int outline) {
        if (pct < 0) return;
        String t = pct + "%";
        boolean low = pct <= 20;
        float tw = g.textWidth(t, Gfx.MEDIUM, T_SECOND, 0);
        g.text(t, R, TOP, Gfx.MEDIUM, T_SECOND, low ? RED : text, Gfx.RIGHT, 0);
        float bx = R - tw - 8 - 30, by = TOP - 13;
        g.strokeRoundRect(bx, by, 26, 13, 3, 1.5f, outline);
        g.roundRect(bx + 27, by + 4, 2.5f, 5, 1, outline);
        g.roundRect(bx + 2.5f, by + 2.5f, 21 * Math.max(0.06f, pct / 100f), 8, 1.5f, low ? RED : text);
    }

    // ------------------------------------------------------------------------------ load film

    /**
     * Choosing film is the home screen when the camera is empty: your last photo, full screen, in the
     * film under the wheel (the way a Fuji previews a film simulation), its name over the picture,
     * and the roll length beside it. The centre button loads it; a half-press loads it and goes to shoot.
     * The chrome keeps to the picture's insets, where the viewfinder has it: a half-press from here leaves
     * the title and the battery where they were.
     */
    /** Choose film's picture: the full-size one, else the film's sample, else null. */
    private static Object loadHero(UiState s) {
        UiState.Film f = s.films.isEmpty() ? null : s.films.get(Math.min(s.filmSel, s.films.size() - 1));
        return s.liveImage != null ? s.liveImage : f != null ? f.sample : null;
    }

    /** Choose film fills the picture's area (with the picture, or its placeholder while it renders). */
    private static boolean loadPicture(UiState s) {
        return loadHero(s) != null || (!s.films.isEmpty() && s.liveCaption.length() > 0);
    }

    private static void load(Gfx g, UiState s) {
        UiState.Film f = s.films.isEmpty() ? null : s.films.get(Math.min(s.filmSel, s.films.size() - 1));
        Object hero = loadHero(s);
        float iw = H * 1.5f, ix = (s.width - iw) / 2, ir = ix + iw, x0 = ix + INSET, x1 = ir - INSET;
        boolean picture = loadPicture(s);
        if (hero != null) g.image(hero, ix, 0, iw, H, false);
        else if (picture) g.rect(ix, 0, iw, H, SURFACE);   // still rendering
        if (picture) {   // a soft fade to black under the words, so they read over any picture
            for (int y = 230; y < H; y += 2) {
                float t = (y - 230) / 170f;
                g.rect(ix - 1, y, iw + 2, 2, ((int) (0xE8 * Math.min(1, t * t * (3 - 2 * Math.min(1, t))))) << 24);
            }
        }

        String head = s.cancelable ? "Change film" : "Choose film";
        float hw = g.textWidth(head, Gfx.MEDIUM, T_SECOND, 0) + 28;
        g.roundRect(x0, 15, hw, 30, 15, GLASS);
        g.text(head, x0 + 14, 36, Gfx.MEDIUM, T_SECOND, INK, Gfx.LEFT, 0);
        if (s.battery >= 0) {
            float bw = g.textWidth(s.battery + "%", Gfx.MEDIUM, 15, 0) + 32 + 28;
            g.roundRect(x1 - bw, 15, bw, 30, 15, GLASS);
            battery2(g, s.battery, x1 - 14, 36);
        }
        if (f == null) {
            g.text("No looks on the card", x0 + 12, 250, Gfx.DISPLAY, T_DISPLAY, INK, Gfx.LEFT, -10);
            g.text("Copy .CUB looks to LATENT/LUTS on the card, then open Latent again.", x0 + 14, 290, Gfx.REGULAR, T_BODY, INK2, Gfx.LEFT, 0);
            hintBar(g, s, INK2, null, new String[] { "menu", "Menu" });
            return;
        }
        if (hero == null && s.liveCaption.length() == 0) {   // nothing shot yet to show the look on
            g.text("Your first photo will show each look.", s.width / 2, 200, Gfx.REGULAR, T_BODY, INK2, Gfx.CENTER, 0);
        }

        // which film of how many: every film's canister, this one raised (the viewfinder's card has the same row)
        float cw = s.films.size() * 34 + 8;
        g.roundRect(s.width / 2 - cw / 2, 12, cw, 40, 20, GLASS);
        canisterRow(g, s.films, s.filmSel, s.width / 2, 22);

        // the roll length: a segmented control on the right, its caption above it
        int n = s.lengthLabels.length;
        float seg = LEN_SEG, sw = seg * n, sx = x1 - sw, sy = LEN_Y, sh = LEN_H;
        // the film: canister, name, ISO and a line about it (up to two lines, never into the length control)
        String sub = (f.iso.length() > 0 ? "ISO " + f.iso : "") + (f.tagline.length() > 0 ? (f.iso.length() > 0 ? "  \u00b7  " : "") + f.tagline : "");
        float tx = x0 + 38, subMax = sx - 28 - tx;
        List<String> lines = lines(g, sub, subMax, Gfx.REGULAR, T_SECOND, 2);
        int stop = sub.lastIndexOf(". ");   // two lines: broken after the character, before the use, where it fits
        if (lines.size() > 1 && stop > 0 && g.textWidth(sub.substring(0, stop + 1), Gfx.REGULAR, T_SECOND, 0) <= subMax
                && g.textWidth(sub.substring(stop + 2), Gfx.REGULAR, T_SECOND, 0) <= subMax) {
            lines = new ArrayList<String>();
            lines.add(sub.substring(0, stop + 1));
            lines.add(sub.substring(stop + 2));
        }
        float ny = 392 - lines.size() * 24;   // the name's baseline: the block ends at the same place
        canister(g, f, x0, ny - 30, 1.4f, true);
        g.text(f.title, tx, ny, Gfx.DISPLAY, T_DISPLAY, INK, Gfx.LEFT, -10);
        float ly = ny + 28;
        for (String l : lines) { g.text(l, tx, ly, Gfx.REGULAR, T_SECOND, INK2, Gfx.LEFT, 0); ly += 24; }

        g.text(s.lengthSel == n - 1 ? "No end: develops as you go" : "exposures", x1, sy - 12, Gfx.REGULAR, T_SECOND, INK2, Gfx.RIGHT, 0);
        g.roundRect(sx, sy, sw, sh, sh / 2, 0xB0222222);
        for (int i = 0; i < n; i++) {
            boolean on = i == s.lengthSel;
            if (on) g.roundRect(sx + i * seg + 4, sy + 4, seg - 8, sh - 8, (sh - 8) / 2, INK);
            float cx = sx + i * seg + seg / 2;
            if (s.lengthLabels[i].equals("\u221e")) infinity(g, cx, sy + sh / 2, on ? BG : INK);   // the fonts have no ∞
            else g.text(s.lengthLabels[i], cx, sy + 27, Gfx.MEDIUM, 18, on ? BG : INK, Gfx.CENTER, 0);
        }

        if (s.loading) {
            g.text("Loading\u2026", L, HINT_Y, Gfx.MEDIUM, T_SECOND, INK, Gfx.LEFT, 0);
            return;
        }
        hintBar(g, s, INK2, new String[] { "wheel", "Film", "updown", "Length" },
                new String[] { "centre", "Load", "menu", s.cancelable ? "Back" : "Menu" });
    }

    /** The darkroom's tray, and the band of the progress bar and its line: during a develop only these are redrawn. */
    static final float TRAY_W = 300, TRAY_H = 200, TRAY_Y = 84, BAND_TOP = 306, BAND_BOTTOM = 366;
    static final int TRAY_STEPS = 5;
    private static final int TRAY_BG = 0xFF110000;

    /** How far up out of the dark the print is drawn, 0..TRAY_STEPS, for a frame this far developed. */
    static int trayStep(float framePct) { return Math.min(TRAY_STEPS, (int) (framePct * TRAY_STEPS)); }

    /** The tray's left edge on a screen this wide (logical pixels). */
    static float trayX(float width) { return width - L - TRAY_W; }

    /**
     * The darkroom, under a safelight, with the screen turned right down: what you need to read in RED, the
     * rest in RED2 at 18 px and up, so it all reads at 5% backlight.
     */
    private static void darkroom(Gfx g, UiState s) {
        float R = right(s);
        UiState.Film f = s.film;
        g.text("Darkroom" + (f != null ? "  \u00b7  " + f.title + " " + f.iso : ""), L, TOP, Gfx.MEDIUM, T_BODY, RED2, Gfx.LEFT, 0);
        battery(g, s.battery, R, RED, RED4);

        // the frame number, large
        String num = (s.devFrame + 1 < 10 ? "0" : "") + (s.devFrame + 1);
        g.text(num, L - 8, 262, Gfx.DISPLAY, T_HERO_DARK, RED, Gfx.LEFT, -30);
        float nw = g.textWidth(num, Gfx.DISPLAY, T_HERO_DARK, -30);
        g.text("of " + s.devTotal, L - 8 + nw + 14, 262, Gfx.REGULAR, 24, RED2, Gfx.LEFT, 0);

        // the print in the tray: the frame comes up out of the dark as it develops, in TRAY_STEPS steps
        // (each step redraws the tray: a smooth fade cost a ~100 ms redraw per percent, v0.41.0)
        float pw = TRAY_W, ph = TRAY_H, px = R - pw, py = TRAY_Y;
        boolean printing = s.devPrint != null && s.devFramePct > 0;
        if (!printing) g.rect(px, py, pw, ph, TRAY_BG);   // (the print covers the tray when it shows)
        if (printing) {
            g.image(s.devPrint, px, py, pw, ph, true);
            int veil = (int) (255 * (1 - Math.min(1f, trayStep(s.devFramePct) / (float) TRAY_STEPS * 1.15f)));
            if (veil > 0) g.rect(px, py, pw, ph, (veil << 24) | (TRAY_BG & 0x00FFFFFF));
        }
        g.strokeRoundRect(px - 1, py - 1, pw + 2, ph + 2, 2, 1, RED3);

        // whole roll: a bar that always moves, and an honest time estimate (inside BAND_TOP..BAND_BOTTOM)
        g.roundRect(L, 318, R - L, 4, 2, RED3);
        g.roundRect(L, 318, Math.max(4, (R - L) * Math.min(1f, s.devProgress)), 4, 2, RED);
        String line = s.devPreparing ? "Getting the darkroom ready" : s.paused ? "Pausing after this frame" : s.devLeft;
        g.text(line, L, 356, Gfx.REGULAR, T_BODY, RED, Gfx.LEFT, 0);
        if (!s.devPreparing) g.text(Math.round(s.devProgress * 100) + "%", R, 356, Gfx.MEDIUM, T_BODY, RED, Gfx.RIGHT, 0);

        // the contact strip: developed frames drop in, the one in the tray is outlined
        int slots = Math.min(s.devTotal, 12), first = Math.max(0, Math.min(s.devFrame - slots / 2, s.devTotal - slots));
        float sh = 34, sw = 51, gap = slots > 1 ? Math.min(14, (R - L - slots * sw) / (slots - 1)) : 0;   // a strip: spread up to the width
        for (int k = 0; k < slots; k++) {
            int i = first + k;
            float x = L + k * (sw + gap), y = 380;
            if (i < s.devStrip.size() && s.devStrip.get(i) != null) g.image(s.devStrip.get(i), x, y, sw, sh, true);
            else g.rect(x, y, sw, sh, i == s.devFrame ? RED3 : TRAY_BG);
            if (i == s.devFrame) g.strokeRoundRect(x - 2, y - 2, sw + 4, sh + 4, 2, 1.5f, RED);
        }
        g.text("Safe to set the camera down", L, HINT_Y, Gfx.REGULAR, 18, RED2, Gfx.LEFT, 0);
        if (!s.devPreparing) hintBar(g, s, RED, null, new String[] { "wheel", "Brighten", "menu", "Pause" });
    }

    // ------------------------------------------------------------------------------ contact sheet

    private static void sheet(Gfx g, UiState s) {
        float R = right(s);
        UiState.Film f = s.film;
        if (f == null) {
            g.text("Contact sheets", L, TOP, Gfx.MEDIUM, T_BODY, INK, Gfx.LEFT, 0);
            g.text("No rolls yet", L - 3, 222, Gfx.DISPLAY, T_DISPLAY, INK, Gfx.LEFT, -10);
            g.text("Developed rolls appear here.", L, 262, Gfx.REGULAR, T_BODY, INK2, Gfx.LEFT, 0);
            hintBar(g, s, INK2, null, new String[] { "menu", "Back" });
            return;
        }
        title(g, f, s.sheetPhotos ? "Photos" : "Roll " + s.rollNumber, INK);
        if (s.thumbs.isEmpty()) {
            g.text(s.sheetUndeveloped ? "Not developed yet" : "No frames", L - 3, 222, Gfx.DISPLAY, T_DISPLAY, INK, Gfx.LEFT, -10);
            if (s.sheetUndeveloped) keyText(g, "Press \u2299 to develop it.", L, 262, Gfx.REGULAR, T_BODY, INK2, Gfx.LEFT);
            hintBar(g, s, INK2, new String[] { "updown", "Roll" }, s.sheetUndeveloped ? new String[] { "centre", "Develop", "menu", "Back" } : new String[] { "menu", "Back" });
            return;
        }
        if (s.filingLeft > 0) {   // until then, leaving or switching to USB leaves an original for next time
            String filing = "Filing " + s.filingLeft + (s.filingLeft == 1 ? " negative\u2026" : " negatives\u2026");
            g.text(filing, R, TOP, Gfx.MEDIUM, T_SECOND, INK2, Gfx.RIGHT, 0);
        } else {
            g.text((s.sheetSel + 1) + " of " + s.thumbs.size(), R, TOP, Gfx.MEDIUM, T_SECOND, INK2, Gfx.RIGHT, 0);
        }

        int cols = SHEET_COLS, per = SHEET_PER, page = s.sheetSel / per;
        float gap = SHEET_GAP, tw = sheetCell(s), th = tw * 2 / 3;
        for (int k = 0; k < per; k++) {
            int i = page * per + k;
            if (i >= s.thumbs.size()) break;
            float x = L + (k % cols) * (tw + gap), y = SHEET_TOP + (k / cols) * (th + 24);
            Object img = s.thumbs.get(i);
            if (img != null) g.image(img, x, y, tw, th, false);   // image() crops the thumbnail's letterbox bars
            else g.rect(x, y, tw, th, SURFACE);
            boolean on = i == s.sheetSel;
            if (on) g.strokeRoundRect(x - 4, y - 4, tw + 8, th + 8, 4, vw(s, 2), INK);
            g.text(String.valueOf(i + 1), x, y + th + 19, Gfx.MONO, 15, on ? f.accent : INK2, Gfx.LEFT, 0);
        }
        hintBar(g, s, INK2, new String[] { "wheel", "Frame", "updown", "Roll" }, new String[] { "centre", "View", "menu", "Back" });
    }

    /** The photo as large as the panel allows; centre toggles filling the whole screen. */
    private static void view(Gfx g, UiState s) {
        if (s.viewImage != null) {
            float iw = g.imageWidth(s.viewImage), ih = g.imageHeight(s.viewImage);
            float sc = s.viewFill ? Math.max(s.width / iw, H / ih) : Math.min(s.width / iw, H / ih);
            float dw = Math.min(s.width, iw * sc), dh = Math.min(H, ih * sc), x = (s.width - dw) / 2, y = (H - dh) / 2;
            // black only where the photo isn't (draw() didn't fill the screen), a pixel under its edges
            if (x > 0) { g.rect(0, 0, x + 1, H, BG); g.rect(x + dw - 1, 0, s.width - x - dw + 1, H, BG); }
            if (y > 0) { g.rect(0, 0, s.width, y + 1, BG); g.rect(0, y + dh - 1, s.width, H - y - dh + 1, BG); }
            g.image(s.viewImage, x, y, dw, dh, false);
        } else {
            g.text("Developing preview\u2026", s.width / 2, 248, Gfx.REGULAR, T_BODY, INK2, Gfx.CENTER, 0);
        }
        if (s.viewCaption) {   // for a moment: which frame, and the keys (on the hint bar's baseline)
            UiState.Film f = s.film;
            g.rect(0, HINT_Y - 26, s.width, H - HINT_Y + 26, GLASS);
            String t = (s.sheetSel + 1) + " of " + s.sheetTotal + (f != null ? "  \u00b7  " + f.title + " " + f.iso : "");
            g.text(t, L, HINT_Y, Gfx.MEDIUM, T_SECOND, INK, Gfx.LEFT, 0);
            hintBar(g, s, INK, new String[] {}, new String[] { "wheel", "Frame", "centre", s.viewFill ? "Fit" : "Fill", "menu", "Sheet" });
        }
    }

    // ------------------------------------------------------------------------------ what a key changes

    // the layout the partial redraws share with the drawing: Choose film's length control, the contact sheet's grid
    static final float LEN_SEG = 54, LEN_Y = 352, LEN_H = 40;
    static final int SHEET_COLS = 5, SHEET_PER = 15;
    static final float SHEET_TOP = 70, SHEET_GAP = 16;

    /** A contact sheet thumbnail's width. */
    private static float sheetCell(UiState s) { return (right(s) - L - (SHEET_COLS - 1) * SHEET_GAP) / SHEET_COLS; }

    /**
     * After a key: the part of the screen that 'b' draws differently from 'a' (the state last drawn), as {x, y, w, h}
     * in the layout, or null for all of it. Only for the small changes keys make: Choose film's length, the menu's
     * chosen row, the contact sheet's frame within a page. Anything else differing (a
     * toast, the battery, the film, a picture arriving) means the whole screen. RenderScreens checks that every
     * pixel that changes lies inside.
     */
    /** The screens whose keys may redraw only part of the screen (dirty). */
    static boolean keyPartial(int mode) {
        return mode == UiState.LOAD || mode == UiState.SETTINGS || mode == UiState.SHEET;
    }

    static float[] dirty(UiState a, UiState b) {
        if (a == null || b == null || a.mode != b.mode || a.width != b.width || a.battery != b.battery || !String.valueOf(a.toast).equals(String.valueOf(b.toast))
                || a.fatal != null || b.fatal != null) return null;
        switch (b.mode) {
            case UiState.LOAD: return loadDirty(a, b);
            case UiState.SETTINGS: return menuDirty(a, b);
            case UiState.SHEET: return sheetDirty(a, b);
            default: return null;
        }
    }

    private static boolean sameFilm(UiState.Film a, UiState.Film b) {
        if (a == null || b == null) return a == b;
        return a.title.equals(b.title) && a.iso.equals(b.iso) && a.tagline.equals(b.tagline) && a.body == b.body && a.band == b.band
                && a.accent == b.accent && a.sample == b.sample;
    }

    private static boolean sameFilms(List<UiState.Film> a, List<UiState.Film> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!sameFilm(a.get(i), b.get(i))) return false;
        return true;
    }

    private static float[] union(float[] a, float[] b) {
        float x = Math.min(a[0], b[0]), y = Math.min(a[1], b[1]);
        return new float[] { x, y, Math.max(a[0] + a[2], b[0] + b[2]) - x, Math.max(a[1] + a[3], b[1] + b[3]) - y };
    }

    /** Choose film, UP/DOWN: the length control and its caption. */
    private static float[] loadDirty(UiState a, UiState b) {
        if (a.filmSel != b.filmSel || a.loading != b.loading || a.cancelable != b.cancelable || !a.liveCaption.equals(b.liveCaption)
                || a.liveImage != b.liveImage || !sameFilms(a.films, b.films) || b.films.isEmpty() || !loadPicture(b)) return null;
        float x1 = (b.width + H * 1.5f) / 2 - INSET, w = Math.max(LEN_SEG * b.lengthLabels.length, 240) + 8;
        return new float[] { x1 + 4 - w, LEN_Y - 34, w, LEN_H + 38 };
    }

    /** The menu, a step: from the higher of the two chosen rows down, footer and all (the title stays). */
    private static float[] menuDirty(UiState a, UiState b) {
        if (a.rows.size() != b.rows.size() || (a.film == null ? INK : a.film.accent) != (b.film == null ? INK : b.film.accent)) return null;
        if (a.rowSel < 0 || b.rowSel < 0 || a.rowSel >= a.rows.size() || b.rowSel >= b.rows.size()) return null;
        for (int i = 0; i < b.rows.size(); i++) {
            if (i == a.rowSel || i == b.rowSel) continue;
            if (!java.util.Arrays.equals(a.rows.get(i), b.rows.get(i))) return null;
        }
        float[] ya = menuYs(a), yb = menuYs(b);
        float sa = menuScroll(a, ya), sb = menuScroll(b, yb);
        if (sa != sb) return null;
        float y = MENU_TOP + Math.min(ya[a.rowSel], yb[b.rowSel]) - sa - 2;
        return new float[] { 0, y, b.width, H - y };
    }

    /** The contact sheet, a frame along within a page: the two frames and the count at the top right. */
    private static float[] sheetDirty(UiState a, UiState b) {
        if (!sameFilm(a.film, b.film) || a.rollNumber != b.rollNumber || a.sheetPhotos != b.sheetPhotos || a.sheetUndeveloped != b.sheetUndeveloped
                || a.filingLeft != b.filingLeft || a.thumbs.size() != b.thumbs.size() || b.thumbs.isEmpty()
                || a.sheetSel / SHEET_PER != b.sheetSel / SHEET_PER) return null;
        for (int i = 0; i < b.thumbs.size(); i++) if (a.thumbs.get(i) != b.thumbs.get(i)) return null;
        float R = right(b);
        return union(union(sheetCellRect(a), sheetCellRect(b)), new float[] { R - 200, TOP - 24, 204, 32 });
    }

    private static float[] sheetCellRect(UiState s) {
        int k = s.sheetSel % SHEET_PER;
        float tw = sheetCell(s), th = tw * 2 / 3;
        float x = L + (k % SHEET_COLS) * (tw + SHEET_GAP), y = SHEET_TOP + (k / SHEET_COLS) * (th + 24);
        return new float[] { x - 7, y - 7, tw + 14, th + 7 + 26 };
    }

    // ------------------------------------------------------------------------------ controls

    /**
     * The hint bar, the same on every screen, on HINT_Y: adjusters (glyph + noun) from the left; at the right
     * the actions (glyph + verb), the centre button's then MENU's, MENU always last. Returns where the right
     * group starts, for a footer or a status to its left.
     */
    static float hintBar(Gfx g, UiState s, int color, String[] left, String[] right) {
        if (left != null) hintPairs(g, left, L, HINT_Y, color, false);
        return right == null ? right(s) : hintPairs(g, right, right(s), HINT_Y, color, true);
    }

    /** [glyph, label] pairs on a baseline, from x (or ending at x, alignRight); returns the far end. */
    private static float hintPairs(Gfx g, String[] p, float x, float y, int color, boolean alignRight) {
        if (alignRight) {
            float w = 0;
            for (int i = 0; i + 1 < p.length; i += 2) w += glyphWidth(g, p[i]) + 8 + g.textWidth(p[i + 1], Gfx.REGULAR, T_SECOND, 0) + (i > 0 ? 26 : 0);
            x -= w;
        }
        float start = x;
        for (int i = 0; i + 1 < p.length; i += 2) {
            if (i > 0) x += 26;
            x += glyph(g, p[i], x, y - 6, color) + 8;
            g.text(p[i + 1], x, y, Gfx.REGULAR, T_SECOND, color, Gfx.LEFT, 0);
            x += g.textWidth(p[i + 1], Gfx.REGULAR, T_SECOND, 0);
        }
        return alignRight ? start : x;
    }

    static float glyphWidth(Gfx g, String kind) {
        return kind.equals("menu") ? g.textWidth("MENU", Gfx.MEDIUM, 11, 40) + 12 : 18;
    }

    /** Small drawn glyphs for the camera's controls. cy is the glyph's vertical centre. */
    private static float glyph(Gfx g, String kind, float x, float cy, int color) {
        if (kind.equals("wheel")) {
            g.strokeCircle(x + 9, cy, 8, 1.5f, color);
            g.circle(x + 9, cy - 5, 1.6f, color);
        } else if (kind.equals("centre")) {
            g.strokeCircle(x + 9, cy, 8, 1.5f, color);
            g.circle(x + 9, cy, 3, color);
        } else if (kind.equals("updown") || kind.equals("up") || kind.equals("down")) {
            boolean up = !kind.equals("down"), down = !kind.equals("up"), both = up && down;
            float o = both ? 0 : 2.5f;
            if (up) { g.line(x + 4, cy - 3 + o, x + 9, cy - 8 + o, 1.6f, color); g.line(x + 9, cy - 8 + o, x + 14, cy - 3 + o, 1.6f, color); }
            if (down) { g.line(x + 4, cy + 3 - o, x + 9, cy + 8 - o, 1.6f, color); g.line(x + 9, cy + 8 - o, x + 14, cy + 3 - o, 1.6f, color); }
        } else if (kind.equals("menu")) {
            float w = glyphWidth(g, "menu");
            g.strokeRoundRect(x, cy - 9, w, 18, 4, 1.4f, color);
            g.text("MENU", x + w / 2, cy + 4, Gfx.MEDIUM, 11, color, Gfx.CENTER, 40);
            return w;
        }
        return 18;
    }

    /** In text, the centre button: drawn as its glyph (the fonts have no such character). "Press ⊙ to develop." */
    static final char KEY = '\u2299';

    /** Width of text that may hold the centre button's glyph. */
    static float keyWidth(Gfx g, String t, int font, float size) {
        int k = t.indexOf(KEY);
        if (k < 0) return g.textWidth(t, font, size, 0);
        return g.textWidth(t.substring(0, k), font, size, 0) + 18 + keyWidth(g, t.substring(k + 1), font, size);
    }

    /** Text that may hold the centre button's glyph (KEY). */
    static void keyText(Gfx g, String t, float x, float y, int font, float size, int color, int align) {
        if (t.indexOf(KEY) < 0) { g.text(t, x, y, font, size, color, align, 0); return; }
        if (align == Gfx.CENTER) x -= keyWidth(g, t, font, size) / 2; else if (align == Gfx.RIGHT) x -= keyWidth(g, t, font, size);
        String[] parts = t.split(String.valueOf(KEY), -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() > 0) { g.text(parts[i], x, y, font, size, color, Gfx.LEFT, 0); x += g.textWidth(parts[i], font, size, 0); }
            if (i < parts.length - 1) { glyph(g, "centre", x, y - size * 0.36f, color); x += 18; }
        }
    }

    /** Word-wraps text into lines no wider than w, at most maxLines (the last one cut with an ellipsis). */
    static List<String> lines(Gfx g, String text, float w, int font, float size, int maxLines) {
        List<String> out = new ArrayList<String>();
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (keyWidth(g, next, font, size) > w && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        if (out.size() > maxLines) {
            String last = out.get(maxLines - 1);
            while (last.length() > 1 && g.textWidth(last + "\u2026", font, size, 0) > w) last = last.substring(0, last.length() - 1);
            out = new ArrayList<String>(out.subList(0, maxLines - 1));
            out.add(last.trim() + "\u2026");
        }
        return out;
    }

    /** Word-wraps text into lines no wider than w (at most maxLines); returns the last line's baseline. */
    private static float wrap(Gfx g, String text, float x, float y, float w, int font, float size, int color, float lineHeight, int maxLines) {
        List<String> ls = lines(g, text, w, font, size, maxLines);
        for (int i = 0; i < ls.size(); i++) {
            if (i > 0) y += lineHeight;
            keyText(g, ls.get(i), x, y, font, size, color, Gfx.LEFT);
        }
        return y;
    }
}
