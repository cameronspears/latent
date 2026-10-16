/*
 * Film Lab image core. See filmcore.h.
 *
 * Pipeline per pixel:  tone curve (1D, per channel)  ->  3D LUT (tetrahedral)  ->  grain (mono)
 * The tone curve is folded into the LUT's index tables, so it costs nothing at run time.
 */
#include "filmcore.h"

#include <errno.h>
#include <math.h>
#include <pthread.h>
#include <setjmp.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include "jpeglib.h"
#include "bluenoise.h"

#define LUT_MAX 65
/* LUTs are resampled to this grid when loaded: 17^3 entries x 3 shorts = 29 KB, which fits the
 * Cortex-A5's L1 data cache; a 33-point LUT (215 KB) misses cache on most pixels. */
#ifndef FL_LUT_GRID
#define FL_LUT_GRID 17
#endif
#define TILE_BITS 10
#define TILE (1 << TILE_BITS)
#define TILE_MASK (TILE - 1)
#define GRAIN_STD 40          /* standard deviation of the noise tile, in tile units */
#define THUMB_W 160
#define THUMB_H 120
#define PREVIEW_W 720             /* viewer previews: 3:2 at the screen's full 480 lines (the A5100's */
#define PREVIEW_H 480             /* panel is 16:9, so a 3:2 photo fills its height)              */

/* ------------------------------------------------------------------------------------------ */
/* errors                                                                                       */

static void set_err(char *err, int errlen, const char *fmt, ...) {
    va_list ap;
    if (!err || errlen <= 0) return;
    va_start(ap, fmt);
    vsnprintf(err, errlen, fmt, ap);
    va_end(ap);
}

struct fl_jerr {
    struct jpeg_error_mgr pub;
    jmp_buf jump;
    char msg[JMSG_LENGTH_MAX];
};

static void fl_error_exit(j_common_ptr cinfo) {
    struct fl_jerr *e = (struct fl_jerr *)cinfo->err;
    (*cinfo->err->format_message)(cinfo, e->msg);
    longjmp(e->jump, 1);
}

static void fl_output_message(j_common_ptr cinfo) { (void)cinfo; }   /* warnings: stay quiet */

static void fl_jerr_init(struct fl_jerr *e) {
    jpeg_std_error(&e->pub);
    e->pub.error_exit = fl_error_exit;
    e->pub.output_message = fl_output_message;
    e->msg[0] = 0;
}

/* ------------------------------------------------------------------------------------------ */
/* look: tone curve + LUT                                                                        */

typedef struct {
    int n;                    /* LUT size, 0 = no LUT */
    short *data;              /* n^3 * 3 values as 8.4 fixed point (0..4080), red fastest (.cube order) */
    unsigned char tone[256];  /* tone curve, used directly when there is no LUT */
    int idx[256];             /* per input value: LUT base index after the tone curve */
    int frac[256];            /* per input value: 0..256 position between base and base+1 */
    int offR[256], offG[256], offB[256];   /* per input value: offset of its base cell in data */
    int shared;               /* data belongs to the LUT cache, not to this look */
} fl_look;

static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

/* Fuji-style highlight / shadow tone. Shadow bump peaks at 25% grey, highlight bump at 75%.
 * One step moves the peak by 2.4% of the range; +-4 stays monotonic. */
static void build_tone(fl_look *L, int highlight, int shadow) {
    int v;
    if (highlight < -4) highlight = -4; if (highlight > 4) highlight = 4;
    if (shadow < -4) shadow = -4; if (shadow > 4) shadow = 4;
    for (v = 0; v < 256; v++) {
        double x = v / 255.0;
        double bs = x * (1 - x) * (1 - x) * (1 - x) * (256.0 / 27.0);
        double bh = x * x * x * (1 - x) * (256.0 / 27.0);
        double y = x + 0.024 * highlight * bh - 0.024 * shadow * bs;
        L->tone[v] = (unsigned char)(clamp01(y) * 255.0 + 0.5);
    }
}

static void build_index(fl_look *L) {
    int v;
    if (L->n < 2) return;
    for (v = 0; v < 256; v++) {
        int t = L->tone[v];
        int pos = t * (L->n - 1) * 256 / 255;      /* fixed point 8.8 */
        int i = pos >> 8;
        int f = pos & 255;
        if (i >= L->n - 1) { i = L->n - 2; f = 256; }
        L->idx[v] = i;
        L->frac[v] = f;
        L->offR[v] = i * 3;
        L->offG[v] = i * L->n * 3;
        L->offB[v] = i * L->n * L->n * 3;
    }
}

/* Minimal .cube reader: LUT_3D_SIZE, optional TITLE / DOMAIN_* (domain assumed 0..1), data rows. */
/* Trilinear resample of an n-point LUT to m points (exact where the grids line up, e.g. 33 -> 17). */
static short *resample_lut(const short *src, int n, int m) {
    short *dst = (short *)malloc(sizeof(short) * 3 * m * m * m);
    int i, j, k, c;
    if (!dst) return NULL;
    for (k = 0; k < m; k++) for (j = 0; j < m; j++) for (i = 0; i < m; i++) {
        double fb = (double)k * (n - 1) / (m - 1), fg = (double)j * (n - 1) / (m - 1), fr = (double)i * (n - 1) / (m - 1);
        int b0 = (int)fb, g0 = (int)fg, r0 = (int)fr;
        int b1 = b0 + 1 < n ? b0 + 1 : b0, g1 = g0 + 1 < n ? g0 + 1 : g0, r1 = r0 + 1 < n ? r0 + 1 : r0;
        double db = fb - b0, dg = fg - g0, dr = fr - r0;
        for (c = 0; c < 3; c++) {
#define AT(b, g, r) src[(((b) * n + (g)) * n + (r)) * 3 + c]
            double v = (1 - db) * ((1 - dg) * ((1 - dr) * AT(b0, g0, r0) + dr * AT(b0, g0, r1)) + dg * ((1 - dr) * AT(b0, g1, r0) + dr * AT(b0, g1, r1)))
                     + db * ((1 - dg) * ((1 - dr) * AT(b1, g0, r0) + dr * AT(b1, g0, r1)) + dg * ((1 - dr) * AT(b1, g1, r0) + dr * AT(b1, g1, r1)));
#undef AT
            dst[((k * m + j) * m + i) * 3 + c] = (short)(v + 0.5);
        }
    }
    return dst;
}

static int load_cube(fl_look *L, const char *path, char *err, int errlen) {
    FILE *f;
    char line[256];
    int n = 0, count = 0, total = 0;
    L->n = 0;
    L->data = NULL;
    if (!path || !path[0]) return 0;
    f = fopen(path, "r");
    if (!f) { set_err(err, errlen, "cannot open look %s", path); return -1; }
    while (fgets(line, sizeof line, f)) {
        char *p = line;
        float r, g, b;
        while (*p == ' ' || *p == '\t') p++;
        if (*p == '#' || *p == '\r' || *p == '\n' || *p == 0) continue;
        if (strncmp(p, "LUT_3D_SIZE", 11) == 0) {
            n = atoi(p + 11);
            if (n < 2 || n > LUT_MAX) { fclose(f); set_err(err, errlen, "unsupported LUT size %d", n); return -1; }
            total = n * n * n;
            L->data = (short *)malloc(sizeof(short) * 3 * total);
            if (!L->data) { fclose(f); set_err(err, errlen, "out of memory for LUT"); return -1; }
            continue;
        }
        if ((*p >= 'A' && *p <= 'Z') || (*p >= 'a' && *p <= 'z')) continue;   /* TITLE, DOMAIN_MIN ... */
        if (!L->data) continue;
        if (sscanf(p, "%f %f %f", &r, &g, &b) == 3 && count < total) {
            L->data[count * 3 + 0] = (short)(clamp01(r) * 4080.0 + 0.5);
            L->data[count * 3 + 1] = (short)(clamp01(g) * 4080.0 + 0.5);
            L->data[count * 3 + 2] = (short)(clamp01(b) * 4080.0 + 0.5);
            count++;
        }
    }
    fclose(f);
    if (!L->data) { set_err(err, errlen, "no LUT_3D_SIZE in %s", path); return -1; }
    if (count != total) {
        free(L->data); L->data = NULL;
        set_err(err, errlen, "LUT %s has %d of %d entries", path, count, total);
        return -1;
    }
    if (n > FL_LUT_GRID) {
        short *small = resample_lut(L->data, n, FL_LUT_GRID);
        if (!small) { free(L->data); L->data = NULL; set_err(err, errlen, "out of memory for LUT"); return -1; }
        free(L->data);
        L->data = small;
        n = FL_LUT_GRID;
    }
    L->n = n;
    return 0;
}

/* The last LUT read, kept for the life of the process: parsing a 33-point .cube (36,000 text lines)
 * took ~3 s per frame on the camera. Keyed by path, size and modification time, so an edited file
 * on the card is read again. Only the worker thread loads looks, so no locking is needed. */
static struct { char path[512]; long size, mtime; int n; short *data; } g_lut;

static int look_init(fl_look *L, const char *lut_path, const int *params, char *err, int errlen) {
    struct stat st;
    memset(L, 0, sizeof *L);
    build_tone(L, params[FL_PARAM_HIGHLIGHT], params[FL_PARAM_SHADOW]);
    if (lut_path && lut_path[0] && stat(lut_path, &st) == 0 && g_lut.data && strcmp(g_lut.path, lut_path) == 0
            && g_lut.size == (long)st.st_size && g_lut.mtime == (long)st.st_mtime) {
        L->n = g_lut.n;
        L->data = g_lut.data;
        L->shared = 1;
    } else {
        if (load_cube(L, lut_path, err, errlen) != 0) return -1;
        if (L->data && strlen(lut_path) < sizeof g_lut.path && stat(lut_path, &st) == 0) {
            free(g_lut.data);                    /* hand the new LUT to the cache */
            strcpy(g_lut.path, lut_path);
            g_lut.size = (long)st.st_size;
            g_lut.mtime = (long)st.st_mtime;
            g_lut.n = L->n;
            g_lut.data = L->data;
            L->shared = 1;
        }
    }
    build_index(L);
    return 0;
}

static void look_free(fl_look *L) { if (!L->shared) free(L->data); L->data = NULL; }

/* Tetrahedral interpolation, one pixel. */
static void look_apply(const fl_look *L, unsigned char *px) {
    int n = L->n, ri, gi, bi, fr, fg, fb, c, sn = 3, sg, sb;
    const short *p000, *p100, *p010, *p001, *p110, *p101, *p011, *p111;
    if (n == 0) {
        px[0] = L->tone[px[0]]; px[1] = L->tone[px[1]]; px[2] = L->tone[px[2]];
        return;
    }
    ri = L->idx[px[0]]; fr = L->frac[px[0]];
    gi = L->idx[px[1]]; fg = L->frac[px[1]];
    bi = L->idx[px[2]]; fb = L->frac[px[2]];
    sg = n * 3; sb = n * n * 3;
    p000 = L->data + bi * sb + gi * sg + ri * sn;
    p100 = p000 + sn; p010 = p000 + sg; p001 = p000 + sb;
    p110 = p100 + sg; p101 = p100 + sb; p011 = p010 + sb; p111 = p110 + sb;
    for (c = 0; c < 3; c++) {
        int v;
        if (fr >= fg) {
            if (fg >= fb)      v = p000[c] * 256 + fr * (p100[c] - p000[c]) + fg * (p110[c] - p100[c]) + fb * (p111[c] - p110[c]);
            else if (fr >= fb) v = p000[c] * 256 + fr * (p100[c] - p000[c]) + fb * (p101[c] - p100[c]) + fg * (p111[c] - p101[c]);
            else               v = p000[c] * 256 + fb * (p001[c] - p000[c]) + fr * (p101[c] - p001[c]) + fg * (p111[c] - p101[c]);
        } else {
            if (fb >= fg)      v = p000[c] * 256 + fb * (p001[c] - p000[c]) + fg * (p011[c] - p001[c]) + fr * (p111[c] - p011[c]);
            else if (fb >= fr) v = p000[c] * 256 + fg * (p010[c] - p000[c]) + fb * (p011[c] - p010[c]) + fr * (p111[c] - p011[c]);
            else               v = p000[c] * 256 + fg * (p010[c] - p000[c]) + fr * (p110[c] - p010[c]) + fb * (p111[c] - p110[c]);
        }
        /* v is 8.4 fixed point * 256: to 8 bit with rounding. No division: the camera's ARM core
         * has no divide instruction, and this runs three times per pixel. */
        v = (v + 2048) >> 12;
        px[c] = (unsigned char)(v < 0 ? 0 : v > 255 ? 255 : v);
    }
}

/* ------------------------------------------------------------------------------------------ */
/* grain                                                                                         */

typedef struct {
    const signed char *tile;  /* TILE x TILE, ~gaussian, std GRAIN_STD (cached, see grain_tile) */
    int amp[256];             /* per output luma: multiplier, delta = tile * amp >> 8 */
    int on;
    int ox, oy;               /* per-frame tile offset */
} fl_grain;

static unsigned int hash32(unsigned int x) {     /* lowbias32 */
    x ^= x >> 16; x *= 0x7feb352dU;
    x ^= x >> 15; x *= 0x846ca68bU;
    x ^= x >> 16;
    return x;
}

/* triangular-distributed value in about -32768..32767 (std ~ 13377) */
static int lattice(int x, int y, unsigned int seed) {
    unsigned int h = hash32((unsigned int)x * 0x8da6b343U ^ (unsigned int)y * 0xd8163841U ^ seed);
    return (int)(h & 0xffff) + (int)(h >> 16) - 65535;
}

/* The noise tile depends only on the grain size, so it is built once per size and kept for the
 * life of the process (1 MB each): every preview and save after the first skips ~1M hashes and
 * divisions. Each photo still gets its own pattern from a random offset into the tile. */
static signed char *g_tiles[4];

static const signed char *grain_tile(int size) {
    const unsigned int seed = 0x2545f491U;
    int *raw, x, y, v, cells, step, wrap;
    long long sumsq = 0;
    double stdv, scale;
    signed char *tile;
    if (g_tiles[size]) return g_tiles[size];
    raw = (int *)malloc(sizeof(int) * TILE * TILE);
    tile = (signed char *)malloc(TILE * TILE);
    if (!raw || !tile) { free(raw); free(tile); return NULL; }
    cells = TILE / size + (TILE % size ? 1 : 0);
    step = 256 / size;               /* fx advances by this per pixel ... */
    wrap = 256 - 256 % size;         /* ... and wraps to the next lattice cell here */

    /* noise is periodic over the tile so it wraps without a seam */
    for (y = 0; y < TILE; y++) {
        int ly = y / size, fy = (y % size) * 256 / size, ly1 = (ly + 1) % cells;
        int lx = 0, fx = 0;
        for (x = 0; x < TILE; x++, fx += step) {
            int fine = lattice(x, y, seed);
            if (fx >= wrap) { fx = 0; lx++; }   /* x / size and x % size without dividing */
            if (size == 1) {
                v = fine;
            } else {
                /* value noise on a lattice of 'size' px (bilinear), plus some per-pixel fine grain */
                int lx1 = lx + 1 == cells ? 0 : lx + 1;
                int a = lattice(lx, ly, seed ^ 0x5bd1e995U), b = lattice(lx1, ly, seed ^ 0x5bd1e995U);
                int c = lattice(lx, ly1, seed ^ 0x5bd1e995U), d = lattice(lx1, ly1, seed ^ 0x5bd1e995U);
                int top = a + (((b - a) * fx) >> 8), bot = c + (((d - c) * fx) >> 8);
                int coarse = top + (((bot - top) * fy) >> 8);
                v = coarse + (size == 2 ? fine / 2 : fine / 3);
            }
            raw[y * TILE + x] = v;
            sumsq += (long long)v * v;
        }
    }
    stdv = sqrt((double)sumsq / (TILE * TILE));
    scale = stdv > 0 ? GRAIN_STD / stdv : 0;
    for (x = 0; x < TILE * TILE; x++) {
        double t = raw[x] * scale;
        tile[x] = (signed char)(t > 127 ? 127 : t < -127 ? -127 : (int)(t < 0 ? t - 0.5 : t + 0.5));
    }
    free(raw);
    g_tiles[size] = tile;
    return tile;
}

static int grain_init(fl_grain *G, const int *params, char *err, int errlen) {
    int strength = params[FL_PARAM_GRAIN], size = params[FL_PARAM_GRAINSIZE], v;
    unsigned int seed = hash32((unsigned int)params[FL_PARAM_SEED] + 0x9e3779b9U);
    memset(G, 0, sizeof *G);
    if (strength <= 0) return 0;
    if (strength > 10) strength = 10;
    if (size < 1) size = 1;
    if (size > 3) size = 3;
    G->tile = grain_tile(size);
    if (!G->tile) { set_err(err, errlen, "out of memory for grain"); return -1; }

    /* Film grain shows most in the midtones, less in deep shadow, least in highlights.
     * Strength 10 -> about 9 levels of std deviation at mid grey. */
    for (v = 0; v < 256; v++) {
        double l = v / 255.0;
        double w = sqrt(4.0 * l * (1.0 - l));
        if (l < 0.5) w = 0.15 + 0.85 * w; else w = w * (1.0 - 0.3 * (l - 0.5));
        G->amp[v] = (int)(strength * 0.9 / GRAIN_STD * 256.0 * w + 0.5);
    }
    G->ox = (int)(hash32(seed ^ 0x1234567U) & TILE_MASK);
    G->oy = (int)(hash32(seed ^ 0x7654321U) & TILE_MASK);
    G->on = 1;
    return 0;
}

static void grain_free(fl_grain *G) { G->tile = NULL; G->on = 0; }   /* the tile stays cached */

/* The hot loop of a develop: look_apply plus grain over a whole row (24M pixels a frame, on one
 * Cortex-A5 core). Same tetrahedral interpolation, arranged for that core: cell offsets come from
 * tables (no multiplies), all three channels share one corner path, no clamping is needed (the
 * result is a weighted average of corners), a pixel equal to the previous one reuses its result
 * (common in skies and shadows), and grain is added while the pixel is still in registers. */
static void look_row(const fl_look *L, const fl_grain *G, unsigned char *row, int width, int y) {
    const int sg = L->n * 3, sb = L->n * L->n * 3;
    const signed char *t = G->on ? G->tile + (((y + G->oy) & TILE_MASK) << TILE_BITS) : NULL;
    int x, pr = -1, pg = -1, pb = -1, r2 = 0, g2 = 0, b2 = 0;
    for (x = 0; x < width; x++, row += 3) {
        int r = row[0], g = row[1], b = row[2];
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
        if (t) {
            int d = (t[(x + G->ox) & TILE_MASK] * G->amp[(r2 * 77 + g2 * 150 + b2 * 29) >> 8]) >> 8;
            int R = r2 + d, Gc = g2 + d, B = b2 + d;
            row[0] = (unsigned char)(R < 0 ? 0 : R > 255 ? 255 : R);
            row[1] = (unsigned char)(Gc < 0 ? 0 : Gc > 255 ? 255 : Gc);
            row[2] = (unsigned char)(B < 0 ? 0 : B > 255 ? 255 : B);
        } else {
            row[0] = (unsigned char)r2; row[1] = (unsigned char)g2; row[2] = (unsigned char)b2;
        }
    }
}

/* ------------------------------------------------------------------------------------------ */
/* decoding helpers                                                                              */

static int check_source(struct jpeg_decompress_struct *d, char *err, int errlen) {
    if (d->progressive_mode) { set_err(err, errlen, "progressive JPEGs are not supported"); return -1; }
    if (d->arith_code) { set_err(err, errlen, "arithmetic-coded JPEGs are not supported"); return -1; }
    if (d->data_precision != 8) { set_err(err, errlen, "only 8-bit JPEGs are supported"); return -1; }
    if (d->num_components != 3 && d->num_components != 1) { set_err(err, errlen, "unsupported colour format"); return -1; }
    if (d->image_width > 20000 || d->image_height > 20000) { set_err(err, errlen, "image too large"); return -1; }
    return 0;
}

/* ------------------------------------------------------------------------------------------ */
/* EXIF thumbnail replacement                                                                    */

static unsigned int rd16(const unsigned char *p, int le) { return le ? (p[0] | p[1] << 8) : (p[0] << 8 | p[1]); }
static unsigned int rd32(const unsigned char *p, int le) {
    return le ? ((unsigned int)p[0] | p[1] << 8 | p[2] << 16 | (unsigned int)p[3] << 24)
              : ((unsigned int)p[0] << 24 | p[1] << 16 | p[2] << 8 | p[3]);
}
static void wr32(unsigned char *p, unsigned int v, int le) {
    if (le) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }
    else { p[0] = v >> 24; p[1] = v >> 16; p[2] = v >> 8; p[3] = v; }
}

/* Locate the IFD1 thumbnail in an APP1 Exif payload. Returns 0 and fills offsets (relative to the
 * APP1 payload start) of the data and of the length field, or -1 if there is none. */
static int find_thumb(const unsigned char *app1, unsigned int len, unsigned int *data_off,
                      unsigned int *data_len, unsigned int *lenfield_off, int *le_out) {
    const unsigned char *t;
    unsigned int tl, ifd0, n0, ifd1, n1, i, off = 0, ln = 0, lnf = 0;
    int le;
    if (len < 14 || memcmp(app1, "Exif\0\0", 6) != 0) return -1;
    t = app1 + 6; tl = len - 6;
    if (t[0] == 'I' && t[1] == 'I') le = 1; else if (t[0] == 'M' && t[1] == 'M') le = 0; else return -1;
    ifd0 = rd32(t + 4, le);
    if (ifd0 + 2 > tl) return -1;
    n0 = rd16(t + ifd0, le);
    if (ifd0 + 2 + n0 * 12 + 4 > tl) return -1;
    ifd1 = rd32(t + ifd0 + 2 + n0 * 12, le);
    if (ifd1 == 0 || ifd1 + 2 > tl) return -1;
    n1 = rd16(t + ifd1, le);
    if (ifd1 + 2 + n1 * 12 > tl) return -1;
    for (i = 0; i < n1; i++) {
        const unsigned char *e = t + ifd1 + 2 + i * 12;
        unsigned int tag = rd16(e, le);
        if (tag == 0x0201) off = rd32(e + 8, le);
        if (tag == 0x0202) { ln = rd32(e + 8, le); lnf = (unsigned int)(e + 8 - app1); }
    }
    if (!off || !ln || !lnf || off + ln > tl) return -1;
    *data_off = off + 6; *data_len = ln; *lenfield_off = lnf; *le_out = le;
    return 0;
}

/* Encode the 160x120 RGB thumbnail at the highest quality that fits in max_len. */
static int encode_thumb(const unsigned char *rgb, unsigned char **out, unsigned long *out_len, unsigned long max_len) {
    static const int qs[] = { 92, 85, 75, 65, 55, 45, 35 };
    int qi;
    for (qi = 0; qi < (int)(sizeof qs / sizeof qs[0]); qi++) {
        struct jpeg_compress_struct c;
        struct fl_jerr je;
        unsigned char *volatile buf = NULL;
        unsigned long blen = 0;
        int y;
        fl_jerr_init(&je);
        c.err = &je.pub;
        if (setjmp(je.jump)) { jpeg_destroy_compress(&c); free(buf); return -1; }
        jpeg_create_compress(&c);
        jpeg_mem_dest(&c, (unsigned char **)&buf, &blen);
        c.image_width = THUMB_W; c.image_height = THUMB_H;
        c.input_components = 3; c.in_color_space = JCS_RGB;
        jpeg_set_defaults(&c);
        jpeg_set_quality(&c, qs[qi], TRUE);
        c.write_JFIF_header = FALSE;
        jpeg_start_compress(&c, TRUE);
        for (y = 0; y < THUMB_H; y++) {
            JSAMPROW r = (JSAMPROW)(rgb + y * THUMB_W * 3);
            jpeg_write_scanlines(&c, &r, 1);
        }
        jpeg_finish_compress(&c);
        jpeg_destroy_compress(&c);
        if (blen <= max_len) { *out = buf; *out_len = blen; return 0; }
        free(buf);
    }
    return -1;
}

/* A box-filter downscaler fed one row at a time during the main pass. Used twice: the letterboxed
 * 160x120 EXIF thumbnail (like the camera's own) and the screen-size preview the viewer shows. */
typedef struct {
    int w, h;                 /* source size */
    int W, H;                 /* canvas size */
    int tw, th, ox, oy;       /* picture area inside the canvas */
    int *xmap;                /* source x -> canvas pixel index within a row */
    unsigned int *sum, *cnt;
} fl_thumb;

/* letterbox: fit the picture inside a W x H canvas with black bars; otherwise the canvas is the
 * fitted picture itself. */
static int thumb_init(fl_thumb *T, int w, int h, int W, int H, int letterbox) {
    int x;
    memset(T, 0, sizeof *T);
    T->w = w; T->h = h;
    if ((long)w * H >= (long)h * W) { T->tw = W; T->th = (int)((long)W * h / w); }
    else { T->th = H; T->tw = (int)((long)H * w / h); }
    if (T->tw < 1) T->tw = 1;
    if (T->th < 1) T->th = 1;
    if (letterbox) { T->W = W; T->H = H; T->ox = (W - T->tw) / 2; T->oy = (H - T->th) / 2; }
    else { T->W = T->tw; T->H = T->th; }
    T->xmap = (int *)malloc(sizeof(int) * w);
    T->sum = (unsigned int *)calloc((size_t)T->W * T->H * 3, sizeof(unsigned int));
    T->cnt = (unsigned int *)calloc((size_t)T->W * T->H, sizeof(unsigned int));
    if (!T->xmap || !T->sum || !T->cnt) return -1;
    for (x = 0; x < w; x++) T->xmap[x] = T->ox + (int)((long)x * T->tw / w);
    return 0;
}

static void thumb_row(fl_thumb *T, const unsigned char *row, int y) {
    int base = (T->oy + (int)((long)y * T->th / T->h)) * T->W, x;
    for (x = 0; x < T->w; x++, row += 3) {
        int o = base + T->xmap[x];
        unsigned int *s = T->sum + o * 3;
        s[0] += row[0]; s[1] += row[1]; s[2] += row[2];
        T->cnt[o]++;
    }
}

static void thumb_rgb(const fl_thumb *T, unsigned char *rgb) {
    int i;
    for (i = 0; i < T->W * T->H; i++) {
        unsigned int c = T->cnt[i];
        rgb[i * 3]     = c ? (unsigned char)((T->sum[i * 3] + c / 2) / c) : 0;
        rgb[i * 3 + 1] = c ? (unsigned char)((T->sum[i * 3 + 1] + c / 2) / c) : 0;
        rgb[i * 3 + 2] = c ? (unsigned char)((T->sum[i * 3 + 2] + c / 2) / c) : 0;
    }
}

/* Writes an RGB image as a JPEG file (via .TMP and rename). 0 on success. */
static int write_jpeg(const char *path, const unsigned char *rgb, int w, int h, int quality) {
    struct jpeg_compress_struct c;
    struct fl_jerr je;
    char tmp[1024];
    FILE *volatile f = NULL;
    const char *dot = strrchr(path, '.');
    int y;
    if (!dot || (size_t)(dot - path) + 5 > sizeof tmp) return -1;
    memcpy(tmp, path, (size_t)(dot - path));
    strcpy(tmp + (dot - path), ".TMP");
    f = fopen(tmp, "wb");
    if (!f) return -1;
    setvbuf(f, NULL, _IOFBF, 64 * 1024);
    fl_jerr_init(&je);
    c.err = &je.pub;
    if (setjmp(je.jump)) { jpeg_destroy_compress(&c); fclose(f); remove(tmp); return -1; }
    jpeg_create_compress(&c);
    jpeg_stdio_dest(&c, f);
    c.image_width = w; c.image_height = h;
    c.input_components = 3; c.in_color_space = JCS_RGB;
    jpeg_set_defaults(&c);
    jpeg_set_quality(&c, quality, TRUE);
    jpeg_start_compress(&c, TRUE);
    for (y = 0; y < h; y++) {
        JSAMPROW r = (JSAMPROW)(rgb + (size_t)y * w * 3);
        jpeg_write_scanlines(&c, &r, 1);
    }
    jpeg_finish_compress(&c);
    jpeg_destroy_compress(&c);
    if (fclose(f) != 0) { remove(tmp); return -1; }
    remove(path);
    return rename(tmp, path) == 0 ? 0 : -1;
}

/* Finish a preview accumulator into a JPEG file. */
static void write_preview(const char *path, const fl_thumb *P) {
    unsigned char *rgb;
    if (!path || !path[0] || !P->sum) return;
    rgb = (unsigned char *)malloc((size_t)P->W * P->H * 3);
    if (!rgb) return;
    thumb_rgb(P, rgb);
    write_jpeg(path, rgb, P->W, P->H, 92);
    free(rgb);
}

static void thumb_free(fl_thumb *T) { free(T->xmap); free(T->sum); free(T->cnt); memset(T, 0, sizeof *T); }

/* Where the EXIF thumbnail sits in the output file, so it can be rewritten after encoding. */
typedef struct {
    long payload;             /* file offset of the APP1 payload ("Exif\0\0..."), 0 if none */
    unsigned int off, len, lenfield;
    int le;
} fl_slot;

/* Copy the source's markers to the output, recording the file offset of the EXIF thumbnail slot.
 * APP2 MPF is dropped (the preview image it points at is not carried over), and so is Adobe APP14
 * (it describes the source's colour transform; ours is always YCbCr). libjpeg has written only SOI
 * before these (no JFIF, no Adobe marker), so each marker's file offset is known exactly. */
static void write_markers(struct jpeg_compress_struct *c, struct jpeg_decompress_struct *d, fl_slot *slot) {
    jpeg_saved_marker_ptr m;
    long pos = 2;             /* after SOI */
    memset(slot, 0, sizeof *slot);
    for (m = d->marker_list; m; m = m->next) {
        if (m->marker == JPEG_APP0 + 2 && m->data_length >= 4 && memcmp(m->data, "MPF\0", 4) == 0) continue;
        if (m->marker == JPEG_APP0 + 14 && m->data_length >= 5 && memcmp(m->data, "Adobe", 5) == 0) continue;
        if (m->marker == JPEG_APP0 + 1 && !slot->payload &&
            find_thumb(m->data, m->data_length, &slot->off, &slot->len, &slot->lenfield, &slot->le) == 0)
            slot->payload = pos + 4;
        jpeg_write_marker(c, m->marker, m->data, m->data_length);
        pos += 4 + (long)m->data_length;
    }
}

/* Rewrite the thumbnail inside the finished file. Checks the slot really holds the old thumbnail
 * first; on any mismatch the file keeps the camera's original thumbnail. */
static void patch_thumb(FILE *f, const fl_slot *slot, const unsigned char *jpg, unsigned long len) {
    unsigned char probe[6], lenbuf[4], zeros[256];
    size_t left;
    if (!slot->payload || len > slot->len) return;
    if (fseek(f, slot->payload, SEEK_SET) != 0 || fread(probe, 1, 6, f) != 6 || memcmp(probe, "Exif\0\0", 6) != 0) return;
    if (fseek(f, slot->payload + slot->off, SEEK_SET) != 0 || fread(probe, 1, 2, f) != 2 || probe[0] != 0xFF || probe[1] != 0xD8) return;
    if (fseek(f, slot->payload + slot->off, SEEK_SET) != 0 || fwrite(jpg, 1, len, f) != len) return;
    memset(zeros, 0, sizeof zeros);
    for (left = slot->len - len; left > 0; ) {
        size_t n = left > sizeof zeros ? sizeof zeros : left;
        if (fwrite(zeros, 1, n, f) != n) return;
        left -= n;
    }
    wr32(lenbuf, (unsigned int)len, slot->le);
    if (fseek(f, slot->payload + slot->lenfield, SEEK_SET) == 0) fwrite(lenbuf, 1, 4, f);
    fseek(f, 0, SEEK_END);
}

/* ------------------------------------------------------------------------------------------ */
/* timing                                                                                        */

static int g_timing[FL_TIME_COUNT];

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

/* CPU time this thread actually ran. Compared with wall time it shows whether the threads really
 * ran in parallel or were time-sliced onto fewer cores than the CPU reports. */
static long cpu_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts) != 0) return 0;
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

void fl_last_timing(int *out) { memcpy(out, g_timing, sizeof g_timing); }

/* libjpeg-turbo quantizes with reciprocal multiplies (IJG divided per coefficient, and this ARM core
 * has no divide instruction), so the accurate integer DCT is now also the fast one here: the
 * Cortex-A5's FPU is slow, and turbo's integer paths are its most optimised C code. */
#define ENCODE_DCT JDCT_ISLOW

#define IO_BUFFER (256 * 1024)   /* big reads and writes: each one is a round trip through Sony's FUSE layer */

/* ------------------------------------------------------------------------------------------ */
/* full-resolution processing                                                                    */
/*
 * Three stages run at once on the camera's four cores, connected by a ring of 16-row strips:
 *
 *   calling thread   decode a strip (libjpeg), feed the thumbnail      FREE -> READ
 *   look workers     tone + LUT + grain on any decoded strip           READ -> DONE
 *   writer thread    encode strips strictly in order (libjpeg)         DONE -> FREE
 *
 * The calling thread does the decoding because the JNI progress callback must stay on it. Each
 * libjpeg object is only ever used by one thread at a time; an error in any stage sets 'abort'
 * and every stage stops at its next wait.
 */

#define STRIP_ROWS 16
#define RING 8                    /* strips in flight: 8 x 16 rows x 6000 px x 3 = 2.3 MB */
#define MAX_WORKERS 3

enum { S_FREE, S_READ, S_BUSY, S_DONE };

typedef struct {
    unsigned char *buf;
    int state, seq, y0, rows;
} fl_strip;

typedef struct {
    pthread_mutex_t lock;
    pthread_cond_t changed;
    fl_strip strip[RING];
    int width, height, nstrips;
    int read_done;                /* all strips decoded */
    int abort;
    const fl_look *L;
    const fl_grain *G;
    struct jpeg_compress_struct *c;
    fl_thumb *pv;                 /* screen preview, fed with final rows by the writer */
    struct fl_jerr jc;            /* the encoder's error target, armed in the writer thread */
    char werr[JMSG_LENGTH_MAX];
    long look_ms, write_ms;       /* busy (wall) time summed over threads */
    long cpu_ms;                  /* CPU time summed over all threads */
} fl_pipe;

static void pipe_fail(fl_pipe *P) {
    pthread_mutex_lock(&P->lock);
    P->abort = 1;
    pthread_cond_broadcast(&P->changed);
    pthread_mutex_unlock(&P->lock);
}

static void pipe_free(fl_pipe *P) {
    int i;
    for (i = 0; i < RING; i++) free(P->strip[i].buf);
    pthread_cond_destroy(&P->changed);
    pthread_mutex_destroy(&P->lock);
    free(P);
}

static void *look_worker(void *arg) {
    fl_pipe *P = (fl_pipe *)arg;
    long busy = 0;
    for (;;) {
        fl_strip *s = NULL;
        int i, k;
        long t;
        pthread_mutex_lock(&P->lock);
        for (;;) {
            if (P->abort) break;
            for (i = 0; i < RING && !s; i++) if (P->strip[i].state == S_READ) s = &P->strip[i];
            if (s || P->read_done) break;
            pthread_cond_wait(&P->changed, &P->lock);
        }
        if (s) s->state = S_BUSY;
        pthread_mutex_unlock(&P->lock);
        if (!s) break;            /* aborted, or nothing left to do */

        t = now_ms();
        for (k = 0; k < s->rows; k++) {
            unsigned char *row = s->buf + (size_t)k * P->width * 3;
            look_row(P->L, P->G, row, P->width, s->y0 + k);
        }
        busy += now_ms() - t;

        pthread_mutex_lock(&P->lock);
        s->state = S_DONE;
        pthread_cond_broadcast(&P->changed);
        pthread_mutex_unlock(&P->lock);
    }
    pthread_mutex_lock(&P->lock);
    P->look_ms += busy;
    P->cpu_ms += cpu_ms();
    pthread_mutex_unlock(&P->lock);
    return NULL;
}

static void *writer(void *arg) {
    fl_pipe *P = (fl_pipe *)arg;
    volatile int seq = 0;
    volatile long busy = 0;
    if (setjmp(P->jc.jump)) {     /* encoder error (e.g. card full) */
        snprintf(P->werr, sizeof P->werr, "%s", P->jc.msg);
        pipe_fail(P);
        return NULL;
    }
    while (seq < P->nstrips) {
        fl_strip *s = &P->strip[seq % RING];
        JSAMPROW rows[STRIP_ROWS];
        int k;
        long t;
        pthread_mutex_lock(&P->lock);
        while (!P->abort && !(s->state == S_DONE && s->seq == seq)) pthread_cond_wait(&P->changed, &P->lock);
        pthread_mutex_unlock(&P->lock);
        if (P->abort) return NULL;
        for (k = 0; k < s->rows; k++) rows[k] = s->buf + (size_t)k * P->width * 3;
        t = now_ms();
        if (P->pv->sum) for (k = 0; k < s->rows; k++) thumb_row(P->pv, rows[k], s->y0 + k);
        jpeg_write_scanlines(P->c, rows, s->rows);
        busy += now_ms() - t;
        pthread_mutex_lock(&P->lock);
        s->state = S_FREE;
        pthread_cond_broadcast(&P->changed);
        pthread_mutex_unlock(&P->lock);
        seq++;
    }
    {
        long t = now_ms();
        jpeg_finish_compress(P->c);
        busy += now_ms() - t;
    }
    P->write_ms = busy;
    pthread_mutex_lock(&P->lock);
    P->cpu_ms += cpu_ms();
    pthread_mutex_unlock(&P->lock);
    return NULL;
}

int fl_process_file(const char *in_path, const char *out_path, const char *lut_path, const char *preview_path,
                    const int *params, fl_progress_fn progress, void *ctx, char *err, int errlen) {
    struct jpeg_decompress_struct d;
    struct jpeg_compress_struct c;
    struct fl_jerr jd;            /* the decoder's error target, in this thread */
    fl_look L;
    fl_grain G;
    fl_thumb T, PV;
    fl_slot slot;
    fl_pipe *volatile P = NULL;
    pthread_t threads[MAX_WORKERS + 1];
    volatile int nthreads = 0, started_c = 0;
    FILE *fin = NULL, *fout = NULL;
    unsigned char *thumb_jpg = NULL;
    unsigned long thumb_len = 0;
    char tmp_path[1024];
    int quality = params[FL_PARAM_QUALITY], workers = params[FL_PARAM_THREADS] - 2, last_pct = -1, i;
    volatile long t_read = 0;
    long t0 = now_ms(), cpu0 = cpu_ms(), t;
    const int sequential = params[FL_PARAM_THREADS] <= 1;   /* everything on this thread, no pthreads */
    const char *dot = strrchr(out_path, '.'), *slash = strrchr(out_path, '/');

    memset(g_timing, 0, sizeof g_timing);
    memset(&T, 0, sizeof T);
    memset(&PV, 0, sizeof PV);
    if (quality < 50 || quality > 100) quality = 95;
    if (workers < 1) workers = 1;
    if (workers > MAX_WORKERS) workers = MAX_WORKERS;
    /* 8.3 file systems: FLA04491.JPG.TMP is not a valid name, so swap the extension instead */
    if (!dot || (slash && dot < slash) || (size_t)(dot - out_path) + 5 > sizeof tmp_path) {
        set_err(err, errlen, "bad output path %s", out_path);
        return -1;
    }
    memcpy(tmp_path, out_path, (size_t)(dot - out_path));
    strcpy(tmp_path + (dot - out_path), ".TMP");

    if (look_init(&L, lut_path, params, err, errlen) != 0) return -1;
    if (grain_init(&G, params, err, errlen) != 0) { look_free(&L); return -1; }

    fin = fopen(in_path, "rb");
    if (!fin) { set_err(err, errlen, "cannot open %s", in_path); goto fail_early; }
    fout = fopen(tmp_path, "w+b");       /* read back too: the thumbnail is patched in at the end */
    if (!fout) { set_err(err, errlen, "cannot create %s", tmp_path); goto fail_early; }
    setvbuf(fin, NULL, _IOFBF, IO_BUFFER);
    setvbuf(fout, NULL, _IOFBF, IO_BUFFER);

    P = (fl_pipe *)calloc(1, sizeof(fl_pipe));
    if (!P) { set_err(err, errlen, "out of memory"); goto fail_early; }
    pthread_mutex_init(&P->lock, NULL);
    pthread_cond_init(&P->changed, NULL);

    fl_jerr_init(&jd);
    fl_jerr_init(&P->jc);
    d.err = &jd.pub;
    c.err = &P->jc.pub;
    jpeg_create_decompress(&d);
    jpeg_create_compress(&c);
    /* Until the writer thread starts, encoder errors land here too: point both at jd's target. */
    c.err = &jd.pub;
    if (setjmp(jd.jump)) {
        set_err(err, errlen, "%s", jd.msg);
        goto fail;
    }

    jpeg_stdio_src(&d, fin);
    jpeg_save_markers(&d, JPEG_COM, 0xFFFF);
    for (i = 0; i < 16; i++) jpeg_save_markers(&d, JPEG_APP0 + i, 0xFFFF);
    jpeg_read_header(&d, TRUE);
    if (check_source(&d, err, errlen) != 0) goto fail;
    if (d.num_components != 3) { set_err(err, errlen, "only colour JPEGs are supported"); goto fail; }
    d.out_color_space = JCS_RGB;
    d.dct_method = JDCT_ISLOW;
    jpeg_start_decompress(&d);

    jpeg_stdio_dest(&c, fout);
    c.image_width = d.output_width;
    c.image_height = d.output_height;
    c.input_components = 3;
    c.in_color_space = JCS_RGB;
    jpeg_set_defaults(&c);
    jpeg_set_quality(&c, quality, TRUE);
    c.dct_method = ENCODE_DCT;
    c.optimize_coding = FALSE;
    c.write_JFIF_header = FALSE;   /* the source's own APP0/APP1 are copied below */
    c.write_Adobe_marker = FALSE;
    for (i = 0; i < 3; i++) {
        c.comp_info[i].h_samp_factor = d.comp_info[i].h_samp_factor;
        c.comp_info[i].v_samp_factor = d.comp_info[i].v_samp_factor;
    }
    jpeg_start_compress(&c, TRUE);
    started_c = 1;
    write_markers(&c, &d, &slot);
    if (slot.payload && thumb_init(&T, (int)d.output_width, (int)d.output_height, THUMB_W, THUMB_H, 1) != 0) {
        thumb_free(&T);
        slot.payload = 0;          /* no memory for it: keep the camera's thumbnail */
    }

    /* set up the ring and start the other stages */
    P->width = (int)d.output_width;
    P->height = (int)d.output_height;
    P->nstrips = (P->height + STRIP_ROWS - 1) / STRIP_ROWS;
    P->L = &L;
    P->G = &G;
    P->c = &c;
    P->pv = &PV;
    if (preview_path && preview_path[0] && thumb_init(&PV, P->width, P->height, PREVIEW_W, PREVIEW_H, 0) != 0) thumb_free(&PV);
    for (i = 0; i < RING; i++) {
        P->strip[i].buf = (unsigned char *)malloc((size_t)P->width * 3 * STRIP_ROWS);
        if (!P->strip[i].buf) { set_err(err, errlen, "out of memory for rows"); goto fail; }
    }
    if (!sequential) {
        pthread_attr_t attr;
        c.err = &P->jc.pub;        /* from here on the encoder belongs to the writer thread */
        pthread_attr_init(&attr);
        pthread_attr_setstacksize(&attr, 256 * 1024);
        if (pthread_create(&threads[nthreads], &attr, writer, P) == 0) nthreads++;
        for (i = 0; i < workers; i++) if (pthread_create(&threads[nthreads], &attr, look_worker, P) == 0) nthreads++;
        pthread_attr_destroy(&attr);
        if (nthreads < 2) { set_err(err, errlen, "cannot start worker threads"); goto fail; }
    }

    /* this thread: decode */
    for (i = 0; i < P->nstrips; i++) {
        fl_strip *s = &P->strip[i % RING];
        JSAMPROW rows[STRIP_ROWS];
        int k, got = 0;
        pthread_mutex_lock(&P->lock);
        while (!P->abort && s->state != S_FREE) pthread_cond_wait(&P->changed, &P->lock);
        pthread_mutex_unlock(&P->lock);
        if (P->abort) break;
        s->y0 = (int)d.output_scanline;
        for (k = 0; k < STRIP_ROWS; k++) rows[k] = s->buf + (size_t)k * P->width * 3;
        t = now_ms();
        while (got < STRIP_ROWS && d.output_scanline < d.output_height)
            got += jpeg_read_scanlines(&d, rows + got, STRIP_ROWS - got);
        t_read += now_ms() - t;
        if (T.sum) for (k = 0; k < got; k++) thumb_row(&T, rows[k], s->y0 + k);
        if (sequential) {          /* look and encode right here */
            t = now_ms();
            for (k = 0; k < got; k++) look_row(&L, &G, rows[k], P->width, s->y0 + k);
            P->look_ms += now_ms() - t;
            t = now_ms();
            if (PV.sum) for (k = 0; k < got; k++) thumb_row(&PV, rows[k], s->y0 + k);
            jpeg_write_scanlines(&c, rows, got);
            P->write_ms += now_ms() - t;
            if (progress) {
                int pct = (int)((long long)(i + 1) * 100 / P->nstrips);
                if (pct != last_pct) { last_pct = pct; progress(ctx, pct); }
            }
            continue;
        }
        pthread_mutex_lock(&P->lock);
        s->rows = got;
        s->seq = i;
        s->state = S_READ;
        if (i == P->nstrips - 1) P->read_done = 1;
        pthread_cond_broadcast(&P->changed);
        pthread_mutex_unlock(&P->lock);
        if (progress) {
            int pct = (int)((long long)(i + 1) * 100 / P->nstrips);
            if (pct != last_pct) { last_pct = pct; progress(ctx, pct); }
        }
    }
    if (sequential) {
        t = now_ms();
        jpeg_finish_compress(&c);
        P->write_ms += now_ms() - t;
    } else if (!P->read_done) {    /* stopped early: make sure idle workers wake up and leave */
        pthread_mutex_lock(&P->lock);
        P->read_done = 1;
        pthread_cond_broadcast(&P->changed);
        pthread_mutex_unlock(&P->lock);
    }
    for (i = 0; i < nthreads; i++) pthread_join(threads[i], NULL);
    nthreads = 0;
    if (P->abort) { set_err(err, errlen, "%s", P->werr[0] ? P->werr : "processing stopped"); goto fail; }

    t = now_ms();
    jpeg_finish_decompress(&d);
    jpeg_destroy_compress(&c);
    jpeg_destroy_decompress(&d);
    started_c = 0;
    fclose(fin); fin = NULL;

    if (T.sum) {
        unsigned char *rgb = (unsigned char *)malloc(THUMB_W * THUMB_H * 3);   /* heap: Dalvik thread stacks are small */
        if (rgb) {
            thumb_rgb(&T, rgb);
            for (i = 0; i < THUMB_W * THUMB_H; i++) look_apply(&L, rgb + i * 3);  /* the look, at thumbnail scale */
            if (fflush(fout) == 0 && encode_thumb(rgb, &thumb_jpg, &thumb_len, slot.len) == 0)
                patch_thumb(fout, &slot, thumb_jpg, thumb_len);
            free(rgb);
        }
    }
    fflush(fout);
    fsync(fileno(fout));          /* Sony's FUSE layer may not implement fsync; the error is ignored */
    if (ferror(fout) || fclose(fout) != 0) { fout = NULL; set_err(err, errlen, "write failed (card full?)"); remove(tmp_path); goto fail_early; }
    fout = NULL;
    if (rename(tmp_path, out_path) != 0) { set_err(err, errlen, "cannot rename to %s", out_path); remove(tmp_path); goto fail_early; }
    write_preview(preview_path, &PV);   /* best effort: the viewer can make one later */
    g_timing[FL_TIME_READ] = (int)t_read;
    g_timing[FL_TIME_LOOK] = (int)P->look_ms;
    g_timing[FL_TIME_WRITE] = (int)P->write_ms;
    g_timing[FL_TIME_FINISH] = (int)(now_ms() - t);
    g_timing[FL_TIME_TOTAL] = (int)(now_ms() - t0);
    g_timing[FL_TIME_CPU] = (int)(P->cpu_ms + cpu_ms() - cpu0);
    pipe_free(P);
    free(thumb_jpg); thumb_free(&T); thumb_free(&PV);
    look_free(&L); grain_free(&G);
    return 0;

fail:
    if (P && nthreads) {
        pipe_fail(P);
        for (i = 0; i < nthreads; i++) pthread_join(threads[i], NULL);
    }
    if (started_c) jpeg_abort_compress(&c);
    jpeg_destroy_compress(&c);
    jpeg_destroy_decompress(&d);
fail_early:
    if (fin) fclose(fin);
    if (fout) { fclose(fout); remove(tmp_path); }
    if (P) pipe_free(P);
    free(thumb_jpg); thumb_free(&T); thumb_free(&PV);
    look_free(&L); grain_free(&G);
    return -1;
}

/* Screen preview of an existing JPEG (frames developed before previews existed). Decodes at the
 * smallest IDCT scale still at least screen size, then box-filters to fit the screen. */
static unsigned char *mpf_preview(FILE *f, unsigned long *len);

int fl_make_preview(const char *in_path, const char *out_path, char *err, int errlen) {
    struct jpeg_decompress_struct d;
    struct fl_jerr je;
    fl_thumb PV;
    FILE *f;
    unsigned char *volatile row = NULL, *volatile mp = NULL;
    unsigned long mlen = 0;
    int rc;
    memset(&PV, 0, sizeof PV);
    f = fopen(in_path, "rb");
    if (!f) { set_err(err, errlen, "cannot open %s", in_path); return -1; }
    setvbuf(f, NULL, _IOFBF, IO_BUFFER);
    mp = mpf_preview(f, &mlen);   /* a camera's embedded preview, when it is big enough, is far quicker */
    rewind(f);
    fl_jerr_init(&je);
    d.err = &je.pub;
    if (setjmp(je.jump)) {
        set_err(err, errlen, "%s", je.msg);
        jpeg_destroy_decompress(&d); fclose(f); free(row); free(mp); thumb_free(&PV);
        return -1;
    }
    jpeg_create_decompress(&d);
    if (mp) {
        jpeg_mem_src(&d, mp, mlen);
        jpeg_read_header(&d, TRUE);
        if (d.image_width < PREVIEW_W || d.image_height < PREVIEW_H) {   /* too small: use the photo itself */
            jpeg_destroy_decompress(&d);
            free(mp);
            mp = NULL;
            jpeg_create_decompress(&d);
        }
    }
    if (!mp) {
        jpeg_stdio_src(&d, f);
        jpeg_read_header(&d, TRUE);
    }
    if (check_source(&d, err, errlen) != 0) { jpeg_destroy_decompress(&d); fclose(f); free(mp); return -1; }
    d.out_color_space = JCS_RGB;
    d.scale_num = 1;
    d.scale_denom = 8;
    while (d.scale_denom > 1 && (d.image_width / d.scale_denom < PREVIEW_W && d.image_height / d.scale_denom < PREVIEW_H))
        d.scale_denom /= 2;
    jpeg_start_decompress(&d);
    if (thumb_init(&PV, (int)d.output_width, (int)d.output_height, PREVIEW_W, PREVIEW_H, 0) != 0) longjmp(je.jump, 1);
    row = (unsigned char *)malloc((size_t)d.output_width * 3);
    if (!row) longjmp(je.jump, 1);
    while (d.output_scanline < d.output_height) {
        int y = (int)d.output_scanline;
        JSAMPROW r = row;
        jpeg_read_scanlines(&d, &r, 1);
        thumb_row(&PV, row, y);
    }
    jpeg_finish_decompress(&d);
    jpeg_destroy_decompress(&d);
    fclose(f);
    free(row);
    free(mp);
    rc = 0;
    {
        unsigned char *rgb = (unsigned char *)malloc((size_t)PV.W * PV.H * 3);
        if (!rgb) rc = -1;
        else {
            thumb_rgb(&PV, rgb);
            if (write_jpeg(out_path, rgb, PV.W, PV.H, 92) != 0) { set_err(err, errlen, "cannot write %s", out_path); rc = -1; }
            free(rgb);
        }
    }
    thumb_free(&PV);
    return rc;
}

/* ------------------------------------------------------------------------------------------ */
/* previews                                                                                      */

static unsigned int to_argb(const unsigned char *p) {
    return 0xFF000000U | ((unsigned int)p[0] << 16) | ((unsigned int)p[1] << 8) | p[2];
}

/* Sony JPEGs carry a second, ~1616x1080 JPEG after the main image (an MPF "large thumbnail").
 * Decoding it instead of all 24 MP makes a screen-size preview many times faster. Returns the
 * preview's bytes (malloc'd) or NULL when the file has none. */
static unsigned char *mpf_preview(FILE *f, unsigned long *len) {
    const long HEAD = 128 * 1024;
    unsigned char *h = (unsigned char *)malloc(HEAD), *out = NULL;
    long n, i = 2, abs_off = 0;
    unsigned long size = 0;
    if (!h) return NULL;
    n = (long)fread(h, 1, HEAD, f);
    while (n >= 4 && i + 4 <= n && h[i] == 0xFF && h[i + 1] != 0xDA) {
        long ln = (h[i + 2] << 8) | h[i + 3];
        if (h[i + 1] == 0xE2 && i + 4 + ln <= n && ln > 16 && memcmp(h + i + 4, "MPF\0", 4) == 0) {
            const unsigned char *t = h + i + 8;
            long tl = ln - 6;
            int le = t[0] == 'I';
            unsigned int ifd = rd32(t + 4, le), cnt, k;
            if (ifd + 2 > (unsigned)tl) break;
            cnt = rd16(t + ifd, le);
            for (k = 0; k < cnt && ifd + 2 + k * 12 + 12 <= (unsigned)tl; k++) {
                const unsigned char *e = t + ifd + 2 + k * 12;
                if (rd16(e, le) == 0xB002 && rd32(e + 4, le) >= 32) {      /* MP entries: 16 bytes each */
                    unsigned int ent = rd32(e + 8, le);
                    if (ent + 32 <= (unsigned)tl) {
                        size = rd32(t + ent + 16 + 4, le);
                        abs_off = (i + 8) + (long)rd32(t + ent + 16 + 8, le);
                    }
                }
            }
            break;
        }
        i += 2 + ln;
    }
    free(h);
    if (!abs_off || size < 1024 || size > 8 * 1024 * 1024) return NULL;
    out = (unsigned char *)malloc(size);
    if (!out) return NULL;
    if (fseek(f, abs_off, SEEK_SET) != 0 || fread(out, 1, size, f) != size || out[0] != 0xFF || out[1] != 0xD8) {
        free(out);
        return NULL;
    }
    *len = size;
    return out;
}

int fl_preview(const char *in_path, const char *lut_path, const int *params, int mode,
               unsigned int *argb, int max_w, int max_h, int *out_wh, char *err, int errlen) {
    struct jpeg_decompress_struct d;
    struct fl_jerr je;
    fl_look L;
    fl_grain G;
    fl_thumb P;
    FILE *f;
    unsigned char *volatile row = NULL, *volatile mp = NULL, *volatile rgb = NULL;
    unsigned long mlen = 0;
    int ow = 0, oh = 0, i;

    memset(&P, 0, sizeof P);
    if (look_init(&L, lut_path, params, err, errlen) != 0) return -1;
    memset(&G, 0, sizeof G);
    if (mode == 1 && grain_init(&G, params, err, errlen) != 0) { look_free(&L); return -1; }
    f = fopen(in_path, "rb");
    if (!f) { set_err(err, errlen, "cannot open %s", in_path); look_free(&L); grain_free(&G); return -1; }
    if (mode == 0) mp = mpf_preview(f, &mlen);
    rewind(f);
    fl_jerr_init(&je);
    d.err = &je.pub;
    if (setjmp(je.jump)) {
        set_err(err, errlen, "%s", je.msg);
        jpeg_destroy_decompress(&d); fclose(f); free(row); free(mp); free(rgb); thumb_free(&P); look_free(&L); grain_free(&G);
        return -1;
    }
    jpeg_create_decompress(&d);
    if (mp) jpeg_mem_src(&d, mp, mlen); else jpeg_stdio_src(&d, f);
    jpeg_read_header(&d, TRUE);
    if (check_source(&d, err, errlen) != 0) {
        jpeg_destroy_decompress(&d); fclose(f); free(mp); look_free(&L); grain_free(&G);
        return -1;
    }
    d.out_color_space = JCS_RGB;

    if (mode == 0) {
        /* smallest IDCT scale still at least the target size, then a box filter down to it */
        d.scale_num = 1;
        d.scale_denom = 8;
        while (d.scale_denom > 1 && (int)(d.image_width / d.scale_denom) < max_w && (int)(d.image_height / d.scale_denom) < max_h)
            d.scale_denom /= 2;
        jpeg_start_decompress(&d);
        if (thumb_init(&P, (int)d.output_width, (int)d.output_height, max_w, max_h, 0) != 0) longjmp(je.jump, 1);
        row = (unsigned char *)malloc(d.output_width * 3);
        if (!row) longjmp(je.jump, 1);
        while (d.output_scanline < d.output_height) {
            int y = (int)d.output_scanline;
            JSAMPROW r = row;
            jpeg_read_scanlines(&d, &r, 1);
            thumb_row(&P, row, y);
        }
        jpeg_finish_decompress(&d);
        ow = P.W;
        oh = P.H;
        rgb = (unsigned char *)malloc((size_t)ow * oh * 3);
        if (!rgb) longjmp(je.jump, 1);
        thumb_rgb(&P, rgb);
        for (i = 0; i < ow * oh; i++) {
            look_apply(&L, rgb + i * 3);
            argb[i] = to_argb(rgb + i * 3);
        }
    } else {
        /* 100% crop around the centre; rows above it are decoded and thrown away */
        int x0, y0, y;
        jpeg_start_decompress(&d);
        ow = max_w < (int)d.output_width ? max_w : (int)d.output_width;
        oh = max_h < (int)d.output_height ? max_h : (int)d.output_height;
        x0 = ((int)d.output_width - ow) / 2;
        y0 = ((int)d.output_height - oh) / 2;
        row = (unsigned char *)malloc(d.output_width * 3);
        if (!row) longjmp(je.jump, 1);
        while ((int)d.output_scanline < y0 + oh) {
            JSAMPROW r = row;
            y = d.output_scanline;
            jpeg_read_scanlines(&d, &r, 1);
            if (y >= y0) {
                int x;
                unsigned char *p = row + x0 * 3;
                look_row(&L, &G, p, ow, y);
                for (x = 0; x < ow; x++) argb[(y - y0) * ow + x] = to_argb(p + x * 3);
            }
        }
        jpeg_abort_decompress(&d);
    }
    jpeg_destroy_decompress(&d);
    fclose(f);
    free(row); free(mp); free(rgb);
    thumb_free(&P);
    look_free(&L);
    grain_free(&G);
    out_wh[0] = ow;
    out_wh[1] = oh;
    return 0;
}

/* ------------------------------------------------------------------------------------------ */
/* the viewfinder: live frames                                                                  */

/* Block size and limits of the horizon search (see fl_level). */
#define LV_BLOCK 8
#define LV_RANGE 12           /* degrees either side of level that count as a level cue      */
#define LV_BINS (LV_RANGE * 8 + 1)   /* 0.25 degree bins                                      */

int fl_level(const unsigned char *rgb, int w, int h, int *out) {
    int bw = (w - 2) / LV_BLOCK, bh = (h - 2) / LV_BLOCK, nb = bw * bh, i, x, y, bx, by, n = 0, near = 0, peak = 0;
    unsigned char *luma, *blur;
    float *ang, *wt, hist[LV_BINS], smooth[LV_BINS], total = 0, mass = 0, sum = 0, mean_e = 0, floor_e;
    int *je;
    out[0] = out[1] = out[2] = 0;
    if (bw < 4 || bh < 4) return -1;
    luma = (unsigned char *)malloc((size_t)w * h);
    blur = (unsigned char *)malloc((size_t)w * h);
    ang = (float *)malloc(sizeof(float) * nb);
    wt = (float *)malloc(sizeof(float) * nb);
    je = (int *)malloc(sizeof(int) * nb * 3);
    if (!luma || !blur || !ang || !wt || !je) { free(luma); free(blur); free(ang); free(wt); free(je); return -1; }
    /* brightness, softened a little ([1 2 1] each way): Scharr's derivative on a softened picture
     * reads an edge's angle to within 1% (Sobel on sharp edges read 10% low) */
    for (i = 0; i < w * h; i++) blur[i] = (unsigned char)((rgb[i * 3] * 77 + rgb[i * 3 + 1] * 150 + rgb[i * 3 + 2] * 29) >> 8);
    for (y = 0; y < h; y++) {
        unsigned char *r = blur + y * w;
        int prev = r[0];
        for (x = 0; x < w; x++) {
            int cur = r[x], next = x + 1 < w ? r[x + 1] : cur;
            luma[y * w + x] = (unsigned char)((prev + 2 * cur + next + 2) >> 2);
            prev = cur;
        }
    }
    for (x = 0; x < w; x++) {
        int prev = luma[x];
        for (y = 0; y < h; y++) {
            int cur = luma[y * w + x], next = y + 1 < h ? luma[(y + 1) * w + x] : cur;
            blur[y * w + x] = (unsigned char)((prev + 2 * cur + next + 2) >> 2);
            prev = cur;
        }
    }
    /* per block, the structure tensor of the gradient (y pointing up): Jxx, Jyy, Jxy. Integer per
     * pixel (a gradient is at most 4080, so a block's sums stay inside 31 bits) */
    for (by = 0; by < bh; by++) for (bx = 0; bx < bw; bx++) {
        int sxx = 0, syy = 0, sxy = 0;
        for (y = 1 + by * LV_BLOCK; y < 1 + (by + 1) * LV_BLOCK; y++) {
            const unsigned char *a = blur + (y - 1) * w, *b = blur + y * w, *c = blur + (y + 1) * w;
            for (x = 1 + bx * LV_BLOCK; x < 1 + (bx + 1) * LV_BLOCK; x++) {
                int gx = 3 * (a[x + 1] - a[x - 1]) + 10 * (b[x + 1] - b[x - 1]) + 3 * (c[x + 1] - c[x - 1]);
                int gy = 3 * (a[x - 1] - c[x - 1]) + 10 * (a[x] - c[x]) + 3 * (a[x + 1] - c[x + 1]);   /* up is positive */
                sxx += gx * gx >> 8; syy += gy * gy >> 8; sxy += gx * gy >> 8;
            }
        }
        i = by * bw + bx;
        je[i * 3] = sxx; je[i * 3 + 1] = syy; je[i * 3 + 2] = sxy;
        mean_e += (float)sxx + syy;
    }
    mean_e /= nb;
    /* a block counts when it holds one clear straight edge: stronger than the frame's average
     * texture and than sensor noise, and all one direction (coherence) */
    floor_e = (float)(LV_BLOCK * LV_BLOCK) * 160 * 160 / 256;   /* an edge of about 10 levels */
    if (floor_e < mean_e) floor_e = mean_e;
    memset(hist, 0, sizeof hist);
    for (i = 0; i < nb; i++) {
        float xx = (float)je[i * 3], yy = (float)je[i * 3 + 1], xy = (float)je[i * 3 + 2], e = xx + yy, coh, a, d, pos, f;
        int k;
        if (e < floor_e) continue;
        coh = (float)sqrt((double)((xx - yy) * (xx - yy) + 4 * xy * xy)) / e;
        if (coh < 0.7f) continue;
        /* the gradient's direction, then the edge's (at right angles), in degrees, -90..90 */
        a = (float)(0.5 * atan2(2.0 * xy, (double)(xx - yy)) * 180.0 / M_PI) + 90;
        if (a > 90) a -= 180;
        d = a > 45 ? a - 90 : a < -45 ? a + 90 : a;   /* a vertical edge says as much as a horizontal one */
        if (d < -LV_RANGE || d > LV_RANGE) continue;
        f = coh * coh * (float)sqrt((double)e) * (a > 45 || a < -45 ? 0.8f : 1.0f);
        ang[n] = d; wt[n] = f; n++;
        pos = (d + LV_RANGE) * 4;
        k = (int)pos;
        if (k >= LV_BINS - 1) { hist[LV_BINS - 1] += f; }
        else { hist[k] += f * (1 - (pos - k)); hist[k + 1] += f * (pos - k); }
        total += f;
    }
    if (n > 0) {
        for (i = 0; i < LV_BINS; i++) {   /* a little blur: one degree wide */
            int k;
            smooth[i] = 0;
            for (k = -2; k <= 2; k++) if (i + k >= 0 && i + k < LV_BINS) smooth[i] += hist[i + k] * (3 - (k < 0 ? -k : k));
            if (smooth[i] > smooth[peak]) peak = i;
        }
        /* the answer: the weighted mean of the edges within a degree of the peak */
        for (i = 0; i < n; i++) {
            float d = ang[i] - (peak / 4.0f - LV_RANGE);
            if (d >= -1 && d <= 1) { sum += ang[i] * wt[i]; mass += wt[i]; near++; }
        }
        if (mass > 0) {
            out[0] = (int)(sum / mass * 100 + (sum >= 0 ? 0.5f : -0.5f));
            out[1] = (int)(mass / total * 100 + 0.5f);
            out[2] = near;
        }
    }
    free(luma); free(blur); free(ang); free(wt); free(je);
    return 0;
}

/* The 4-bit value of 0..255 with an ordered-dither threshold t (0..15 of a 4-bit step): [t][v]. A table,
 * as the camera's core has no divide instruction (three a pixel, 260K pixels a frame, were half the look). */
static unsigned char g_q4[16][256];
static int g_q4_ready;

static void q4_init(void) {
    int t, v;
    if (g_q4_ready) return;
    for (t = 0; t < 16; t++) {
        int off = (t * 255 + 128) / 16;
        for (v = 0; v < 256; v++) { int q = (v * 15 + off) / 255; g_q4[t][v] = (unsigned char)(q > 15 ? 15 : q); }
    }
    g_q4_ready = 1;
}

/* One source row stretched across: per output column and channel, the two neighbours blended (0..65280). */
static void stretch_row(const unsigned int *r, const int *xi, const int *xw, int ow, unsigned short *h) {
    int x;
    for (x = 0; x < ow; x++, h += 3) {
        unsigned int a = r[xi[x]], b = r[xi[x] + 1];
        int wx = xw[x], ux = 256 - wx;
        h[0] = (unsigned short)(((a >> 16) & 255) * ux + ((b >> 16) & 255) * wx);
        h[1] = (unsigned short)(((a >> 8) & 255) * ux + ((b >> 8) & 255) * wx);
        h[2] = (unsigned short)((a & 255) * ux + (b & 255) * wx);
    }
}

int fl_to4444(const unsigned int *argb, int w, int h, unsigned short *out, int ow, int oh, int aspect1000, const int *shifts) {
    static const unsigned char bayer[16] = { 0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5 };
    int x, y, rs, gs, bs, as, held[2];
    int *xi, *xw;
    unsigned short *hrow[2], alpha;
    double sw, sh, sx0, sy0;
    if (!argb || !out || !shifts || w < 2 || h < 2 || ow < 1 || oh < 1 || ow > 4096 || oh > 4096) return -1;
    rs = shifts[0]; gs = shifts[1]; bs = shifts[2]; as = shifts[3];
    if (rs < 0 || rs > 12 || gs < 0 || gs > 12 || bs < 0 || bs > 12 || as < 0 || as > 12) return -1;
    q4_init();
    alpha = (unsigned short)(15 << as);
    /* the source rectangle of the wanted shape (aspect1000, else ow:oh), centred, in source pixels */
    {
        double want = aspect1000 > 0 ? aspect1000 / 1000.0 : (double)ow / oh;
        if ((double)w / h > want) { sh = h; sw = h * want; } else { sw = w; sh = w / want; }
        sx0 = (w - sw) / 2; sy0 = (h - sh) / 2;
    }
    xi = (int *)malloc(sizeof(int) * ow * 2 + sizeof(unsigned short) * ow * 6);
    if (!xi) return -1;
    xw = xi + ow;
    hrow[0] = (unsigned short *)(xw + ow);
    hrow[1] = hrow[0] + ow * 3;
    held[0] = held[1] = -1;
    for (x = 0; x < ow; x++) {   /* per column: the left source pixel and its weight (0..256) */
        double fx = sx0 + (x + 0.5) * sw / ow - 0.5;
        int ix = (int)fx;
        if (fx < 0) { fx = 0; ix = 0; }
        if (ix > w - 2) { ix = w - 2; fx = w - 1; }
        xi[x] = ix;
        xw[x] = (int)((fx - ix) * 256 + 0.5);
    }
    /* separable: each source row is stretched across once (the screen has ~2x the rows), then rows blend */
    for (y = 0; y < oh; y++) {
        double fy = sy0 + (y + 0.5) * sh / oh - 0.5;
        int iy = (int)fy, wy, uy, k;
        const unsigned short *h0, *h1;
        const unsigned char *q[4];
        unsigned short *o = out + (size_t)y * ow;
        if (fy < 0) { fy = 0; iy = 0; }
        if (iy > h - 2) { iy = h - 2; fy = h - 1; }
        wy = (int)((fy - iy) * 256 + 0.5);
        uy = 256 - wy;
        if (held[0] != iy) {
            if (held[1] == iy) {   /* moved down a row: the lower becomes the upper */
                unsigned short *t = hrow[0]; hrow[0] = hrow[1]; hrow[1] = t;
                held[0] = iy; held[1] = -1;
            } else { stretch_row(argb + (size_t)iy * w, xi, xw, ow, hrow[0]); held[0] = iy; }
        }
        if (held[1] != iy + 1) { stretch_row(argb + (size_t)(iy + 1) * w, xi, xw, ow, hrow[1]); held[1] = iy + 1; }
        h0 = hrow[0]; h1 = hrow[1];
        for (k = 0; k < 4; k++) q[k] = g_q4[bayer[(y & 3) * 4 + k]];
        for (x = 0; x < ow; x++, h0 += 3, h1 += 3) {
            const unsigned char *t = q[x & 3];
            int r = (h0[0] * uy + h1[0] * wy + 32768) >> 16;
            int g = (h0[1] * uy + h1[1] * wy + 32768) >> 16;
            int b = (h0[2] * uy + h1[2] * wy + 32768) >> 16;
            o[x] = (unsigned short)((t[r] << rs) | (t[g] << gs) | (t[b] << bs) | alpha);
        }
    }
    free(xi);
    return 0;
}

/* The film for live view, kept between frames (a frame used to stat the LUT and build its tables each
 * time): its own copy of the LUT, so a develop's cache never pulls it away. One look thread only. */
static struct { char path[512]; int highlight, shadow, ready; fl_look L; } g_live;

static const fl_look *live_look(const char *lut_path, const int *params, char *err, int errlen) {
    const char *p = lut_path ? lut_path : "";
    fl_look L;
    if (g_live.ready && strcmp(g_live.path, p) == 0 && g_live.highlight == params[FL_PARAM_HIGHLIGHT]
            && g_live.shadow == params[FL_PARAM_SHADOW]) return &g_live.L;
    if (strlen(p) >= sizeof g_live.path) { set_err(err, errlen, "LUT path too long"); return NULL; }
    if (look_init(&L, lut_path, params, err, errlen) != 0) return NULL;
    if (g_live.ready) free(g_live.L.data);
    g_live.ready = 0;
    g_live.L = L;
    g_live.L.data = NULL;
    if (L.data) {
        size_t bytes = sizeof(short) * 3 * (size_t)L.n * L.n * L.n;
        g_live.L.data = (short *)malloc(bytes);
        if (!g_live.L.data) { look_free(&L); set_err(err, errlen, "out of memory"); return NULL; }
        memcpy(g_live.L.data, L.data, bytes);
    }
    g_live.L.shared = 1;
    look_free(&L);
    strcpy(g_live.path, p);
    g_live.highlight = params[FL_PARAM_HIGHLIGHT];
    g_live.shadow = params[FL_PARAM_SHADOW];
    g_live.ready = 1;
    return &g_live.L;
}

int fl_live(const unsigned char *jpeg, unsigned long len, const char *lut_path, const int *params,
            unsigned int *argb, int max_w, int max_h, int *info, char *err, int errlen) {
    struct jpeg_decompress_struct d;
    struct fl_jerr je;
    const fl_look *L;
    fl_thumb P;
    unsigned char *volatile row = NULL, *volatile rgb = NULL;
    int ow, oh, i;

    memset(&P, 0, sizeof P);
    memset(info, 0, sizeof(int) * FL_LIVE_INFO);
    if ((L = live_look(lut_path, params, err, errlen)) == NULL) return -1;
    fl_jerr_init(&je);
    d.err = &je.pub;
    if (setjmp(je.jump)) {
        set_err(err, errlen, "%s", je.msg);
        jpeg_destroy_decompress(&d); free(row); free(rgb); thumb_free(&P);
        return -1;
    }
    jpeg_create_decompress(&d);
    jpeg_mem_src(&d, (unsigned char *)jpeg, len);
    jpeg_read_header(&d, TRUE);
    d.out_color_space = JCS_RGB;
    d.dct_method = JDCT_IFAST;
    d.do_fancy_upsampling = FALSE;
    d.scale_num = 1;
    d.scale_denom = 8;
    while (d.scale_denom > 1 && (int)(d.image_width / d.scale_denom) < max_w && (int)(d.image_height / d.scale_denom) < max_h)
        d.scale_denom /= 2;
    jpeg_start_decompress(&d);
    if (thumb_init(&P, (int)d.output_width, (int)d.output_height, max_w, max_h, 0) != 0) longjmp(je.jump, 1);
    row = (unsigned char *)malloc(d.output_width * 3);
    if (!row) longjmp(je.jump, 1);
    while (d.output_scanline < d.output_height) {
        int y = (int)d.output_scanline;
        JSAMPROW r = row;
        jpeg_read_scanlines(&d, &r, 1);
        thumb_row(&P, row, y);
    }
    jpeg_finish_decompress(&d);
    jpeg_destroy_decompress(&d);
    ow = P.W;
    oh = P.H;
    rgb = (unsigned char *)malloc((size_t)ow * oh * 3);
    if (!rgb) { thumb_free(&P); free(row); set_err(err, errlen, "out of memory"); return -1; }
    thumb_rgb(&P, rgb);
    /* the level and the histogram read the picture as it comes from the camera, before the look */
    fl_level(rgb, ow, oh, info + 2);
    for (i = 0; i < ow * oh; i++) info[5 + ((rgb[i * 3] * 77 + rgb[i * 3 + 1] * 150 + rgb[i * 3 + 2] * 29) >> 10)]++;
    look_row(L, &(fl_grain){ 0 }, rgb, ow * oh, 0);
    for (i = 0; i < ow * oh; i++) argb[i] = to_argb(rgb + i * 3);
    info[0] = ow;
    info[1] = oh;
    free(row); free(rgb);
    thumb_free(&P);
    return 0;
}

/* ------------------------------------------------------------------------------------------ */
/* the viewfinder's frame composer                                                               */
/*
 * One live frame, start to finish, for the camera's 16-bit screen: the JPEG decoded straight into a
 * kept buffer, the level and histogram read (when asked), the film's look, then for every screen pixel
 * one pass that stretches (bilinear), lays Latent's own type and fades over it (the overlay, drawn by
 * the screen only when it changes), and dithers to 4 bits a channel with blue noise. The screen then
 * copies one opaque picture a frame. A context per look thread: its own buffers and its own film.
 */

struct fl_frame_ctx {
    struct { char path[512]; int highlight, shadow, ready; fl_look L; } film;
    unsigned char *rgb, *row;
    int rgb_cap, row_cap, sw, sh;          /* the last frame, decoded and looked */
    unsigned int *prb;                     /* the looked frame packed: red << 16 | blue, a word a pixel */
    unsigned char *pg;                     /* and its green */
    int cap_px;
    unsigned int *hrb;                     /* two stretched rows: red and blue in one word, green apart */
    unsigned char *hg;
    int *xi, *xw, cap_ow;
};

fl_frame_ctx *fl_frame_new(void) { return (fl_frame_ctx *)calloc(1, sizeof(fl_frame_ctx)); }

void fl_frame_free(fl_frame_ctx *c) {
    if (!c) return;
    if (c->film.ready) free(c->film.L.data);
    free(c->rgb); free(c->row); free(c->prb); free(c->pg); free(c->hrb); free(c->hg); free(c->xi);
    free(c);
}

static const fl_look *frame_film(fl_frame_ctx *c, const char *lut_path, const int *params, char *err, int errlen) {
    const char *p = lut_path ? lut_path : "";
    fl_look L;
    if (c->film.ready && strcmp(c->film.path, p) == 0 && c->film.highlight == params[FL_PARAM_HIGHLIGHT]
            && c->film.shadow == params[FL_PARAM_SHADOW]) return &c->film.L;
    if (strlen(p) >= sizeof c->film.path) { set_err(err, errlen, "LUT path too long"); return NULL; }
    if (look_init(&L, lut_path, params, err, errlen) != 0) return NULL;
    if (c->film.ready) free(c->film.L.data);
    c->film.ready = 0;
    c->film.L = L;
    c->film.L.data = NULL;
    if (L.data) {
        size_t bytes = sizeof(short) * 3 * (size_t)L.n * L.n * L.n;
        c->film.L.data = (short *)malloc(bytes);
        if (!c->film.L.data) { look_free(&L); set_err(err, errlen, "out of memory"); return NULL; }
        memcpy(c->film.L.data, L.data, bytes);
    }
    c->film.L.shared = 1;
    look_free(&L);
    strcpy(c->film.path, p);
    c->film.highlight = params[FL_PARAM_HIGHLIGHT];
    c->film.shadow = params[FL_PARAM_SHADOW];
    c->film.ready = 1;
    return &c->film.L;
}

/* ---- the overlay: Latent's type and fades over the picture, premultiplied, with the runs where it shows */

typedef struct {
    int w, h, gen, refs, retired;
    unsigned int *rb;       /* per pixel: red << 16 | blue, premultiplied */
    unsigned short *ga;     /* per pixel: green (premultiplied) | (255 - alpha + its top bit, 0..256) << 8 */
    int *row_runs;          /* h + 1 offsets into runs */
    unsigned short *runs;   /* x0, x1 pairs: where alpha > 0 */
} fl_overlay;

static pthread_mutex_t g_ovl_mu = PTHREAD_MUTEX_INITIALIZER;
static fl_overlay *g_ovl;
static int g_ovl_gen;

static void ovl_free(fl_overlay *o) { if (o) { free(o->rb); free(o->ga); free(o->row_runs); free(o->runs); free(o); } }

static fl_overlay *ovl_acquire(void) {
    fl_overlay *o;
    pthread_mutex_lock(&g_ovl_mu);
    o = g_ovl;
    if (o) o->refs++;
    pthread_mutex_unlock(&g_ovl_mu);
    return o;
}

static void ovl_release(fl_overlay *o) {
    int gone = 0;
    if (!o) return;
    pthread_mutex_lock(&g_ovl_mu);
    if (--o->refs == 0 && o->retired) gone = 1;
    pthread_mutex_unlock(&g_ovl_mu);
    if (gone) ovl_free(o);
}

/* x / 255, rounded, for x up to 65535: no divide (the camera's core has none) */
static inline int div255(int x) { x += 128; return (x + (x >> 8)) >> 8; }

int fl_set_overlay(const unsigned int *argb, int w, int h) {
    fl_overlay *o = NULL, *old;
    int gen;
    if (argb) {
        int x, y, n = 0, cap = 1024;
        if (w < 1 || h < 1 || w > 4096 || h > 4096) return -1;
        o = (fl_overlay *)calloc(1, sizeof *o);
        if (!o) return -1;
        o->w = w; o->h = h;
        o->rb = (unsigned int *)malloc(sizeof(unsigned int) * (size_t)w * h);
        o->ga = (unsigned short *)malloc(sizeof(unsigned short) * (size_t)w * h);
        o->row_runs = (int *)malloc(sizeof(int) * (h + 1));
        o->runs = (unsigned short *)malloc(sizeof(unsigned short) * 2 * cap);
        if (!o->rb || !o->ga || !o->row_runs || !o->runs) { ovl_free(o); return -1; }
        for (y = 0; y < h; y++) {
            const unsigned int *r = argb + (size_t)y * w;
            unsigned int *drb = o->rb + (size_t)y * w;
            unsigned short *dga = o->ga + (size_t)y * w;
            int in = 0;
            o->row_runs[y] = n;
            for (x = 0; x <= w; x++) {
                unsigned int p = x < w ? r[x] : 0;
                int a = (int)(p >> 24);
                if (x < w) {
                    int ia = 255 - a;
                    drb[x] = (unsigned int)div255(((p >> 16) & 255) * a) << 16 | (unsigned int)div255((p & 255) * a);
                    dga[x] = (unsigned short)(div255(((p >> 8) & 255) * a) | (ia + (ia >> 7)) << 8);
                }
                if (a > 0 && !in) {
                    if (n >= cap) {
                        unsigned short *more = (unsigned short *)realloc(o->runs, sizeof(unsigned short) * 4 * cap);
                        if (!more) { ovl_free(o); return -1; }
                        o->runs = more; cap *= 2;
                    }
                    o->runs[n * 2] = (unsigned short)x; in = 1;
                } else if (a == 0 && in) { o->runs[n * 2 + 1] = (unsigned short)x; n++; in = 0; }
            }
        }
        o->row_runs[h] = n;
    }
    pthread_mutex_lock(&g_ovl_mu);
    old = g_ovl;
    gen = ++g_ovl_gen;
    if (o) o->gen = gen;
    g_ovl = o;
    if (old) { old->retired = 1; if (old->refs > 0) old = NULL; }
    pthread_mutex_unlock(&g_ovl_mu);
    ovl_free(old);
    return gen;
}

/* ---- decode: straight into the kept buffer when the frame already fits, else resampled */

static int frame_decode(fl_frame_ctx *c, const unsigned char *jpeg, unsigned long len, int max_w, int max_h, char *err, int errlen) {
    struct jpeg_decompress_struct d;
    struct fl_jerr je;
    fl_thumb P;
    int volatile thumbing = 0;
    int ow, oh;
    memset(&P, 0, sizeof P);
    fl_jerr_init(&je);
    d.err = &je.pub;
    if (setjmp(je.jump)) {
        set_err(err, errlen, "%s", je.msg);
        jpeg_destroy_decompress(&d);
        if (thumbing) thumb_free(&P);
        return -1;
    }
    jpeg_create_decompress(&d);
    jpeg_mem_src(&d, (unsigned char *)jpeg, len);
    jpeg_read_header(&d, TRUE);
    d.out_color_space = JCS_RGB;
    d.dct_method = JDCT_IFAST;
    d.do_fancy_upsampling = TRUE;   /* chroma eased between samples, not in 2-pixel steps (cheap for 4:2:2) */
    d.scale_num = 1;
    d.scale_denom = 8;
    while (d.scale_denom > 1 && (int)(d.image_width / d.scale_denom) < max_w && (int)(d.image_height / d.scale_denom) < max_h)
        d.scale_denom /= 2;
    jpeg_start_decompress(&d);
    if ((int)d.output_width <= max_w && (int)d.output_height <= max_h) { ow = (int)d.output_width; oh = (int)d.output_height; }
    else {
        if (thumb_init(&P, (int)d.output_width, (int)d.output_height, max_w, max_h, 0) != 0) longjmp(je.jump, 1);
        thumbing = 1;
        ow = P.W; oh = P.H;
    }
    if (c->rgb_cap < ow * oh * 3) {
        free(c->rgb); c->rgb_cap = 0;
        c->rgb = (unsigned char *)malloc((size_t)ow * oh * 3);
        if (!c->rgb) longjmp(je.jump, 1);
        c->rgb_cap = ow * oh * 3;
    }
    if (thumbing && c->row_cap < (int)d.output_width * 3) {
        free(c->row); c->row_cap = 0;
        c->row = (unsigned char *)malloc(d.output_width * 3);
        if (!c->row) longjmp(je.jump, 1);
        c->row_cap = (int)d.output_width * 3;
    }
    while (d.output_scanline < d.output_height) {
        int y = (int)d.output_scanline;
        JSAMPROW r = thumbing ? c->row : c->rgb + (size_t)y * ow * 3;
        jpeg_read_scanlines(&d, &r, 1);
        if (thumbing) thumb_row(&P, c->row, y);
    }
    jpeg_finish_decompress(&d);
    jpeg_destroy_decompress(&d);
    if (thumbing) { thumb_rgb(&P, c->rgb); thumb_free(&P); }
    c->sw = ow; c->sh = oh;
    return 0;
}

/* ---- compose: stretch, overlay, dither, pack; every screen pixel once */

/* One source row stretched across, two channels a multiply (red and blue share a word, 16 bits apart:
 * a channel times a weight of at most 256 stays inside its half). Rounded to 8 bits. */
static void stretch_rb(const unsigned int *srb, const unsigned char *sg, const int *xi, const int *xw, int ow,
                       unsigned int *hrb, unsigned char *hg) {
    int x;
    for (x = 0; x < ow; x++) {
        int i = xi[x], wx = xw[x], ux = 256 - wx;
        hrb[x] = ((srb[i] * (unsigned)ux + srb[i + 1] * (unsigned)wx + 0x00800080u) >> 8) & 0x00FF00FFu;
        hg[x] = (unsigned char)((sg[i] * ux + sg[i + 1] * wx + 128) >> 8);
    }
}

/* Pass 1 of a screen row: the two stretched rows blended (two channels a multiply) into lrb / lg. */
static void blend_rows(const unsigned int *h0rb, const unsigned int *h1rb, const unsigned char *h0g, const unsigned char *h1g,
                       unsigned int uy, unsigned int wy, int ow, unsigned int *lrb, unsigned char *lg) {
    int x;
    if (wy == 0) {   /* exactly on a source row */
        memcpy(lrb, h0rb, sizeof(unsigned int) * ow);
        memcpy(lg, h0g, (size_t)ow);
        return;
    }
    for (x = 0; x < ow; x++) {
        lrb[x] = ((h0rb[x] * uy + h1rb[x] * wy + 0x00800080u) >> 8) & 0x00FF00FFu;
        lg[x] = (unsigned char)((h0g[x] * uy + h1g[x] * wy + 128) >> 8);
    }
}

/* The overlay over x0..x1 of the row: premultiplied, 255 - alpha widened to 0..256, so a channel never
 * passes 255 (the picture's share is at most 255 - alpha, the overlay's at most alpha). */
static void overlay_span(unsigned int *lrb, unsigned char *lg, const unsigned int *orb, const unsigned short *oga, int x0, int x1) {
    int x;
    for (x = x0; x < x1; x++) {
        unsigned int ga = oga[x], ia = ga >> 8;
        lrb[x] = (((lrb[x] * ia) >> 8) & 0x00FF00FFu) + orb[x];
        lg[x] = (unsigned char)(((lg[x] * ia) >> 8) + (ga & 255));
    }
}

/*
 * Pass 2: dithered to 4 bits a channel by arithmetic alone (no divide): v * 241 >> 8 is
 * v * 15 / 255 in sixteenths (0..240), plus the pixel's blue-noise threshold (0..15), over 16; red and
 * blue together in one word. On average exactly v / 17, the 4-bit level. Packed through two small
 * tables (pack_tables): RGB565 carrying the 4-bit values exactly, or 4444 at the given shifts.
 */
static void dither_row(const unsigned int *lrb, const unsigned char *lg, const unsigned char *bn, int ox, int ow,
                       unsigned short *o16, const unsigned short *trb, const unsigned short *tg) {
    unsigned int tt[64];
    int x;
    const unsigned int m = 0x000F000Fu;
    for (x = 0; x < 64; x++) { unsigned int t = bn[(x + ox) & 63] >> 4; tt[x] = t | t << 16; }   /* this row's thresholds, both lanes */
    for (x = 0; x < ow; x++) {
        unsigned int t = tt[x & 63];
        unsigned int q = (((((lrb[x] * 241u) >> 8) & 0x00FF00FFu) + t) >> 4) & m;   /* red << 16 | blue, 4 bits each */
        unsigned int n = (((lg[x] * 241u) >> 8) + t) >> 4 & 15;
        o16[x] = (unsigned short)(trb[(q >> 12 | q) & 255] | tg[n]);
    }
}

/* The packing tables for dither_row: red and blue nibbles (red << 4 | blue) and green, already in place. */
static void pack_tables(int f565, int rs, int gs, int bs, unsigned short alpha, unsigned short *trb, unsigned short *tg) {
    int r, b;
    for (r = 0; r < 16; r++) {
        for (b = 0; b < 16; b++)
            trb[r << 4 | b] = f565 ? (unsigned short)(((r << 1) | (r >> 3)) << 11 | ((b << 1) | (b >> 3)))
                                   : (unsigned short)(r << rs | b << bs | alpha);
        tg[r] = f565 ? (unsigned short)(((r << 2) | (r >> 2)) << 5) : (unsigned short)(r << gs);
    }
}

static int frame_compose(fl_frame_ctx *c, fl_overlay *o, unsigned short *out, int ow, int oh, int stride,
                         int aspect1000, int format, const int *shifts, int ox, int oy) {
    const int w = c->sw, h = c->sh;
    int x, y, i, held[2], rs = 11, gs = 5, bs = 0, as = 0, use_ovl, f565 = format != FL_FMT_4444;
    unsigned int *hrb[2], *lrb;
    unsigned char *hg[2], *lg;
    unsigned short alpha = 0, trb[256], tg[16];
    double sw, sh, sx0, sy0;
    if (!c->rgb || w < 2 || h < 2 || ow < 1 || oh < 1 || ow > 4096 || oh > 4096 || stride < ow) return -1;
    if (!f565) {
        if (!shifts) return -1;
        rs = shifts[0]; gs = shifts[1]; bs = shifts[2]; as = shifts[3];
        if (rs < 0 || rs > 12 || gs < 0 || gs > 12 || bs < 0 || bs > 12 || as < 0 || as > 12) return -1;
        alpha = (unsigned short)(15 << as);
    }
    pack_tables(f565, rs, gs, bs, alpha, trb, tg);
    use_ovl = o && o->w == ow && o->h == oh;
    if (c->cap_px < w * h) {
        free(c->prb); free(c->pg);
        c->cap_px = 0;
        c->prb = (unsigned int *)malloc(sizeof(unsigned int) * (size_t)w * h);
        c->pg = (unsigned char *)malloc((size_t)w * h);
        if (!c->prb || !c->pg) { free(c->prb); free(c->pg); c->prb = NULL; c->pg = NULL; return -1; }
        c->cap_px = w * h;
    }
    if (c->cap_ow < ow) {
        free(c->xi); free(c->hrb); free(c->hg);
        c->cap_ow = 0;
        c->xi = (int *)malloc(sizeof(int) * ow * 2);
        c->hrb = (unsigned int *)malloc(sizeof(unsigned int) * ow * 3);   /* two stretched rows and the screen row */
        c->hg = (unsigned char *)malloc((size_t)ow * 3);
        if (!c->xi || !c->hrb || !c->hg) { free(c->xi); free(c->hrb); free(c->hg); c->xi = NULL; c->hrb = NULL; c->hg = NULL; return -1; }
        c->cap_ow = ow;
    }
    {   /* the looked frame, packed for two channels a multiply */
        const unsigned char *p = c->rgb;
        for (i = 0; i < w * h; i++, p += 3) { c->prb[i] = (unsigned int)p[0] << 16 | p[2]; c->pg[i] = p[1]; }
    }
    c->xw = c->xi + ow;
    hrb[0] = c->hrb; hrb[1] = c->hrb + ow;
    hg[0] = c->hg; hg[1] = c->hg + ow;
    lrb = c->hrb + ow * 2; lg = c->hg + ow * 2;
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
        c->xi[x] = ix;
        c->xw[x] = (int)((fx - ix) * 256 + 0.5);
    }
    for (y = 0; y < oh; y++) {
        double fy = sy0 + (y + 0.5) * sh / oh - 0.5;
        int iy = (int)fy, wy;
        const unsigned char *bn = fl_bluenoise + (((y + oy) & 63) << 6);
        unsigned short *o16 = out + (size_t)y * stride;
        if (fy < 0) { fy = 0; iy = 0; }
        if (iy > h - 2) { iy = h - 2; fy = h - 1; }
        wy = (int)((fy - iy) * 256 + 0.5);
        if (held[0] != iy) {
            if (held[1] == iy) {   /* moved down a row: the lower becomes the upper */
                unsigned int *t = hrb[0]; unsigned char *tg = hg[0];
                hrb[0] = hrb[1]; hg[0] = hg[1]; hrb[1] = t; hg[1] = tg;
                held[0] = iy; held[1] = -1;
            } else {
                stretch_rb(c->prb + (size_t)iy * w, c->pg + (size_t)iy * w, c->xi, c->xw, ow, hrb[0], hg[0]);
                held[0] = iy;
            }
        }
        if (held[1] != iy + 1) {
            stretch_rb(c->prb + (size_t)(iy + 1) * w, c->pg + (size_t)(iy + 1) * w, c->xi, c->xw, ow, hrb[1], hg[1]);
            held[1] = iy + 1;
        }
        blend_rows(hrb[0], hrb[1], hg[0], hg[1], (unsigned)(256 - wy), (unsigned)wy, ow, lrb, lg);
        if (use_ovl) {   /* the overlay's runs on this row */
            int k, k1 = o->row_runs[y + 1];
            for (k = o->row_runs[y]; k < k1; k++)
                overlay_span(lrb, lg, o->rb + (size_t)y * ow, o->ga + (size_t)y * ow, o->runs[k * 2], o->runs[k * 2 + 1]);
        }
        dither_row(lrb, lg, bn, ox, ow, o16, trb, tg);
    }
    return 0;
}

int fl_frame(fl_frame_ctx *c, const unsigned char *jpeg, unsigned long len, const char *lut_path, const int *params,
             int flags, int seq, unsigned short *out, int ow, int oh, int stride, int aspect1000, int format,
             const int *shifts, int max_w, int max_h, int *info, int *tm, char *err, int errlen) {
    long t0 = now_ms(), t;
    fl_overlay *o;
    int rc, i, ox = 0, oy = 0;
    memset(info, 0, sizeof(int) * FL_FRAME_INFO);
    if (tm) memset(tm, 0, sizeof(int) * FL_FRAME_TIMES);
    if (!c) { set_err(err, errlen, "no frame context"); return -1; }
    if (jpeg) {
        const fl_look *L = frame_film(c, lut_path, params, err, errlen);
        if (!L) return -1;
        if (frame_decode(c, jpeg, len, max_w, max_h, err, errlen) != 0) return -1;
        t = now_ms(); if (tm) tm[FL_TM_DECODE] = (int)(t - t0); t0 = t;
        if (flags & FL_F_LEVEL) fl_level(c->rgb, c->sw, c->sh, info + 2);
        else info[2] = info[3] = info[4] = FL_NO_READING;
        if (flags & FL_F_HIST) {
            const unsigned char *p = c->rgb;
            for (i = 0; i < c->sw * c->sh; i++, p += 3) info[5 + ((p[0] * 77 + p[1] * 150 + p[2] * 29) >> 10)]++;
        }
        t = now_ms(); if (tm) tm[FL_TM_READ] = (int)(t - t0); t0 = t;
        look_row(L, &(fl_grain){ 0 }, c->rgb, c->sw * c->sh, 0);
        t = now_ms(); if (tm) tm[FL_TM_LOOK] = (int)(t - t0); t0 = t;
    } else {
        if (!c->rgb) { set_err(err, errlen, "no frame yet"); return -1; }
        info[2] = info[3] = info[4] = FL_NO_READING;
    }
    if (flags & FL_F_TEMPORAL) {   /* the grain moves each frame, as film's does */
        unsigned int hsh = hash32((unsigned int)seq * 2654435761u + 1);
        ox = (int)(hsh & 63); oy = (int)((hsh >> 8) & 63);
    }
    o = ovl_acquire();
    rc = frame_compose(c, o, out, ow, oh, stride, aspect1000, format, shifts, ox, oy);
    info[FL_FI_OVERLAY] = o && o->w == ow && o->h == oh ? o->gen : 0;
    ovl_release(o);
    if (rc != 0) { set_err(err, errlen, "bad screen size"); return -1; }
    if (tm) tm[FL_TM_COMPOSE] = (int)(now_ms() - t0);
    info[0] = c->sw;
    info[1] = c->sh;
    return 0;
}

/* ------------------------------------------------------------------------------------------ */
/* developing in the camera's own pixel format                                                  */
/*
 * The camera's hardware decoder and encoder work on packed UYVY (Cb Y0 Cr Y1, full-range BT.601,
 * 4:2:2). fl_develop_uyvy applies exactly the same look and grain to such rows, fetched and stored
 * through a callback, so this file never touches Sony memory itself (see sony_bridge.c).
 */

static int yc_r_cr[256], yc_g_cb[256], yc_g_cr[256], yc_b_cb[256];   /* 16.16 fixed point */

static void yc_tables(void) {
    int i;
    if (yc_r_cr[0]) return;
    for (i = 0; i < 256; i++) {
        int c = i - 128;
        yc_r_cr[i] = (int)(1.402 * 65536 + 0.5) * c;
        yc_b_cb[i] = (int)(1.772 * 65536 + 0.5) * c;
        yc_g_cb[i] = -(int)(0.344136 * 65536 + 0.5) * c;
        yc_g_cr[i] = -(int)(0.714136 * 65536 + 0.5) * c;
    }
}

static unsigned char clamp8(int v) { return (unsigned char)(v < 0 ? 0 : v > 255 ? 255 : v); }

void fl_uyvy_to_rgb(const unsigned char *in, unsigned char *rgb, int w) {
    int x;
    yc_tables();
    for (x = 0; x + 1 < w; x += 2, in += 4, rgb += 6) {
        int cb = in[0], cr = in[2], y0 = in[1] << 16, y1 = in[3] << 16;
        int dr = yc_r_cr[cr], dg = yc_g_cb[cb] + yc_g_cr[cr], db = yc_b_cb[cb];
        rgb[0] = clamp8((y0 + dr + 32768) >> 16); rgb[1] = clamp8((y0 + dg + 32768) >> 16); rgb[2] = clamp8((y0 + db + 32768) >> 16);
        rgb[3] = clamp8((y1 + dr + 32768) >> 16); rgb[4] = clamp8((y1 + dg + 32768) >> 16); rgb[5] = clamp8((y1 + db + 32768) >> 16);
    }
}

/* BT.601 full range, chroma of each pixel pair averaged (8-bit fixed point weights) */
void fl_rgb_to_uyvy(const unsigned char *rgb, unsigned char *out, int w) {
    int x;
    for (x = 0; x + 1 < w; x += 2, rgb += 6, out += 4) {
        int r0 = rgb[0], g0 = rgb[1], b0 = rgb[2], r1 = rgb[3], g1 = rgb[4], b1 = rgb[5];
        int y0 = (19595 * r0 + 38470 * g0 + 7471 * b0 + 32768) >> 16;
        int y1 = (19595 * r1 + 38470 * g1 + 7471 * b1 + 32768) >> 16;
        int r = r0 + r1, g = g0 + g1, b = b0 + b1;   /* pair sums */
        int cb = ((-11059 * r - 21709 * g + 32768 * b + 65536) >> 17) + 128;   /* + 65536: round to nearest */
        int cr = ((32768 * r - 27439 * g - 5329 * b + 65536) >> 17) + 128;
        out[0] = clamp8(cb); out[1] = clamp8(y0); out[2] = clamp8(cr); out[3] = clamp8(y1);
    }
}

#ifndef FL_YCC_GRID
#define FL_YCC_GRID 25
#endif

/*
 * The look, re-expressed on the camera's own values: a LUT from (Y, Cb, Cr) straight to (Y', Cb', Cr'),
 * built once from the tone curve and the RGB LUT. Per pixel that saves converting to RGB and back,
 * and grain (equal in R, G and B) becomes a change to Y alone.
 */
static int build_ycc_look(const fl_look *rgb, fl_look *Y, int grid) {
    int i, j, k;
    memset(Y, 0, sizeof *Y);
    for (i = 0; i < 256; i++) Y->tone[i] = (unsigned char)i;
    Y->n = grid;
    Y->data = (short *)malloc(sizeof(short) * 3 * grid * grid * grid);
    if (!Y->data) return -1;
    for (k = 0; k < grid; k++) for (j = 0; j < grid; j++) for (i = 0; i < grid; i++) {
        double y = i * 255.0 / (grid - 1), cb = j * 255.0 / (grid - 1) - 128, cr = k * 255.0 / (grid - 1) - 128;
        double r = y + 1.402 * cr, g = y - 0.344136 * cb - 0.714136 * cr, b = y + 1.772 * cb;
        unsigned char px[3];
        double R, G, B, yo, cbo, cro;
        short *o = Y->data + ((k * grid + j) * grid + i) * 3;
        px[0] = (unsigned char)(r < 0 ? 0 : r > 255 ? 255 : r + 0.5);
        px[1] = (unsigned char)(g < 0 ? 0 : g > 255 ? 255 : g + 0.5);
        px[2] = (unsigned char)(b < 0 ? 0 : b > 255 ? 255 : b + 0.5);
        look_apply(rgb, px);
        /* outside the RGB cube, carry on in a straight line from its surface rather than flattening:
           cells that straddle the edge then interpolate smoothly instead of being pulled off course */
        R = px[0] + (r - (r < 0 ? 0 : r > 255 ? 255 : r));
        G = px[1] + (g - (g < 0 ? 0 : g > 255 ? 255 : g));
        B = px[2] + (b - (b < 0 ? 0 : b > 255 ? 255 : b));
        yo = 0.299 * R + 0.587 * G + 0.114 * B;
        cbo = -0.168736 * R - 0.331264 * G + 0.5 * B + 128;
        cro = 0.5 * R - 0.418688 * G - 0.081312 * B + 128;
        o[0] = (short)(clamp01(yo / 255) * 4080 + 0.5);
        o[1] = (short)(clamp01(cbo / 255) * 4080 + 0.5);
        o[2] = (short)(clamp01(cro / 255) * 4080 + 0.5);
    }
    build_index(Y);
    return 0;
}

#ifndef FL_YCC_PAIRS
#define FL_YCC_PAIRS 1
#endif
#define PAIR_SPLIT 8   /* a pair whose brightnesses differ more than this (an edge) is looked up pixel by pixel */

/*
 * The look on the camera's own values (Cb Y0 Cr Y1 per pair), in a few interchangeable ways. They give
 * the same picture to within a level or so; which is fastest depends on the chip (cache size, no
 * NEON), so fl_bench times them all on the camera itself, on the same pixels.
 */
enum { V_PAIRS, V_REUSE, V_PACKED, V_SMALL, V_COUNT };
static const char *const V_NAMES[V_COUNT] = { "per pair", "colour reuse", "packed table", "smaller table" };
static int g_variant = V_PAIRS;
static int g_last_threads = 1;          /* threads the last block develop used */
static char g_helper_note[96];          /* why a helper didn't join, if one didn't */

typedef struct {
    fl_look L;                        /* the Y'CbCr table: 3 shorts per node */
    short *pad;                       /* the same with 4 shorts per node (8-byte aligned), or NULL */
    int ypos[256];                    /* brightness as a position in the table (x 256), as build_index places it */
    int o4R[256], o4G[256], o4B[256]; /* node offsets in pad */
} ycc_look;

static int ycc_make(const fl_look *rgb, ycc_look *Y, int grid, int padded) {
    int i, n = grid * grid * grid;
    memset(Y, 0, sizeof *Y);
    if (build_ycc_look(rgb, &Y->L, grid) != 0) return -1;
    for (i = 0; i < 256; i++) {
        Y->ypos[i] = i * (grid - 1) * 256 / 255;
        Y->o4R[i] = Y->L.idx[i] * 4;
        Y->o4G[i] = Y->L.idx[i] * grid * 4;
        Y->o4B[i] = Y->L.idx[i] * grid * grid * 4;
    }
    if (padded) {
        Y->pad = (short *)malloc(sizeof(short) * 4 * n);
        if (!Y->pad) { free(Y->L.data); Y->L.data = NULL; return -1; }
        for (i = 0; i < n; i++) {
            Y->pad[i * 4] = Y->L.data[i * 3];
            Y->pad[i * 4 + 1] = Y->L.data[i * 3 + 1];
            Y->pad[i * 4 + 2] = Y->L.data[i * 3 + 2];
            Y->pad[i * 4 + 3] = 0;
        }
    }
    return 0;
}

static void ycc_free(ycc_look *Y) {
    free(Y->L.data);
    free(Y->pad);
    Y->L.data = NULL;
    Y->pad = NULL;
}

/*
 * Tetrahedral lookup at (y, cb, cr), each output left unrounded (x 4096) in acc, plus how Y' changes
 * with y inside the chosen tetrahedron (per unit of table position). st = shorts per node (3 or 4).
 */
static inline void ycc_tetra(const short *c0, int st, int sg, int sb, int fr, int fg, int fb, int acc[3], int *slope) {
    const short *c1, *c2, *c3 = c0 + st + sg + sb;
    int f1, f2, f3, k, which;   /* which step of the walk moves along y: 1, 2 or 3 */
    if (fr >= fg) {
        if (fg >= fb)      { c1 = c0 + st; c2 = c0 + st + sg; f1 = fr; f2 = fg; f3 = fb; which = 1; }
        else if (fr >= fb) { c1 = c0 + st; c2 = c0 + st + sb; f1 = fr; f2 = fb; f3 = fg; which = 1; }
        else               { c1 = c0 + sb; c2 = c0 + st + sb; f1 = fb; f2 = fr; f3 = fg; which = 2; }
    } else {
        if (fb >= fg)      { c1 = c0 + sb; c2 = c0 + sg + sb; f1 = fb; f2 = fg; f3 = fr; which = 3; }
        else if (fb >= fr) { c1 = c0 + sg; c2 = c0 + sg + sb; f1 = fg; f2 = fb; f3 = fr; which = 3; }
        else               { c1 = c0 + sg; c2 = c0 + st + sg; f1 = fg; f2 = fr; f3 = fb; which = 2; }
    }
    for (k = 0; k < 3; k++)
        acc[k] = (c0[k] << 8) + f1 * (c1[k] - c0[k]) + f2 * (c2[k] - c1[k]) + f3 * (c3[k] - c2[k]);
    *slope = which == 1 ? c1[0] - c0[0] : which == 2 ? c2[0] - c1[0] : c3[0] - c2[0];
}

static inline int clamp255(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

static inline void add_grain(const fl_grain *G, const signed char *t, int x, int *o0, int *o1) {
    *o0 = clamp255(*o0 + ((t[(x + G->ox) & TILE_MASK] * G->amp[*o0]) >> 8));
    *o1 = clamp255(*o1 + ((t[(x + 1 + G->ox) & TILE_MASK] * G->amp[*o1]) >> 8));
}

#define PAIR_SPLIT 8   /* a pair whose brightnesses differ more than this (an edge) is looked up pixel by pixel */

/*
 * One lookup per pair: the two pixels share their colour, so the table is read once at their average
 * brightness, and each pixel's own brightness follows the table's slope from there (exact while both
 * stay in the same tetrahedron). Pairs straddling an edge are looked up one by one. packed: read the
 * 4-short table. Grain goes on brightness only.
 */
static inline void pairs_core(const ycc_look *Y, int packed, const fl_grain *G, unsigned char *u, int w, int y) {
    const fl_look *L = &Y->L;
    const int st = packed ? 4 : 3, sg = L->n * st, sb = L->n * L->n * st;
    const short *data = packed ? Y->pad : L->data;
    const int *ypos = Y->ypos;
    const signed char *t = G->on ? G->tile + (((y + G->oy) & TILE_MASK) << TILE_BITS) : NULL;
    int x, pm = -1, pcb = -1, pcr = -1, acc[3] = { 0, 0, 0 }, slope = 0, cb2 = 0, cr2 = 0;
#define NODE(yy, b, r) (data + (packed ? Y->o4R[yy] + Y->o4G[b] + Y->o4B[r] : L->offR[yy] + L->offG[b] + L->offB[r]))
    for (x = 0; x + 1 < w; x += 2, u += 4) {
        int cb = u[0], y0 = u[1], cr = u[2], y1 = u[3], d = y0 - y1, o0, o1;
        if (d > PAIR_SPLIT || d < -PAIR_SPLIT) {
            int a0[3], a1[3], s0, s1;
            ycc_tetra(NODE(y0, cb, cr), st, sg, sb, L->frac[y0], L->frac[cb], L->frac[cr], a0, &s0);
            ycc_tetra(NODE(y1, cb, cr), st, sg, sb, L->frac[y1], L->frac[cb], L->frac[cr], a1, &s1);
            o0 = (a0[0] + 2048) >> 12;
            o1 = (a1[0] + 2048) >> 12;
            u[0] = (unsigned char)((a0[1] + a1[1] + 4096) >> 13);
            u[2] = (unsigned char)((a0[2] + a1[2] + 4096) >> 13);
            pm = -1;
        } else {
            int m = (y0 + y1 + 1) >> 1;
            if (m != pm || cb != pcb || cr != pcr) {   /* neighbouring pairs often repeat */
                ycc_tetra(NODE(m, cb, cr), st, sg, sb, L->frac[m], L->frac[cb], L->frac[cr], acc, &slope);
                cb2 = (acc[1] + 2048) >> 12;
                cr2 = (acc[2] + 2048) >> 12;
                pm = m; pcb = cb; pcr = cr;
            }
            o0 = (acc[0] + slope * (ypos[y0] - ypos[m]) + 2048) >> 12;
            o1 = (acc[0] + slope * (ypos[y1] - ypos[m]) + 2048) >> 12;
            u[0] = (unsigned char)cb2;
            u[2] = (unsigned char)cr2;
        }
        o0 = clamp255(o0);
        o1 = clamp255(o1);
        if (t) add_grain(G, t, x, &o0, &o1);
        u[1] = (unsigned char)o0;
        u[3] = (unsigned char)o1;
    }
#undef NODE
}

static void ycc_pairs(const ycc_look *Y, const fl_grain *G, unsigned char *u, int w, int y) { pairs_core(Y, 0, G, u, w, y); }
static void ycc_pairs_packed(const ycc_look *Y, const fl_grain *G, unsigned char *u, int w, int y) { pairs_core(Y, 1, G, u, w, y); }

/*
 * Colour reuse: interpolate across colour (Cb, Cr) once on the two brightness planes either side,
 * then each pixel only blends between those two along brightness. While neighbouring pairs keep the
 * same colour and brightness cell (common in smooth areas) the table isn't read at all.
 * This is trilinear interpolation, not tetrahedral: a slightly different, equally smooth blend.
 */
/* Bilinear across colour on brightness planes j and j + 1: b0 = plane j, db = plane j + 1 minus plane j (x 4096). */
static inline void colour_planes(const fl_look *L, int j, int cb, int cr, int b0[3], int db[3]) {
    const int sg = L->n * 3, sb = L->n * L->n * 3;
    const short *p = L->data + j * 3 + L->offG[cb] + L->offB[cr];
    int fg = L->frac[cb], fb = L->frac[cr], k;
    for (k = 0; k < 3; k++) {
        int c00 = p[k], c10 = p[sg + k], c01 = p[sb + k], c11 = p[sg + sb + k];
        int d00 = p[3 + k], d10 = p[3 + sg + k], d01 = p[3 + sb + k], d11 = p[3 + sg + sb + k];
        int lo = (c00 << 8) + fg * (c10 - c00) + fb * (c01 - c00) + ((fg * fb * (c11 - c10 - c01 + c00)) >> 8);
        int hi = (d00 << 8) + fg * (d10 - d00) + fb * (d01 - d00) + ((fg * fb * (d11 - d10 - d01 + d00)) >> 8);
        b0[k] = lo;
        db[k] = hi - lo;
    }
}

static void ycc_reuse(const ycc_look *Y, const fl_grain *G, unsigned char *u, int w, int y) {
    const fl_look *L = &Y->L;
    const signed char *t = G->on ? G->tile + (((y + G->oy) & TILE_MASK) << TILE_BITS) : NULL;
    int x, kj = -1, kcb = -1, kcr = -1, b0[3] = { 0, 0, 0 }, db[3] = { 0, 0, 0 };
    for (x = 0; x + 1 < w; x += 2, u += 4) {
        int cb = u[0], y0 = u[1], cr = u[2], y1 = u[3], j = L->idx[y0], o0, o1, f0, f1;
        if (L->idx[y1] != j) {   /* the pair spans two brightness cells (an edge): each pixel on its own */
            int a0[3], d0[3], a1[3], d1[3], j1 = L->idx[y1];
            colour_planes(L, j, cb, cr, a0, d0);
            colour_planes(L, j1, cb, cr, a1, d1);
            f0 = Y->ypos[y0] - j * 256;
            f1 = Y->ypos[y1] - j1 * 256;
            o0 = clamp255((a0[0] + ((d0[0] * f0) >> 8) + 2048) >> 12);
            o1 = clamp255((a1[0] + ((d1[0] * f1) >> 8) + 2048) >> 12);
            u[0] = (unsigned char)clamp255((a0[1] + ((d0[1] * f0) >> 8) + a1[1] + ((d1[1] * f1) >> 8) + 4096) >> 13);
            u[2] = (unsigned char)clamp255((a0[2] + ((d0[2] * f0) >> 8) + a1[2] + ((d1[2] * f1) >> 8) + 4096) >> 13);
        } else {
            int fm;
            if (j != kj || cb != kcb || cr != kcr) {   /* same colour and cell as the last pair: nothing to read */
                colour_planes(L, j, cb, cr, b0, db);
                kj = j; kcb = cb; kcr = cr;
            }
            f0 = Y->ypos[y0] - j * 256;
            f1 = Y->ypos[y1] - j * 256;
            fm = (f0 + f1) >> 1;
            o0 = clamp255((b0[0] + ((db[0] * f0) >> 8) + 2048) >> 12);
            o1 = clamp255((b0[0] + ((db[0] * f1) >> 8) + 2048) >> 12);
            u[0] = (unsigned char)clamp255((b0[1] + ((db[1] * fm) >> 8) + 2048) >> 12);
            u[2] = (unsigned char)clamp255((b0[2] + ((db[2] * fm) >> 8) + 2048) >> 12);
        }
        if (t) add_grain(G, t, x, &o0, &o1);
        u[1] = (unsigned char)o0;
        u[3] = (unsigned char)o1;
    }
}

/* The grid each variant's table uses. */
static int variant_grid(int v) { return v == V_SMALL ? 17 : FL_YCC_GRID; }

static void look_variant(int v, const ycc_look *Y, const fl_grain *G, unsigned char *u, int w, int y) {
    if (v == V_REUSE) ycc_reuse(Y, G, u, w, y);
    else if (v == V_PACKED) ycc_pairs_packed(Y, G, u, w, y);
    else ycc_pairs(Y, G, u, w, y);
}

void fl_set_variant(int v) { g_variant = v >= 0 && v < V_COUNT ? v : V_PAIRS; }

static int develop_rows(const char *lut_path, const int *params, int w, int h, fl_row_io io,
                        void *ctx, fl_progress_fn progress, void *pctx, char *err, int errlen);

int fl_develop_uyvy(const char *lut_path, const int *params, int w, int h, fl_row_io io, void *ctx,
                    fl_progress_fn progress, void *pctx, char *err, int errlen) {
    return develop_rows(lut_path, params, w, h, io, ctx, progress, pctx, err, errlen);
}

/* Rows copied in and out one at a time (io): the reference path that the block develop is tested against. */
static int develop_rows(const char *lut_path, const int *params, int w, int h, fl_row_io io,
                        void *ctx, fl_progress_fn progress, void *pctx, char *err, int errlen) {
    fl_look L;
    ycc_look Y;
    fl_grain G, none;
    unsigned char *uyvy = NULL, *rgb = NULL;
    long t0 = now_ms(), cpu0 = cpu_ms(), t, t_read = 0, t_look = 0, t_write = 0;
    int y, x, last = -1, rc = 0, v = g_variant;

    memset(g_timing, 0, sizeof g_timing);
    if (w < 2 || h < 1 || (w & 1) || w > 20000 || h > 20000) { set_err(err, errlen, "bad image size %dx%d", w, h); return -1; }
    yc_tables();
    if (look_init(&L, lut_path, params, err, errlen) != 0) return -1;
    if (grain_init(&G, params, err, errlen) != 0) { look_free(&L); return -1; }
    memset(&none, 0, sizeof none);
    memset(&Y, 0, sizeof Y);
    if (FL_YCC_GRID && ycc_make(&L, &Y, variant_grid(v), v == V_PACKED) != 0) { set_err(err, errlen, "out of memory for the look"); rc = -1; goto done; }
    uyvy = (unsigned char *)malloc((size_t)w * 2);
    rgb = (unsigned char *)malloc((size_t)w * 3);
    if (!uyvy || !rgb) { set_err(err, errlen, "out of memory"); rc = -1; goto done; }
    for (y = 0; y < h; y++) {
        unsigned char *row = uyvy;
        t = now_ms();
        if (io(ctx, y, uyvy, 0) != 0) { set_err(err, errlen, "reading row %d failed", y); rc = -1; goto done; }
        t_read += now_ms() - t;
        t = now_ms();
#if FL_YCC_GRID == 0   /* reference path for tests: through RGB */
        fl_uyvy_to_rgb(row, rgb, w);
        look_row(&L, &G, rgb, w, y);
        fl_rgb_to_uyvy(rgb, row, w);
        (void)x; (void)none;
#elif FL_YCC_PAIRS
        look_variant(v, &Y, &G, row, w, y);
        (void)none; (void)x;
#else
        /* each pixel as (Y, Cb, Cr): a pair shares its chroma */
        for (x = 0; x + 1 < w; x += 2) {
            unsigned char *q = rgb + x * 3, *u = row + x * 2;
            q[0] = u[1]; q[1] = u[0]; q[2] = u[2];
            q[3] = u[3]; q[4] = u[0]; q[5] = u[2];
        }
        look_row(&Y.L, &none, rgb, w, y);
        if (G.on) {   /* grain: the same change in R, G and B is a change in Y alone */
            const signed char *tl = G.tile + (((y + G.oy) & TILE_MASK) << TILE_BITS);
            for (x = 0; x < w; x++) {
                int v = rgb[x * 3], d = (tl[(x + G.ox) & TILE_MASK] * G.amp[v]) >> 8;
                v += d;
                rgb[x * 3] = (unsigned char)(v < 0 ? 0 : v > 255 ? 255 : v);
            }
        }
        for (x = 0; x + 1 < w; x += 2) {
            const unsigned char *q = rgb + x * 3;
            unsigned char *u = row + x * 2;
            u[0] = (unsigned char)((q[1] + q[4] + 1) >> 1);
            u[1] = q[0];
            u[2] = (unsigned char)((q[2] + q[5] + 1) >> 1);
            u[3] = q[3];
        }
#endif
        t_look += now_ms() - t;
        t = now_ms();
        if (io(ctx, y, uyvy, 1) != 0) { set_err(err, errlen, "writing row %d failed", y); rc = -1; goto done; }
        t_write += now_ms() - t;
        if (progress) {
            int pct = (int)((long long)(y + 1) * 100 / h);
            if (pct != last) { last = pct; progress(pctx, pct); }
        }
    }
    g_timing[FL_TIME_READ] = (int)t_read;
    g_timing[FL_TIME_LOOK] = (int)t_look;
    g_timing[FL_TIME_WRITE] = (int)t_write;
    g_timing[FL_TIME_TOTAL] = (int)(now_ms() - t0);
    g_timing[FL_TIME_CPU] = (int)(cpu_ms() - cpu0);
done:
    free(uyvy); free(rgb);
    ycc_free(&Y);
    look_free(&L); grain_free(&G);
    return rc;
}

/* ------------------------------------------------------------------------------------------ */
/* speed test: the look-loop variants timed against each other on the same pixels              */

/*
 * Decodes in_path at 1/scale into the camera's pixel layout (UYVY), applies the look with every
 * variant, and reports each one's time and how far its picture is from the full-precision reference
 * (through RGB). Nothing is written to the card. 0 = ok; report gets one line per variant.
 */
/* Decodes in_path at 1/scale (1, 2, 4 or 8) into packed UYVY rows (malloc'd), as the camera holds pixels. */
static int decode_uyvy(const char *in_path, int scale, unsigned char **out, int *pw, int *ph, char *err, int len) {
    struct jpeg_decompress_struct d;
    struct fl_jerr je;
    FILE *f;
    unsigned char *volatile buf = NULL, *volatile rgb = NULL;
    size_t stride;
    int w, h, y;
    f = fopen(in_path, "rb");
    if (!f) { set_err(err, len, "cannot open %s", in_path); return -1; }
    fl_jerr_init(&je);
    d.err = &je.pub;
    if (setjmp(je.jump)) {
        if (je.msg[0]) set_err(err, len, "%s", je.msg);
        jpeg_destroy_decompress(&d); fclose(f); free(buf); free(rgb);
        return -1;
    }
    jpeg_create_decompress(&d);
    jpeg_stdio_src(&d, f);
    jpeg_read_header(&d, TRUE);
    d.out_color_space = JCS_RGB;
    d.scale_num = 1;
    d.scale_denom = (unsigned)(scale == 1 || scale == 2 || scale == 4 || scale == 8 ? scale : 2);
    jpeg_start_decompress(&d);
    w = (int)d.output_width & ~1;
    h = (int)d.output_height;
    stride = (size_t)w * 2;
    buf = (unsigned char *)malloc(stride * h);
    rgb = (unsigned char *)malloc((size_t)d.output_width * 3);
    if (!buf || !rgb) { set_err(err, len, "out of memory for %dx%d", w, h); je.msg[0] = 0; longjmp(je.jump, 1); }
    while (d.output_scanline < d.output_height) {
        JSAMPROW r = rgb;
        y = (int)d.output_scanline;
        jpeg_read_scanlines(&d, &r, 1);
        fl_rgb_to_uyvy(rgb, buf + (size_t)y * stride, w);
    }
    jpeg_finish_decompress(&d);
    jpeg_destroy_decompress(&d);
    fclose(f);
    free(rgb);
    *out = buf; *pw = w; *ph = h;
    return 0;
}

int fl_bench(const char *in_path, const char *lut_path, const int *params, int scale, char *report, int len) {
    unsigned char *src = NULL, *ref = NULL, *work = NULL, *rgb = NULL;
    fl_look L;
    fl_grain G;
    int w, h, y, v, rep, pos = 0;
    size_t stride, total;
    long t, best;

    report[0] = 0;
    if (look_init(&L, lut_path, params, report, len) != 0) return -1;
    if (grain_init(&G, params, report, len) != 0) { look_free(&L); return -1; }
    yc_tables();
    if (decode_uyvy(in_path, scale, &src, &w, &h, report, len) != 0) { look_free(&L); grain_free(&G); return -1; }
    stride = (size_t)w * 2;
    total = stride * h;
    ref = (unsigned char *)malloc(total);
    work = (unsigned char *)malloc(total);
    rgb = (unsigned char *)malloc((size_t)w * 3);
    if (!ref || !work || !rgb) {
        set_err(report, len, "out of memory for %dx%d", w, h);
        free(src); free(ref); free(work); free(rgb); look_free(&L); grain_free(&G);
        return -1;
    }

    /* the reference: every pixel through RGB at full precision */
    memcpy(ref, src, total);
    t = now_ms();
    for (y = 0; y < h; y++) {
        fl_uyvy_to_rgb(ref + (size_t)y * stride, rgb, w);
        look_row(&L, &G, rgb, w, y);
        fl_rgb_to_uyvy(rgb, ref + (size_t)y * stride, w);
    }
    pos += snprintf(report + pos, len - pos, "%dx%d; through RGB %ld ms\n", w, h, now_ms() - t);

    /* two plain copies of the image: what moving rows in and out of a buffer costs */
    t = now_ms();
    memcpy(work, src, total);
    memcpy(work, ref, total);
    pos += snprintf(report + pos, len - pos, "two copies of the image %ld ms\n", now_ms() - t);

    for (v = 0; v < V_COUNT && pos < len - 1; v++) {
        ycc_look Y;
        long build, diff = 0;
        int maxd = 0;
        size_t i;
        t = now_ms();
        if (ycc_make(&L, &Y, variant_grid(v), v == V_PACKED) != 0) { pos += snprintf(report + pos, len - pos, "%s: out of memory\n", V_NAMES[v]); continue; }
        build = now_ms() - t;
        best = -1;
        for (rep = 0; rep < 2; rep++) {   /* best of two: the camera may be doing something else */
            memcpy(work, src, total);
            t = now_ms();
            for (y = 0; y < h; y++) look_variant(v, &Y, &G, work + (size_t)y * stride, w, y);
            t = now_ms() - t;
            if (best < 0 || t < best) best = t;
        }
        for (i = 0; i < total; i++) {
            int dd = work[i] - ref[i];
            if (dd < 0) dd = -dd;
            diff += dd;
            if (dd > maxd) maxd = dd;
        }
        pos += snprintf(report + pos, len - pos, "%s %ld ms (table %ld ms), vs reference mean %.2f max %d\n",
                        V_NAMES[v], best, build, (double)diff / (double)total, maxd);
        ycc_free(&Y);
    }
    free(src); free(ref); free(work); free(rgb);
    look_free(&L); grain_free(&G);
    return 0;
}

/* ------------------------------------------------------------------------------------------ */
/* cores: what the camera lets Latent use, and whether a second core speeds the look up        */

#ifdef __linux__
#include <sys/syscall.h>
#endif

/* First line of a small file (or the rest of the line starting with key), trimmed. */
static void read_text(const char *path, const char *key, char *out, int n) {
    char line[256];
    FILE *f = fopen(path, "r");
    out[0] = 0;
    if (!f) { snprintf(out, n, "unreadable"); return; }
    while (fgets(line, sizeof line, f)) {
        char *v = line;
        if (key) {
            if (strncmp(line, key, strlen(key)) != 0) continue;
            v = line + strlen(key);
            while (*v == ' ' || *v == '\t') v++;
        }
        v[strcspn(v, "\r\n")] = 0;
        snprintf(out, n, "%s", v);
        break;
    }
    fclose(f);
}

/* Per-core busy and total jiffies from /proc/stat; returns how many cores it saw (at most 8). */
static int read_stat(long long busy[8], long long total[8]) {
    char line[256];
    int n = 0;
    FILE *f = fopen("/proc/stat", "r");
    if (!f) return 0;
    while (fgets(line, sizeof line, f) && n < 8) {
        long long u, ni, sy, id, io = 0, irq = 0, sirq = 0;
        int cpu;
        if (strncmp(line, "cpu", 3) != 0 || line[3] < '0' || line[3] > '9') continue;
        if (sscanf(line, "cpu%d %lld %lld %lld %lld %lld %lld %lld", &cpu, &u, &ni, &sy, &id, &io, &irq, &sirq) < 5 || cpu < 0 || cpu >= 8) continue;
        busy[cpu] = u + ni + sy + irq + sirq;
        total[cpu] = busy[cpu] + id + io;
        if (cpu + 1 > n) n = cpu + 1;
    }
    fclose(f);
    return n;
}

/*
 * Read only: which cores exist and are on, which Latent may use, its CPU group, and how busy each
 * core is over one second while Latent sits idle. One "key: value" line each.
 */
int fl_cores(char *report, int len) {
    char a[128], b[128], c[128], d[128], e[160];
    long long b0[8] = { 0 }, t0[8] = { 0 }, b1[8] = { 0 }, t1[8] = { 0 };
    int pos = 0, n, i;
    read_text("/sys/devices/system/cpu/present", NULL, a, sizeof a);
    read_text("/sys/devices/system/cpu/online", NULL, b, sizeof b);
    read_text("/proc/self/status", "Cpus_allowed_list:", c, sizeof c);
    read_text("/proc/self/cpuset", NULL, d, sizeof d);
    read_text("/proc/self/cgroup", NULL, e, sizeof e);
    pos += snprintf(report + pos, len - pos, "cores present: %s\nonline: %s\nLatent allowed: %s\ncpuset: %s\ncgroup: %s\n", a, b, c, d, e);
    n = read_stat(b0, t0);
    usleep(1000000);
    read_stat(b1, t1);
    pos += snprintf(report + pos, len - pos, "busy while idle:");
    for (i = 0; i < n && pos < len - 1; i++) {
        long long dt = t1[i] - t0[i];
        if (dt <= 0) pos += snprintf(report + pos, len - pos, " cpu%d off", i);
        else pos += snprintf(report + pos, len - pos, " cpu%d %lld%%", i, (b1[i] - b0[i]) * 100 / dt);
    }
    if (pos < len - 1) pos += snprintf(report + pos, len - pos, "\n");
    return 0;
}

/* Which core a thread last ran on (field 39 of its stat), or -1. */
static int thread_cpu(int tid) {
#ifdef __linux__
    char path[64], buf[1024], *p;
    int field = 1, cpu = -1;
    FILE *f;
    snprintf(path, sizeof path, "/proc/self/task/%d/stat", tid);
    f = fopen(path, "r");
    if (!f) return -1;
    if (!fgets(buf, sizeof buf, f)) { fclose(f); return -1; }
    fclose(f);
    p = strrchr(buf, ')');   /* the name may contain spaces: count from after it (field 2) */
    if (!p) return -1;
    field = 2;
    for (p++; *p; p++) {
        if (*p == ' ') {
            field++;
            if (field == 39) { cpu = atoi(p + 1); break; }
        }
    }
    return cpu;
#else
    (void)tid;
    return -1;
#endif
}

static int my_tid(void) {
#ifdef __linux__
    return (int)syscall(__NR_gettid);
#else
    return 0;
#endif
}

int fl_pin_core(int core, int *err_no) {
    *err_no = 0;
    if (core < 0 || core > 31) { *err_no = EINVAL; return -1; }
#ifdef __linux__
    {
        unsigned long want = 1UL << core, got = 0;
        if (syscall(__NR_sched_setaffinity, my_tid(), sizeof want, &want) != 0) { *err_no = errno; return -1; }
        if (syscall(__NR_sched_getaffinity, my_tid(), sizeof got, &got) < 0 || got != want) { *err_no = EPERM; return -1; }
    }
    return 0;
#else
    return 0;
#endif
}

typedef struct {
    const ycc_look *Y;
    const fl_grain *G;
    unsigned char *pix;
    size_t stride;
    int w, y0, y1, variant;
    int widen;                      /* ask to be allowed onto every core first */
    char note[128];                 /* what the affinity request did */
    int cpu;                        /* where it ran */
} rows_job;

static void *rows_worker(void *p) {
    rows_job *j = (rows_job *)p;
    int y;
    j->note[0] = 0;
#ifdef __linux__
    if (j->widen) {
        unsigned long want = 0, got = 0;
        long ncpu = sysconf(_SC_NPROCESSORS_CONF), i;
        for (i = 0; i < ncpu && i < (long)(8 * sizeof want); i++) want |= 1UL << i;
        if (syscall(__NR_sched_setaffinity, my_tid(), sizeof want, &want) != 0)
            snprintf(j->note, sizeof j->note, "refused (errno %d)", errno);
        else if (syscall(__NR_sched_getaffinity, my_tid(), sizeof got, &got) < 0)
            snprintf(j->note, sizeof j->note, "asked %#lx, read-back failed (errno %d)", want, errno);
        else
            snprintf(j->note, sizeof j->note, "asked %#lx, got %#lx", want, got);
    }
#endif
    for (y = j->y0; y < j->y1; y++) look_variant(j->variant, j->Y, j->G, j->pix + (size_t)y * j->stride, j->w, y);
    j->cpu = thread_cpu(my_tid());
    return NULL;
}

/*
 * Times the look on in_path (decoded at 1/scale) on one core, then split across two threads with the
 * helper allowed onto every core, and checks both pictures are identical. Report lines say what the
 * affinity request did, where each thread ran, and the two times.
 */
int fl_bench_cores(const char *in_path, const char *lut_path, const int *params, int scale, char *report, int len) {
    unsigned char *src = NULL, *one = NULL, *two = NULL;
    int w = 0, h = 0, pos = 0, rc = -1;
    size_t stride, total;
    fl_look L;
    fl_grain G;
    ycc_look Y;
    rows_job main_job, helper;
    pthread_t th;
    long t, t_one, t_two;

    report[0] = 0;
    if (decode_uyvy(in_path, scale, &src, &w, &h, report, len) != 0) return -1;
    stride = (size_t)w * 2;
    total = stride * h;
    if (look_init(&L, lut_path, params, report, len) != 0) { free(src); return -1; }
    if (grain_init(&G, params, report, len) != 0) { look_free(&L); free(src); return -1; }
    memset(&Y, 0, sizeof Y);
    one = (unsigned char *)malloc(total);
    two = (unsigned char *)malloc(total);
    if (!one || !two || ycc_make(&L, &Y, variant_grid(g_variant), g_variant == V_PACKED) != 0) { set_err(report, len, "out of memory"); goto done; }

    memset(&main_job, 0, sizeof main_job);
    main_job.Y = &Y; main_job.G = &G; main_job.stride = stride; main_job.w = w; main_job.variant = g_variant;
    helper = main_job;

    memcpy(one, src, total);   /* one core: every row on this thread */
    main_job.pix = one; main_job.y0 = 0; main_job.y1 = h;
    t = now_ms();
    rows_worker(&main_job);
    t_one = now_ms() - t;

    memcpy(two, src, total);   /* two: the helper takes the bottom half */
    main_job.pix = two; main_job.y0 = 0; main_job.y1 = h / 2;
    helper.pix = two; helper.y0 = h / 2; helper.y1 = h; helper.widen = 1;
    t = now_ms();
    if (pthread_create(&th, NULL, rows_worker, &helper) != 0) { set_err(report, len, "no second thread"); goto done; }
    rows_worker(&main_job);
    pthread_join(th, NULL);
    t_two = now_ms() - t;

    pos += snprintf(report + pos, len - pos, "picture: %dx%d\nwiden: %s\n", w, h, helper.note[0] ? helper.note : "not tried (not Linux)");
    pos += snprintf(report + pos, len - pos, "ran on: main cpu %d, helper cpu %d\n", main_job.cpu, helper.cpu);
    pos += snprintf(report + pos, len - pos, "one core %ld ms, two threads %ld ms, pictures %s\n", t_one, t_two,
                    memcmp(one, two, total) == 0 ? "identical" : "DIFFERENT");
    rc = 0;
done:
    free(src); free(one); free(two);
    ycc_free(&Y);
    look_free(&L); grain_free(&G);
    return rc;
}

/* ------------------------------------------------------------------------------------------ */
/* developing in blocks, with helper threads on other cores                                     */

#include <sys/resource.h>

/*
 * The cores helpers may use, in order. Core 3 is Latent's own (where the camera starts it); core 0
 * carries most of the camera's own work, so it is left alone.
 */
static const int HELPER_CORES[] = { 1, 2 };
#define MAX_HELPERS 2
#define HELPER_NICE 5   /* below the camera's own work: anything it needs on those cores comes first */

typedef struct {
    pthread_mutex_t mu;
    pthread_cond_t go, done;
    int gen, finished, quit, ready, threads;   /* threads: this one plus the helpers that started well */
    const ycc_look *Y;
    const fl_grain *G;
    int variant, w;
    unsigned char *rows;                       /* the block in hand */
    int stride, y0, n;
    int next;                                  /* next row of the block nobody has taken yet */
} look_pool;

typedef struct {
    look_pool *P;
    pthread_t th;
    int core, index, ok, started;
    long cpu;   /* CPU ms it ran, filled in as it quits */
    char why[96];
} look_helper;

/*
 * Takes rows of the block one at a time until none are left: whoever is free takes the next, so a
 * thread that also had reading and writing to do simply takes fewer. Rows are independent, so the
 * picture doesn't depend on who did which.
 */
static int pool_slice(look_pool *P) {
    int r, done = 0;
    while ((r = __sync_fetch_and_add(&P->next, 1)) < P->n) {
        look_variant(P->variant, P->Y, P->G, P->rows + (size_t)r * P->stride, P->w, P->y0 + r);
        done++;
    }
    return done;
}

static void *pool_helper(void *arg) {
    look_helper *h = (look_helper *)arg;
    look_pool *P = h->P;
    int seen = 0;
    long cpu0 = cpu_ms();
    h->ok = 1;
#ifdef __linux__
    {
        unsigned long want = 1UL << h->core, got = 0;
        if (syscall(__NR_sched_setaffinity, my_tid(), sizeof want, &want) != 0) {
            snprintf(h->why, sizeof h->why, "core %d refused (errno %d)", h->core, errno);
            h->ok = 0;
        } else if (syscall(__NR_sched_getaffinity, my_tid(), sizeof got, &got) < 0 || got != want) {
            snprintf(h->why, sizeof h->why, "core %d not granted (got %#lx)", h->core, got);
            h->ok = 0;
        }
        if (h->ok) setpriority(PRIO_PROCESS, my_tid(), HELPER_NICE);
    }
#endif
    pthread_mutex_lock(&P->mu);
    P->ready++;
    pthread_cond_broadcast(&P->done);
    if (!h->ok) { pthread_mutex_unlock(&P->mu); return NULL; }   /* no other core: the main thread does its share */
    for (;;) {
        while (P->gen == seen && !P->quit) pthread_cond_wait(&P->go, &P->mu);
        if (P->quit) break;
        seen = P->gen;
        pthread_mutex_unlock(&P->mu);
        pool_slice(P);
        pthread_mutex_lock(&P->mu);
        P->finished++;
        pthread_cond_broadcast(&P->done);
    }
    pthread_mutex_unlock(&P->mu);
    h->cpu = cpu_ms() - cpu0;
    return NULL;
}

/*
 * The look over the whole picture, a block of rows at a time: get hands over the next block (read
 * once, changed in place), put sends it on. params[FL_PARAM_THREADS] = 1 develops on this core only;
 * 2 or 3 add helpers pinned to cores 1 (and 2) at lower priority. A helper that can't have its core
 * just doesn't join: the picture is the same either way, row for row.
 */
int fl_develop_blocks(const char *lut_path, const int *params, int w, int h, fl_block_get get, fl_block_put put, void *ctx,
                      fl_progress_fn progress, void *pctx, char *err, int errlen) {
    fl_look L;
    ycc_look Y;
    fl_grain G;
    look_pool P;
    look_helper helpers[MAX_HELPERS];
    long t0 = now_ms(), cpu0 = cpu_ms(), t, t_read = 0, t_look = 0, t_write = 0, t_wait = 0, hcpu = 0;
    int y = 0, last = -1, rc = 0, i, nh = 0, want, v = g_variant, own_rows = 0;

    memset(g_timing, 0, sizeof g_timing);
    if (w < 2 || h < 1 || (w & 1) || w > 20000 || h > 20000) { set_err(err, errlen, "bad image size %dx%d", w, h); return -1; }
    yc_tables();
    if (look_init(&L, lut_path, params, err, errlen) != 0) return -1;
    if (grain_init(&G, params, err, errlen) != 0) { look_free(&L); return -1; }
    memset(&Y, 0, sizeof Y);
    if (ycc_make(&L, &Y, variant_grid(v), v == V_PACKED) != 0) {
        set_err(err, errlen, "out of memory for the look");
        look_free(&L); grain_free(&G);
        return -1;
    }

    memset(&P, 0, sizeof P);
    pthread_mutex_init(&P.mu, NULL);
    pthread_cond_init(&P.go, NULL);
    pthread_cond_init(&P.done, NULL);
    P.Y = &Y; P.G = &G; P.variant = v; P.w = w; P.threads = 1;
    want = params[FL_PARAM_THREADS] - 1;
    if (want > MAX_HELPERS) want = MAX_HELPERS;
    memset(helpers, 0, sizeof helpers);
    for (i = 0; i < want; i++) {
        helpers[i].P = &P;
        helpers[i].core = HELPER_CORES[i];
        if (pthread_create(&helpers[i].th, NULL, pool_helper, &helpers[i]) == 0) { helpers[i].started = 1; nh++; }
        else snprintf(helpers[i].why, sizeof helpers[i].why, "no thread");
    }
    pthread_mutex_lock(&P.mu);
    while (P.ready < nh) pthread_cond_wait(&P.done, &P.mu);   /* each says whether it has its core */
    for (i = 0; i < want; i++) if (helpers[i].started && helpers[i].ok) helpers[i].index = P.threads++;
    pthread_mutex_unlock(&P.mu);
    g_last_threads = P.threads;
    g_helper_note[0] = 0;
    for (i = 0; i < want; i++) if (helpers[i].why[0]) snprintf(g_helper_note, sizeof g_helper_note, "%s", helpers[i].why);

    /*
     * Two block buffers, so reading and writing overlap the look: while the helpers work on block k
     * in one, this thread writes block k - 1 out of the other and reads block k + 1 into it, then joins
     * in on block k. Without helpers it is simply read, look, write.
     */
    {
        int cur = 0, n = 0, stride = 0, prev_y = -1, prev_n = 0;
        unsigned char *rows;
        t = now_ms();
        rows = get(ctx, 0, cur, &n, &stride);
        t_read += now_ms() - t;
        if (!rows || n < 1 || n > h) { set_err(err, errlen, "reading rows from 0 failed"); rc = -1; }
        while (rc == 0 && y < h) {
            int next_n = 0, next_stride = 0, other = 1 - cur;
            unsigned char *next_rows = NULL;
            long tb = now_ms();
            pthread_mutex_lock(&P.mu);   /* hand block k to the helpers */
            P.rows = rows; P.stride = stride; P.y0 = y; P.n = n; P.next = 0;
            P.finished = 0;
            P.gen++;
            pthread_cond_broadcast(&P.go);
            pthread_mutex_unlock(&P.mu);
            if (P.threads > 1) {
                if (prev_n > 0) {   /* block k - 1 out of the other buffer, then block k + 1 into it */
                    t = now_ms();
                    if (put(ctx, prev_y, prev_n, other) != 0) { set_err(err, errlen, "writing rows from %d failed", prev_y); rc = -1; }
                    t_write += now_ms() - t;
                    prev_n = 0;
                }
                if (rc == 0 && y + n < h) {
                    t = now_ms();
                    next_rows = get(ctx, y + n, other, &next_n, &next_stride);
                    t_read += now_ms() - t;
                    if (!next_rows || next_n < 1 || y + n + next_n > h) { set_err(err, errlen, "reading rows from %d failed", y + n); rc = -1; }
                }
            }
            own_rows += pool_slice(&P);   /* then help with block k */
            t = now_ms();
            pthread_mutex_lock(&P.mu);
            while (P.finished < P.threads - 1) pthread_cond_wait(&P.done, &P.mu);
            pthread_mutex_unlock(&P.mu);
            t_wait += now_ms() - t;
            t_look += now_ms() - tb;
            if (rc != 0) break;
            if (P.threads > 1) {
                prev_y = y; prev_n = n;   /* written while the next block develops */
                y += n;
                if (y < h) { rows = next_rows; n = next_n; stride = next_stride; cur = other; }
            } else {   /* on our own: read, look, write */
                t = now_ms();
                if (put(ctx, y, n, cur) != 0) { set_err(err, errlen, "writing rows from %d failed", y); rc = -1; break; }
                t_write += now_ms() - t;
                y += n;
                if (y < h) {
                    t = now_ms();
                    rows = get(ctx, y, cur, &n, &stride);
                    t_read += now_ms() - t;
                    if (!rows || n < 1 || y + n > h) { set_err(err, errlen, "reading rows from %d failed", y); rc = -1; break; }
                }
            }
            if (progress) {
                int pct = (int)((long long)y * 100 / h);
                if (pct != last) { last = pct; progress(pctx, pct); }
            }
        }
        if (rc == 0 && prev_n > 0) {   /* the last block */
            t = now_ms();
            if (put(ctx, prev_y, prev_n, cur) != 0) { set_err(err, errlen, "writing rows from %d failed", prev_y); rc = -1; }
            t_write += now_ms() - t;
        }
    }

    pthread_mutex_lock(&P.mu);
    P.quit = 1;
    pthread_cond_broadcast(&P.go);
    pthread_mutex_unlock(&P.mu);
    for (i = 0; i < want; i++) if (helpers[i].started) { pthread_join(helpers[i].th, NULL); hcpu += helpers[i].cpu; }
    pthread_cond_destroy(&P.go);
    pthread_cond_destroy(&P.done);
    pthread_mutex_destroy(&P.mu);
    if (rc == 0) {
        g_timing[FL_TIME_READ] = (int)t_read;
        g_timing[FL_TIME_LOOK] = (int)t_look;
        g_timing[FL_TIME_WRITE] = (int)t_write;
        g_timing[FL_TIME_TOTAL] = (int)(now_ms() - t0);
        g_timing[FL_TIME_CPU] = (int)(cpu_ms() - cpu0);
        g_timing[FL_TIME_THREADS] = P.threads;
        g_timing[FL_TIME_WAIT] = (int)t_wait;
        g_timing[FL_TIME_ROWS] = own_rows;
        g_timing[FL_TIME_HCPU] = (int)hcpu;
    }
    ycc_free(&Y);
    look_free(&L); grain_free(&G);
    return rc;
}

int fl_last_threads(char *note, int len) {
    if (note && len > 0) snprintf(note, len, "%s", g_helper_note);
    return g_last_threads;
}
