package com.latent.camera;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileOutputStream;

/**
 * How fast this camera can put the viewfinder's picture on the screen, four ways, with no camera: the same
 * 16-bit picture and a few marks over it, 40 frames each, as fast as the screen takes them, with the screen
 * in its 16-bit mode as while shooting.
 *
 *  1. Today: Latent's screen (a View in a 32-bit window), the picture's area redrawn each frame. In v0.42.0
 *     the picture's copy alone took 43-49 ms of each draw, where copying it into a 16-bit bitmap takes 3.
 *  2. The same after asking for a 16-bit window (v0.39.2 asked; the copy didn't get cheaper).
 *  3. A 16-bit surface of its own on top (a SurfaceView), drawn on the UI thread.
 *  4. That surface drawn from a thread of its own: the look threads could put frames up themselves.
 *  5. A quarter-size surface (half each way) the screen stretches to the picture's area: if the camera's
 *     display hardware does the stretch, each frame is a quarter of the pixels to make and copy.
 *
 * The fastest is noted (pref screenPath); the viewfinder uses it from a later version, after this test.
 * Log: LATENT/SCREEN.TXT.
 */
final class ScreenTest {
    static final String[] STEPS = { "Test picture", "Today's screen", "16-bit window", "16-bit surface", "Surface from a thread",
            "Quarter size, scaled", "Fastest" };
    static final int OK = Probe.OK, FAIL = Probe.FAIL, RUN = Probe.RUN, INFO = Probe.INFO;
    static final int FRAMES = 40;
    static final String P_PATH = "screenPath";
    static final String[] PATHS = { "today's screen", "a 16-bit window", "a 16-bit surface", "a 16-bit surface from a thread",
            "a quarter-size surface the screen scales" };

    interface Host {
        FrameLayout root();
        View screen();
        /** The window's pixel format (PixelFormat.RGB_565 / RGBA_8888). */
        void windowFormat(int format);
        /** While true, the screen's onDraw calls draw(Canvas) instead of drawing Latent. */
        void hook(boolean on);
        void finished();
    }

    private final Activity activity;
    private final File card;
    private final SharedPreferences prefs;
    private final Probe.Listener out;
    private final Host host;
    private final Handler ui = new Handler();
    private final StringBuilder log = new StringBuilder();
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG), type = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap pic;
    private Rect area;
    private volatile boolean stopped;
    private final double[] fps = new double[5];
    private final double[] drawMs = new double[5];

    ScreenTest(Activity activity, File card, SharedPreferences prefs, Probe.Listener out, Host host) {
        this.activity = activity; this.card = card; this.prefs = prefs; this.out = out; this.host = host;
        mark.setStyle(Paint.Style.STROKE);
        mark.setStrokeWidth(2);
        mark.setColor(0xE6F4F1EA);
        type.setColor(0xFFF4F1EA);
        type.setTextSize(20);
    }

    private void report(int i, int status, String detail) {
        log.append(STEPS[i]).append(": ").append(detail).append("\r\n");
        out.step(i, status, detail);
    }

    void stop() { stopped = true; }

    void start() {
        log.append("Latent screen test\r\n");
        View s = host.screen();
        int w = s.getWidth(), h = s.getHeight();
        if (w <= 0 || h <= 0) { report(0, FAIL, "no screen size yet"); finish(); return; }
        float aspect = Display.aspect(), logical = 480f * (aspect > 0 ? aspect : (float) w / h);
        int pw = Math.round(720f * w / logical), left = (w - pw) / 2;   // the picture's 3:2 area, as the viewfinder's
        area = new Rect(left, 0, left + pw, h);
        try {   // a gradient with fine detail: what a frame of the look would be
            pic = Bitmap.createBitmap(pw, h, Bitmap.Config.RGB_565);
            int[] row = new int[pw];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < pw; x++) {
                    int r = x * 255 / pw, g = y * 255 / h, b = ((x / 8 + y / 8) & 1) * 60 + 90;
                    row[x] = 0xFF000000 | r << 16 | g << 8 | b;
                }
                pic.setPixels(row, 0, pw, 0, y, pw, 1);
            }
        } catch (Throwable e) {
            report(0, FAIL, Probe.why(e));
            finish();
            return;
        }
        report(0, OK, pw + "x" + h + " at " + left + ", the screen " + w + "x" + h);
        viewPath(1, new Runnable() {
            public void run() {
                host.windowFormat(PixelFormat.RGB_565);
                ui.postDelayed(new Runnable() {   // the window's new surface comes on the next passes
                    public void run() {
                        viewPath(2, new Runnable() {
                            public void run() {
                                host.windowFormat(PixelFormat.RGBA_8888);
                                ui.postDelayed(new Runnable() { public void run() { surfacePath(); } }, 600);
                            }
                        });
                    }
                }, 600);
            }
        });
    }

    // ---- 1, 2: Latent's own screen, the picture's area redrawn each frame

    private int frame;
    private long firstAt, drawSum;
    private Runnable then;
    private int path;

    private void viewPath(int step, Runnable then) {
        if (stopped) { finish(); return; }
        out.step(step, RUN, "drawing…");
        this.then = then;
        path = step - 1;
        frame = 0;
        drawSum = 0;
        host.hook(true);
        host.screen().invalidate();
    }

    /** The screen's onDraw while hooked (UI thread). */
    void draw(Canvas c) {
        long t = SystemClock.uptimeMillis();
        if (frame == 0) firstAt = t;
        paint(c);
        drawSum += SystemClock.uptimeMillis() - t;
        if (++frame <= FRAMES && !stopped) { host.screen().invalidate(area); return; }
        long ms = SystemClock.uptimeMillis() - firstAt;
        host.hook(false);
        result(path + 1, path, FRAMES, ms, drawSum);
        ui.post(then);
    }

    private void paint(Canvas c) {
        c.drawBitmap(pic, area.left, 0, null);
        int cx = area.centerX(), cy = area.centerY();
        for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2) {   // focus brackets, as the viewfinder's
            c.drawLine(cx + sx * 56, cy + sy * 37, cx + sx * 40, cy + sy * 37, mark);
            c.drawLine(cx + sx * 56, cy + sy * 37, cx + sx * 56, cy + sy * 25, mark);
        }
        c.drawText("1/125  F5.6  ISO 400  " + frame, area.left + 30, area.bottom - 30, type);
    }

    private void result(int step, int p, int frames, long ms, long drawn) {
        fps[p] = frames * 1000.0 / Math.max(1, ms);
        drawMs[p] = drawn / (double) Math.max(1, frames);
        report(step, OK, String.format(java.util.Locale.US, "%.1f frames a second, %.0f ms drawing each", fps[p], drawMs[p]));
    }

    // ---- 3, 4: a 16-bit surface of its own, on top

    private SurfaceView sv;

    private void surfacePath() {
        if (stopped) { finish(); return; }
        out.step(3, RUN, "making the surface…");
        try {
            sv = new SurfaceView(activity);
            sv.setZOrderOnTop(true);
            sv.getHolder().setFormat(PixelFormat.RGB_565);
            final boolean[] made = { false };
            sv.getHolder().addCallback(new SurfaceHolder.Callback() {
                public void surfaceCreated(SurfaceHolder holder) {
                    if (made[0]) return;
                    made[0] = true;
                    ui.post(new Runnable() { public void run() { surfaceOnUi(0, 0, 0); } });
                }
                public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { }
                public void surfaceDestroyed(SurfaceHolder holder) { }
            });
            host.root().addView(sv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.FILL_PARENT, FrameLayout.LayoutParams.FILL_PARENT));
            ui.postDelayed(new Runnable() {
                public void run() { if (!made[0]) { report(3, FAIL, "no surface in 3 s"); report(4, INFO, "not tried"); scaledPath(); } }
            }, 3000);
        } catch (Throwable e) {
            report(3, FAIL, Probe.why(e));
            report(4, INFO, "not tried");
            scaledPath();
        }
    }

    /** One frame into the surface, then the next posted: the UI thread does nothing else meanwhile. */
    private void surfaceOnUi(int n, long started, long drawn) {
        if (stopped) { done(); return; }
        long t = SystemClock.uptimeMillis();
        if (n == 0) started = t;
        try {
            frame = n;
            if (!surfaceFrame()) { report(3, FAIL, "the surface couldn't be locked"); report(4, INFO, "not tried"); scaledPath(); return; }
        } catch (Throwable e) {
            report(3, FAIL, Probe.why(e));
            report(4, INFO, "not tried");
            scaledPath();
            return;
        }
        drawn += SystemClock.uptimeMillis() - t;
        if (n + 1 < FRAMES) {
            final int nn = n + 1;
            final long st = started, dr = drawn;
            ui.post(new Runnable() { public void run() { surfaceOnUi(nn, st, dr); } });
            return;
        }
        result(3, 2, FRAMES, SystemClock.uptimeMillis() - started, drawn);
        out.step(4, RUN, "drawing from a thread…");
        new Thread(new Runnable() {
            public void run() {
                long st = SystemClock.uptimeMillis(), dr = 0;
                int n = 0;
                String err = null;
                try {
                    for (; n < FRAMES && !stopped; n++) {
                        long t = SystemClock.uptimeMillis();
                        frame = n;
                        if (!surfaceFrame()) { err = "the surface couldn't be locked"; break; }
                        dr += SystemClock.uptimeMillis() - t;
                    }
                } catch (Throwable e) {
                    err = Probe.why(e);
                }
                final long ms = SystemClock.uptimeMillis() - st, drawn = dr;
                final int frames = n;
                final String why = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (why != null) report(4, FAIL, why); else result(4, 3, frames, ms, drawn);
                        scaledPath();
                    }
                });
            }
        }, "screen test").start();
    }

    private boolean surfaceFrame() {
        SurfaceHolder h = sv.getHolder();
        Rect dirty = new Rect(area);
        Canvas c = h.lockCanvas(dirty);
        if (c == null) return false;
        try { paint(c); } finally { h.unlockCanvasAndPost(c); }
        return true;
    }

    // ---- 5: a quarter-size surface, stretched to the picture's area by the screen

    private SurfaceView small;
    private Bitmap smallPic;

    private void scaledPath() {
        if (sv != null) { try { host.root().removeView(sv); } catch (Throwable ignored) { } sv = null; }
        if (stopped) { done(); return; }
        out.step(5, RUN, "making the surface…");
        try {
            final int sw = area.width() / 2, sh = area.height() / 2;
            smallPic = Bitmap.createScaledBitmap(pic, sw, sh, true).copy(Bitmap.Config.RGB_565, false);
            small = new SurfaceView(activity);
            small.setZOrderOnTop(true);
            small.getHolder().setFormat(PixelFormat.RGB_565);
            small.getHolder().setFixedSize(sw, sh);   // the buffer a quarter of the area; the screen stretches it
            final boolean[] made = { false };
            small.getHolder().addCallback(new SurfaceHolder.Callback() {
                public void surfaceCreated(SurfaceHolder holder) {
                    if (made[0]) return;
                    made[0] = true;
                    new Thread(new Runnable() {
                        public void run() {
                            long st = SystemClock.uptimeMillis(), dr = 0;
                            int n = 0;
                            String err = null;
                            try {
                                SurfaceHolder h = small.getHolder();
                                for (; n < FRAMES && !stopped; n++) {
                                    long t = SystemClock.uptimeMillis();
                                    Canvas c = h.lockCanvas();
                                    if (c == null) { err = "the surface couldn't be locked"; break; }
                                    try {
                                        c.drawBitmap(smallPic, 0, 0, null);
                                        c.drawText("1/125  F5.6  ISO 400  " + n, 15, sh - 15, type);
                                    } finally { h.unlockCanvasAndPost(c); }
                                    dr += SystemClock.uptimeMillis() - t;
                                }
                            } catch (Throwable e) {
                                err = Probe.why(e);
                            }
                            final long ms = SystemClock.uptimeMillis() - st, drawn = dr;
                            final int frames = n;
                            final String why = err;
                            ui.postDelayed(new Runnable() {   // left up a moment: how a stretched frame looks
                                public void run() {
                                    if (why != null) report(5, FAIL, why); else result(5, 4, frames, ms, drawn);
                                    done();
                                }
                            }, 1500);
                        }
                    }, "screen test small").start();
                }
                public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { }
                public void surfaceDestroyed(SurfaceHolder holder) { }
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(area.width(), area.height());
            lp.leftMargin = area.left;
            lp.gravity = android.view.Gravity.LEFT | android.view.Gravity.TOP;
            host.root().addView(small, lp);
            ui.postDelayed(new Runnable() {
                public void run() { if (!made[0]) { report(5, FAIL, "no surface in 3 s"); done(); } }
            }, 3000);
        } catch (Throwable e) {
            report(5, FAIL, Probe.why(e));
            done();
        }
    }

    private boolean doneOnce;

    private void done() {
        if (doneOnce) return;
        doneOnce = true;
        if (sv != null) { try { host.root().removeView(sv); } catch (Throwable ignored) { } sv = null; }
        if (small != null) { try { host.root().removeView(small); } catch (Throwable ignored) { } small = null; }
        if (smallPic != null) { smallPic.recycle(); smallPic = null; }
        int best = 0;
        for (int i = 1; i < fps.length; i++) if (fps[i] > fps[best] * 1.1) best = i;   // a clear win only
        if (fps[best] > 0) {
            prefs.edit().putInt(P_PATH, best).commit();
            report(6, OK, PATHS[best] + String.format(java.util.Locale.US, ", %.1f frames a second (today %.1f)", fps[best], fps[0]));
        } else report(6, INFO, "nothing measured");
        finish();
    }

    private void finish() {
        host.hook(false);
        if (pic != null) { pic.recycle(); pic = null; }
        try {
            FileOutputStream o = new FileOutputStream(new File(Library.home(card), "SCREEN.TXT"));
            try { o.write(log.toString().getBytes("UTF-8")); } finally { o.close(); }
        } catch (Throwable ignored) { }
        host.finished();
    }
}
