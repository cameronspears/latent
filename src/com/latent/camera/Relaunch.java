package com.latent.camera;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;

import java.io.File;

/**
 * A blank screen of Latent's own, in a process of its own (":relaunch"), shown while Latent's main
 * process ends and starts again. Once a process has opened the camera, Sony's image memory comes back
 * only when that process ends; with this screen in front, the camera never sees Latent left.
 *
 * Waits until the old process has gone (it ends itself on pause, after giving the camera back; after
 * 15 s this screen ends it, as the same app may), then starts Latent again and ends too.
 */
public final class Relaunch extends Activity {
    static final String EXTRA_PID = "pid", EXTRA_AT = "at", EXTRA_WHY = "why";
    /** The darkroom getting ready, drawn as MainActivity draws it: the film's name, ISO, colours, and how many frames. */
    static final String EXTRA_FILM = "film", EXTRA_ISO = "iso", EXTRA_ACCENT = "accent", EXTRA_BODY = "body", EXTRA_BAND = "band", EXTRA_TOTAL = "total", EXTRA_BATTERY = "battery";
    /** Up to this long for the old process to end by itself (it waits for any picture still being stored). */
    static final long WAIT_MS = 15000;

    private final Handler ui = new Handler();
    private int pid;
    private long started;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // true black; restarting to develop, the darkroom itself, getting ready, drawn by Latent's own Ui exactly
        // as the screen before and after it, so the restart doesn't show
        final Intent in = getIntent();
        final boolean develop = "develop".equals(in.getStringExtra(EXTRA_WHY));
        if (develop) {   // the darkroom's light, as before and after
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = 0.05f;
            getWindow().setAttributes(lp);
        }
        final float aspect = Display.aspect();
        View v = new View(this) {
            private AndroidGfx gfx;
            @Override
            protected void onDraw(android.graphics.Canvas c) {
                if (gfx == null) gfx = new AndroidGfx(getAssets());
                float logical = 480f * (aspect > 0 ? aspect : (float) getWidth() / Math.max(1, getHeight()));
                UiState s = new UiState();
                s.width = logical;
                s.mode = develop ? UiState.DARKROOM : UiState.BACK;
                s.battery = in.getIntExtra(EXTRA_BATTERY, -1);
                if (develop) {
                    s.devPreparing = true;
                    s.devTotal = Math.max(1, in.getIntExtra(EXTRA_TOTAL, 1));
                    String title = in.getStringExtra(EXTRA_FILM);
                    if (title != null) {
                        s.film = new UiState.Film();
                        s.film.title = title;
                        s.film.iso = in.getStringExtra(EXTRA_ISO) == null ? "" : in.getStringExtra(EXTRA_ISO);
                        s.film.accent = in.getIntExtra(EXTRA_ACCENT, Ui.RED);
                        s.film.body = in.getIntExtra(EXTRA_BODY, 0);
                        s.film.band = in.getIntExtra(EXTRA_BAND, 0);
                    }
                }
                c.save();
                c.scale(getWidth() / logical, getHeight() / 480f);
                gfx.begin(c);
                if (develop) Ui.draw(gfx, s); else c.drawColor(0xFF000000);
                c.restore();
            }
        };
        v.setBackgroundColor(0xFF000000);
        setContentView(v);
        pid = getIntent().getIntExtra(EXTRA_PID, 0);
        started = SystemClock.uptimeMillis();
        ui.post(check);
    }

    private final Runnable check = new Runnable() {
        public void run() {
            boolean alive = pid > 0 && new File("/proc/" + pid).exists();
            long waited = SystemClock.uptimeMillis() - started;
            if (alive && waited < WAIT_MS) { ui.postDelayed(this, 50); return; }
            if (alive) {   // it didn't end by itself: the same app may end it
                android.os.Process.killProcess(pid);
                ui.postDelayed(this, 300);
                pid = 0;
                return;
            }
            Intent i = new Intent(Relaunch.this, MainActivity.class);
            i.putExtras(getIntent());   // the film and the count too, so Latent opens on the same darkroom
            i.removeExtra(EXTRA_PID);
            startActivity(i);
            overridePendingTransition(0, 0);
            finish();
            overridePendingTransition(0, 0);
            ui.postDelayed(new Runnable() { public void run() { android.os.Process.killProcess(android.os.Process.myPid()); } }, 2000);
        }
    };

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent e) { return true; }   // a moment: nothing to press

    @Override
    public boolean onKeyUp(int keyCode, android.view.KeyEvent e) { return true; }
}
