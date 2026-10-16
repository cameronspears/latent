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

    /** Every stock the card has a look for, built-ins first. */
    static List<Stock> all(File card) {
        List<Stock> out = new ArrayList<Stock>();
        List<File> luts = Library.luts(card);
        File cuban = find(luts, "CUBANNEG"), portra = find(luts, "PORTRA4W");
        if (cuban != null) out.add(new Stock("CUBANNEG", "Cuban Neg", "400", "Warm, punchy street colour with deep teal shadows.",
                cuban, -2, 1, 5, 2, 0xFF17675F, 0xFFEC6A2C, 0xFFF0743A));
        if (portra != null) out.add(new Stock("PORTRA4W", "Portra", "400", "Soft contrast, warm creamy highlights and golden skin.",
                portra, -1, -1, 4, 1, 0xFFC79A3E, 0xFFF3E7C9, 0xFFE8B653));
        for (File f : luts) {
            String id = Library.lutName(f);
            if (f == cuban || f == portra) continue;
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
