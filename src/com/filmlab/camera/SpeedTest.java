package com.filmlab.camera;

import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Times the film look on this camera's own chip, on your newest photo decoded at half size into
 * memory: today's inner loop against the alternatives (keeping the fastest that is as accurate),
 * and whether a second core helps. For the cores it only reads what the camera reports, and lets one
 * helper thread of Latent's ask to run on the other cores; developing still uses one core.
 * Nothing on the card changes except the log, FILMLAB/SPEED.TXT.
 */
final class SpeedTest {
    static final String[] STEPS = { "Test picture", "Today's loop", "Other loops", "Cores", "Load while idle", "Two cores",
            "Developing with" };
    static final int OK = Probe.OK, FAIL = Probe.FAIL, RUN = Probe.RUN, INFO = Probe.INFO;
    /** A loop is only chosen if its picture is this close to the reference (mean and worst, in levels). */
    static final double MAX_MEAN = 0.5;
    static final int MAX_WORST = 12;

    private final File card;
    private final Stock stock;
    private final SharedPreferences prefs;
    private final Probe.Listener out;
    private final StringBuilder log = new StringBuilder();

    SpeedTest(File card, Stock stock, SharedPreferences prefs, Probe.Listener out) {
        this.card = card; this.stock = stock; this.prefs = prefs; this.out = out;
    }

    private void report(int i, int status, String detail) {
        log.append(STEPS[i]).append(": ").append(detail).append("\r\n");
        out.step(i, status, detail);
    }

    private static String field(String text, String key) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(key) + ":\\s*(.*)$").matcher(text);
        return m.find() ? m.group(1).trim() : "?";
    }

    String run() {
        log.append("Latent speed test\r\n");
        try {
            List<File> photos = Library.photos(card);
            if (photos.isEmpty()) { report(0, FAIL, "no photo on the card"); return finish(); }
            File photo = photos.get(0);
            int[] p = new int[FilmCore.COUNT];
            p[FilmCore.HIGHLIGHT] = stock.highlight; p[FilmCore.SHADOW] = stock.shadow;
            p[FilmCore.GRAIN] = stock.grain; p[FilmCore.GRAIN_SIZE] = stock.grainSize;
            p[FilmCore.QUALITY] = 95; p[FilmCore.SEED] = 1; p[FilmCore.THREADS] = 1;

            // the cores first, while nothing of ours is running: read only
            out.step(3, RUN, "reading…");
            String cores = FilmCore.cores();
            log.append(cores.replace("\n", "\r\n"));
            report(3, INFO, "present " + field(cores, "cores present") + ", on " + field(cores, "online") + ", Latent allowed "
                    + field(cores, "Latent allowed"));
            report(4, INFO, field(cores, "busy while idle"));

            // the loops
            out.step(0, RUN, "decoding " + photo.getName() + "…");
            out.step(1, RUN, "timing…");
            out.step(2, RUN, "timing…");
            String r = FilmCore.bench(photo.getPath(), stock.lut.getPath(), p, 2);
            if (r == null) { report(0, FAIL, FilmCore.lastError()); return finish(); }
            log.append(r.replace("\n", "\r\n"));
            report(0, OK, photo.getName() + ", " + r.split("\n")[0]);
            long[] ms = new long[FilmCore.VARIANTS.length];
            double[] mean = new double[ms.length];
            int[] worst = new int[ms.length];
            for (int v = 0; v < ms.length; v++) {
                Matcher m = Pattern.compile(Pattern.quote(FilmCore.VARIANTS[v]) + " (\\d+) ms \\(table (\\d+) ms\\), vs reference mean ([0-9.]+) max (\\d+)").matcher(r);
                if (!m.find()) { ms[v] = -1; continue; }
                ms[v] = Long.parseLong(m.group(1));
                mean[v] = Double.parseDouble(m.group(3));
                worst[v] = Integer.parseInt(m.group(4));
            }
            if (ms[0] < 0) { report(1, FAIL, "not timed"); return finish(); }
            report(1, OK, ms[0] + " ms at half size, off by " + mean[0] + " on average");
            int best = 0;
            for (int v = 1; v < ms.length; v++) {
                if (ms[v] < 0 || mean[v] > MAX_MEAN || worst[v] > MAX_WORST) continue;
                if (ms[v] * 100 < ms[best] * 95) best = v;   // needs to be clearly (5%) faster to switch
            }
            StringBuilder others = new StringBuilder();
            for (int v = 1; v < ms.length; v++) if (ms[v] >= 0) others.append(others.length() > 0 ? ", " : "").append(FilmCore.VARIANTS[v]).append(" ").append(ms[v]);
            report(2, INFO, best == 0 ? "none clearly faster (" + others + " ms)" : FilmCore.VARIANTS[best] + " is " + (100 - ms[best] * 100 / ms[0]) + "% faster");

            // two cores: one helper thread asks to run on the other cores; nothing else changes
            out.step(5, RUN, "timing…");
            String two = FilmCore.benchCores(photo.getPath(), stock.lut.getPath(), p, 2);
            if (two == null) { prefs.edit().putBoolean("multicoreOk", false).commit(); report(5, FAIL, FilmCore.lastError()); }
            else {
                log.append(two.replace("\n", "\r\n"));
                Matcher m = Pattern.compile("one core (\\d+) ms, two threads (\\d+) ms, pictures (\\w+)").matcher(two);
                String widen = field(two, "widen"), ran = field(two, "ran on");
                if (m.find()) {
                    long a = Long.parseLong(m.group(1)), b = Long.parseLong(m.group(2));
                    boolean same = m.group(3).equals("identical");
                    String speed = b > 0 ? String.format(java.util.Locale.US, "%.1fx", (double) a / b) : "?";
                    boolean helps = same && b * 10 < a * 8 && widen.startsWith("asked") && !widen.contains("got 0x8");
                    prefs.edit().putBoolean("multicoreOk", helps).commit();   // the darkroom may use cores 1 and 2 only after this
                    report(5, helps ? OK : INFO, speed + " (" + a + " -> " + b + " ms), " + (same ? "same picture" : "PICTURES DIFFER")
                            + "; " + ran + "; " + widen);
                } else {
                    prefs.edit().putBoolean("multicoreOk", false).commit();
                    report(5, FAIL, "not timed; " + widen);
                }
            }

            prefs.edit().putInt("lookVariant", best).commit();
            boolean multi = prefs.getBoolean("multicoreOk", false) && prefs.getInt("cores", 3) > 1;
            report(6, OK, FilmCore.VARIANTS[best] + (multi ? ", on cores 3, 1 and 2" : prefs.getBoolean("multicoreOk", false)
                    ? ", one core (switched off under More)" : ", one core"));
        } catch (Throwable e) {
            log.append("stopped: ").append(Probe.why(e)).append("\r\n");
            out.step(0, FAIL, Probe.why(e));
        }
        return finish();
    }

    private String finish() {
        try {
            FileOutputStream o = new FileOutputStream(new File(Library.home(card), "SPEED.TXT"));
            try { o.write(log.toString().getBytes("UTF-8")); } finally { o.close(); }
        } catch (Exception e) {
            // the screen shows the same
        }
        return log.toString();
    }
}
