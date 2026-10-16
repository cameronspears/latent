/*
 * Desktop test tool for the Film Lab core: runs exactly the code the camera runs.
 *
 *   fltool process IN.JPG OUT.JPG [--lut F.cub] [--hl N] [--sh N] [--grain N] [--size N] [--q N] [--seed N]
 *   fltool preview IN.JPG OUT.ppm [--crop] [... same options]
 *   fltool live IN.JPG OUT.ppm [--lut F.cub]       the viewfinder's frame path (from memory, 320x240)
 *   fltool level IN.JPG DEGREES [WIDTH]            the level on IN turned DEGREES anticlockwise, at WIDTH (320)
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>

#include <math.h>
#include "filmcore.h"
#include "jpeglib.h"

/* IN decoded at about 1/4 size, RGB (malloc'd); *w, *h get its size. */
static unsigned char *decode(const char *path, int *w, int *h) {
    struct jpeg_decompress_struct d;
    struct jpeg_error_mgr e;
    FILE *f = fopen(path, "rb");
    unsigned char *rgb;
    if (!f) return NULL;
    d.err = jpeg_std_error(&e);
    jpeg_create_decompress(&d);
    jpeg_stdio_src(&d, f);
    jpeg_read_header(&d, TRUE);
    d.out_color_space = JCS_RGB;
    d.scale_num = 1;
    d.scale_denom = d.image_width >= 2400 ? 4 : d.image_width >= 1200 ? 2 : 1;
    jpeg_start_decompress(&d);
    *w = (int)d.output_width; *h = (int)d.output_height;
    rgb = malloc((size_t)*w * *h * 3);
    while (d.output_scanline < d.output_height) {
        JSAMPROW r = rgb + (size_t)d.output_scanline * *w * 3;
        jpeg_read_scanlines(&d, &r, 1);
    }
    jpeg_finish_decompress(&d);
    jpeg_destroy_decompress(&d);
    fclose(f);
    return rgb;
}

/* fltool level: the picture turned 'deg' anticlockwise (as if the camera were turned clockwise),
 * cropped to the largest centred rectangle of the same shape with no corners missing, scaled to
 * 'ow' wide as the camera's live frame would be, then fl_level. Prints "angle confidence blocks". */
static int level_cmd(const char *path, double deg, int ow) {
    int w, h, x, y, oh, out[3];
    unsigned char *src = decode(path, &w, &h), *dst;
    double t = deg * M_PI / 180, c = cos(t), s = sin(t), at = fabs(s), k1, k2, k;
    if (!src) { fprintf(stderr, "cannot read %s\n", path); return 1; }
    k1 = 1 / (c + (double)h / w * at);
    k2 = 1 / (c + (double)w / h * at);
    k = k1 < k2 ? k1 : k2;
    oh = (int)((long)ow * h / w);
    dst = malloc((size_t)ow * oh * 3);
    for (y = 0; y < oh; y++) for (x = 0; x < ow; x++) {
        /* box-average a few source samples per output pixel, as the camera's scaler would */
        int acc[3] = { 0, 0, 0 }, n = 0, i, j, ch;
        for (j = 0; j < 4; j++) for (i = 0; i < 4; i++) {
            double u = ((x + (i + 0.5) / 4) / ow - 0.5) * w * k, v = ((y + (j + 0.5) / 4) / oh - 0.5) * h * k;
            double sx = u * c - v * s + w / 2.0, sy = u * s + v * c + h / 2.0;
            int ix = (int)sx, iy = (int)sy;
            double fx = sx - ix, fy = sy - iy;
            if (ix < 0 || iy < 0 || ix + 1 >= w || iy + 1 >= h) continue;
            for (ch = 0; ch < 3; ch++) {
                const unsigned char *p = src + ((size_t)iy * w + ix) * 3 + ch;
                acc[ch] += (int)((p[0] * (1 - fx) + p[3] * fx) * (1 - fy) + (p[w * 3] * (1 - fx) + p[w * 3 + 3] * fx) * fy);
            }
            n++;
        }
        for (ch = 0; ch < 3; ch++) dst[((size_t)y * ow + x) * 3 + ch] = (unsigned char)(n ? acc[ch] / n : 0);
    }
    fl_level(dst, ow, oh, out);
    printf("%.2f %d %d\n", out[0] / 100.0, out[1], out[2]);
    free(src);
    free(dst);
    return 0;
}

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
    if (argc >= 4 && !strcmp(argv[1], "level")) return level_cmd(argv[2], atof(argv[3]), argc >= 5 ? atoi(argv[4]) : 320);
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
    } else if (!strcmp(argv[1], "live")) {
        int info[FL_LIVE_INFO], x, y, k;
        unsigned int *px = malloc(sizeof(unsigned int) * 320 * 240);
        unsigned char *jpg;
        long len;
        double t1;
        FILE *f = fopen(argv[2], "rb");
        if (!f) { fprintf(stderr, "cannot read %s\n", argv[2]); return 1; }
        fseek(f, 0, SEEK_END); len = ftell(f); rewind(f);
        jpg = malloc(len);
        if (fread(jpg, 1, len, f) != (size_t)len) { fprintf(stderr, "short read\n"); return 1; }
        fclose(f);
        t1 = now();
        for (k = 0; k < 10; k++)
            if (fl_live(jpg, (unsigned long)len, lut, params, px, 320, 240, info, err, sizeof err) != 0) { fprintf(stderr, "error: %s\n", err); return 1; }
        fprintf(stderr, "live %dx%d in %.1f ms; level %.2f deg, confidence %d, %d blocks\n", info[0], info[1], (now() - t1) * 100, info[2] / 100.0, info[3], info[4]);
        {   /* the same frame for the 16-bit screen, as the viewfinder makes it */
            static const int sh[4] = { 12, 8, 4, 0 };
            unsigned short *o16 = malloc(sizeof(unsigned short) * 540 * 480);
            double t2 = now();
            if (!o16 || fl_to4444(px, info[0], info[1], o16, 540, 480, 1500, sh) != 0) { fprintf(stderr, "error: 16-bit frame\n"); return 1; }
            fprintf(stderr, "16-bit 540x480 in %.1f ms\n", (now() - t2) * 1000);
            free(o16);
        }
        f = fopen(argv[3], "wb");
        fprintf(f, "P6\n%d %d\n255\n", info[0], info[1]);
        for (y = 0; y < info[1]; y++)
            for (x = 0; x < info[0]; x++) {
                unsigned int p = px[y * info[0] + x];
                fputc((p >> 16) & 255, f); fputc((p >> 8) & 255, f); fputc(p & 255, f);
            }
        fclose(f);
        free(px); free(jpg);
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
