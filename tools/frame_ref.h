/*
 * v0.44.1's live frame path, kept word for word as the reference the composer is tested against (host test
 * builds only: jni/filmcore.c includes this when FL_FRAME_REFERENCE is defined, as tools/test-jni.sh and
 * tools/frame-test.sh do; the camera's library never holds it). RGB decode, the look on the 8.4 table into
 * red << 16 | blue and green apart, the stretch, the blend, the overlay (its own copy, encoded v0.44's way),
 * the blue-noise dither and the packing.
 */

/* ---- the look (v0.44.1 look_pack) */
static void ref_look_pack(const fl_look *L, const unsigned char *rgb, int n, unsigned int *prb, unsigned char *pgo) {
    const int sg = L->n * 3, sb = L->n * L->n * 3;
    int x, pr = -1, pg = -1, pb = -1, r2 = 0, g2 = 0, b2 = 0;
    for (x = 0; x < n; x++, rgb += 3) {
        int r = rgb[0], g = rgb[1], b = rgb[2];
        if (r != pr || g != pg || b != pb) {
            pr = r; pg = g; pb = b;
            if (L->n == 0) {
                r2 = L->tone[r]; g2 = L->tone[g]; b2 = L->tone[b];
            } else {
                const short *c0 = L->data + L->offR[r] + L->offG[g] + L->offB[b], *c1, *c2, *c3;
                int fr = L->frac[r], fg = L->frac[g], fb = L->frac[b], f1, f2, f3;
                if (fr >= fg) {
                    if (fg >= fb)      { c1 = c0 + 3;  c2 = c0 + 3 + sg;  f1 = fr; f2 = fg; f3 = fb; }
                    else if (fr >= fb) { c1 = c0 + 3;  c2 = c0 + 3 + sb;  f1 = fr; f2 = fb; f3 = fg; }
                    else               { c1 = c0 + sb; c2 = c0 + 3 + sb;  f1 = fb; f2 = fr; f3 = fg; }
                } else {
                    if (fb >= fg)      { c1 = c0 + sb; c2 = c0 + sg + sb; f1 = fb; f2 = fg; f3 = fr; }
                    else if (fb >= fr) { c1 = c0 + sg; c2 = c0 + sg + sb; f1 = fg; f2 = fb; f3 = fr; }
                    else               { c1 = c0 + sg; c2 = c0 + 3 + sg;  f1 = fg; f2 = fr; f3 = fb; }
                }
                c3 = c0 + 3 + sg + sb;
                r2 = ((c0[0] << 8) + f1 * (c1[0] - c0[0]) + f2 * (c2[0] - c1[0]) + f3 * (c3[0] - c2[0]) + 2048) >> 12;
                g2 = ((c0[1] << 8) + f1 * (c1[1] - c0[1]) + f2 * (c2[1] - c1[1]) + f3 * (c3[1] - c2[1]) + 2048) >> 12;
                b2 = ((c0[2] << 8) + f1 * (c1[2] - c0[2]) + f2 * (c2[2] - c1[2]) + f3 * (c3[2] - c2[2]) + 2048) >> 12;
            }
        }
        prb[x] = (unsigned int)r2 << 16 | (unsigned int)b2;
        pgo[x] = (unsigned char)g2;
    }
}

/* ---- the overlay, encoded as v0.44.1 did */
typedef struct {
    int w, h;
    unsigned int *base_rb, *base_ga;
    int *row_runs, *runs;
    unsigned int *px_rb, *px_ga;
    int pixels;
} ref_overlay;

static ref_overlay *g_ref_ovl;   /* set with the real overlay (fl_set_overlay); read by ref_frame on the test's thread */

static void ref_ovl_encode(unsigned int p, unsigned int *rb, unsigned int *ga) {
    int a = (int)(p >> 24), ia = 255 - a;
    *rb = (unsigned int)div255(((p >> 16) & 255) * a) << 16 | (unsigned int)div255((p & 255) * a);
    *ga = (unsigned int)(div255(((p >> 8) & 255) * a) | (ia + (ia >> 7)) << 8);
}

static void ref_ovl_free(ref_overlay *o) {
    if (o) { free(o->base_rb); free(o->base_ga); free(o->row_runs); free(o->runs); free(o->px_rb); free(o->px_ga); free(o); }
}

static void ref_set_overlay(const unsigned int *argb, int w, int h) {
    ref_overlay *o = NULL;
    if (argb && w >= 1 && h >= 1) {
        int x, y, n = 0;
        o = (ref_overlay *)calloc(1, sizeof *o);
        o->w = w; o->h = h;
        o->base_rb = (unsigned int *)malloc(sizeof(unsigned int) * h);
        o->base_ga = (unsigned int *)malloc(sizeof(unsigned int) * h);
        o->row_runs = (int *)malloc(sizeof(int) * (h + 1));
        o->runs = (int *)malloc(sizeof(int) * 3 * (size_t)h * (w / 2 + 1));
        o->px_rb = (unsigned int *)malloc(sizeof(unsigned int) * (size_t)w * h);
        o->px_ga = (unsigned int *)malloc(sizeof(unsigned int) * (size_t)w * h);
        for (y = 0; y < h; y++) {
            const unsigned int *r = argb + (size_t)y * w;
            unsigned int cand = r[0], brb, bga;
            int votes = 0, count = 0, in = 0;
            for (x = 0; x < w; x++) { if (votes == 0) { cand = r[x]; votes = 1; } else votes += r[x] == cand ? 1 : -1; }
            for (x = 0; x < w; x++) count += r[x] == cand;
            if (count * 2 <= w || (cand >> 24) == 0) cand = 0;
            ref_ovl_encode(cand, &brb, &bga);
            o->base_rb[y] = brb;
            o->base_ga[y] = bga;
            o->row_runs[y] = n;
            for (x = 0; x <= w; x++) {
                int differs = x < w && r[x] != cand && !((r[x] >> 24) == 0 && cand == 0);
                if (differs && !in) { o->runs[n * 3] = x; o->runs[n * 3 + 2] = o->pixels; in = 1; }
                else if (!differs && in) { o->runs[n * 3 + 1] = x; n++; in = 0; }
                if (differs) { ref_ovl_encode(r[x], o->px_rb + o->pixels, o->px_ga + o->pixels); o->pixels++; }
            }
        }
        o->row_runs[h] = n;
    }
    ref_ovl_free(g_ref_ovl);
    g_ref_ovl = o;
}

/* ---- the compose (v0.44.1 stretch_rb, blend_rows, base_span, overlay_row, dither_row, pack_tables, frame_compose) */
static void ref_stretch_rb(const unsigned int *srb, const unsigned char *sg, const int *xi, const int *xw, int ow,
                           unsigned int *hrb, unsigned char *hg) {
    int x;
    for (x = 0; x < ow; x++) {
        int i = xi[x], wx = xw[x], ux = 256 - wx;
        hrb[x] = ((srb[i] * (unsigned)ux + srb[i + 1] * (unsigned)wx + 0x00800080u) >> 8) & 0x00FF00FFu;
        hg[x] = (unsigned char)((sg[i] * ux + sg[i + 1] * wx + 128) >> 8);
    }
}

static void ref_blend_rows(const unsigned int *h0rb, const unsigned int *h1rb, const unsigned char *h0g, const unsigned char *h1g,
                           unsigned int uy, unsigned int wy, int ow, unsigned int *lrb, unsigned char *lg) {
    int x;
    if (wy == 0) {
        memcpy(lrb, h0rb, sizeof(unsigned int) * ow);
        memcpy(lg, h0g, (size_t)ow);
        return;
    }
    for (x = 0; x < ow; x++) {
        lrb[x] = ((h0rb[x] * uy + h1rb[x] * wy + 0x00800080u) >> 8) & 0x00FF00FFu;
        lg[x] = (unsigned char)((h0g[x] * uy + h1g[x] * wy + 128) >> 8);
    }
}

static void ref_base_span(unsigned int *lrb, unsigned char *lg, unsigned int brb, unsigned int bga, int x, int x1) {
    unsigned int ia = bga >> 8, bg = bga & 255;
    for (; x < x1; x++) {
        lrb[x] = (((lrb[x] * ia) >> 8) & 0x00FF00FFu) + brb;
        lg[x] = (unsigned char)(((lg[x] * ia) >> 8) + bg);
    }
}

static void ref_overlay_row(const ref_overlay *o, int y, unsigned int *lrb, unsigned char *lg, int ow) {
    unsigned int brb = o->base_rb[y], bga = o->base_ga[y];
    int base = (bga >> 8) < 256, k, k1 = o->row_runs[y + 1], x = 0;
    for (k = o->row_runs[y]; k < k1; k++) {
        const int *rn = o->runs + k * 3;
        const unsigned int *prb = o->px_rb + rn[2] - rn[0];
        const unsigned int *pga = o->px_ga + rn[2] - rn[0];
        int i;
        if (base) ref_base_span(lrb, lg, brb, bga, x, rn[0]);
        for (i = rn[0]; i < rn[1]; i++) {
            unsigned int ga = pga[i], ia = ga >> 8;
            lrb[i] = (((lrb[i] * ia) >> 8) & 0x00FF00FFu) + prb[i];
            lg[i] = (unsigned char)(((lg[i] * ia) >> 8) + (ga & 255));
        }
        x = rn[1];
    }
    if (base) ref_base_span(lrb, lg, brb, bga, x, ow);
}

static void ref_dither_row(const unsigned int *lrb, const unsigned char *lg, const unsigned char *bn, int ox, int ow,
                           unsigned short *o16, const unsigned short *trb, const unsigned short *tg) {
    unsigned int tt[64];
    int x;
    const unsigned int m = 0x000F000Fu;
    for (x = 0; x < 64; x++) { unsigned int t = bn[(x + ox) & 63] >> 4; tt[x] = t | t << 16; }
#define REF_ONE(X) (trb[((((((lrb[X] * 241u) >> 8) & 0x00FF00FFu) + tt[(X) & 63]) >> 4) & m) >> 12 & 0xF0 \
                        | (((((lrb[X] * 241u) >> 8) & 0x00FF00FFu) + tt[(X) & 63]) >> 4 & 15)]      \
                    | tg[(((lg[X] * 241u) >> 8) + tt[(X) & 63]) >> 4 & 15])
    for (x = 0; x < ow; x++) o16[x] = (unsigned short)REF_ONE(x);
#undef REF_ONE
}

static void ref_pack_tables(int f565, int rs, int gs, int bs, unsigned short alpha, unsigned short *trb, unsigned short *tg) {
    int r, b;
    for (r = 0; r < 16; r++) {
        for (b = 0; b < 16; b++)
            trb[r << 4 | b] = f565 ? (unsigned short)(((r << 1) | (r >> 3)) << 11 | ((b << 1) | (b >> 3)))
                                   : (unsigned short)(r << rs | b << bs | alpha);
        tg[r] = f565 ? (unsigned short)(((r << 2) | (r >> 2)) << 5) : (unsigned short)(r << gs);
    }
}

/* v0.44.1's frame_compose over a looked frame (prb, pg: w x h), with o (or NULL) laid in. 0, or -1. */
static int ref_compose(const unsigned int *prb, const unsigned char *pg, int w, int h, const ref_overlay *o,
                       unsigned short *out, int ow, int oh, int stride, int aspect1000, int format, const int *shifts, int ox, int oy) {
    int x, y, held[2], rs = 11, gs = 5, bs = 0, as = 0, use_ovl, f565 = format != FL_FMT_4444;
    unsigned int *hrb[2], *lrb, *hrbm;
    unsigned char *hg[2], *lg, *hgm;
    int *xi, *xw;
    unsigned short alpha = 0, trb[256], tg[16];
    double sw, sh, sx0, sy0;
    if (w < 2 || h < 2 || ow < 1 || oh < 1 || stride < ow) return -1;
    if (!f565) {
        if (!shifts) return -1;
        rs = shifts[0]; gs = shifts[1]; bs = shifts[2]; as = shifts[3];
        alpha = (unsigned short)(15 << as);
    }
    ref_pack_tables(f565, rs, gs, bs, alpha, trb, tg);
    use_ovl = o && o->w == ow && o->h == oh;
    xi = (int *)malloc(sizeof(int) * ow * 2);
    hrbm = (unsigned int *)malloc(sizeof(unsigned int) * ow * 3);
    hgm = (unsigned char *)malloc((size_t)ow * 3);
    if (!xi || !hrbm || !hgm) { free(xi); free(hrbm); free(hgm); return -1; }
    xw = xi + ow;
    hrb[0] = hrbm; hrb[1] = hrbm + ow; lrb = hrbm + ow * 2;
    hg[0] = hgm; hg[1] = hgm + ow; lg = hgm + ow * 2;
    held[0] = held[1] = -1;
    {
        double want = aspect1000 > 0 ? aspect1000 / 1000.0 : (double)ow / oh;
        if ((double)w / h > want) { sh = h; sw = h * want; } else { sw = w; sh = w / want; }
        sx0 = (w - sw) / 2; sy0 = (h - sh) / 2;
    }
    for (x = 0; x < ow; x++) {
        double fx = sx0 + (x + 0.5) * sw / ow - 0.5;
        int ix = (int)fx;
        if (fx < 0) { fx = 0; ix = 0; }
        if (ix > w - 2) { ix = w - 2; fx = w - 1; }
        xi[x] = ix;
        xw[x] = (int)((fx - ix) * 256 + 0.5);
    }
    for (y = 0; y < oh; y++) {
        double fy = sy0 + (y + 0.5) * sh / oh - 0.5;
        int iy = (int)fy, wy;
        const unsigned char *bn = fl_bluenoise + (((y + oy) & 63) << 6);
        if (fy < 0) { fy = 0; iy = 0; }
        if (iy > h - 2) { iy = h - 2; fy = h - 1; }
        wy = (int)((fy - iy) * 256 + 0.5);
        if (held[0] != iy) {
            if (held[1] == iy) {
                unsigned int *t = hrb[0]; unsigned char *tgp = hg[0];
                hrb[0] = hrb[1]; hg[0] = hg[1]; hrb[1] = t; hg[1] = tgp;
                held[0] = iy; held[1] = -1;
            } else { ref_stretch_rb(prb + (size_t)iy * w, pg + (size_t)iy * w, xi, xw, ow, hrb[0], hg[0]); held[0] = iy; }
        }
        if (held[1] != iy + 1) { ref_stretch_rb(prb + (size_t)(iy + 1) * w, pg + (size_t)(iy + 1) * w, xi, xw, ow, hrb[1], hg[1]); held[1] = iy + 1; }
        ref_blend_rows(hrb[0], hrb[1], hg[0], hg[1], (unsigned)(256 - wy), (unsigned)wy, ow, lrb, lg);
        if (use_ovl) ref_overlay_row(o, y, lrb, lg, ow);
        ref_dither_row(lrb, lg, bn, ox, ow, out + (size_t)y * stride, trb, tg);
    }
    free(xi); free(hrbm); free(hgm);
    return 0;
}

/* The film as v0.44.1's frame_film kept it: RGB, 17 points. */
static const fl_look *ref_film(fl_frame_ctx *c, const char *lut_path, const int *params, char *err, int errlen) {
    return frame_film(c, lut_path, params, err, errlen) ? &c->film.L : NULL;
}

/* A whole v0.44.1 frame: that film, the RGB decode, the look, the compose. */
static int ref_frame(fl_frame_ctx *c, const unsigned char *jpeg, unsigned long len, const char *lut_path, const int *params,
                     int flags, int seq, unsigned short *out, int ow, int oh, int stride, int aspect1000, int format,
                     const int *shifts, int max_w, int max_h, int *info, char *err, int errlen) {
    const fl_look *L;
    unsigned int *prb;
    unsigned char *pg;
    int ox = 0, oy = 0, rc;
    if (!jpeg) { set_err(err, errlen, "the reference composes from a JPEG only"); return -1; }
    if (!(L = ref_film(c, lut_path, params, err, errlen))) return -1;
    if (frame_decode(c, jpeg, len, max_w, max_h, 1, err, errlen) != 0) return -1;
    prb = (unsigned int *)malloc(sizeof(unsigned int) * (size_t)c->sw * c->sh);
    pg = (unsigned char *)malloc((size_t)c->sw * c->sh);
    if (!prb || !pg) { free(prb); free(pg); set_err(err, errlen, "out of memory"); return -1; }
    ref_look_pack(L, c->rgb, c->sw * c->sh, prb, pg);
    if (flags & FL_F_TEMPORAL) {
        unsigned int hsh = hash32((unsigned int)seq * 2654435761u + 1);
        ox = (int)(hsh & 63); oy = (int)((hsh >> 8) & 63);
    }
    rc = ref_compose(prb, pg, c->sw, c->sh, g_ref_ovl, out, ow, oh, stride, aspect1000, format, shifts, ox, oy);
    free(prb); free(pg);
    if (rc != 0) { set_err(err, errlen, "bad screen size"); return -1; }
    info[0] = c->sw;
    info[1] = c->sh;
    return 0;
}
