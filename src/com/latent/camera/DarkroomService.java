package com.latent.camera;

import android.app.Service;
import android.content.Intent;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;

/**
 * The darkroom in a process of its own (":darkroom", see AndroidManifest.xml). Once Latent's own
 * process has opened the camera, Sony's image processor gives it no memory for as long as it runs
 * (v0.23.2: none for 20 s after the release). This process never opens the camera, so it may get
 * that memory while Latent shoots. Asked by intent: "check" the image memory, or "develop" one
 * photo (by its DCF name) in a look. Answers in LATENT/DARKPROC.TXT, one line per request:
 * tag|status|detail (status as Probe: OK, FAIL, INFO).
 */
public final class DarkroomService extends Service {
    static final String WHAT = "what", TAG = "tag", SHOT = "shot", LUT = "lut", PARAMS = "params";

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(final Intent intent, int flags, final int startId) {
        final File card = Environment.getExternalStorageDirectory();
        new Thread(new Runnable() {
            public void run() {
                String tag = intent == null ? "?" : intent.getStringExtra(TAG);
                int status;
                String detail;
                StringBuilder log = new StringBuilder();
                long t0 = SystemClock.uptimeMillis();
                try {
                    HardwareDevelop h = new HardwareDevelop(DarkroomService.this, card, log, null);
                    h.prepare(false);
                    if ("develop".equals(intent.getStringExtra(WHAT))) {
                        File shot = new File(intent.getStringExtra(SHOT));
                        HardwareDevelop.Print print = h.develop(shot, intent.getStringExtra(LUT), intent.getIntArrayExtra(PARAMS), null);
                        try { Library.ignore(card, print.file); } catch (Exception ignored) { }
                        status = print.catalogued ? Probe.OK : Probe.INFO;
                        detail = print.file.getName() + " in " + (SystemClock.uptimeMillis() - t0) / 1000.0 + " s" + (print.catalogued ? "" : " (not catalogued)");
                    } else {
                        status = Probe.OK;
                        detail = "image memory in " + (SystemClock.uptimeMillis() - t0) + " ms";
                    }
                } catch (Throwable e) {
                    status = Probe.FAIL;
                    detail = Probe.why(e) + " (" + (SystemClock.uptimeMillis() - t0) + " ms)";
                }
                answer(card, tag + "|" + status + "|" + detail.replace('|', '/').replace('\r', ' ').replace('\n', ' '), log);
                stopSelf(startId);
            }
        }, "darkroom").start();
        return START_NOT_STICKY;
    }

    private static void answer(File card, String line, StringBuilder log) {
        try {
            FileOutputStream o = new FileOutputStream(new File(Library.home(card), "DARKPROC.TXT"), true);
            try {
                o.write((line + "\r\n").getBytes("UTF-8"));
                for (String l : log.toString().split("\r\n")) if (l.length() > 0) o.write(("  " + l + "\r\n").getBytes("UTF-8"));
            } finally { o.close(); }
        } catch (Exception ignored) {
            // the asker times out and says so
        }
    }
}
