/*
 * Desktop test tool for the Film Lab core: runs exactly the code the camera runs.
 *
 *   fltool process IN.JPG OUT.JPG [--lut F.cub] [--hl N] [--sh N] [--grain N] [--size N] [--q N] [--seed N]
 *   fltool preview IN.JPG OUT.ppm [--crop] [... same options]
 *   fltool live IN.JPG OUT.ppm [--lut F.cub]       the viewfinder's frame composer (from memory, 540x480)
 *   fltool cubecheck F.cub...                      the fast .cube reader against sscanf, row by row
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>

#include "filmcore.h"
#include "jpeglib.h"

static void progress(void *ctx, int pct) {
    (void)ctx;
    if (pct % 25 == 0) fprintf(stderr, "  %d%%\n", pct);
}

static double now(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return tv.tv_sec + tv.tv_usec / 1e6;
}

int main(int argc, char **argv) {
    int params[FL_PARAM_COUNT] = { 0, 0, 0, 1, 95, 1, 4 };
    const char *lut = NULL, *preview = NULL;
    int crop = 0, i;
    char err[256] = "";
    double t0;
    if (argc >= 3 && !strcmp(argv[1], "cubecheck")) {   /* fltool cubecheck F.cub...: the fast .cube reader against sscanf */
        int k, bad = 0;
        for (k = 2; k < argc; k++) {
            int ms[4], rc = fl_cube_check(argv[k], ms, err, sizeof err);
            printf("  %s  %s: sscanf %d ms, fast %d ms, %d rows fast, %d left to sscanf%s%s\n", rc == 0 ? "pass" : "FAIL", argv[k],
                   ms[0], ms[1], ms[2], ms[3], rc ? " - " : "", rc ? err : "");
            if (rc) bad = 1;
        }
        return bad;
    }
    if (argc < 4) {
        fprintf(stderr, "usage: %s process|preview|live|makepreview IN OUT [--lut F] [--hl N] [--sh N] [--grain N] [--size N] [--q N] [--seed N] [--crop]\n", argv[0]);
        return 2;
    }
    for (i = 4; i < argc; i++) {
        if (!strcmp(argv[i], "--crop")) { crop = 1; continue; }
        if (i + 1 >= argc) { fprintf(stderr, "missing value for %s\n", argv[i]); return 2; }
        if (!strcmp(argv[i], "--lut")) lut = argv[++i];
        else if (!strcmp(argv[i], "--preview")) preview = argv[++i];
        else if (!strcmp(argv[i], "--hl")) params[FL_PARAM_HIGHLIGHT] = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--sh")) params[FL_PARAM_SHADOW] = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--grain")) params[FL_PARAM_GRAIN] = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--size")) params[FL_PARAM_GRAINSIZE] = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--q")) params[FL_PARAM_QUALITY] = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--seed")) params[FL_PARAM_SEED] = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--threads")) params[FL_PARAM_THREADS] = atoi(argv[++i]);
        else { fprintf(stderr, "unknown option %s\n", argv[i]); return 2; }
    }
    t0 = now();
    if (!strcmp(argv[1], "process")) {
        if (fl_process_file(argv[2], argv[3], lut, preview, params, progress, NULL, err, sizeof err) != 0) {
            fprintf(stderr, "error: %s\n", err);
            return 1;
        }
    } else if (!strcmp(argv[1], "live")) {
        /* the frame composer as the viewfinder runs it: overlay, 565 and 4444, the histogram, the last frame again */
        static const int sh[4] = { 12, 8, 4, 0 };
        fl_frame_ctx *fc = fl_frame_new();
        unsigned short *o16 = malloc(sizeof(unsigned short) * 540 * 480);
        unsigned int *ov = calloc(540 * 480, sizeof(unsigned int));
        int fi[FL_FRAME_INFO], tm[FL_FRAME_TIMES], n, x, y;
        unsigned char *jpg;
        long len;
        double t1;
        FILE *f = fopen(argv[2], "rb");
        if (!f) { fprintf(stderr, "cannot read %s\n", argv[2]); return 1; }
        fseek(f, 0, SEEK_END); len = ftell(f); rewind(f);
        jpg = malloc(len);
        if (fread(jpg, 1, len, f) != (size_t)len) { fprintf(stderr, "short read\n"); return 1; }
        fclose(f);
        for (y = 0; y < 90; y++) for (x = 0; x < 540; x++) ov[y * 540 + x] = (unsigned int)(0x78 - y) << 24;
        for (y = 200; y < 230; y++) for (x = 100; x < 400; x++) ov[y * 540 + x] = 0xFFF4F1EA;
        if (!fc || !o16 || fl_set_overlay(ov, 540, 480) <= 0) { fprintf(stderr, "error: composer\n"); return 1; }
        t1 = now();
        for (n = 0; n < 10; n++)
            if (fl_frame(fc, jpg, (unsigned long)len, lut, params, FL_F_HIST | FL_F_TEMPORAL, n, o16, 540, 480, 540, 1500,
                         n & 1 ? FL_FMT_565 : FL_FMT_4444, sh, 320, 240, fi, tm, err, sizeof err) != 0) { fprintf(stderr, "error: %s\n", err); return 1; }
        fprintf(stderr, "composed 540x480 from %dx%d in %.2f ms (decode %d, read %d, look %d, compose %d)\n", fi[0], fi[1], (now() - t1) * 100,
                tm[0], tm[1], tm[2], tm[3]);
        if (fl_frame(fc, NULL, 0, lut, params, 0, 3, o16, 540, 480, 540, 1500, FL_FMT_4444, sh, 320, 240, fi, tm, err, sizeof err) != 0) { fprintf(stderr, "error: again: %s\n", err); return 1; }
        f = fopen(argv[3], "wb");
        fprintf(f, "P6\n540 480\n255\n");
        for (n = 0; n < 540 * 480; n++) { fputc((o16[n] >> 12 & 15) * 17, f); fputc((o16[n] >> 8 & 15) * 17, f); fputc((o16[n] >> 4 & 15) * 17, f); }
        fclose(f);
        fl_set_overlay(NULL, 0, 0);
        fl_frame_free(fc);
        free(o16); free(ov); free(jpg);
    } else if (!strcmp(argv[1], "makepreview")) {
        if (fl_make_preview(argv[2], argv[3], err, sizeof err) != 0) { fprintf(stderr, "error: %s\n", err); return 1; }
    } else if (!strcmp(argv[1], "preview")) {
        int w = 640, h = 480, wh[2], x, y;
        unsigned int *px = malloc(sizeof(unsigned int) * w * h);
        FILE *f;
        if (fl_preview(argv[2], lut, params, crop, px, w, h, wh, err, sizeof err) != 0) {
            fprintf(stderr, "error: %s\n", err);
            return 1;
        }
        f = fopen(argv[3], "wb");
        fprintf(f, "P6\n%d %d\n255\n", wh[0], wh[1]);
        for (y = 0; y < wh[1]; y++)
            for (x = 0; x < wh[0]; x++) {
                unsigned int p = px[y * wh[0] + x];
                fputc((p >> 16) & 255, f); fputc((p >> 8) & 255, f); fputc(p & 255, f);
            }
        fclose(f);
        free(px);
        fprintf(stderr, "preview %dx%d\n", wh[0], wh[1]);
    } else {
        fprintf(stderr, "unknown command %s\n", argv[1]);
        return 2;
    }
    if (!strcmp(argv[1], "process")) {
        int tm[FL_TIME_COUNT];
        fl_last_timing(tm);
        fprintf(stderr, "ms: read %d  look %d  write %d  finish %d  total %d  cpu %d\n", tm[0], tm[1], tm[2], tm[3], tm[4], tm[5]);
    }
    fprintf(stderr, "done in %.2f s\n", now() - t0);
    return 0;
}
