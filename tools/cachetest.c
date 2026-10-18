/*
 * Mac test for the image core's shared caches (v0.45.0: films, grain tiles, decoded previews, the block
 * develop's table), as the camera uses them at once: three viewfinder composers on three threads, each
 * changing film every few frames, while two more threads make home-screen and Lab previews in other films
 * (the samples' size, the hero's and the Lab's 100% view: more pictures than the preview cache keeps), read
 * films ahead (fl_cache_lut), make the next develop's film ready (fl_prewarm) and now and then forget every
 * film (fl_forget_lut). More film files than the film cache holds, so films are dropped while other threads
 * still use them. Every frame and every preview must be exactly what one thread alone made.
 *   cachetest PHOTO.JPG LUTDIR TMPDIR [rounds]
 */
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "filmcore.h"

#define NFILMS 6
#define NPATHS 12                /* each film under two names: more than the cache's eight */
#define OW 540
#define OH 480

static const char *FILMS[NFILMS] = { "NATURAL.CUB", "PORTRA4W.CUB", "CLASSNEG.CUB", "CUBANNEG.CUB", "TRIX400.CUB", "PARTY800.CUB" };
static char g_paths[NPATHS][1024];
static const char *g_photo;
static unsigned char *g_jpeg;
static long g_len;
static int g_rounds = 40;
static const int PARAMS[FL_PARAM_COUNT] = { -1, 1, 6, 2, 95, 42, 1 };
static const int SHIFTS[4] = { 12, 8, 4, 0 };

/* the previews: the samples' size, the hero's, the Lab's 100% view */
#define NKINDS 3
static const int KIND[NKINDS][3] = { { 0, 420, 280 }, { 0, 720, 480 }, { 1, 420, 280 } };   /* mode, w, h */
static const char *KIND_NAME[NKINDS] = { "sample", "hero", "100% view" };

static unsigned short *g_ref_frame[NFILMS];
static unsigned int *g_ref_prev[NKINDS][NFILMS];
static int g_ref_wh[NKINDS][2];

static long g_frames, g_previews, g_bad;
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;

static void bad(const char *what, int film, const char *err) {
    pthread_mutex_lock(&g_mu);
    g_bad++;
    if (g_bad <= 10) printf("  FAIL  %s, film %s: %s\n", what, FILMS[film], err ? err : "picture differs");
    pthread_mutex_unlock(&g_mu);
}

static void count(long *n) {
    pthread_mutex_lock(&g_mu);
    (*n)++;
    pthread_mutex_unlock(&g_mu);
}

static int frame(fl_frame_ctx *c, int path, unsigned short *out, char *err) {
    int info[256], tm[16];   /* more than FL_FRAME_INFO / FL_FRAME_TIMES: the frame API may grow */
    return fl_frame(c, g_jpeg, (unsigned long)g_len, g_paths[path], PARAMS, FL_F_HIST, 1, out, OW, OH, OW, 1500, FL_FMT_565,
                    SHIFTS, 320, 240, info, tm, err, 256);
}

static int preview(int kind, int path, unsigned int *px, int *wh, char *err) {
    return fl_preview(g_photo, g_paths[path], PARAMS, KIND[kind][0], px, KIND[kind][1], KIND[kind][2], wh, err, 256);
}

static void *composer(void *arg) {
    int t = (int)(long)arg, k;
    fl_frame_ctx *c = fl_frame_new();
    unsigned short *out = malloc(sizeof(unsigned short) * OW * OH);
    char err[256];
    for (k = 0; k < g_rounds * 3; k++) {
        int path = (k / 3 + t * 5) % NPATHS, film = path % NFILMS;   /* a new film every third frame */
        err[0] = 0;
        if (frame(c, path, out, err) != 0) bad("frame", film, err);
        else if (memcmp(out, g_ref_frame[film], sizeof(unsigned short) * OW * OH) != 0) bad("frame", film, NULL);
        count(&g_frames);
    }
    fl_frame_free(c);
    free(out);
    return NULL;
}

/* worker 0: samples and the 100% view, reads ahead and forgets; worker 1: the hero and the 100% view, prewarms */
static void *worker(void *arg) {
    int id = (int)(long)arg, k, wh[2];
    unsigned int *px = malloc(sizeof(unsigned int) * 720 * 480);
    char err[256];
    for (k = 0; k < g_rounds * 2; k++) {
        int path = (k * 7 + 3 + id * 4) % NPATHS, film = path % NFILMS, kind = k % 3 == 2 ? 2 : id;
        err[0] = 0;
        if (preview(kind, path, px, wh, err) != 0) bad(KIND_NAME[kind], film, err);
        else if (memcmp(wh, g_ref_wh[kind], sizeof wh) != 0
                 || memcmp(px, g_ref_prev[kind][film], sizeof(unsigned int) * wh[0] * wh[1]) != 0) bad(KIND_NAME[kind], film, NULL);
        count(&g_previews);
        if (id == 0 && k % 4 == 1 && fl_cache_lut(g_paths[(path + 5) % NPATHS], err, sizeof err) != 0) bad("cacheLut", (path + 5) % NFILMS, err);
        if (id == 0 && k % 9 == 8) fl_forget_lut();
        if (id == 1 && k % 5 == 3) fl_prewarm(g_paths[(path + 2) % NPATHS], PARAMS);
    }
    free(px);
    return NULL;
}

int main(int argc, char **argv) {
    char err[256], live[1024];
    FILE *f;
    int i, k, n;
    pthread_t th[5];
    if (argc < 4) { fprintf(stderr, "cachetest PHOTO.JPG LUTDIR TMPDIR [rounds]\n"); return 2; }
    g_photo = argv[1];
    if (argc > 4) g_rounds = atoi(argv[4]);
    /* the films, each under two names (copies in TMPDIR) */
    for (i = 0; i < NPATHS; i++) {
        if (i < NFILMS) snprintf(g_paths[i], sizeof g_paths[i], "%s/%s", argv[2], FILMS[i]);
        else {
            FILE *a = fopen(g_paths[i - NFILMS], "rb"), *b;
            char buf[65536];
            size_t got;
            snprintf(g_paths[i], sizeof g_paths[i], "%s/COPY%d.CUB", argv[3], i);
            b = fopen(g_paths[i], "wb");
            if (!a || !b) { printf("FAIL cannot copy %s\n", g_paths[i - NFILMS]); return 1; }
            while ((got = fread(buf, 1, sizeof buf, a)) > 0) fwrite(buf, 1, got, b);
            fclose(a); fclose(b);
        }
    }
    /* a live frame like the camera's: a small JPEG of the photo */
    snprintf(live, sizeof live, "%s/LIVE.JPG", argv[3]);
    if (fl_make_preview(g_photo, live, err, sizeof err) != 0) { printf("FAIL live frame: %s\n", err); return 1; }
    f = fopen(live, "rb");
    fseek(f, 0, SEEK_END); g_len = ftell(f); rewind(f);
    g_jpeg = malloc(g_len);
    if (fread(g_jpeg, 1, g_len, f) != (size_t)g_len) { printf("FAIL short read\n"); return 1; }
    fclose(f);
    fl_set_overlay(NULL, 0, 0);
    /* the references: one thread, one film at a time, each from cold */
    for (k = 0; k < NFILMS; k++) {
        fl_frame_ctx *c = fl_frame_new();
        g_ref_frame[k] = malloc(sizeof(unsigned short) * OW * OH);
        fl_forget_lut();
        if (frame(c, k, g_ref_frame[k], err) != 0) { printf("FAIL reference frame: %s\n", err); return 1; }
        fl_frame_free(c);
        for (n = 0; n < NKINDS; n++) {
            g_ref_prev[n][k] = malloc(sizeof(unsigned int) * KIND[n][1] * KIND[n][2]);
            if (preview(n, k, g_ref_prev[n][k], g_ref_wh[n], err) != 0) { printf("FAIL reference %s: %s\n", KIND_NAME[n], err); return 1; }
        }
    }
    fl_forget_lut();
    for (i = 0; i < 3; i++) pthread_create(&th[i], NULL, composer, (void *)(long)i);
    for (i = 0; i < 2; i++) pthread_create(&th[3 + i], NULL, worker, (void *)(long)i);
    for (i = 0; i < 5; i++) pthread_join(th[i], NULL);
    fl_forget_lut();
    for (i = NFILMS; i < NPATHS; i++) remove(g_paths[i]);
    remove(live);
    if (g_bad) { printf("CACHE STRESS FAILED: %ld wrong of %ld frames and %ld previews\n", g_bad, g_frames, g_previews); return 1; }
    printf("CACHE STRESS OK: %ld frames on 3 threads and %ld previews on 2 more, %d film files through 8 slots, every picture exact\n",
           g_frames, g_previews, NPATHS);
    return 0;
}
