package com.latent.camera;

/**
 * Film Lab's screens, drawn through Gfx so the camera and the Mac preview share one implementation.
 *
 * Design rules: true black, warm white type, one accent (the loaded stock's colour), one hero per
 * screen. Inter Display Light for the big numbers, Inter for everything else.
 *
 * Geometry: the design space is 480 high and as wide as the panel really is (s.width: 853 on the
 * A5100's 16:9 panel). The camera's 640x480 frame buffer is stretched onto that panel, so the
 * Canvas is scaled to undo the stretch and everything here keeps its true proportions.
 */
final class Ui {
    private Ui() {}

    // palette
    static final int BG = 0xFF000000, INK = 0xFFF4F1EA, INK2 = 0xFF9A958B, INK3 = 0xFF5E5A53, LINE = 0xFF2C2A26,
            SURFACE = 0xFF171614;
    static final int RED = 0xFFE0412F, RED2 = 0xFF8E2A20, RED3 = 0xFF3A110D, RED4 = 0xFF5C1A13;

    static final float L = 56, TOP = 50, FOOT = 444, H = 480;

    static void draw(Gfx g, UiState s) {
        if (s.live) {   // live view shows through: only a quiet label over it
            liveOverlay(g, s);
            return;
        }
        if (s.mode == UiState.PHOTO && s.liveThrough) {   // the camera's own live view shows in the picture: black bars only
            float iw = H * 1.5f, ix = (s.width - iw) / 2;
            g.rect(0, 0, ix, H, BG);
            g.rect(ix + iw, 0, s.width - ix - iw, H, BG);
        } else {
            g.rect(0, 0, s.width, H, BG);
        }
        switch (s.mode) {
            case UiState.BACK: back(g, s); break;
            case UiState.LOAD: load(g, s); break;
            case UiState.DARKROOM: darkroom(g, s); break;
            case UiState.SHEET: sheet(g, s); break;
            case UiState.VIEW: view(g, s); break;
            case UiState.LAB: lab(g, s); break;
            case UiState.PROBE: probe(g, s); break;
            case UiState.PHOTO: photo(g, s); break;
            case UiState.SETTINGS: settings(g, s); break;
            default: break;
        }
        toast(g, s);
    }

    private static float right(UiState s) { return s.width - L; }

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
        canisterIcon(g, f, L, TOP - 15);
        g.text(f.title + " " + f.iso + "  ·  Roll " + s.rollNumber, L + 26, TOP, Gfx.MEDIUM, 18, INK, Gfx.LEFT, 0);

        int shown = s.endless ? s.exposed : Math.min(s.exposed, s.length);
        String num = String.valueOf(shown);
        g.text(num, L - 10, 300, Gfx.DISPLAY, 230, INK, Gfx.LEFT, -30);
        float nw = g.textWidth(num, Gfx.DISPLAY, 230, -30);
        g.text(s.endless ? (shown == 1 ? "photo" : "photos") : "of " + s.length, L - 10 + nw + 16, 300, Gfx.REGULAR, 26, INK2, Gfx.LEFT, 0);

        String note;
        if (s.endless) {   // develops as you go: the newest prints, small, in a row
            float tw = 84, th = 56, x = L;
            for (int i = 0; i < s.thumbs.size() && x + tw <= R; i++, x += tw + 10) {
                Object t = s.thumbs.get(i);
                if (t != null) g.image(t, x, 322, tw, th, false);
                else g.rect(x, 322, tw, th, SURFACE);
            }
            note = s.developLater && s.waiting > 0 ? s.waiting + (s.waiting == 1 ? " new photo. Press to develop it." : " new photos. Press to develop them.")
                    : s.developing > 0 ? "" : shown == 0 ? "No end to this roll. Photos develop each time you come back." : "";
            if (note.length() > 0) g.text(note, L, s.thumbs.isEmpty() ? 356 : 410, Gfx.REGULAR, 19, INK2, Gfx.LEFT, 0);
        } else {
            ticks(g, s.length, shown, -1, L, R, 338, INK, LINE, f.accent);
            note = s.developed >= 0 ? s.developed + " of " + shown + " developed. Press to carry on."
                    : shown >= s.length ? "Roll finished. Press to develop."
                    : shown == 0 ? "Fresh roll. Half-press the shutter to shoot."
                    : (s.length - shown) + (s.length - shown == 1 ? " exposure left" : " exposures left");
            g.text(note, L, 384, Gfx.REGULAR, 19, INK2, Gfx.LEFT, 0);
        }
        boolean develop = s.developed >= 0 || (!s.endless && shown >= s.length) || (s.endless && s.developLater && s.waiting > 0);
        hints(g, s, INK2, new String[] { "centre", develop ? "Develop" : "Shoot" },
                new String[] { "menu", "Menu" });
        if (s.developing > 0) developingPill(g, s, R - g.textWidth("Menu", Gfx.REGULAR, 17, 0) - glyphWidth(g, "menu") - 30, f.accent);
    }

    /** ∞, drawn: two loops side by side. */
    private static void infinity(Gfx g, float cx, float cy, int color) {
        g.strokeCircle(cx - 5.2f, cy, 5f, 2f, color);
        g.strokeCircle(cx + 5.2f, cy, 5f, 2f, color);
    }

    /** "2 developing": a quiet pill, right-aligned at x. */
    private static void developingPill(Gfx g, UiState s, float x, int accent) {
        String d = s.developing + " developing";
        float dw = g.textWidth(d, Gfx.MEDIUM, 16, 0) + 34;
        g.roundRect(x - dw, FOOT - 20, dw, 28, 14, SURFACE);
        g.circle(x - dw + 15, FOOT - 6, 3.5f, accent);
        g.text(d, x - 12, FOOT - 1, Gfx.MEDIUM, 16, INK, Gfx.RIGHT, 0);
    }

    // ------------------------------------------------------------------------------ the viewfinder

    static final int GREEN = 0xFF5BD68E, AMBER = 0xFFF2A63C;

    /**
     * The viewfinder: Latent's own live view, with a film look on it, and the camera's information drawn
     * the way a Fuji or a Leica shows it: quiet until it matters. The picture is 3:2, full height, so the
     * 16:9 panel leaves a black bar each side; the steady details live there, out of the picture.
     *
     * Focus is the bracket in the middle. White while ready; tightening while it searches (with the AF
     * light noted when it's on); green and snapped tight on a lock, with the focused areas marked and a
     * green dot by the exposure (as Fuji confirms focus); amber when it can't lock, saying why.
     * Exposure brightens on a half-press, as a camera's does. After a shot the print develops in the
     * corner, from latent to finished.
     */
    private static void photo(Gfx g, UiState s) {
        float iw = H * 1.5f, ix = (s.width - iw) / 2, ir = ix + iw;
        UiState.Film f = s.looks.isEmpty() ? null : s.looks.get(Math.min(s.lookSel, s.looks.size() - 1));
        int accent = f != null ? f.accent : INK;
        if (s.liveImage != null && s.liveExact) g.imageExact(s.liveImage, ix, 0, iw, H);
        else if (s.liveImage != null) g.image(s.liveImage, ix, 0, iw, H, false);
        else if (!s.liveThrough) g.rect(ix, 0, iw, H, 0xFF0B0B0A);
        if (s.flash) { g.rect(ix, 0, iw, H, 0xD0000000); }   // the shutter: a blink

        // soft fades top and bottom, so the type reads over any picture
        for (int y = 0; y < 92; y += 2) g.rect(ix, y, iw, 2, ((int) (0x78 * (1 - y / 92f) * (1 - y / 92f))) << 24);
        for (int y = 352; y < H; y += 2) { float t = (y - 352) / 128f; g.rect(ix, y, iw, 2, ((int) (0xB8 * t * t)) << 24); }
        if (s.grid) {   // thirds
            for (int i = 1; i < 3; i++) {
                g.rect(ix + iw * i / 3, 0, 1, H, 0x38FFFFFF);
                g.rect(ix, H * i / 3, iw, 1, 0x38FFFFFF);
            }
        }

        // ---- top: the film, the frame counter, the battery
        if (f != null) {
            canisterIcon(g, f, ix + 18, 20);
            g.text(f.title, ix + 44, 36, Gfx.MEDIUM, 17, INK, Gfx.LEFT, 0);
            if (s.lookLive) {
                float x = ix + 44 + g.textWidth(f.title, Gfx.MEDIUM, 17, 0) + 10;
                g.roundRect(x, 22, 38, 18, 9, 0x40F4F1EA);
                g.text("LIVE", x + 19, 35, Gfx.MEDIUM, 10, INK, Gfx.CENTER, 120);
            }
        }
        frameWindow(g, s, s.width / 2, 36, accent);
        if (s.battery >= 0) battery2(g, s.battery, ir - 18, 36);
        if (s.hist != null) histogram(g, s.hist, ir - 18 - 112, 54, 112, 40);
        if (s.vfNote.length() > 0) {   // a word under the counter, for a moment
            float tw = g.textWidth(s.vfNote, Gfx.MEDIUM, 15, 0) + 30;
            g.roundRect(s.width / 2 - tw / 2, 58, tw, 28, 14, 0xB0000000);
            g.text(s.vfNote, s.width / 2, 77, Gfx.MEDIUM, 15, INK, Gfx.CENTER, 0);
        }

        // ---- the side bars: focus mode on the left, photos left on the card on the right
        if (!s.clean) {
            sideLabel(g, ix / 2, 214, s.afMode, "FOCUS");
            sideLabel(g, ix / 2, 268, s.afArea, "AREA");
            sideLabel(g, ir + ix / 2, 214, s.shotsLeft >= 0 ? String.valueOf(s.shotsLeft) : "", "LEFT");
        }
        if (s.afLamp) lamp(g, ir + ix / 2, 268, s.afState == 1 ? INK : INK2);

        // ---- the level, then focus (both out of the way while the look card is up)
        if (s.levelShown && !s.lookShown) level(g, s, ix + iw / 2, H / 2);
        if (!s.lookShown) afBrackets(g, s, ix, iw);

        // ---- bottom: exposure, the meter, the print
        float by = 448;
        boolean live = s.afState == 2 || s.afState == 1;   // half-pressed: the numbers come up
        int ink = live ? INK : 0xCCF4F1EA;
        float x = ix + 22;
        if (s.afState == 2 || s.afState == 3) { g.circle(x + 4, by - 6, 4.5f, s.afState == 2 ? GREEN : AMBER); x += 18; }
        if (s.evEdit) {   // DOWN (±): the exposure scale, large, the wheel moves it
            evPanel(g, s, accent);
        } else if (!s.clean) {
            x = expo(g, x, by, s.shutter, "", ink);
            x = expo(g, x + 22, by, s.aperture, "", ink);
            x = expo(g, x + 22, by, s.iso, s.isoAuto ? "ISO A" : "ISO", ink);
            meter(g, s.width / 2 + 40, by - 6, 150, s.ev, ink, accent);
        }
        print(g, s, ir - 18, by, accent);

        // ---- the look, large, for a moment after the wheel turns (a Fuji film simulation answering its dial)
        if (s.lookShown && f != null) lookCard(g, s, f);
    }

    /** The frame counter as a film camera's window: "09 / 24", or "∞ 23" on an endless roll. */
    private static void frameWindow(Gfx g, UiState s, float cx, float y, int accent) {
        String n = s.endless ? String.valueOf(s.exposed) : (s.exposed < 10 ? "0" : "") + s.exposed;
        String of = s.endless ? "" : " / " + s.length;
        float nw = g.textWidth(n, Gfx.MEDIUM, 19, 20), ow = g.textWidth(of, Gfx.REGULAR, 15, 0);
        float w = (s.endless ? 22 : 0) + nw + ow + 30, x = cx - w / 2;
        g.roundRect(x, y - 21, w, 30, 15, 0x66000000);
        g.strokeRoundRect(x, y - 21, w, 30, 15, 1, 0x40F4F1EA);
        float tx = x + 15;
        if (s.endless) { infinity(g, tx + 8, y - 6, INK2); tx += 22; }
        g.text(n, tx, y, Gfx.MEDIUM, 19, INK, Gfx.LEFT, 20);
        if (of.length() > 0) g.text(of, tx + nw, y, Gfx.REGULAR, 15, INK2, Gfx.LEFT, 0);
        if (s.flash) g.roundRect(x + 10, y + 5, w - 20, 2, 1, accent);   // the frame just advanced
    }

    /** Battery for the viewfinder: the outline and its percent, right-aligned at R. */
    private static void battery2(Gfx g, int pct, float R, float y) {
        boolean low = pct <= 20;
        String t = pct + "%";
        float tw = g.textWidth(t, Gfx.MEDIUM, 15, 0);
        g.text(t, R, y, Gfx.MEDIUM, 15, low ? RED : INK, Gfx.RIGHT, 0);
        float bx = R - tw - 32, by = y - 12;
        g.strokeRoundRect(bx, by, 24, 12, 3, 1.4f, 0xCCF4F1EA);
        g.roundRect(bx + 25, by + 4, 2, 4, 1, 0xCCF4F1EA);
        g.roundRect(bx + 2.5f, by + 2.5f, 19 * Math.max(0.06f, pct / 100f), 7, 1.5f, low ? RED : INK);
    }

    /** A small value with its label above, centred in a side bar. */
    private static void sideLabel(Gfx g, float cx, float y, String value, String label) {
        if (value == null || value.length() == 0) return;
        g.text(label, cx, y - 18, Gfx.MEDIUM, 9, INK3, Gfx.CENTER, 140);
        g.text(value, cx, y, Gfx.MEDIUM, 14, INK2, Gfx.CENTER, 0);
    }

    /** The AF illuminator: a small lamp with rays. */
    private static void lamp(Gfx g, float cx, float cy, int color) {
        g.strokeCircle(cx, cy - 6, 6, 1.6f, color);
        g.rect(cx - 3, cy + 2, 6, 2, color);
        for (int i = -1; i <= 1; i++) g.line(cx + i * 9, cy - 18 + Math.abs(i) * 3, cx + i * 11, cy - 21 + Math.abs(i) * 3, 1.4f, color);
        g.text("AF LIGHT", cx, cy + 20, Gfx.MEDIUM, 8, INK3, Gfx.CENTER, 120);
    }

    /** One exposure value, with a small label in front ("ISO"); returns where the next one goes. */
    private static float expo(Gfx g, float x, float y, String value, String label, int ink) {
        if (value == null || value.length() == 0) return x;
        if (label.length() > 0) {
            g.text(label, x, y - 1, Gfx.MEDIUM, 11, (ink & 0x00FFFFFF) | 0x99000000, Gfx.LEFT, 80);
            x += g.textWidth(label, Gfx.MEDIUM, 11, 80) + 5;
        }
        g.text(value, x, y, Gfx.MEDIUM, 20, ink, Gfx.LEFT, 0);
        return x + g.textWidth(value, Gfx.MEDIUM, 20, 0);
    }

    /** Exposure compensation in words: "0", "+1/3", "–1 2/3", "+2" (the en dash: Latent's fonts have no minus sign). */
    static String evText(int ev) {
        if (ev == 0) return "0";
        int a = Math.abs(ev), whole = a / 3, third = a % 3;
        return (ev > 0 ? "+" : "\u2013") + (whole > 0 ? String.valueOf(whole) : "") + (third == 0 ? "" : (whole > 0 ? " " : "") + third + "/3");
    }

    /**
     * The exposure scale, open (DOWN, the camera's ± key): the value large over the scale, in a panel
     * at the foot of the picture, the way a camera shows its exposure dial being turned. The wheel moves
     * it a third at a time; DOWN, centre or a half-press closes it.
     */
    private static void evPanel(Gfx g, UiState s, int accent) {
        float cx = s.width / 2, w = 340, top = 362, h = 96;
        g.roundRect(cx - w / 2, top, w, h, 22, 0xC4000000);
        g.text("EXPOSURE", cx, top + 24, Gfx.MEDIUM, 10, INK2, Gfx.CENTER, 160);
        g.text(evText(s.ev), cx, top + 60, Gfx.DISPLAY, 34, s.ev == 0 ? INK : accent, Gfx.CENTER, -10);
        meter(g, cx, top + 78, 250, s.ev, INK, accent);
        g.text("\u2039", cx - w / 2 + 22, top + 64, Gfx.REGULAR, 26, INK3, Gfx.CENTER, 0);
        g.text("\u203a", cx + w / 2 - 22, top + 64, Gfx.REGULAR, 26, INK3, Gfx.CENTER, 0);
    }

    /** The exposure scale: -3 to +3 in thirds, a mark where exposure sits (ev in thirds, 0 = metered). */
    private static void meter(Gfx g, float cx, float y, float w, int ev, int ink, int accent) {
        float x0 = cx - w / 2, step = w / 18;
        for (int i = 0; i <= 18; i++) {
            boolean major = i % 3 == 0, zero = i == 9;
            float h = zero ? 10 : major ? 7 : 4;
            g.rect(x0 + i * step - 0.6f, y - h / 2, 1.2f, h, zero ? ink : (ink & 0x00FFFFFF) | (major ? 0xAA000000 : 0x66000000));
        }
        g.text("\u2013", x0 - 10, y + 5, Gfx.MEDIUM, 13, (ink & 0x00FFFFFF) | 0x99000000, Gfx.CENTER, 0);
        g.text("+", x0 + w + 10, y + 5, Gfx.MEDIUM, 13, (ink & 0x00FFFFFF) | 0x99000000, Gfx.CENTER, 0);
        int e = Math.max(-9, Math.min(9, ev));
        float mx = cx + e * step;
        g.line(mx - 5, y + 12, mx, y + 7, 1.6f, ev == 0 ? ink : accent);
        g.line(mx, y + 7, mx + 5, y + 12, 1.6f, ev == 0 ? ink : accent);
        if (ev != 0 && w < 200) g.text(evText(ev), mx, y - 12, Gfx.MEDIUM, 12, accent, Gfx.CENTER, 0);
    }

    /** The last shot as a small print developing in the corner; with photos queued, how many. */
    private static void print(Gfx g, UiState s, float R, float y, int accent) {
        if (s.printImage == null && s.developing <= 0) return;
        float w = 66, h = 44, x = R - w, top = y - h + 6;
        if (s.printImage == null) {   // nothing to show yet: a print still latent, waiting for the darkroom
            g.roundRect(x - 3, top - 3, w + 6, h + 6, 4, 0xFFEDE8DE);
            g.rect(x, top, w, h, 0xFF231D17);
            g.rect(x, top + h - 2, w, 2, (accent & 0x00FFFFFF) | 0xCC000000);
        } else {
            g.roundRect(x - 3, top - 3, w + 6, h + 6, 4, 0xFFEDE8DE);   // a white border, like a print
            g.image(s.printImage, x, top, w, h, false);
            float left = 1 - Math.max(0, Math.min(1, s.printProgress));
            if (left > 0) {   // still latent: a ghost of the picture, developing up from the bottom
                g.rect(x, top, w, h * left, 0xB8231D17);
                g.rect(x, top + h * left - 1, w, 2, (accent & 0x00FFFFFF) | 0xCC000000);
            }
        }
        if (s.developing > 0) {
            String d = String.valueOf(s.developing);
            float dw = Math.max(20, g.textWidth(d, Gfx.MEDIUM, 12, 0) + 12);
            g.roundRect(x - dw / 2, top - 10, dw, 20, 10, accent);
            g.text(d, x, top + 4, Gfx.MEDIUM, 12, BG, Gfx.CENTER, 0);
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
            default: c = 0xE6F4F1EA; k = 1f; stroke = 1.6f; break;   // ready
        }
        if (s.afState == 2 && s.afAreas != null) {   // the areas the camera focused on
            for (float[] a : s.afAreas) {
                float ax = ix + a[0] * iw, ay = a[1] * H, aw = a[2] * iw, ah = a[3] * H;
                g.strokeRoundRect(ax + 0.8f, ay + 0.8f, aw, ah, 3, 3.2f, 0x55000000);
                g.strokeRoundRect(ax, ay, aw, ah, 3, 2f, GREEN);
            }
        } else {
            corners(g, cx - w * k / 2, cy - h * k / 2, w * k, h * k, 16, stroke, c);
            if (s.afState == 1) corners(g, cx - w / 2, cy - h / 2, w, h, 16, 1, 0x40F4F1EA);   // where it started
        }
        if (s.afState == 2 && s.afAreas == null) g.circle(cx, cy, 2.5f, GREEN);
        String say = s.afState == 3 ? (s.afNote.length() > 0 ? s.afNote : "Can't focus") : s.afState == 1 && s.afTry > 1 ? "Trying again \u00b7 " + s.afTry : "";
        if (say.length() > 0) {
            float tw = g.textWidth(say, Gfx.MEDIUM, 14, 0) + 26, ty = cy + h / 2 + 18;
            g.roundRect(cx - tw / 2, ty, tw, 26, 13, 0x99000000);
            g.text(say, cx, ty + 18, Gfx.MEDIUM, 14, s.afState == 3 ? AMBER : INK, Gfx.CENTER, 0);
        }
    }

    /**
     * The level, read from the picture: a line along the scene's horizon, broken where the focus
     * brackets sit, between two fixed marks that are the frame's own level. Turn the camera until the
     * line meets the marks; then it turns green, as a camera's own level does.
     */
    private static void level(Gfx g, UiState s, float cx, float cy) {
        float a = Math.max(-12, Math.min(12, s.levelAngle)) * (float) Math.PI / 180, c = (float) Math.cos(a), sn = (float) Math.sin(a);
        int col = s.levelOk ? GREEN : 0xE6F4F1EA;
        float stroke = s.levelOk ? 2.2f : 1.6f, in = 96, out = 214;
        for (int side = -1; side <= 1; side += 2) {
            float x1 = cx + side * in * c, y1 = cy - side * in * sn, x2 = cx + side * out * c, y2 = cy - side * out * sn;
            g.line(x1 + 0.8f, y1 + 0.8f, x2 + 0.8f, y2 + 0.8f, stroke + 1.6f, 0x55000000);
            g.line(x1, y1, x2, y2, stroke, col);
            float m0 = cx + side * 226, m1 = cx + side * 246;   // the frame's level: fixed marks beyond the line's ends
            g.line(m0 + 0.8f, cy + 0.8f, m1 + 0.8f, cy + 0.8f, 3.6f, 0x55000000);
            g.line(m0, cy, m1, cy, 2f, s.levelOk ? GREEN : 0xB3F4F1EA);
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

    /** A tiny histogram of live view's brightness. */
    private static void histogram(Gfx g, int[] hist, float x, float y, float w, float h) {
        g.roundRect(x - 6, y - 6, w + 12, h + 12, 6, 0x66000000);
        int max = 1;
        for (int v : hist) max = Math.max(max, v);
        float bw = w / hist.length;
        for (int i = 0; i < hist.length; i++) {
            float bh = h * hist[i] / max;
            if (bh >= 0.5f) g.rect(x + i * bw, y + h - bh, bw + 0.3f, bh, 0xB3F4F1EA);
        }
        g.rect(x, y + h, w, 1, 0x40F4F1EA);
    }

    /** The film simulation card: the film's name large, every film's canister in a row, this one raised. */
    private static void lookCard(Gfx g, UiState s, UiState.Film f) {
        String tag = s.cardNote.length() > 0 ? s.cardNote : f.tagline;
        String sub = (f.iso.length() > 0 ? "ISO " + f.iso : "") + (tag.length() > 0 ? (f.iso.length() > 0 ? "  \u00b7  " : "") + tag : "");
        float tw = Math.max(Math.max(g.textWidth(f.title, Gfx.DISPLAY, 56, -10), g.textWidth(sub, Gfx.REGULAR, 15, 0)), s.looks.size() * 34f) + 70;
        float x = s.width / 2 - tw / 2, y = 150;
        g.roundRect(x, y, tw, 168, 26, 0xC4000000);
        g.text(f.title, s.width / 2, y + 74, Gfx.DISPLAY, 56, INK, Gfx.CENTER, -10);
        if (sub.length() > 0) g.text(sub, s.width / 2, y + 102, Gfx.REGULAR, 15, INK2, Gfx.CENTER, 0);
        float cw = 34, cx0 = s.width / 2 - (s.looks.size() - 1) * cw / 2;
        for (int i = 0; i < s.looks.size(); i++) {
            UiState.Film o = s.looks.get(i);
            boolean on = i == s.lookSel;
            float cx = cx0 + i * cw, cyy = y + 128 - (on ? 4 : 0);
            float sw = on ? 18 : 13, sh = on ? 21 : 15;
            g.roundRect(cx - sw / 2, cyy, sw, sh, 3, on ? o.body : (o.body & 0x00FFFFFF) | 0x99000000);
            g.rect(cx - sw / 2, cyy + sh / 3, sw, sh / 3, on ? o.band : (o.band & 0x00FFFFFF) | 0x99000000);
            if (on) g.roundRect(cx - 8, cyy + sh + 6, 16, 3, 1.5f, o.accent);
        }
    }

    // ------------------------------------------------------------------------------ settings

    /**
     * The menu: one plain list, the way the Settings app does it. Headings, switches, and rows that
     * open. It scrolls to keep the chosen row in view, under a fixed title and footer.
     */
    private static void settings(Gfx g, UiState s) {
        float R = right(s), top = 70, bottom = FOOT - 30;
        float[] ys = new float[s.rows.size()];
        float y = 0;
        for (int i = 0; i < s.rows.size(); i++) {
            if (s.rows.get(i)[1].length() == 0) { ys[i] = y + 12; y += 26; }
            else { ys[i] = y; y += 38; }
        }
        float scroll = 0;
        if (s.rowSel >= 0 && s.rowSel < ys.length) {
            float selBottom = top + 8 + ys[s.rowSel] + 38;
            if (selBottom > bottom) scroll = selBottom - bottom;
            if (s.rowSel <= 1) scroll = 0;   // the first row: the list from its top, heading and all
        }
        for (int i = 0; i < s.rows.size(); i++) {
            String[] r = s.rows.get(i);
            float ry = top + 8 + ys[i] - scroll;
            if (ry + 38 < top || ry > bottom + 4) continue;
            if (r[1].length() == 0) {   // a heading
                g.text(r[0].toUpperCase(java.util.Locale.US), L, ry + 6, Gfx.MEDIUM, 12, INK3, Gfx.LEFT, 120);
                continue;
            }
            boolean on = i == s.rowSel;
            if (on) g.roundRect(L - 14, ry - 2, R - L + 28, 38, 10, SURFACE);
            g.text(r[0], L, ry + 23, Gfx.REGULAR, 19, on ? INK : INK2, Gfx.LEFT, 0);
            if (r[1].equals("on") || r[1].equals("off")) {   // a switch
                boolean v = r[1].equals("on");
                g.roundRect(R - 44, ry + 5, 44, 24, 12, v ? 0xFF3FA66B : 0xFF3A3834);
                g.circle(v ? R - 12 : R - 32, ry + 17, 9.5f, INK);
            } else if (r[1].equals("\u203a")) {
                g.line(R - 8, ry + 11, R - 2, ry + 17, 2, INK3);
                g.line(R - 2, ry + 17, R - 8, ry + 23, 2, INK3);
            } else {
                g.text(r[1], R, ry + 23, Gfx.REGULAR, 17, INK3, Gfx.RIGHT, 0);
            }
        }
        // title and footer stay put; rows slide under them
        g.rect(0, 0, s.width, top, BG);
        g.rect(0, bottom + 6, s.width, H - bottom - 6, BG);
        if (scroll > 0) g.rect(L - 14, top - 1, R - L + 28, 1, LINE);
        g.text("Menu", L, TOP, Gfx.MEDIUM, 18, INK, Gfx.LEFT, 0);
        battery(g, s.battery, R, INK2, INK3);
        if (s.footer.length() > 0) g.text(s.footer, L, FOOT, Gfx.REGULAR, 15, INK3, Gfx.LEFT, 0);
        menuHint(g, "Back", INK3, R, FOOT);
    }

    /** A short notice, centred under the top bar. Never on top of the controls. */
    private static void toast(Gfx g, UiState s) {
        if (s.toast == null || s.toast.length() == 0) return;
        boolean dark = s.mode == UiState.DARKROOM;
        float w = g.textWidth(s.toast, Gfx.MEDIUM, 17, 0) + 36;
        float x = (s.width - w) / 2, y = 72;
        g.roundRect(x, y, w, 34, 17, dark ? 0xFF1E0806 : 0xFF262421);
        g.text(s.toast, s.width / 2, y + 23, Gfx.MEDIUM, 17, dark ? RED : INK, Gfx.CENTER, 0);
    }

    /** A row of thin marks, one per frame, every sixth a little taller: the scale on a film counter. */
    private static void ticks(Gfx g, int n, int filled, int current, float x0, float x1, float y, int on, int off, int last) {
        if (n <= 0 || n > 72) return;   // a film counter has at most 72 frames (a half-frame 36)
        float step = (x1 - x0 - 4) / Math.max(1, n - 1);
        for (int i = 0; i < n; i++) {
            float x = x0 + i * step;
            int c = i < filled ? (i == filled - 1 ? last : on) : off;
            if (i == current) c = last;
            boolean major = (i + 1) % 6 == 0;
            g.roundRect(x, major ? y - 4 : y, 4, major ? 20 : 16, 2, c);
        }
    }

    /** A tiny film canister: the stock's two colours. */
    private static void canisterIcon(Gfx g, UiState.Film f, float x, float y) {
        g.roundRect(x + 5, y - 2, 6, 3, 1, INK3);
        g.roundRect(x, y, 16, 18, 3, f.body);
        g.rect(x, y + 6, 16, 6, f.band);
    }

    private static void battery(Gfx g, int pct, float R, int text, int outline) {
        if (pct < 0) return;
        String t = pct + "%";
        boolean low = pct <= 20;
        float tw = g.textWidth(t, Gfx.MEDIUM, 18, 0);
        g.text(t, R, TOP, Gfx.MEDIUM, 18, low ? RED : text, Gfx.RIGHT, 0);
        float bx = R - tw - 8 - 30, by = TOP - 14;
        g.strokeRoundRect(bx, by, 26, 13, 3, 1.5f, outline);
        g.roundRect(bx + 27, by + 4, 2.5f, 5, 1, outline);
        g.roundRect(bx + 2.5f, by + 2.5f, 21 * Math.max(0.06f, pct / 100f), 8, 1.5f, low ? RED : text);
    }

    // ------------------------------------------------------------------------------ load film

    /**
     * Choosing film is the home screen when the camera is empty: your last photo, full screen, in the
     * film under the wheel (the way a Fuji previews a film simulation), its name over the picture,
     * and the roll length beside it. The centre button loads it; a half-press loads it and goes to shoot.
     */
    private static void load(Gfx g, UiState s) {
        float R = right(s);
        UiState.Film f = s.films.isEmpty() ? null : s.films.get(Math.min(s.filmSel, s.films.size() - 1));
        Object hero = s.liveImage != null ? s.liveImage : f != null ? f.sample : null;
        float iw = H * 1.5f, ix = (s.width - iw) / 2;
        if (hero != null) g.image(hero, ix, 0, iw, H, false);
        else if (f != null && s.liveCaption.length() > 0) {   // still rendering
            g.rect(ix, 0, iw, H, SURFACE);
        }
        // a soft fade to black under the words, so they read over any picture
        for (int y = 230; y < H; y += 2) {
            float t = (y - 230) / 170f;
            g.rect(0, y, s.width, 2, ((int) (0xE8 * Math.min(1, t * t * (3 - 2 * Math.min(1, t))))) << 24);
        }

        int glass = 0x99000000;
        String head = s.cancelable ? "Change film" : "Choose film";
        float hw = g.textWidth(head, Gfx.MEDIUM, 17, 0) + 28;
        g.roundRect(L - 14, TOP - 22, hw, 30, 15, glass);
        g.text(head, L, TOP - 1, Gfx.MEDIUM, 17, INK, Gfx.LEFT, 0);
        if (s.battery >= 0) g.roundRect(R - 92, TOP - 23, 104, 32, 16, glass);
        battery(g, s.battery, R, INK, INK2);
        if (f == null) {
            g.text("No looks on the card", L, 300, Gfx.DISPLAY, 44, INK, Gfx.LEFT, -10);
            return;
        }
        if (hero == null && s.liveCaption.length() == 0) {   // nothing shot yet to show the look on
            g.text("Your first photo will show each look.", s.width / 2, 200, Gfx.REGULAR, 19, INK2, Gfx.CENTER, 0);
        }

        // which film of how many: dots, centred, like pages
        float dw = 14, dx = s.width / 2 - (s.films.size() - 1) * dw / 2;
        for (int i = 0; i < s.films.size(); i++) g.circle(dx + i * dw, TOP - 7, i == s.filmSel ? 3.5f : 2.5f, i == s.filmSel ? INK : 0x80F4F1EA);

        // the film: canister, name, ISO and a line about it
        g.roundRect(L + 2, 352, 16 * 1.4f, 18 * 1.4f, 4, f.body);
        g.rect(L + 2, 352 + 8.4f, 16 * 1.4f, 8.4f, f.band);
        g.text(f.title, L + 38, 378, Gfx.DISPLAY, 50, INK, Gfx.LEFT, -10);
        String sub = (f.iso.length() > 0 ? "ISO " + f.iso : "") + (f.tagline.length() > 0 ? (f.iso.length() > 0 ? "  ·  " : "") + f.tagline : "");
        g.text(sub, L + 38, 410, Gfx.REGULAR, 18, INK2, Gfx.LEFT, 0);

        // the roll length: a segmented control on the right
        int n = s.lengthLabels.length;
        float seg = 54, sw = seg * n, sx = R - sw, sy = 344, sh = 40;
        g.roundRect(sx, sy, sw, sh, sh / 2, 0xB0171614);
        for (int i = 0; i < n; i++) {
            boolean on = i == s.lengthSel;
            if (on) g.roundRect(sx + i * seg + 4, sy + 4, seg - 8, sh - 8, (sh - 8) / 2, INK);
            float cx = sx + i * seg + seg / 2;
            if (s.lengthLabels[i].equals("\u221e")) infinity(g, cx, sy + sh / 2, on ? BG : INK);   // the fonts have no ∞
            else g.text(s.lengthLabels[i], cx, sy + 27, Gfx.MEDIUM, 18, on ? BG : INK, Gfx.CENTER, 0);
        }
        g.text(s.lengthSel == n - 1 ? "No end: develops as you go" : "exposures", sx + sw / 2, sy + 62, Gfx.REGULAR, 15, INK2, Gfx.CENTER, 0);

        if (s.loading) {
            g.text("Loading…", L, FOOT + 14, Gfx.MEDIUM, 17, INK, Gfx.LEFT, 0);
            return;
        }
        float y = FOOT + 14, x = L;
        String[] h = { "wheel", "Film", "updown", "Length", "centre", "Load" };
        for (int i = 0; i < h.length; i += 2) {
            x += glyph(g, h[i], x, y - 6, INK2) + 8;
            g.text(h[i + 1], x, y, Gfx.REGULAR, 17, INK2, Gfx.LEFT, 0);
            x += g.textWidth(h[i + 1], Gfx.REGULAR, 17, 0) + 26;
        }
        menuHint(g, s.cancelable ? "Back" : "Menu", INK2, R, y);
    }

    private static void darkroom(Gfx g, UiState s) {
        float R = right(s);
        UiState.Film f = s.film;
        g.text("Darkroom" + (f != null ? "  ·  " + f.title + " " + f.iso : ""), L, TOP, Gfx.MEDIUM, 18, RED2, Gfx.LEFT, 0);
        battery(g, s.battery, R, RED2, RED4);

        // the frame number, large
        String num = (s.devFrame + 1 < 10 ? "0" : "") + (s.devFrame + 1);
        g.text(num, L - 8, 262, Gfx.DISPLAY, 190, RED, Gfx.LEFT, -30);
        float nw = g.textWidth(num, Gfx.DISPLAY, 190, -30);
        g.text("of " + s.devTotal, L - 8 + nw + 14, 262, Gfx.REGULAR, 24, RED2, Gfx.LEFT, 0);

        // the print in the tray: the frame comes up out of the dark as it develops
        float pw = 300, ph = 200, px = R - pw, py = 84;
        g.rect(px, py, pw, ph, 0xFF140404);
        if (s.devPrint != null && s.devFramePct > 0) {
            g.image(s.devPrint, px, py, pw, ph, true);
            int veil = (int) (255 * (1 - Math.min(1f, s.devFramePct * 1.15f)));   // fades away as it develops
            if (veil > 0) g.rect(px, py, pw, ph, (veil << 24) | 0x140404);
        }
        g.strokeRoundRect(px - 1, py - 1, pw + 2, ph + 2, 2, 1, RED3);

        // whole roll: a bar that always moves, and an honest time estimate
        g.roundRect(L, 318, R - L, 4, 2, RED3);
        g.roundRect(L, 318, Math.max(4, (R - L) * Math.min(1f, s.devProgress)), 4, 2, RED);
        String line = s.devPreparing ? "Getting the darkroom ready" : s.paused ? "Pausing after this frame" : s.devLeft;
        g.text(line, L, 356, Gfx.REGULAR, 20, RED2, Gfx.LEFT, 0);
        if (!s.devPreparing) g.text(Math.round(s.devProgress * 100) + "%", R, 356, Gfx.MEDIUM, 20, RED2, Gfx.RIGHT, 0);

        // the contact strip: developed frames drop in, the one in the tray is outlined
        int slots = Math.min(s.devTotal, 12), first = Math.max(0, Math.min(s.devFrame - slots / 2, s.devTotal - slots));
        float sh = 34, sw = 51, gap = slots > 1 ? Math.min(14, (R - L - slots * sw) / (slots - 1)) : 0;   // a strip: spread up to the width
        for (int k = 0; k < slots; k++) {
            int i = first + k;
            float x = L + k * (sw + gap), y = 384;
            if (i < s.devStrip.size() && s.devStrip.get(i) != null) g.image(s.devStrip.get(i), x, y, sw, sh, true);
            else g.rect(x, y, sw, sh, i == s.devFrame ? 0xFF2A0B08 : 0xFF140404);
            if (i == s.devFrame) g.strokeRoundRect(x - 2, y - 2, sw + 4, sh + 4, 2, 1.5f, RED);
        }
        g.text("Safe to set the camera down", L, FOOT + 12, Gfx.REGULAR, 16, RED4, Gfx.LEFT, 0);
        if (!s.devPreparing) menuHint(g, "Pause", RED4, R, FOOT + 12);
    }

    // ------------------------------------------------------------------------------ contact sheet

    private static void sheet(Gfx g, UiState s) {
        float R = right(s);
        UiState.Film f = s.film;
        if (f == null) {
            g.text("Contact sheets", L, TOP, Gfx.MEDIUM, 18, INK, Gfx.LEFT, 0);
            g.text("No rolls yet", L - 3, 222, Gfx.DISPLAY, 60, INK, Gfx.LEFT, -10);
            g.text("Developed rolls appear here.", L, 266, Gfx.REGULAR, 21, INK2, Gfx.LEFT, 0);
            menuHint(g, "Back", INK3, R, FOOT);
            return;
        }
        canisterIcon(g, f, L, TOP - 15);
        g.text((s.sheetPhotos ? "Photos" : "Roll " + s.rollNumber) + "  ·  " + f.title + " " + f.iso, L + 26, TOP, Gfx.MEDIUM, 18, INK, Gfx.LEFT, 0);
        if (s.thumbs.isEmpty()) {
            g.text(s.sheetUndeveloped ? "Not developed yet" : "No frames", L - 3, 222, Gfx.DISPLAY, 54, INK, Gfx.LEFT, -10);
            if (s.sheetUndeveloped) g.text("Press the centre button to develop it.", L, 266, Gfx.REGULAR, 21, INK2, Gfx.LEFT, 0);
            hints(g, s, INK3, new String[] { "updown", "Roll" }, new String[] { "menu", "Back" });
            return;
        }
        if (s.filingLeft > 0) {   // until then, leaving or switching to USB leaves an original for next time
            String filing = "Filing " + s.filingLeft + (s.filingLeft == 1 ? " negative…" : " negatives…");
            g.text(filing, R, TOP, Gfx.MEDIUM, 18, INK2, Gfx.RIGHT, 0);
        } else {
            g.text((s.sheetSel + 1) + " of " + s.thumbs.size(), R, TOP, Gfx.MEDIUM, 18, INK3, Gfx.RIGHT, 0);
        }

        int cols = 5, per = cols * 3, page = s.sheetSel / per;
        float gap = 16, tw = (R - L - (cols - 1) * gap) / cols, th = tw * 2 / 3;
        for (int k = 0; k < per; k++) {
            int i = page * per + k;
            if (i >= s.thumbs.size()) break;
            float x = L + (k % cols) * (tw + gap), y = 70 + (k / cols) * (th + 24);
            Object img = s.thumbs.get(i);
            if (img != null) g.image(img, x, y, tw, th, false);   // image() crops the thumbnail's letterbox bars
            else g.rect(x, y, tw, th, SURFACE);
            boolean on = i == s.sheetSel;
            if (on) g.strokeRoundRect(x - 4, y - 4, tw + 8, th + 8, 4, 2, INK);
            g.text(String.valueOf(i + 1), x, y + th + 19, Gfx.MONO, 13, on ? f.accent : INK3, Gfx.LEFT, 0);
        }
        hints(g, s, INK3, new String[] { "wheel", "Frame", "updown", "Roll" }, new String[] { "centre", "View" });
    }

    /** The photo as large as the panel allows; centre toggles filling the whole screen. */
    private static void view(Gfx g, UiState s) {
        if (s.viewImage != null) {
            float iw = g.imageWidth(s.viewImage), ih = g.imageHeight(s.viewImage);
            float sc = s.viewFill ? Math.max(s.width / iw, H / ih) : Math.min(s.width / iw, H / ih);
            float dw = Math.min(s.width, iw * sc), dh = Math.min(H, ih * sc);
            g.image(s.viewImage, (s.width - dw) / 2, (H - dh) / 2, dw, dh, false);
        } else {
            g.text("Developing preview…", s.width / 2, 248, Gfx.REGULAR, 20, INK3, Gfx.CENTER, 0);
        }
        if (s.viewCaption) {
            UiState.Film f = s.film;
            g.rect(0, 0, s.width, 30, 0x99000000);
            g.text((s.sheetSel + 1) + " of " + s.sheetTotal, 16, 21, Gfx.MEDIUM, 16, INK, Gfx.LEFT, 0);
            if (f != null) g.text(f.title + " " + f.iso, s.width / 2, 21, Gfx.MEDIUM, 16, INK2, Gfx.CENTER, 0);
            g.text(s.viewFill ? "Centre: fit" : "Centre: fill", s.width - 16, 21, Gfx.REGULAR, 16, INK2, Gfx.RIGHT, 0);
        }
    }

    // ------------------------------------------------------------------------------ lab

    private static void lab(Gfx g, UiState s) {
        float R = right(s);
        g.text("Lab", L, TOP, Gfx.MEDIUM, 18, INK, Gfx.LEFT, 0);
        g.text(s.labCaption, R, TOP, Gfx.MEDIUM, 18, INK3, Gfx.RIGHT, 0);
        float px = L, py = 76, pw = 420, ph = 280;
        if (s.labImage != null) {
            float iw = g.imageWidth(s.labImage), ih = g.imageHeight(s.labImage);
            float sc = Math.min(pw / iw, ph / ih), dw = iw * sc, dh = ih * sc;
            g.image(s.labImage, px + (pw - dw) / 2, py + (ph - dh) / 2, dw, dh, false);
        }
        float x = px + pw + 48;
        for (int i = 0; i < s.labRows.length; i++) {
            float y = 100 + i * 38;
            boolean on = i == s.labRow, action = s.labValues[i].length() == 0;
            if (on) g.roundRect(x - 12, y - 18, 3, 24, 1.5f, INK);
            g.text(s.labRows[i], x, y, action ? Gfx.MEDIUM : Gfx.REGULAR, 18, on || action ? INK : INK2, Gfx.LEFT, 0);
            if (action) glyph(g, "centre", R - 18, y - 6, on ? INK : INK3);
            else g.text(s.labValues[i], R, y, Gfx.MEDIUM, 18, on ? INK : INK2, Gfx.RIGHT, 0);
        }
        if (s.labProgress >= 0) {
            g.roundRect(L, 378, R - L, 4, 2, LINE);
            g.roundRect(L, 378, (R - L) * s.labProgress / 100f, 4, 2, INK);
        }
        hints(g, s, INK3, new String[] { "updown", "Setting", "wheel", "Change" }, new String[] { "menu", "Back" });
    }

    // ------------------------------------------------------------------------------ camera roll test

    static final String[] PROBE_PLAN = {
            "Looks up the Sony functions a camera roll needs. Calls none of them.",
            "Reads Sony's library file to see the native functions are there. Never runs them.",
            "Reads the camera's photo catalogue without changing it.",
            "Decodes your newest photo in hardware, as playback does, and times it.",
            "Copies that photo to a test file, asks the camera to re-scan, checks it appears, then removes the copy with Sony's own delete.",
    };
    static final String PROBE_FOOTER = "Your photos are never changed. About a minute; results go to LATENT/PROBE.TXT.";

    static final String[] HARDWARE_PLAN = {
            "Decodes your newest photo in hardware. The original file is only ever read.",
            "Checks Sony's private memory functions against its public ones on scratch buffers, at each transfer size it will use. Any mismatch stops the test.",
            "Makes a separate output image with Sony's scaler, and refuses to continue if it shares memory with the original.",
            "Develops your photo into it with the loaded film stock (Cuban Neg if none), moving pixels in large verified blocks.",
            "Sony's encoder saves it as a new photo in the camera roll. Look at it in playback; delete it with the trash button if you like.",
    };
    static final String HARDWARE_FOOTER = "If the camera stops responding, turn it off or remove the battery. Log: LATENT/HWTEST.TXT.";

    static final String[] LIVE_PLAN = {
            "Opens the camera and shows its live view for a few seconds, as Recipe Lab does.",
            "Asks the camera to hand Latent the live frames too: how many arrive, how big, and whether they hold a picture. That decides if film looks can be shown live.",
            "Changes no setting and takes no picture. Then closes the camera again. Point it around while it runs.",
    };
    static final String[] SHOOT_PLAN = {
            "Reads the focus options the camera offers (modes, areas, faces) and listens to its motion sensors for two seconds, to see if a level is possible. Only reads.",
            "Opens the camera with live view, then focuses by itself. If it can't lock, as in a dark room, it tries again, up to four times, and notes how long the AF lamp is on.",
            "Takes one photo with the film look applied as it is captured, using the camera's own memory map, as Sony's effect apps do. Kept off your rolls. If the camera hangs, pull the battery.",
            "Changes no camera setting. Point the camera at something and hold still.",
    };
    static final String[] LOOK_PLAN = {
            "Opens the camera and runs the film look on its live frames exactly as the viewfinder does, pausing while each photo is written.",
            "Takes two photos, waits until the camera has saved them, gives it back, and restarts Latent. Kept off your rolls.",
            "Then counts the new photos on the card itself. If both are there, Film look on live view comes on.",
    };
    static final String LOOK_FOOTER = "About 45 seconds. Log: LATENT/LIVELOOK.TXT.";

    static final String[] RESTART_PLAN = {
            "Copies Sony's own camera apps (Smart Remote among them) to LATENT/APPS, to be read on a computer. Only reads.",
            "Opens the camera with live view for a moment, the way the viewfinder does, and gives it back. Takes no picture.",
            "Then Latent ends and starts again by itself, a black screen in between, and checks when it can develop again (up to a minute and a half). Watch whether the camera's own screen shows up.",
    };
    static final String RESTART_FOOTER = "Up to two minutes. Log: LATENT/RESTART.TXT, the apps in LATENT/APPS.";
    static final String SHOOT_FOOTER = "About twenty seconds. Hold it level at first. MENU stops it. Log: LATENT/SHOOTING.TXT";
    static final String LIVE_FOOTER = "About ten seconds. MENU stops it. Results go to LATENT/LIVEVIEW.TXT.";

    private static void liveOverlay(Gfx g, UiState s) {
        String t = s.liveNote == null || s.liveNote.length() == 0 ? "Live view" : s.liveNote;
        float w = g.textWidth(t, Gfx.MEDIUM, 17, 0) + 36;
        g.roundRect(s.width / 2 - w / 2, 22, w, 36, 18, 0xB0000000);
        g.text(t, s.width / 2, 46, Gfx.MEDIUM, 17, INK, Gfx.CENTER, 0);
    }

    static final String[] SPEED_PLAN = {
            "Reads how many cores the camera has, which ones Latent may use, and how busy each is while idle. Read only.",
            "Times the film look on your newest photo, decoded at half size into memory, four ways, each checked against a full-precision reference.",
            "Lets one helper thread of Latent's ask to run on the other cores and times the look split across two. If that is faster and the picture identical, developing also uses cores 1 and 2 (switch: More > 3 cores).",
    };
    static final String SPEED_FOOTER = "About a minute. Nothing on the card changes; results go to LATENT/SPEED.TXT.";

    static final String[] SETTINGS_PLAN = {
            "Reads your white balance, its fine-tune grid, and your Creative Style, the settings the camera keeps between uses.",
            "Shows them as the camera's menus do, so you can check them against MENU > White Balance and MENU > Creative Style.",
            "Checks whether Latent would be allowed to change them later. Nothing is changed now: this check can only read.",
    };
    static final String SETTINGS_FOOTER = "Takes a few seconds. Every raw value goes to LATENT/SETTINGS.TXT.";

    private static void probe(Gfx g, UiState s) {
        float R = right(s);
        g.text(s.probeTitle, L, TOP, Gfx.MEDIUM, 18, INK, Gfx.LEFT, 0);
        if (s.probeStage == 0) {
            g.text("What this does", L - 2, 108, Gfx.DISPLAY, 40, INK, Gfx.LEFT, -10);
            float y = 150;
            for (int i = 0; i < s.probePlan.length; i++) {
                g.text((i + 1) + "", L, y, Gfx.MONO, 15, INK3, Gfx.LEFT, 0);
                y = wrap(g, s.probePlan[i], L + 24, y, R - L - 24, Gfx.REGULAR, 18, INK2, 24) + 28;
            }
            g.text(s.probeFooter, L, Math.max(y + 10, 390), Gfx.REGULAR, 17, INK3, Gfx.LEFT, 0);
            hints(g, s, INK3, new String[] {}, new String[] { "centre", "Run test" });
            menuHint(g, "Back", INK3, R - 150, FOOT);
            return;
        }
        String[] names = s.probeSteps;
        for (int i = 0; i < names.length && i < s.probeStatus.length; i++) {
            float step = Math.min(46, (FOOT - 46 - 100) / Math.max(1, names.length - 1f)), y = 100 + i * step;   // many steps: closer together
            int st = s.probeStatus[i];
            int c = st == Probe.OK ? 0xFF6FBF73 : st == Probe.FAIL ? RED : st == Probe.RUN ? INK : INK3;
            if (st == Probe.OK) { g.line(L, y - 7, L + 6, y - 1, 2, c); g.line(L + 6, y - 1, L + 16, y - 13, 2, c); }
            else if (st == Probe.FAIL) { g.line(L + 2, y - 13, L + 14, y - 1, 2, c); g.line(L + 14, y - 13, L + 2, y - 1, 2, c); }
            else if (st == Probe.RUN) g.circle(L + 8, y - 7, 5, c);
            else g.strokeCircle(L + 8, y - 7, 5, 1.5f, c);
            g.text(names[i], L + 30, y, Gfx.MEDIUM, 18, st < 0 ? INK3 : INK, Gfx.LEFT, 0);
            String d = s.probeDetail[i] == null ? "" : s.probeDetail[i];
            float maxw = R - (L + 230);
            while (d.length() > 4 && g.textWidth(d, Gfx.REGULAR, 16, 0) > maxw) d = d.substring(0, d.length() - 2);
            if (!d.equals(s.probeDetail[i] == null ? "" : s.probeDetail[i])) d = d + "…";
            g.text(d, L + 230, y, Gfx.REGULAR, 16, INK2, Gfx.LEFT, 0);
        }
        if (s.probeStage == 1) g.text("Running. Leave the camera on.", L, FOOT, Gfx.REGULAR, 17, INK2, Gfx.LEFT, 0);
        else { g.text("Finished. Full details are in the log on the card.", L, FOOT, Gfx.REGULAR, 17, INK2, Gfx.LEFT, 0); menuHint(g, "Back", INK3, R, FOOT); }
    }

    // ------------------------------------------------------------------------------ controls

    /** Control hints along the bottom: [glyph, label] pairs on the left, one pair on the right. */
    private static void hints(Gfx g, UiState s, int color, String[] left, String[] right) {
        float x = L, R = right(s);
        for (int i = 0; i + 1 < left.length; i += 2) {
            x += glyph(g, left[i], x, FOOT - 6, color) + 8;
            g.text(left[i + 1], x, FOOT, Gfx.REGULAR, 17, color, Gfx.LEFT, 0);
            x += g.textWidth(left[i + 1], Gfx.REGULAR, 17, 0) + 26;
        }
        if (right != null) {
            float w = g.textWidth(right[1], Gfx.REGULAR, 17, 0);
            g.text(right[1], R, FOOT, Gfx.REGULAR, 17, color, Gfx.RIGHT, 0);
            glyph(g, right[0], R - w - 8 - glyphWidth(g, right[0]), FOOT - 6, color);
        }
    }

    private static void menuHint(Gfx g, String label, int color, float R, float y) {
        float w = g.textWidth(label, Gfx.REGULAR, 17, 0);
        g.text(label, R, y, Gfx.REGULAR, 17, color, Gfx.RIGHT, 0);
        glyph(g, "menu", R - w - 8 - glyphWidth(g, "menu"), y - 6, color);
    }

    private static float glyphWidth(Gfx g, String kind) {
        return kind.equals("menu") ? g.textWidth("MENU", Gfx.MEDIUM, 10, 60) + 12 : 18;
    }

    /** Small drawn glyphs for the camera's controls. cy is the glyph's vertical centre. */
    private static float glyph(Gfx g, String kind, float x, float cy, int color) {
        if (kind.equals("wheel")) {
            g.strokeCircle(x + 9, cy, 8, 1.5f, color);
            g.circle(x + 9, cy - 5, 1.6f, color);
        } else if (kind.equals("centre")) {
            g.strokeCircle(x + 9, cy, 8, 1.5f, color);
            g.circle(x + 9, cy, 3, color);
        } else if (kind.equals("updown")) {
            g.line(x + 4, cy - 3, x + 9, cy - 8, 1.6f, color);
            g.line(x + 9, cy - 8, x + 14, cy - 3, 1.6f, color);
            g.line(x + 4, cy + 3, x + 9, cy + 8, 1.6f, color);
            g.line(x + 9, cy + 8, x + 14, cy + 3, 1.6f, color);
        } else if (kind.equals("menu")) {
            float w = glyphWidth(g, "menu");
            g.strokeRoundRect(x, cy - 9, w, 18, 4, 1.3f, color);
            g.text("MENU", x + w / 2, cy + 4, Gfx.MEDIUM, 10, color, Gfx.CENTER, 60);
            return w;
        }
        return 18;
    }

    /** Word-wraps text into lines no wider than w. */
    private static float wrap(Gfx g, String text, float x, float y, float w, int font, float size, int color, float lineHeight) {
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (g.textWidth(next, font, size, 0) > w && line.length() > 0) {
                g.text(line.toString(), x, y, font, size, color, Gfx.LEFT, 0);
                y += lineHeight;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) g.text(line.toString(), x, y, font, size, color, Gfx.LEFT, 0);
        return y;
    }
}
