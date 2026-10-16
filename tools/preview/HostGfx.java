package com.filmlab.camera;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/** Gfx on Java2D, for rendering the camera's screens on a Mac (tools/preview). */
final class HostGfx implements Gfx {
    private final Graphics2D g;
    private final Font[] fonts = new Font[4];

    HostGfx(Graphics2D g, File fontDir) throws Exception {
        this.g = g;
        String[] files = { "inter-display-light.ttf", "inter-regular.ttf", "inter-medium.ttf", "plex-mono-medium.ttf" };
        for (int i = 0; i < 4; i++) fonts[i] = Font.createFont(Font.TRUETYPE_FONT, new File(fontDir, files[i]));
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private void color(int c) { g.setColor(new Color(c, true)); }

    public void rect(float x, float y, float w, float h, int c) { color(c); g.fill(new Rectangle2D.Float(x, y, w, h)); }

    public void roundRect(float x, float y, float w, float h, float r, int c) {
        color(c); g.fill(new RoundRectangle2D.Float(x, y, w, h, r * 2, r * 2));
    }

    public void strokeRoundRect(float x, float y, float w, float h, float r, float width, int c) {
        color(c); g.setStroke(new BasicStroke(width)); g.draw(new RoundRectangle2D.Float(x, y, w, h, r * 2, r * 2));
    }

    public void circle(float cx, float cy, float r, int c) { color(c); g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2)); }

    public void strokeCircle(float cx, float cy, float r, float width, int c) {
        color(c); g.setStroke(new BasicStroke(width)); g.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
    }

    public void line(float x1, float y1, float x2, float y2, float width, int c) {
        color(c); g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(new Line2D.Float(x1, y1, x2, y2));
    }

    // tracking is drawn character by character, exactly as AndroidGfx does (Android 2.3 has no letter spacing)
    public void text(String s, float x, float y, int font, float size, int c, int align, int tracking) {
        Font f = fonts[font].deriveFont(size);
        g.setFont(f);
        color(c);
        float w = textWidth(s, font, size, tracking);
        if (align == CENTER) x -= w / 2; else if (align == RIGHT) x -= w;
        if (tracking == 0) { g.drawString(s, x, y); return; }
        for (int i = 0; i < s.length(); i++) {
            String ch = s.substring(i, i + 1);
            g.drawString(ch, x, y);
            x += (float) f.getStringBounds(ch, g.getFontRenderContext()).getWidth() + size * tracking / 1000f;
        }
    }

    public float textWidth(String s, int font, float size, int tracking) {
        Font f = fonts[font].deriveFont(size);
        if (tracking == 0) return (float) f.getStringBounds(s, g.getFontRenderContext()).getWidth();
        float w = 0;
        for (int i = 0; i < s.length(); i++) w += (float) f.getStringBounds(s.substring(i, i + 1), g.getFontRenderContext()).getWidth();
        return w + size * tracking / 1000f * Math.max(0, s.length() - 1);
    }

    public void image(Object img, float x, float y, float w, float h, boolean red) {
        BufferedImage b = (BufferedImage) img;
        float iw = b.getWidth(), ih = b.getHeight(), sc = Math.max(w / iw, h / ih);
        float sw = w / sc, sh = h / sc, sx = (iw - sw) / 2, sy = (ih - sh) / 2;
        g.drawImage(b, Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h),
                Math.round(sx), Math.round(sy), Math.round(sx + sw), Math.round(sy + sh), null);
        if (red) {   // approximate the safelight tint for previews
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f));
            color(0xFF3A0905);
            g.fill(new Rectangle2D.Float(x, y, w, h));
            g.setComposite(AlphaComposite.SrcOver);
        }
    }

    public int imageWidth(Object img) { return ((BufferedImage) img).getWidth(); }
    public int imageHeight(Object img) { return ((BufferedImage) img).getHeight(); }
}
