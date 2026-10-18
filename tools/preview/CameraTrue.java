package com.latent.camera;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A screen as the A5100 really shows it, so legibility can be judged on the Mac (the 853 previews flatter it):
 * - drawn at the panel's 640 dots across, through the same 0.75 horizontal scale the camera's Canvas uses
 *   (MainActivity's Screen), so strokes thinner than a dot go faint and uneven as they do on the LCD;
 * - 4 bits a channel, the OSD's RGBA4444 while the camera is open (every screen after the first viewfinder
 *   visit): Latent's window and the picture surface truncated to it, the viewfinder's picture and its
 *   overlay dithered to it with the composer's own blue-noise thresholds (jni/bluenoise.h, dither_row);
 * - the viewfinder in its parts, as on the camera: the composed picture (the look plus the overlay, dithered)
 *   on its 16-bit surface with what moves drawn over it, Latent's window (the side bars) around it;
 * - then stretched back to 853 for viewing. sun() adds a reflected-light veil for judging it outdoors.
 */
final class CameraTrue {
    private CameraTrue() {}

    static final int DOTS = 640, ROWS = 480;
    private static int[] noise;

    /** The camera's screen for this state, at 640 x 480 dots (viewing size: view()). */
    static BufferedImage dots(UiState s, File fonts, BufferedImage video) throws Exception {
        if (s.mode == UiState.PHOTO && s.liveImage != null && !s.live) return viewfinder(s, fonts);
        BufferedImage osd = new BufferedImage(DOTS, ROWS, BufferedImage.TYPE_INT_ARGB);   // transparent where nothing is drawn
        Graphics2D g = osd.createGraphics();
        g.scale(DOTS / s.width, 1);
        Ui.draw(new HostGfx(g, fonts), s);
        g.dispose();
        truncate(osd);
        BufferedImage out = new BufferedImage(DOTS, ROWS, BufferedImage.TYPE_INT_RGB);
        g = out.createGraphics();
        if (video != null) {   // the camera's own live view: its video plane, under the OSD (full colour)
            int vw = Math.round(720 * DOTS / s.width);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(video, (DOTS - vw) / 2, 0, vw, ROWS, null);
        }
        g.drawImage(osd, 0, 0, null);
        g.dispose();
        return out;
    }

    /** The viewfinder in its parts, as the camera puts it together (see the class comment). */
    private static BufferedImage viewfinder(UiState s, File fonts) throws Exception {
        int lw = Math.round(720 * DOTS / s.width), lh = ROWS;   // Viewfinder's liveW x liveH: 540 x 480
        // the composer: the look's frame stretched to the picture's pixels, the overlay laid in, dithered to 4 bits
        BufferedImage pic = new BufferedImage(lw, lh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = pic.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage((BufferedImage) s.liveImage, 0, 0, lw, lh, null);
        g.dispose();
        BufferedImage ovl = new BufferedImage(lw, lh, BufferedImage.TYPE_INT_ARGB);
        g = ovl.createGraphics();
        g.scale(lw / 720f, lh / 480f);   // as renderOverlay: the picture's 720 x 480 of Latent's layout
        g.translate(-(s.width - 720) / 2, 0);
        UiState o = s.copy();
        o.vfLayer = UiState.LAYER_OVERLAY;
        o.vfPart = UiState.PART_ALL;
        Ui.draw(new HostGfx(g, fonts), o);
        g.dispose();
        g = pic.createGraphics();
        g.drawImage(ovl, 0, 0, null);
        g.dispose();
        dither(pic);
        // the presenter: on the 16-bit surface, the composed picture and what moves over it (no dither: truncated)
        BufferedImage surf = new BufferedImage(lw, lh, BufferedImage.TYPE_INT_RGB);
        g = surf.createGraphics();
        g.drawImage(pic, 0, 0, null);
        g.scale(lw / 720f, lh / 480f);
        g.translate(-(s.width - 720) / 2, 0);
        UiState sp = s.copy();
        sp.vfLayer = UiState.LAYER_SCREEN;
        sp.vfPart = UiState.PART_PICTURE;
        sp.liveImage = null;
        Ui.draw(new HostGfx(g, fonts), sp);
        g.dispose();
        truncate(surf);
        // Latent's window: the side bars (the surface sits on top of the rest)
        BufferedImage win = new BufferedImage(DOTS, ROWS, BufferedImage.TYPE_INT_ARGB);
        g = win.createGraphics();
        g.scale(DOTS / s.width, 1);
        UiState w = s.copy();
        w.vfLayer = UiState.LAYER_SCREEN;
        w.vfPart = UiState.PART_WINDOW;
        w.liveImage = null;
        Ui.draw(new HostGfx(g, fonts), w);
        g.dispose();
        truncate(win);
        BufferedImage out = new BufferedImage(DOTS, ROWS, BufferedImage.TYPE_INT_RGB);
        g = out.createGraphics();
        g.drawImage(win, 0, 0, null);
        g.drawImage(surf, (DOTS - lw) / 2, 0, null);
        g.dispose();
        return out;
    }

    /** 640 dots stretched back to the panel's shape (853 x 480 at the previews' scale), as the eye sees it. */
    static BufferedImage view(BufferedImage dots, float width) {
        BufferedImage v = new BufferedImage(Math.round(width), ROWS, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = v.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(dots, 0, 0, v.getWidth(), ROWS, null);
        g.dispose();
        return v;
    }

    /** Daylight on the screen: a reflected-light veil over everything (0.55 of the picture, 0.45 of a mid-grey). */
    static BufferedImage sun(BufferedImage in) {
        BufferedImage out = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < in.getHeight(); y++) for (int x = 0; x < in.getWidth(); x++) {
            int p = in.getRGB(x, y), r = (p >> 16) & 255, gg = (p >> 8) & 255, b = p & 255;
            out.setRGB(x, y, veil(r) << 16 | veil(gg) << 8 | veil(b));
        }
        return out;
    }

    private static int veil(int v) { return Math.round(0.55f * v + 0.45f * 180); }

    /** The OSD's 4 bits a channel (alpha too), by truncation, as the 16-bit plane takes a 32-bit pixel. */
    static void truncate(BufferedImage img) {
        boolean alpha = img.getColorModel().hasAlpha();
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) {
            int p = img.getRGB(x, y);
            int a = alpha ? t4(p >>> 24) : 255;
            img.setRGB(x, y, a << 24 | t4((p >> 16) & 255) << 16 | t4((p >> 8) & 255) << 8 | t4(p & 255));
        }
    }

    private static int t4(int v) { return (v & 0xF0) | (v >> 4); }

    /** dither_row's arithmetic: v * 241 >> 8 in sixteenths, plus the pixel's blue-noise threshold, over 16. */
    static void dither(BufferedImage img) throws Exception {
        int[] bn = noise();
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) {
            int t = bn[((y & 63) << 6) | (x & 63)] >> 4, p = img.getRGB(x, y);
            img.setRGB(x, y, 0xFF000000 | d4((p >> 16) & 255, t) << 16 | d4((p >> 8) & 255, t) << 8 | d4(p & 255, t));
        }
    }

    private static int d4(int v, int t) { return (((v * 241 >> 8) + t) >> 4) * 17; }

    /** The composer's 64 x 64 thresholds, read from jni/bluenoise.h. */
    private static int[] noise() throws Exception {
        if (noise != null) return noise;
        String h = new String(Files.readAllBytes(new File("jni/bluenoise.h").toPath()), "US-ASCII");
        h = h.substring(h.indexOf('{') + 1, h.lastIndexOf('}'));
        Matcher m = Pattern.compile("\\d+").matcher(h);
        int[] t = new int[64 * 64];
        int n = 0;
        while (m.find() && n < t.length) t[n++] = Integer.parseInt(m.group());
        if (n != t.length) throw new IllegalStateException("jni/bluenoise.h: " + n + " thresholds, not 4096");
        return noise = t;
    }
}
