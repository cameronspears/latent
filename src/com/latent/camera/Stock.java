package com.latent.camera;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A film stock: a look (LUT file in LATENT/LUTS) plus the tone and grain it is developed with,
 * and the colours its canister is drawn in. The built-in stocks are tuned presets; any other .CUB
 * file on the card shows up as a plain grey "custom" canister with neutral settings. Films Latent no
 * longer offers (retired) still develop and show their names on the rolls shot in them.
 */
final class Stock {
    final String id;          // LUT file stem, e.g. CUBANNEG
    final String name;        // "Cuban Neg 400"
    final String title, iso;  // "Cuban Neg", "400"
    final String tagline;
    final File lut;
    final int highlight, shadow, grain, grainSize;
    final int body, band, accent; // canister colours, and the UI accent while this stock is loaded
    final boolean retired;        // no longer offered: kept for the rolls already shot in it

    private Stock(String id, String title, String iso, String tagline, File lut, int highlight, int shadow, int grain,
                  int grainSize, int body, int band, int accent, boolean retired) {
        this.id = id; this.title = title; this.iso = iso; this.name = iso.length() > 0 ? title + " " + iso : title;
        this.tagline = tagline; this.lut = lut;
        this.highlight = highlight; this.shadow = shadow; this.grain = grain; this.grainSize = grainSize;
        this.body = body; this.band = band; this.accent = accent; this.retired = retired;
    }

    /**
     * The built-in stocks, in the order they're offered: a small, distinct set rather than a long list.
     * id, title, ISO, tagline, highlight, shadow, grain, grain size, canister body, band, accent.
     * The looks themselves come from tools/make_luts.py, tone included (highlight and shadow stay 0); grain size
     * 1 is a colour film's fine dye clouds, 2 a 400 film's, 3 Tri-X's coarse silver, 4 Painterly's paper (its tooth,
     * not grain; jni/filmcore.c). Painterly is no film, so it has no ISO. Taglines say the
     * character, then the use, in a line (Fuji's film simulation notes); colours are on the screen's 4-bit grid (Ui's
     * palette), so the canister and accent look the same on the 32-bit screen, its 16-bit plane and in the viewfinder.
     */
    private static final Object[][] BUILT_IN = {
        { "NATURAL", "Natural", "100", "True colour, soft highlights. For anything.",
                0, 0, 2, 1, 0xFF888899, 0xFFEEDDDD, 0xFFDDCCBB },
        { "PORTRA4W", "Portra", "400", "Soft and warm, golden skin. For people.",
                0, 0, 4, 1, 0xFFCC9944, 0xFFEEEECC, 0xFFEEBB55 },
        { "CUBANNEG", "Cuban Neg", "400", "Warm and punchy, deep teal. For sunny days.",
                0, 0, 5, 2, 0xFF116666, 0xFFEE6633, 0xFFEE7733 },
        { "PAINTRLY", "Painterly", "", "Soft and luminous, on paper. For travel.",
                0, 0, 5, 4, 0xFF6677AA, 0xFFEEBB99, 0xFFFFCCAA },
        { "TRIX400", "Tri-X", "400", "Inky blacks, real grain. For people and night.",
                0, 0, 7, 3, 0xFF222222, 0xFFEEBB33, 0xFFFFFFEE },
    };

    /**
     * Films Latent offered until v0.45.0, as they were then: a roll shot in one still develops in it (its look file
     * stays on the card) and shows its canister, but it isn't offered for a new roll.
     */
    private static final Object[][] RETIRED = {
        { "CLASSNEG", "Classic Neg", "400", "Hard, muted colour, teal shadows. For streets.",
                1, 1, 3, 1, 0xFF226655, 0xFFEEDDCC, 0xFF66BB99 },
        { "PARTY800", "Afterparty", "800", "Warm skin, big grain. For flash at night.",
                -1, 2, 7, 3, 0xFF442277, 0xFFEE4488, 0xFFEE6699 },
    };

    /** The built-in stocks without a card (no look files): how they're shown, for the Mac previews. */
    static List<Stock> builtIn() {
        List<Stock> out = new ArrayList<Stock>();
        for (Object[] b : BUILT_IN) out.add(make(b, null, false));
        return out;
    }

    private static Stock make(Object[] b, File lut, boolean retired) {
        return new Stock((String) b[0], (String) b[1], (String) b[2], (String) b[3], lut, (Integer) b[4], (Integer) b[5],
                (Integer) b[6], (Integer) b[7], (Integer) b[8], (Integer) b[9], (Integer) b[10], retired);
    }

    /**
     * Every stock the card has a look for: the built-ins in their order, then any of your own, then the retired
     * films whose looks are still on the card (offered() leaves those out).
     */
    static List<Stock> all(File card) {
        List<Stock> out = new ArrayList<Stock>();
        List<File> luts = Library.luts(card);
        List<File> used = new ArrayList<File>();
        List<Stock> retired = new ArrayList<Stock>();
        for (Object[] b : BUILT_IN) {
            File f = find(luts, (String) b[0]);
            if (f == null) continue;
            used.add(f);
            out.add(make(b, f, false));
        }
        for (Object[] b : RETIRED) {
            File f = find(luts, (String) b[0]);
            if (f == null) continue;
            used.add(f);
            retired.add(make(b, f, true));
        }
        for (File f : luts) {
            if (used.contains(f)) continue;
            String id = Library.lutName(f);
            out.add(new Stock(id, id, "", "Your own look, from LATENT/LUTS.", f, 0, 0, 4, 1, 0xFF555544, 0xFFDDCCCC, 0xFFFFFFEE, false));
        }
        out.addAll(retired);
        return out;
    }

    /** The stocks offered for a new roll (Choose film, the viewfinder's wheel): all but the retired. */
    static List<Stock> offered(List<Stock> all) {
        List<Stock> out = new ArrayList<Stock>(all.size());
        for (Stock s : all) if (!s.retired) out.add(s);
        return out;
    }

    static Stock byId(List<Stock> stocks, String id) {
        for (Stock s : stocks) if (s.id.equals(id)) return s;
        return null;
    }

    private static File find(List<File> luts, String id) {
        for (File f : luts) if (Library.lutName(f).equals(id)) return f;
        return null;
    }
}
