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
    /** Up to this long for the old process to end by itself (it waits for any picture still being stored). */
    static final long WAIT_MS = 15000;

    private final Handler ui = new Handler();
    private int pid;
    private long started;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        View v = new View(this);
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
            i.putExtra(EXTRA_AT, getIntent().getLongExtra(EXTRA_AT, 0));
            i.putExtra(EXTRA_WHY, getIntent().getStringExtra(EXTRA_WHY));
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
