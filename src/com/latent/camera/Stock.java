package com.latent.camera;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A film stock: a look (LUT file in LATENT/LUTS) plus the tone and grain it is developed with,
 * and the colours its canister is drawn in. The built-in stocks are tuned presets; any other .CUB
 * file on the card shows up as a plain grey "custom" canister with neutral settings.
 */
final class Stock {
    final String id;          // LUT file stem, e.g. CUBANNEG
    final String name;        // "Cuban Neg 400"
    final String title, iso;  // "Cuban Neg", "400"
    final String tagline;
    final File lut;
    final int highlight, shadow, grain, grainSize;
    final int body, band, accent; // canister colours, and the UI accent while this stock is loaded

    private Stock(String id, String title, String iso, String tagline, File lut, int highlight, int shadow, int grain,
                  int grainSize, int body, int band, int accent) {
        this.id = id; this.title = title; this.iso = iso; this.name = iso.length() > 0 ? title + " " + iso : title;
        this.tagline = tagline; this.lut = lut;
        this.highlight = highlight; this.shadow = shadow; this.grain = grain; this.grainSize = grainSize;
        this.body = body; this.band = band; this.accent = accent;
    }

    /**
     * The built-in stocks, in the order they're offered: a small, distinct set rather than a long list.
     * id, title, ISO, tagline, highlight, shadow, grain, grain size, canister body, band, accent.
     * The looks themselves come from tools/make_luts.py.
     */
    private static final Object[][] BUILT_IN = {
        { "NATURAL", "Natural", "100", "Faithful, rich colour and the smoothest highlights. The quiet one.",
                0, 0, 1, 1, 0xFF8C9096, 0xFFE9E5DC, 0xFFD9D2C3 },
        { "PORTRA4W", "Portra", "400", "Soft and airy, with creamy highlights and golden skin.",
                -1, -1, 4, 1, 0xFFC79A3E, 0xFFF3E7C9, 0xFFE8B653 },
        { "CLASSNEG", "Classic Neg", "400", "Hard, quiet colour with teal shadows and warm highlights.",
                1, 1, 3, 1, 0xFF1F6E52, 0xFFE8E2D3, 0xFF6CC29E },
        { "CUBANNEG", "Cuban Neg", "400", "Warm, punchy street colour with deep teal shadows.",
                -2, 1, 5, 2, 0xFF17675F, 0xFFEC6A2C, 0xFFF0743A },
        { "TRIX400", "Tri-X", "400", "Gritty black and white: inky blacks, bright whites, real grain.",
                0, 1, 8, 2, 0xFF1D1C1A, 0xFFF2C230, 0xFFF4F1EA },
        { "PARTY800", "Afterparty", "800", "For nights out with the flash on: punchy, warm skin, big grain.",
                -1, 2, 7, 3, 0xFF4B2470, 0xFFE8458B, 0xFFF0629F },
    };

    /** Every stock the card has a look for: the built-ins in their order, then any of your own. */
    static List<Stock> all(File card) {
        List<Stock> out = new ArrayList<Stock>();
        List<File> luts = Library.luts(card);
        List<File> used = new ArrayList<File>();
        for (Object[] b : BUILT_IN) {
            File f = find(luts, (String) b[0]);
            if (f == null) continue;
            used.add(f);
            out.add(new Stock((String) b[0], (String) b[1], (String) b[2], (String) b[3], f, (Integer) b[4], (Integer) b[5],
                    (Integer) b[6], (Integer) b[7], (Integer) b[8], (Integer) b[9], (Integer) b[10]));
        }
        for (File f : luts) {
            if (used.contains(f)) continue;
            String id = Library.lutName(f);
            out.add(new Stock(id, id, "", "Your own look, from LATENT/LUTS.", f, 0, 0, 4, 1, 0xFF555049, 0xFFD8D2C4, 0xFFF4F1EA));
        }
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
