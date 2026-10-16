/*
 * Desktop test tool for the Film Lab core: runs exactly the code the camera runs.
 *
 *   fltool process IN.JPG OUT.JPG [--lut F.cub] [--hl N] [--sh N] [--grain N] [--size N] [--q N] [--seed N]
 *   fltool preview IN.JPG OUT.ppm [--crop] [... same options]
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>

#include "filmcore.h"

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
    if (argc >= 2 && !strcmp(argv[1], "cores")) {   /* fltool cores */
        static char report[1024];
        fl_cores(report, sizeof report);
        fputs(report, stdout);
        return 0;
    }
    if (argc < 4) {
        fprintf(stderr, "usage: %s process|preview IN OUT  |  bench IN SCALE [--lut F] [--hl N] [--sh N] [--grain N] [--size N] [--q N] [--seed N] [--crop]\n", argv[0]);
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
    } else if (!strcmp(argv[1], "bench2")) {   /* fltool bench2 IN SCALE [--lut F] ... */
        static char report[1024];
        if (fl_bench_cores(argv[2], lut, params, atoi(argv[3]), report, sizeof report) != 0) { fprintf(stderr, "error: %s\n", report); return 1; }
        fputs(report, stdout);
        return 0;
    } else if (!strcmp(argv[1], "bench")) {   /* fltool bench IN SCALE [--lut F] [--grain N] ... */
        static char report[2048];
        if (fl_bench(argv[2], lut, params, atoi(argv[3]), report, sizeof report) != 0) { fprintf(stderr, "error: %s\n", report); return 1; }
        fputs(report, stdout);
        return 0;
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
