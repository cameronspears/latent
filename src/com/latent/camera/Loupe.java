package com.latent.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * A developed photo held open for the viewer's 100% view away from its centre (the centre itself comes from
 * FilmCore.preview's crop, which decodes only that). Android's region decoder (API 10) reads the JPEG through once
 * to index it, then decodes any part of it at full size without the rest. Kept apart from the viewer so that a
 * camera whose system lacks it fails here, as an exception the viewer catches (and the view stays at the centre).
 *
 * Worker thread only: closing it while a part is being decoded isn't safe, so whoever opened it closes it there.
 */
final class Loupe {
    final File file;
    private final FileInputStream in;
    private final BitmapRegionDecoder d;
    private final Rect r = new Rect();

    /** Indexes the photo (the slow part: a read through the whole file). Throws if it can't be opened or indexed. */
    Loupe(File file) throws IOException {
        this.file = file;
        in = new FileInputStream(file);
        BitmapRegionDecoder made = null;
        try {
            // shareable: the decoder reads the file as it needs it, rather than copying all of it into memory
            made = BitmapRegionDecoder.newInstance(in.getFD(), true);
        } finally {
            if (made == null) in.close();
        }
        if (made == null) throw new IOException("no region decoder for " + file.getName());
        d = made;
    }

    int width() { return d.getWidth(); }
    int height() { return d.getHeight(); }

    /** The part of the photo from (x, y), w x h at full size, or null. */
    Bitmap part(int x, int y, int w, int h) {
        r.set(x, y, x + w, y + h);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        o.inPreferQualityOverSpeed = true;   // the accurate DCT: at 100% the difference is what you're looking at
        o.inDither = false;
        return d.decodeRegion(r, o);
    }

    void close() {
        try { d.recycle(); } catch (Throwable ignored) { }
        try { in.close(); } catch (IOException ignored) { }
    }
}
