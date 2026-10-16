package com.filmlab.camera;

import java.io.File;

/** Mac test of the neutral switch: the real native bridge against tools/fakeosal.c's stand-in settings service. */
public class SettingsTest {
    public static void main(String[] a) throws Exception {
        File card = new File(a[0]);
        new File(card, "FILMLAB").mkdirs();
        check(CameraSettings.available(), "settings service found");
        check(v(CameraSettings.WB_AB) == 2 && v(CameraSettings.WB_GM) == 2 && v(CameraSettings.CONTRAST) == 1 && v(CameraSettings.DRO) == 1,
                "reads the camera's A2 / M2, contrast +1, DRO Auto");
        // the fence: only the switched settings, only in range
        check(CameraSettings.write(CameraSettings.WB_KELVIN, 50) < 0 && v(CameraSettings.WB_KELVIN) == 60, "colour temperature can't be written");
        check(CameraSettings.write(CameraSettings.EV, 0) < 0, "exposure compensation can't be written");
        check(CameraSettings.write(CameraSettings.CONTRAST, 5) < 0 && v(CameraSettings.CONTRAST) == 1, "out-of-range contrast refused");
        check(CameraSettings.write(CameraSettings.WB_AB, -9) < 0 && v(CameraSettings.WB_AB) == 2, "out-of-range fine-tune refused");
        check(CameraSettings.write(0x01079999, 0) < 0, "an unknown (read-only) setting refused");

        StringBuilder log = new StringBuilder();
        NeutralSettings ns = new NeutralSettings(card, log);
        check(ns.apply() == null && ns.active(), "neutral applied, note kept: " + log.toString().replace("\r\n", " | "));
        check(ns.yoursSummary().equals("A2 · M2, contrast +1, saturation +1, DRO Auto"), "summary in the menus' words: " + ns.yoursSummary());
        int[] ids = NeutralSettings.IDS;
        boolean neutral = true;
        for (int i = 0; i < ids.length; i++) neutral &= v(ids[i]) == NeutralSettings.NEUTRAL[i];
        check(neutral, "every switched setting reads neutral");
        int before = log.length();
        check(ns.apply() == null && log.length() == before, "applying again changes nothing");
        // you change something yourself while the roll is loaded
        check(CameraSettings.write(CameraSettings.CONTRAST, 2) == 0, "(you set contrast +2 mid-roll)");
        check(ns.restore() == null && !ns.active(), "restored, note removed: " + log.substring(before).replace("\r\n", " | "));
        check(v(CameraSettings.WB_AB) == 2 && v(CameraSettings.WB_GM) == 2 && v(CameraSettings.WB_AB_AUTO) == 2 && v(CameraSettings.WB_GM_AUTO) == 2,
                "fine-tune A2 / M2 back, Auto copies too");
        check(v(CameraSettings.SATURATION) == 1 && v(CameraSettings.DRO) == 1 && v(CameraSettings.STYLE) == 1 && v(CameraSettings.DRO_LEVEL) == 1,
                "saturation +1, DRO Auto, Standard back");
        check(v(CameraSettings.CONTRAST) == 2, "your mid-roll contrast +2 is left alone");
        check(ns.restore() == null, "restoring with nothing to restore is a no-op");
        System.out.println("SETTINGS OK");
    }

    static int v(int id) { byte[] b = CameraSettings.read(id); if (b == null) throw new RuntimeException(CameraSettings.lastError()); return b[0]; }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  pass  " : "  FAIL  ") + what);
        if (!ok) System.exit(1);
    }
}
