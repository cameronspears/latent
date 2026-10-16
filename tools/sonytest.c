/*
 * Mac test for jni/sony_bridge.c's block transfers. The four Sony functions are replaced by fakes
 * over plain memory that refuse any out-of-bounds access and count calls. A real photo is developed
 * through the bridge with several block sizes into a contiguous and a padded output canvas; every
 * run must give identical pixels, and the padded canvas's padding must be untouched.
 *   sonytest IN.JPG LUT
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "filmcore.h"
#include "jpeglib.h"

typedef struct { unsigned char *mem; int size, type; } fakemem;
static long calls_read, calls_write, refused;

/* SB_SLOW=ns_per_byte_read,ns_per_byte_write: keep this core busy that long per byte, like the camera's own copies */
#include <time.h>
static double slow_read, slow_write;
static void busy(double ns) {
    struct timespec a, b;
    if (ns <= 0) return;
    clock_gettime(CLOCK_MONOTONIC, &a);
    do clock_gettime(CLOCK_MONOTONIC, &b); while ((b.tv_sec - a.tv_sec) * 1e9 + (b.tv_nsec - a.tv_nsec) < ns);
}

static int f_type(void *self) { return ((fakemem *)self)->type; }
static int f_prop(void *self, int key, int *v) { if (key != 2) return 0; *v = ((fakemem *)self)->size; return 1; }
static int f_read(void *self, void *dst, unsigned size, unsigned off) {
    fakemem *m = (fakemem *)self;
    calls_read++;
    if ((unsigned long long)off + size > (unsigned)m->size) { refused++; return -1; }
    memcpy(dst, m->mem + off, size);
    busy(slow_read * size);
    return (int)size;
}
static int f_write(void *self, const void *src, unsigned size, unsigned off) {
    fakemem *m = (fakemem *)self;
    calls_write++;
    if ((unsigned long long)off + size > (unsigned)m->size) { refused++; return -1; }
    memcpy(m->mem + off, src, size);
    busy(slow_write * size);
    return (int)size;
}
typedef int (*type_fn)(void *);
typedef int (*prop_fn)(void *, int, int *);
typedef int (*read_fn)(void *, void *, unsigned, unsigned);
typedef int (*write_fn)(void *, const void *, unsigned, unsigned);
type_fn sb_test_type = f_type;
prop_fn sb_test_prop = f_prop;
read_fn sb_test_read = f_read;
write_fn sb_test_write = f_write;

int sb_develop(void *src, const int *sg, void *dst, const int *dg, int block, const char *lut, const int *params,
               fl_progress_fn progress, void *pctx);
const char *sb_last_error(void);
int sb_develop_inplace(void *img, const int *g, int block, const char *lut, const int *params, fl_progress_fn progress, void *pctx);
int sb_verify_inplace(void *img, const int *g, int size);
int sb_hash(void *img, const int *g, int block, unsigned long long *out, long long *out_us);
int sb_speed(void *img, const int *g, int size, int count, int write_back, long long *out_us);

/* the Develop test's hash, computed here straight from memory: each row's picture bytes as 32-bit words */
static unsigned long long ref_hash(const unsigned char *mem, const int *g) {
    unsigned long long hash = 1469598103934665603ULL;
    for (int y = 0; y < g[1]; y++) {
        const unsigned char *r = mem + g[4] + ((size_t)(g[6] + y) * g[2] + g[5]) * 2;
        for (int k = 0; k + 4 <= g[0] * 2; k += 4)
            hash = (hash ^ (r[k] | (unsigned)r[k + 1] << 8 | (unsigned)r[k + 2] << 16 | (unsigned)r[k + 3] << 24)) * 1099511628211ULL;
    }
    return hash;
}

/* the reference: the same picture through the core's copy-in / copy-out path, without the bridge */
static unsigned char *g_src, *g_ref;
static int g_sstride, g_w;
static int mem_io(void *ctx, int y, unsigned char *row, int write) {
    (void)ctx;
    if (!write) memcpy(row, g_src + (size_t)y * g_sstride, (size_t)g_w * 2);
    else memcpy(g_ref + (size_t)y * g_w * 2, row, (size_t)g_w * 2);
    return 0;
}

int main(int argc, char **argv) {
    struct jpeg_decompress_struct d;
    struct jpeg_error_mgr e;
    FILE *in = fopen(argv[1], "rb");
    unsigned char *rgb, *reference = NULL;
    int w, h, y, i, b, pass = 1;
    int params[FL_PARAM_COUNT] = { -2, 1, 5, 2, 95, 7, 1 };
    if (argc > 3) params[FL_PARAM_THREADS] = atoi(argv[3]);   /* threads for the block develop */
    if (getenv("SB_SLOW")) sscanf(getenv("SB_SLOW"), "%lf,%lf", &slow_read, &slow_write);
    int blocks[] = { 1, 65536, 262144, 1048576, 2097152, 4194304 };
    fakemem src;
    d.err = jpeg_std_error(&e);
    jpeg_create_decompress(&d);
    jpeg_stdio_src(&d, in);
    jpeg_read_header(&d, TRUE);
    d.out_color_space = JCS_RGB;
    jpeg_start_decompress(&d);
    w = d.output_width & ~1; h = d.output_height;
    /* source laid out like the camera's: canvas 80 wider and 48 taller than the picture */
    int sg[8] = { w, h, w + 80, h + 48, 0, 0, 0, 0 };
    sg[7] = sg[2] * sg[3] * 2;
    src.size = sg[7]; src.type = 2; src.mem = calloc(src.size, 1);
    rgb = malloc((size_t)d.output_width * 3);
    while (d.output_scanline < d.output_height) {
        JSAMPROW r = rgb;
        y = d.output_scanline;
        jpeg_read_scanlines(&d, &r, 1);
        fl_rgb_to_uyvy(rgb, src.mem + (size_t)y * sg[2] * 2, w);
    }
    jpeg_finish_decompress(&d);
    {
        char err[256];
        g_src = src.mem; g_sstride = sg[2] * 2; g_w = w;
        g_ref = reference = malloc((size_t)w * 2 * h);
        if (fl_develop_uyvy(argv[2], params, w, h, mem_io, NULL, NULL, NULL, err, sizeof err) != 0) { printf("FAIL reference: %s\n", err); return 1; }
    }
    /* a source without padding, for the case where the output is laid out exactly like it */
    fakemem flat;
    int fg[8] = { w, h, w, h, 0, 0, 0, 0 };
    fg[7] = w * 2 * h;
    flat.size = fg[7]; flat.type = 2; flat.mem = malloc(flat.size);
    for (y = 0; y < h; y++) memcpy(flat.mem + (size_t)y * w * 2, src.mem + (size_t)y * sg[2] * 2, (size_t)w * 2);
    for (i = 0; i < 3; i++) {                       /* contiguous output, padded output, same layout as an unpadded source */
        int pad = i == 1 ? 16 : 0;
        int dg[8] = { w, h, w + pad, h + (i == 1 ? 8 : 0), i == 2 ? 0 : 64, 0, 0, 0 };
        fakemem *from = i == 2 ? &flat : &src;
        int *fromg = i == 2 ? fg : sg;
        dg[7] = dg[4] + dg[2] * dg[3] * 2;
        for (b = 0; b < 6; b++) {
            fakemem dst;
            dst.size = dg[7]; dst.type = 2; dst.mem = malloc(dst.size);
            memset(dst.mem, 0xA5, dst.size);        /* sentinel: padding must stay 0xA5 */
            calls_read = calls_write = refused = 0;
            if (sb_develop(from, fromg, &dst, dg, blocks[b], argv[2], params, NULL, NULL) != 0) {
                printf("  FAIL  canvas %s block %d: %s\n", i == 1 ? "padded" : i == 2 ? "same" : "contiguous", blocks[b], sb_last_error());
                pass = 0; free(dst.mem); continue;
            }
            /* gather the picture, check padding */
            unsigned char *pic = malloc((size_t)w * 2 * h);
            int padding_ok = 1;
            for (y = 0; y < h; y++) {
                memcpy(pic + (size_t)y * w * 2, dst.mem + dg[4] + (size_t)y * dg[2] * 2, (size_t)w * 2);
                for (int k = w * 2; k < dg[2] * 2; k++) if (dst.mem[dg[4] + (size_t)y * dg[2] * 2 + k] != 0xA5) padding_ok = 0;
            }
            for (int k = 0; k < dg[4]; k++) if (dst.mem[k] != 0xA5) padding_ok = 0;
            int same = memcmp(pic, reference, (size_t)w * 2 * h) == 0;   /* against the copy path */
            {   /* the Develop test's hash: the same at every block size, and as computed straight from memory */
                unsigned long long hh = 0, want = ref_hash(dst.mem, dg);
                long long us;
                static unsigned long long first[3];
                long r0 = calls_read, w0 = calls_write;
                if (sb_hash(&dst, dg, blocks[b], &hh, &us) != 0 || hh != want || (b > 0 && hh != first[i]) || calls_write != w0) {
                    printf("  FAIL  hash canvas %d block %d: %s\n", i, blocks[b], sb_last_error()); pass = 0;
                }
                if (b == 0) first[i] = hh;
                int per = blocks[b] / (dg[2] * 2) > 0 ? blocks[b] / (dg[2] * 2) : 1;   /* whole rows per read */
                if (calls_read - r0 != (h + per - 1) / per) { printf("  FAIL  hash read in too many calls\n"); pass = 0; }
            }
            if (slow_read > 0 && blocks[b] == 2097152) {
                int t[FL_TIME_COUNT];
                fl_last_timing(t);
                printf("  timing (%d threads): read %d, look %d, write %d, total %d ms\n", t[FL_TIME_THREADS], t[FL_TIME_READ],
                       t[FL_TIME_LOOK], t[FL_TIME_WRITE], t[FL_TIME_TOTAL]);
            }
            printf("  %s  canvas %-10s block %8d: %5ld reads %5ld writes, refused %ld, %s, padding %s\n",
                   same && padding_ok && !refused ? "pass" : "FAIL", i == 1 ? "padded" : i == 2 ? "same" : "contiguous", blocks[b],
                   calls_read, calls_write, refused, same ? "pixels identical" : "PIXELS DIFFER", padding_ok ? "untouched" : "OVERWRITTEN");
            if (!same || !padding_ok || refused) pass = 0;
            free(pic); free(dst.mem);
        }
    }
    /* in place, as during a capture: a padded image and an unpadded one, every block size */
    for (i = 0; i < 2; i++) {
        int *g0 = i == 0 ? sg : fg;
        fakemem *orig = i == 0 ? &src : &flat;
        for (b = 0; b < 6; b++) {
            fakemem img;
            int ig[8];
            memcpy(ig, g0, sizeof ig);
            img.size = orig->size; img.type = 2; img.mem = malloc(img.size);
            memcpy(img.mem, orig->mem, img.size);
            if (i == 0) for (y = 0; y < h; y++) memset(img.mem + (size_t)y * sg[2] * 2 + w * 2, 0xA5, (size_t)(sg[2] - w) * 2);   /* padding sentinel */
            calls_read = calls_write = refused = 0;
            if (sb_verify_inplace(&img, ig, blocks[b] < 65536 ? 65536 : blocks[b]) != 0) { printf("  FAIL  in place verify %d: %s\n", blocks[b], sb_last_error()); pass = 0; free(img.mem); continue; }
            if (sb_develop_inplace(&img, ig, blocks[b], argv[2], params, NULL, NULL) != 0) {
                printf("  FAIL  in place %s block %d: %s\n", i == 0 ? "padded" : "unpadded", blocks[b], sb_last_error());
                pass = 0; free(img.mem); continue;
            }
            unsigned char *pic = malloc((size_t)w * 2 * h);
            int padding_ok = 1;
            for (y = 0; y < h; y++) {
                memcpy(pic + (size_t)y * w * 2, img.mem + (size_t)y * ig[2] * 2, (size_t)w * 2);
                if (i == 0) for (int k = w * 2; k < ig[2] * 2; k++) if (img.mem[(size_t)y * ig[2] * 2 + k] != 0xA5) padding_ok = 0;
            }
            int same = memcmp(pic, reference, (size_t)w * 2 * h) == 0;
            printf("  %s  in place %-8s block %8d: %5ld reads %5ld writes, refused %ld, %s, padding %s\n",
                   same && padding_ok && !refused ? "pass" : "FAIL", i == 0 ? "padded" : "unpadded", blocks[b], calls_read, calls_write, refused,
                   same ? "pixels identical" : "PIXELS DIFFER", padding_ok ? "untouched" : "OVERWRITTEN");
            if (!same || !padding_ok || refused) pass = 0;
            free(pic); free(img.mem);
        }
    }
    /* the hash sees a one-byte change anywhere in the picture, and ignores the padding */
    {
        unsigned long long a = 0, b2 = 0, c = 0;
        long long us;
        int ok = sb_hash(&src, sg, 2097152, &a, &us) == 0;
        src.mem[(size_t)(h / 2) * sg[2] * 2 + 7] ^= 1;
        ok = ok && sb_hash(&src, sg, 2097152, &b2, &us) == 0 && b2 != a;
        src.mem[(size_t)(h / 2) * sg[2] * 2 + 7] ^= 1;
        src.mem[(size_t)(h / 2) * sg[2] * 2 + w * 2 + 3] ^= 0x40;   /* padding */
        ok = ok && sb_hash(&src, sg, 2097152, &c, &us) == 0 && c == a;
        src.mem[(size_t)(h / 2) * sg[2] * 2 + w * 2 + 3] ^= 0x40;
        printf("  %s  hash: one changed byte %s, padding %s\n", ok ? "pass" : "FAIL", b2 != a ? "seen" : "MISSED", c == a ? "left out" : "COUNTED");
        if (!ok) pass = 0;
    }
    /* raw transfer timing: bounded, wraps at the canvas end, writing back leaves the image as it was */
    {
        long long us[2];
        unsigned char *before = malloc(src.size);
        int sizes[] = { 65536, 1048576, 4194304 }, ok = 1;
        memcpy(before, src.mem, src.size);
        for (int k = 0; k < 3; k++) {
            refused = 0;
            int n = (int)((long long)sg[2] * sg[3] * 2 / sizes[k]) + 3;   /* walks past the end: must wrap */
            if (sb_speed(&src, sg, sizes[k], n, 1, us) != 0 || refused) { printf("  FAIL  speed %d: %s\n", sizes[k], sb_last_error()); ok = 0; }
        }
        if (memcmp(before, src.mem, src.size) != 0) { printf("  FAIL  speed write-back changed the image\n"); ok = 0; }
        if (sb_speed(&src, sg, 4194305, 1, 0, us) == 0 || sb_speed(&src, sg, 0, 1, 0, us) == 0) { printf("  FAIL  speed took a bad size\n"); ok = 0; }
        printf("  %s  transfer timing: bounded, wraps, writes back unchanged\n", ok ? "pass" : "FAIL");
        if (!ok) pass = 0;
        free(before);
    }
    printf(pass ? "SONY BRIDGE OK\n" : "SONY BRIDGE FAILED\n");
    return pass ? 0 : 1;
}
