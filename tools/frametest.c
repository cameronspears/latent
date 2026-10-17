/*
 * Host test of the viewfinder's frame composer (jni/filmcore.c fl_frame and its kernels), against v0.44.1's
 * composer kept in tools/frame_ref.h. Each test photo is made into a frame like the camera's (320 x 216, 4:2:2,
 * fairly compressed), then composed through every film. tools/frame-test.sh builds and runs it.
 *
 *   frametest [--bench] [--render DIR] IN.JPG...
 *
 * --bench: Mac timings of each stage, today's composer against v0.44.1's (ratios only: the camera is ~70x slower
 * and ranks ARM code differently; tools/asm-budget.sh counts its instructions). --render: PPMs of composed frames.
 */
#define FL_FRAME_REFERENCE 1
#include "../jni/filmcore.c"

#include <sys/time.h>

static int g_fail;

static void check(int ok, const char *fmt, ...) {
    va_list ap;
    printf(ok ? "  pass  " : "  FAIL  ");
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
    if (!ok) g_fail = 1;
}

static double now_s(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return tv.tv_sec + tv.tv_usec / 1e6;
}

static const char *const FILMS[] = { "assets/luts/NATURAL.CUB", "assets/luts/PORTRA4W.CUB", "assets/luts/CLASSNEG.CUB",
                                     "assets/luts/CUBANNEG.CUB", "assets/luts/TRIX400.CUB", "assets/luts/PARTY800.CUB" };
#define NFILMS 6

typedef struct { const char *name; unsigned char *jpg; unsigned long len; } frame_jpg;

/* IN as the camera would send it: decoded small, box-scaled to 320 x 216, encoded 4:2:2 at quality q. */
static int camera_frame(const char *path, int q, frame_jpg *out) {
    struct jpeg_decompress_struct d;
    struct jpeg_compress_struct e;
    struct jpeg_error_mgr je, ce;
    FILE *f = fopen(path, "rb");
    unsigned char *src, *dst;
    int w, h, x, y, k, W = 320, H = 216;
    if (!f) return -1;
    d.err = jpeg_std_error(&je);
    jpeg_create_decompress(&d);
    jpeg_stdio_src(&d, f);
    jpeg_read_header(&d, TRUE);
    d.out_color_space = JCS_RGB;
    d.scale_num = 1;
    d.scale_denom = d.image_width >= 2560 ? 8 : d.image_width >= 1280 ? 4 : d.image_width >= 640 ? 2 : 1;
    jpeg_start_decompress(&d);
    w = (int)d.output_width; h = (int)d.output_height;
    src = (unsigned char *)malloc((size_t)w * h * 3);
    while (d.output_scanline < d.output_height) { JSAMPROW r = src + (size_t)d.output_scanline * w * 3; jpeg_read_scanlines(&d, &r, 1); }
    jpeg_finish_decompress(&d);
    jpeg_destroy_decompress(&d);
    fclose(f);
    dst = (unsigned char *)malloc((size_t)W * H * 3);
    {   /* the centre 3:2, box-averaged */
        double sw = w, sh = w / 1.5, sx0, sy0;
        if (sh > h) { sh = h; sw = h * 1.5; }
        sx0 = (w - sw) / 2; sy0 = (h - sh) / 2;
        for (y = 0; y < H; y++) for (x = 0; x < W; x++) {
            int x0 = (int)(sx0 + x * sw / W), x1 = (int)(sx0 + (x + 1) * sw / W), y0 = (int)(sy0 + y * sh / H), y1 = (int)(sy0 + (y + 1) * sh / H);
            int i, j, n = 0, acc[3] = { 0, 0, 0 };
            if (x1 <= x0) x1 = x0 + 1;
            if (y1 <= y0) y1 = y0 + 1;
            for (j = y0; j < y1 && j < h; j++) for (i = x0; i < x1 && i < w; i++, n++) for (k = 0; k < 3; k++) acc[k] += src[((size_t)j * w + i) * 3 + k];
            for (k = 0; k < 3; k++) dst[((size_t)y * W + x) * 3 + k] = (unsigned char)((acc[k] + n / 2) / (n ? n : 1));
        }
    }
    free(src);
    e.err = jpeg_std_error(&ce);
    jpeg_create_compress(&e);
    out->jpg = NULL; out->len = 0;
    jpeg_mem_dest(&e, &out->jpg, &out->len);
    e.image_width = W; e.image_height = H; e.input_components = 3; e.in_color_space = JCS_RGB;
    jpeg_set_defaults(&e);
    jpeg_set_quality(&e, q, TRUE);
    e.comp_info[0].h_samp_factor = 2; e.comp_info[0].v_samp_factor = 1;   /* 4:2:2, as the camera's */
    e.comp_info[1].h_samp_factor = 1; e.comp_info[1].v_samp_factor = 1;
    e.comp_info[2].h_samp_factor = 1; e.comp_info[2].v_samp_factor = 1;
    jpeg_start_compress(&e, TRUE);
    while (e.next_scanline < e.image_height) { JSAMPROW r = dst + (size_t)e.next_scanline * W * 3; jpeg_write_scanlines(&e, &r, 1); }
    jpeg_finish_compress(&e);
    jpeg_destroy_compress(&e);
    free(dst);
    out->name = strrchr(path, '/') ? strrchr(path, '/') + 1 : path;
    return 0;
}

static const int SH[4] = { 12, 8, 4, 0 };
static const int PARAMS[FL_PARAM_COUNT] = { 0, 0, 0, 1, 95, 1, 1 };
#define OW 540
#define OH 480

/* The 4-bit levels of a 4444 pixel (shifts 12, 8, 4). */
static void lv(unsigned short v, int *l) { l[0] = v >> 12 & 15; l[1] = v >> 8 & 15; l[2] = v >> 4 & 15; }

/* a against b: the share of pixels identical, the largest level difference, the mean (signed). */
static void versus(const unsigned short *a, const unsigned short *b, int n, double *same, int *maxd, double *mean) {
    int i, k, s = 0, m = 0;
    long sum = 0;
    for (i = 0; i < n; i++) {
        int la[3], lb[3], eq = 1;
        lv(a[i], la); lv(b[i], lb);
        for (k = 0; k < 3; k++) { int d = la[k] - lb[k]; sum += d; if (d) eq = 0; if (abs(d) > m) m = abs(d); }
        s += eq;
    }
    *same = (double)s / n; *maxd = m; *mean = (double)sum / (3.0 * n);
}

/* An overlay like the viewfinder's: fades over the top and bottom bands, ink type, a translucent edge. */
static unsigned int *viewfinder_overlay(void) {
    unsigned int *ov = (unsigned int *)calloc((size_t)OW * OH, sizeof(unsigned int));
    int x, y;
    for (y = 0; y < 64; y++) for (x = 0; x < OW; x++) ov[y * OW + x] = (unsigned int)(0x99 - y * 2) << 24;
    for (y = 384; y < OH; y++) for (x = 0; x < OW; x++) ov[y * OW + x] = (unsigned int)(0x20 + (y - 384)) << 24;
    for (y = 20; y < 44; y++) for (x = 40; x < 140; x++) ov[y * OW + x] = (x % 7 < 3) ? 0xFFF4F1EA : (x % 7 == 3 ? 0x80F4F1EA : ov[y * OW + x]);
    for (y = 420; y < 450; y++) for (x = 300; x < 500; x++) ov[y * OW + x] = (y % 5 < 3) ? 0xFFFFAA33 : ov[y * OW + x];
    for (y = 200; y < 210; y++) for (x = 200; x < 300; x++) ov[y * OW + x] = 0xFFFFFFFF;
    return ov;
}

/* ---- the look kernels on decoded frames, against v0.44.1's look_pack (8.4 nodes) */
static void test_look_kernels(frame_jpg *fr, int nfr) {
    static const int TONES[3][2] = { { 0, 0 }, { 3, -2 }, { -4, 4 } };
    fl_frame_ctx *c = fl_frame_new();
    int i, f, t, exact = 1, worst = 0, tone_exact = 1;
    long diffs = 0, samples = 0;
    char err[256];
    for (i = 0; i < nfr; i++) for (f = 0; f <= NFILMS; f++) for (t = 0; t < 3; t++) {
        int params[FL_PARAM_COUNT], n, k;
        unsigned int *ref, *now, *ref_rb;
        unsigned char *ref_g;
        memcpy(params, PARAMS, sizeof params);
        params[FL_PARAM_HIGHLIGHT] = TONES[t][0]; params[FL_PARAM_SHADOW] = TONES[t][1];
        if (!frame_film(c, f < NFILMS ? FILMS[f] : NULL, params, err, sizeof err) || frame_decode(c, fr[i].jpg, fr[i].len, 320, 240, 1, err, sizeof err)) {
            check(0, "film / decode: %s", err);
            continue;
        }
        n = c->sw * c->sh;
        ref_rb = (unsigned int *)malloc(sizeof(unsigned int) * n); ref_g = (unsigned char *)malloc(n);
        ref = (unsigned int *)malloc(sizeof(unsigned int) * n); now = (unsigned int *)malloc(sizeof(unsigned int) * n);
        ref_look_pack(&c->film.L, c->rgb, n, ref_rb, ref_g);
        for (k = 0; k < n; k++) ref[k] = ref_rb[k] | (unsigned int)ref_g[k] << 8;
        if (!c->film.F.n) {
            look_tone(&c->film.F, c->rgb, n, now);
            if (memcmp(now, ref, sizeof(unsigned int) * n)) tone_exact = 0;
        } else {
            look_live(&c->film.F, c->rgb, n, now);
            if (memcmp(now, ref, sizeof(unsigned int) * n)) exact = 0;
            if (c->film.F.n == 17) look_packed17(&c->film.F, c->rgb, n, now); else look_packed(&c->film.F, c->rgb, n, now);
            for (k = 0; k < n; k++) {
                int ch;
                for (ch = 0; ch < 24; ch += 8) {
                    int d = abs((int)(now[k] >> ch & 255) - (int)(ref[k] >> ch & 255));
                    if (d > worst) worst = d;
                    diffs += d;
                    samples++;
                }
            }
        }
        free(ref_rb); free(ref_g); free(ref); free(now);
    }
    check(exact, "the look kernel (8.4 nodes): exactly v0.44.1's, %d frames x %d films x 3 tone settings", nfr, NFILMS);
    check(tone_exact, "no film, the tone curve alone: exactly v0.44.1's");
    check(worst <= 1, "the look kernel on 8-bit nodes: within %d/255 of v0.44.1's (mean %.3f/255)", worst, (double)diffs / (samples ? samples : 1));
    fl_frame_free(c);
}

/* ---- the composer against v0.44.1's, every frame through every film */
static void test_versus_reference(frame_jpg *fr, int nfr) {
    fl_frame_ctx *c = fl_frame_new();
    unsigned short *a = (unsigned short *)malloc(sizeof(unsigned short) * OW * OH), *b = (unsigned short *)malloc(sizeof(unsigned short) * OW * OH);
    unsigned int *ov = viewfinder_overlay();
    int info[FL_FRAME_INFO], tm[FL_FRAME_TIMES], i, f, o, worst_max = 0;
    double worst_same = 1, worst_mean = 0;
    char err[256];
    for (o = 0; o < 2; o++) {
        fl_set_overlay(o ? ov : NULL, OW, OH);
        for (i = 0; i < nfr; i++) for (f = 0; f < NFILMS; f++) {
            double same, mean;
            int maxd;
            if (fl_frame(c, fr[i].jpg, fr[i].len, FILMS[f], PARAMS, FL_F_TEMPORAL | FL_F_LEVEL, 5, a, OW, OH, OW, 1500, FL_FMT_4444, SH, 320, 240, info, tm, err, sizeof err)
                    || fl_frame(c, fr[i].jpg, fr[i].len, FILMS[f], PARAMS, FL_F_TEMPORAL | FL_F_REFERENCE, 5, b, OW, OH, OW, 1500, FL_FMT_4444, SH, 320, 240, info, tm, err, sizeof err)) {
                check(0, "compose %s %s: %s", fr[i].name, FILMS[f], err);
                continue;
            }
            versus(a, b, OW * OH, &same, &maxd, &mean);
            if (same < worst_same) worst_same = same;
            if (maxd > worst_max) worst_max = maxd;
            if (fabs(mean) > fabs(worst_mean)) worst_mean = mean;
        }
    }
    check(worst_same >= 0.90 && worst_max <= 1 && fabs(worst_mean) <= 0.02,
          "the composer against v0.44.1's, %d frames x %d films, with and without an overlay (the same decode): at worst %.1f%% identical, %d level apart, mean %+.4f levels",
          nfr, NFILMS, worst_same * 100, worst_max, worst_mean);
    fl_set_overlay(NULL, 0, 0);
    fl_frame_free(c);
    free(a); free(b); free(ov);
}

/* ---- Mac timings: today's composer against v0.44.1's on the same frames (each the fastest of many runs: the
 * Mac is busy with other things, and the fastest run is the one they disturbed least) */
static double fastest(fl_frame_ctx *c, const frame_jpg *f, int flags, unsigned short *a) {
    int info[FL_FRAME_INFO], tm[FL_FRAME_TIMES], k;
    char err[256];
    double best = 1e9;
    for (k = 0; k < 40; k++) {
        double t = now_s();
        fl_frame(c, f ? f->jpg : NULL, f ? f->len : 0, FILMS[3], PARAMS, FL_F_TEMPORAL | flags, k, a, OW, OH, OW, 1500, FL_FMT_565, SH, 320, 240, info, tm, err, sizeof err);
        t = now_s() - t;
        if (t < best) best = t;
    }
    return best;
}

static void bench(frame_jpg *fr, int nfr) {
    fl_frame_ctx *c = fl_frame_new();
    unsigned short *a = (unsigned short *)malloc(sizeof(unsigned short) * OW * OH);
    unsigned int *ov = viewfinder_overlay();
    int i;
    double now_total = 0, ref_total = 0, again = 0;
    fl_set_overlay(ov, OW, OH);
    for (i = 0; i < nfr; i++) {
        now_total += fastest(c, &fr[i], 0, a);
        again += fastest(c, NULL, 0, a);
        ref_total += fastest(c, &fr[i], FL_F_REFERENCE, a);
    }
    printf("  Mac, a frame (mean of %d frames, overlay laid in): today %.0f us (of which compose ~%.0f us), v0.44.1 %.0f us: %.2fx\n",
           nfr, now_total / nfr * 1e6, again / nfr * 1e6, ref_total / nfr * 1e6, ref_total / now_total);
    fl_set_overlay(NULL, 0, 0);
    fl_frame_free(c);
    free(a); free(ov);
}

/* A composed 4444 frame as a PPM (each 4-bit level x 17). */
static void render(const char *dir, const char *name, const unsigned short *o) {
    char path[1024];
    FILE *f;
    int i;
    snprintf(path, sizeof path, "%s/%s.ppm", dir, name);
    f = fopen(path, "wb");
    if (!f) return;
    fprintf(f, "P6\n%d %d\n255\n", OW, OH);
    for (i = 0; i < OW * OH; i++) { int l[3]; lv(o[i], l); fputc(l[0] * 17, f); fputc(l[1] * 17, f); fputc(l[2] * 17, f); }
    fclose(f);
}

static void renders(const char *dir, frame_jpg *fr, int nfr) {
    fl_frame_ctx *c = fl_frame_new();
    unsigned short *a = (unsigned short *)malloc(sizeof(unsigned short) * OW * OH);
    unsigned int *ov = viewfinder_overlay();
    int info[FL_FRAME_INFO], tm[FL_FRAME_TIMES], i;
    char err[256], name[256];
    fl_set_overlay(ov, OW, OH);
    for (i = 0; i < nfr; i++) {
        char base[64];
        snprintf(base, sizeof base, "%.*s", (int)(strchr(fr[i].name, '.') ? strchr(fr[i].name, '.') - fr[i].name : (long)strlen(fr[i].name)), fr[i].name);
        fl_frame(c, fr[i].jpg, fr[i].len, FILMS[3], PARAMS, 0, 1, a, OW, OH, OW, 1500, FL_FMT_4444, SH, 320, 240, info, tm, err, sizeof err);
        snprintf(name, sizeof name, "%s-now", base); render(dir, name, a);
        fl_frame(c, fr[i].jpg, fr[i].len, FILMS[3], PARAMS, FL_F_REFERENCE, 1, a, OW, OH, OW, 1500, FL_FMT_4444, SH, 320, 240, info, tm, err, sizeof err);
        snprintf(name, sizeof name, "%s-v0441", base); render(dir, name, a);
    }
    fl_set_overlay(NULL, 0, 0);
    fl_frame_free(c);
    free(a); free(ov);
}

int main(int argc, char **argv) {
    frame_jpg fr[16];
    int nfr = 0, i, do_bench = 0;
    const char *dir = NULL;
    for (i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--bench")) { do_bench = 1; continue; }
        if (!strcmp(argv[i], "--render") && i + 1 < argc) { dir = argv[++i]; continue; }
        if (nfr < 16 && camera_frame(argv[i], 65, &fr[nfr]) == 0) nfr++;
        else fprintf(stderr, "cannot read %s\n", argv[i]);
    }
    if (!nfr) { fprintf(stderr, "usage: frametest [--bench] [--render DIR] IN.JPG...\n"); return 2; }
    test_look_kernels(fr, nfr);
    test_versus_reference(fr, nfr);
    if (do_bench) bench(fr, nfr);
    if (dir) renders(dir, fr, nfr);
    for (i = 0; i < nfr; i++) free(fr[i].jpg);
    printf(g_fail ? "FRAME TEST FAILED\n" : "FRAME OK\n");
    return g_fail;
}
