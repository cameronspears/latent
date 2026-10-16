package com.filmlab.camera;

import android.content.Context;
import android.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * The hardware develop, run once on the newest photo with a full log (FILMLAB/HWTEST.TXT). It is
 * exactly the darkroom's develop (HardwareDevelop), minus the negatives: the original stays where it
 * is, and the new photo is an extra copy you can delete in playback.
 */
final class HardwareTest {
    static final String[] STEPS = HardwareDevelop.STEPS;

    private final Context ctx;
    private final File card;
    private final Stock stock;
    private final Probe.Listener out;
    private final FilmCore.Progress progress;
    private final StringBuilder log = new StringBuilder();

    HardwareTest(Context ctx, File card, Stock stock, Probe.Listener out, FilmCore.Progress progress) {
        this.ctx = ctx; this.card = card; this.stock = stock; this.out = out; this.progress = progress;
    }

    String run() {
        log.append("Latent hardware develop test\r\n");
        HardwareDevelop hw = new HardwareDevelop(ctx, card, log, out);
        try {
            List<File> photos = Library.photos(card);
            if (photos.isEmpty()) { out.step(HardwareDevelop.S_PHOTO, HardwareDevelop.FAIL, "no photo on the card"); return finish(); }
            File newest = photos.get(0);
            hw.prepare(true);
            int[] p = new int[FilmCore.COUNT];
            p[FilmCore.HIGHLIGHT] = stock.highlight; p[FilmCore.SHADOW] = stock.shadow;
            p[FilmCore.GRAIN] = stock.grain; p[FilmCore.GRAIN_SIZE] = stock.grainSize;
            p[FilmCore.QUALITY] = 95; p[FilmCore.SEED] = (int) newest.lastModified(); p[FilmCore.THREADS] = 1;
            log.append("developing ").append(newest.getName()).append(" as ").append(stock.name).append("\r\n");
            HardwareDevelop.Print print = hw.develop(newest, stock.lut.getPath(), p, progress);
            long[] t = hw.ms;
            log.append("ms: decode ").append(t[HardwareDevelop.T_DECODE]).append(", image ").append(t[HardwareDevelop.T_IMAGE])
                    .append(", develop ").append(t[HardwareDevelop.T_DEVELOP]).append(", save ").append(t[HardwareDevelop.T_SAVE])
                    .append(", find ").append(t[HardwareDevelop.T_FIND]).append(", orientation ").append(t[HardwareDevelop.T_ORIENT])
                    .append(", total ").append(t[HardwareDevelop.T_TOTAL]).append("\r\n");
            ExifInterface ex = new ExifInterface(print.file.getPath()), orig = new ExifInterface(newest.getPath());
            String[] tags = { "DateTime", "DateTimeOriginal", "Orientation", "Make", "Model", "ExposureTime", "FNumber", "ISOSpeedRatings", "ImageWidth", "ImageLength" };
            for (String tag : tags) log.append("  exif ").append(tag).append(": original=").append(orig.getAttribute(tag)).append(" new=").append(ex.getAttribute(tag)).append("\r\n");
            log.append("  file time: original=").append(newest.lastModified()).append(" new=").append(print.file.lastModified()).append("\r\n");
        } catch (Throwable e) {
            if (!(e instanceof HardwareDevelop.Stuck) && !(e instanceof HardwareDevelop.Failed)) {
                log.append("stopped: ").append(Probe.why(e)).append("\r\n");
                out.step(hw.current, HardwareDevelop.FAIL, Probe.why(e));
            }
        }
        return finish();
    }

    private String finish() {
        try {
            FileOutputStream o = new FileOutputStream(new File(Library.home(card), "HWTEST.TXT"));
            try { o.write(log.toString().getBytes("UTF-8")); } finally { o.close(); }
        } catch (Exception e) {
            // the screen shows the same
        }
        return log.toString();
    }
}
