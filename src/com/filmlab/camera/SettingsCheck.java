package com.filmlab.camera;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Reads the camera's stored white balance and Creative Style, and shows them as the menus would.
 * Changes nothing: the native side can only ask for a setting's size, attributes and value.
 * Log: FILMLAB/SETTINGS.TXT, with every raw value, to compare against the camera's menus.
 */
final class SettingsCheck {
    static final String[] STEPS = { "Settings access", "White balance", "WB fine-tune", "Creative Style", "Style tweaks",
            "Also set", "Changeable later" };
    static final int OK = Probe.OK, FAIL = Probe.FAIL, RUN = Probe.RUN, INFO = Probe.INFO;

    private final File card;
    private final Probe.Listener out;
    private final StringBuilder log = new StringBuilder();
    private final Integer[] value = new Integer[CameraSettings.ALL.length];
    private final int[] attrs = new int[CameraSettings.ALL.length];

    SettingsCheck(File card, Probe.Listener out) { this.card = card; this.out = out; }

    private void report(int i, int status, String detail) {
        log.append(STEPS[i]).append(": ").append(status == OK ? "ok" : status == FAIL ? "FAILED" : "info").append(" - ").append(detail).append("\r\n");
        out.step(i, status, detail);
    }

    private Integer v(int id) {
        for (int i = 0; i < CameraSettings.ALL.length; i++) if (CameraSettings.ALL[i] == id) return value[i];
        return null;
    }

    String run() {
        log.append("Latent settings check (read only)\r\n");
        try {
            out.step(0, RUN, "looking…");
            Boolean ok = (Boolean) HardwareDevelop.guarded(new HardwareDevelop.Call() {
                public Object run() { return CameraSettings.available(); }
            }, 10000);
            if (!ok) { report(0, FAIL, CameraSettings.lastError()); return finish(); }
            int read = 0;
            for (int i = 0; i < CameraSettings.ALL.length; i++) {
                final int id = CameraSettings.ALL[i];
                byte[] b = (byte[]) HardwareDevelop.guarded(new HardwareDevelop.Call() {
                    public Object run() { return CameraSettings.read(id); }
                }, 5000);
                Integer a = (Integer) HardwareDevelop.guarded(new HardwareDevelop.Call() {
                    public Object run() { return CameraSettings.attr(id); }
                }, 5000);
                attrs[i] = a;
                log.append(String.format("  0x%08x %-24s ", id, CameraSettings.NAMES[i]));
                if (b == null) { log.append("not read: ").append(CameraSettings.lastError()); }
                else {
                    read++;
                    value[i] = (int) b[0];
                    log.append("bytes");
                    for (byte x : b) log.append(String.format(" %02x", x & 0xff));
                    log.append("  attr ").append(a);
                }
                log.append("\r\n");
            }
            report(0, read > 0 ? OK : FAIL, read + " of " + CameraSettings.ALL.length + " settings read; nothing written");
            if (read == 0) return finish();

            Integer mode = v(CameraSettings.WB_MODE), k = v(CameraSettings.WB_KELVIN);
            if (mode == null) report(1, FAIL, "not read");
            else {
                int m = mode & 0xff;
                report(1, OK, CameraSettings.wbMode(m) + (m == 14 && k != null ? " " + (k & 0xff) * 100 + "K" : ""));
            }
            Integer ab = v(CameraSettings.WB_AB), gm = v(CameraSettings.WB_GM), abA = v(CameraSettings.WB_AB_AUTO), gmA = v(CameraSettings.WB_GM_AUTO);
            if (ab == null && abA == null) report(2, FAIL, "not read");
            else report(2, OK, "menu " + (ab == null ? "?" : CameraSettings.amberBlue(ab)) + " / " + (gm == null ? "?" : CameraSettings.greenMagenta(gm))
                    + ";  Auto applies " + (abA == null ? "?" : CameraSettings.amberBlue(abA)) + " / " + (gmA == null ? "?" : CameraSettings.greenMagenta(gmA)));
            Integer st = v(CameraSettings.STYLE);
            if (st == null) report(3, FAIL, "not read");
            else report(3, OK, CameraSettings.style(st & 0xff));
            Integer con = v(CameraSettings.CONTRAST), sat = v(CameraSettings.SATURATION), sh = v(CameraSettings.SHARPNESS);
            report(4, con == null ? FAIL : OK, "contrast " + (con == null ? "?" : CameraSettings.signed(con)) + ", saturation "
                    + (sat == null ? "?" : CameraSettings.signed(sat)) + ", sharpness " + (sh == null ? "?" : CameraSettings.signed(sh)));
            Integer pe = v(CameraSettings.EFFECT), dro = v(CameraSettings.DRO), ev = v(CameraSettings.EV);
            report(5, INFO, "Picture Effect " + (pe == null ? "?" : CameraSettings.effect(pe & 0xff)) + ", DRO " + (dro == null ? "?" : CameraSettings.dro(dro & 0xff))
                    + ", exposure " + (ev == null ? "?" : CameraSettings.signed(ev)) + "/3");
            int locked = 0;
            for (int i = 0; i < attrs.length; i++) if (value[i] != null && attrs[i] >= 0 && (attrs[i] & CameraSettings.ATTR_READ_ONLY) != 0) locked++;
            report(6, locked == 0 ? OK : INFO, locked == 0 ? "yes: none of them is write-protected (nothing was written)" : locked + " are write-protected; see the log");
        } catch (Throwable e) {
            log.append("stopped: ").append(Probe.why(e)).append("\r\n");
            out.step(0, FAIL, Probe.why(e));
        }
        return finish();
    }

    private String finish() {
        log.append("Compare: MENU > White Balance (press right on Auto for the fine-tune grid) and MENU > Creative Style.\r\n");
        try {
            FileOutputStream o = new FileOutputStream(new File(Library.home(card), "SETTINGS.TXT"));
            try { o.write(log.toString().getBytes("UTF-8")); } finally { o.close(); }
        } catch (Exception e) {
            // the screen shows the same
        }
        return log.toString();
    }
}
