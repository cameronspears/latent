package com.latent.camera;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * What a photo was shot with, from its EXIF (v0.48.0): Creative Style, contrast, saturation and sharpness, the white
 * balance shift, DRO, Picture Effect and HDR, from Sony's makernote ("SONY DSC"), else the standard EXIF tags. Every
 * frame Latent develops is checked against neutral, so each one says on the card whether Latent's neutral held. Reads
 * the first 64 KB of the file (the EXIF block); no camera call.
 */
final class PictureCheck {
    private PictureCheck() {}

    static final int HEAD = 65536;

    /** One photo's picture settings. Unknown values are Integer.MIN_VALUE (null for the style). */
    static final class Result {
        String style;
        int contrast = Integer.MIN_VALUE, saturation = Integer.MIN_VALUE, sharpness = Integer.MIN_VALUE;
        int shiftAB = Integer.MIN_VALUE, shiftGM = Integer.MIN_VALUE, dro = Integer.MIN_VALUE, effect = Integer.MIN_VALUE, hdr = Integer.MIN_VALUE;
        boolean makernote;   // the exact values came from Sony's makernote (else EXIF's normal / low / high)

        /** Standard 0 / 0 / 0, no white balance shift, DRO, effect or HDR (what isn't recorded doesn't count against it). */
        boolean neutral() {
            return (style == null || style.equals("Standard")) && zero(contrast) && zero(saturation) && zero(sharpness)
                    && zero(shiftAB) && zero(shiftGM) && zero(dro) && zero(effect) && (hdr == Integer.MIN_VALUE || (hdr & 0xffff) == 0);
        }

        private static boolean zero(int v) { return v == Integer.MIN_VALUE || v == 0; }

        /** "Standard 0/0/0, WB shift 0/0, DRO off: neutral", or what isn't: "not neutral: contrast -1, saturation +1, ...". */
        @Override public String toString() {
            StringBuilder b = new StringBuilder();
            b.append(style != null ? style : "style ?").append(' ').append(n(contrast)).append('/').append(n(saturation)).append('/').append(n(sharpness));
            b.append(", WB shift ").append(shiftAB == Integer.MIN_VALUE ? "?" : shift(shiftAB, "A", "B") + " \u00b7 " + shift(shiftGM, "M", "G"));
            b.append(", DRO ").append(dro == Integer.MIN_VALUE ? "?" : dro == 0 ? "off" : String.valueOf(dro));
            if (effect != Integer.MIN_VALUE && effect != 0) b.append(", effect ").append(effect);
            if (hdr != Integer.MIN_VALUE && (hdr & 0xffff) != 0) b.append(", HDR ").append(hdr);
            if (!makernote) b.append(" (EXIF only)");
            return b.append(neutral() ? ": neutral" : ": NOT neutral").toString();
        }

        private static String n(int v) { return v == Integer.MIN_VALUE ? "?" : v > 0 ? "+" + v : String.valueOf(v); }

        /** As the menus say a shift, amber and magenta positive (CameraSettings' words; that class loads native code). */
        private static String shift(int v, String plus, String minus) { return v == 0 ? "0" : v > 0 ? plus + v : minus + (-v); }
    }

    /** The photo's settings, or null when its EXIF can't be read. */
    static Result read(File jpeg) {
        byte[] d = new byte[HEAD];
        int len = 0;
        try {
            FileInputStream in = new FileInputStream(jpeg);
            try {
                for (int r; len < d.length && (r = in.read(d, len, d.length - len)) > 0; ) len += r;
            } finally {
                in.close();
            }
            return parse(d, len);
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {   // a damaged EXIF block: unread, never the develop's end
            return null;
        }
    }

    /** The settings from a JPEG's first len bytes, or null. */
    static Result parse(byte[] d, int len) {
        if (len < 4 || (d[0] & 0xff) != 0xFF || (d[1] & 0xff) != 0xD8) return null;
        int p = 2;
        while (p + 4 <= len && (d[p] & 0xff) == 0xFF) {
            int marker = d[p + 1] & 0xff, size = ((d[p + 2] & 0xff) << 8) | (d[p + 3] & 0xff);
            if (marker == 0xDA || size < 2) return null;   // the picture started: no EXIF
            if (marker == 0xE1 && p + 10 <= len && d[p + 4] == 'E' && d[p + 5] == 'x' && d[p + 6] == 'i' && d[p + 7] == 'f') return tiff(d, Math.min(len, p + 2 + size), p + 10);
            p += 2 + size;
        }
        return null;
    }

    private static Result tiff(byte[] d, int end, int t) {
        if (t + 8 > end) return null;
        boolean le = d[t] == 'I';
        Result r = new Result();
        int ifd0 = t + u32(d, t + 4, le);
        int exif = find(d, end, t, ifd0, le, 0x8769);
        if (exif < 0) return null;
        int ex = t + u32(d, exif + 8, le);
        int c = find(d, end, t, ex, le, 0xA408), s = find(d, end, t, ex, le, 0xA409), h = find(d, end, t, ex, le, 0xA40A);
        if (c >= 0) r.contrast = exifLevel(u16(d, c + 8, le));
        if (s >= 0) r.saturation = exifLevel(u16(d, s + 8, le));
        if (h >= 0) r.sharpness = exifLevel(u16(d, h + 8, le));
        int mn = find(d, end, t, ex, le, 0x927C);
        if (mn < 0) return r;
        int mo = t + u32(d, mn + 8, le);
        if (mo + 14 > end || !"SONY DSC ".equals(new String(d, mo, 9, java.nio.charset.Charset.forName("US-ASCII")))) return r;
        int m = mo + 12;   // Sony's makernote: an IFD right after its name, offsets from the TIFF header
        r.makernote = true;
        int e;   // each only in the form the A5100 writes it (other bodies use some of these numbers otherwise)
        if ((e = entry(d, end, t, m, le, 0x2004, 9, 1)) >= 0) r.contrast = value(d, end, t, e, le, 0);
        if ((e = entry(d, end, t, m, le, 0x2005, 9, 1)) >= 0) r.saturation = value(d, end, t, e, le, 0);
        if ((e = entry(d, end, t, m, le, 0x2006, 9, 1)) >= 0) r.sharpness = value(d, end, t, e, le, 0);
        if ((e = entry(d, end, t, m, le, 0x2014, 9, 2)) >= 0) { r.shiftAB = value(d, end, t, e, le, 0); r.shiftGM = value(d, end, t, e, le, 1); }
        if ((e = entry(d, end, t, m, le, 0xB025, 4, 1)) >= 0) r.dro = value(d, end, t, e, le, 0);
        if ((e = entry(d, end, t, m, le, 0x200E, 3, 1)) >= 0) r.effect = value(d, end, t, e, le, 0);
        if ((e = entry(d, end, t, m, le, 0x200A, 4, 1)) >= 0) r.hdr = value(d, end, t, e, le, 0);
        if ((e = entry(d, end, t, m, le, 0xB020, 2, -1)) >= 0) r.style = text(d, end, t, e, le);
        if (r.style != null && r.style.length() == 0) r.style = null;
        return r;
    }

    /** EXIF's 0 normal, 1 low (soft), 2 high (hard), as -1 / 0 / +1. */
    private static int exifLevel(int v) { return v == 0 ? 0 : v == 1 ? -1 : v == 2 ? 1 : Integer.MIN_VALUE; }

    /** The entry for tag, only of this type and count (-1: any count), or -1. */
    private static int entry(byte[] d, int end, int t, int ifd, boolean le, int tag, int type, int count) {
        int e = find(d, end, t, ifd, le, tag);
        if (e < 0 || u16(d, e + 2, le) != type || (count >= 0 && u32(d, e + 4, le) != count)) return -1;
        return e;
    }

    /** The entry for tag in the IFD at ifd (its 12-byte record's offset), or -1. */
    private static int find(byte[] d, int end, int t, int ifd, boolean le, int tag) {
        if (ifd < t || ifd + 2 > end) return -1;
        int n = u16(d, ifd, le);
        for (int i = 0; i < n; i++) {
            int e = ifd + 2 + 12 * i;
            if (e + 12 > end) return -1;
            if (u16(d, e, le) == tag) return e;
        }
        return -1;
    }

    /** The k-th number of an entry (BYTE, SHORT, LONG, SBYTE, SSHORT, SLONG; signed as its type says). */
    private static int value(byte[] d, int end, int t, int e, boolean le, int k) {
        int type = u16(d, e + 2, le), count = u32(d, e + 4, le);
        int size = type == 1 || type == 6 ? 1 : type == 3 || type == 8 ? 2 : type == 4 || type == 9 ? 4 : 0;
        if (size == 0 || k >= count) return Integer.MIN_VALUE;
        int at = size * count <= 4 ? e + 8 : t + u32(d, e + 8, le);
        at += size * k;
        if (at < 0 || at + size > end) return Integer.MIN_VALUE;
        switch (type) {
            case 1: return d[at] & 0xff;
            case 6: return d[at];
            case 3: return u16(d, at, le);
            case 8: return (short) u16(d, at, le);
            default: return u32(d, at, le);   // LONG's top bit never set here; SLONG as is
        }
    }

    private static String text(byte[] d, int end, int t, int e, boolean le) {
        int count = u32(d, e + 4, le);
        int at = count <= 4 ? e + 8 : t + u32(d, e + 8, le);
        if (count <= 0 || at < 0 || at + count > end) return null;
        int n = 0;
        while (n < count && d[at + n] != 0) n++;
        return new String(d, at, n, java.nio.charset.Charset.forName("US-ASCII")).trim();
    }

    private static int u16(byte[] d, int i, boolean le) {
        return le ? (d[i] & 0xff) | ((d[i + 1] & 0xff) << 8) : ((d[i] & 0xff) << 8) | (d[i + 1] & 0xff);
    }

    private static int u32(byte[] d, int i, boolean le) {
        return le ? (d[i] & 0xff) | ((d[i + 1] & 0xff) << 8) | ((d[i + 2] & 0xff) << 16) | (d[i + 3] << 24)
                : (d[i] << 24) | ((d[i + 1] & 0xff) << 16) | ((d[i + 2] & 0xff) << 8) | (d[i + 3] & 0xff);
    }
}
