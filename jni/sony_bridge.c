/*
 * Bounded access to the camera's image memory (Sony "device memory"), for developing in hardware.
 *
 * Sony's public Java API exposes these images only as opaque objects. Reading and writing their
 * pixels needs four functions in the firmware's libcameraseq.so (DeviceMemoryDiadem getType,
 * getPropertyDiadem, readBuffer, writeBuffer), resolved here at run time; nothing Sony ships is
 * bundled. Every call is checked first: the handle must be a valid image or buffer, its real size
 * (asked from Sony) must match what the public API reported, every transfer must fit inside the
 * allocation and be no larger than a block size first verified on a scratch buffer, and a develop
 * must read and write two separate images, except the in-place develop below.
 *
 * In place (sb_develop_inplace): the look applied to one image, as the camera hands it over during
 * a capture (Sony's own capture-time apps change the developed image before storing it). Safe
 * because blocks go strictly forward: each block of rows is read before it is written back, and no
 * row is read again after it was written (the read-ahead only ever reads rows further on).
 *
 * Each Sony transfer costs ~11 ms however small (it maps shared memory), so a develop moves many
 * rows per call: one call per row took 90 s for a 24 MP frame.
 */
#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>

#include "filmcore.h"

typedef int (*type_fn)(void *self);
typedef int (*prop_fn)(void *self, int key, int *value);
typedef int (*read_fn)(void *self, void *dst, unsigned size, unsigned offset);
typedef int (*write_fn)(void *self, const void *src, unsigned size, unsigned offset);

#define MAX_TRANSFER (4 * 1024 * 1024)   /* hard ceiling; the block actually used is verified first */
#define PROP_SIZE 2              /* getPropertyDiadem key for the allocation size */

static struct {
    void *lib;
    type_fn type;
    prop_fn prop;
    read_fn read;
    write_fn write;
} S;

static char s_err[256];

#ifdef SB_HOST_TEST
/* Mac test build (tools/sonytest.c): the four Sony functions are fakes over plain memory. */
extern type_fn sb_test_type;
extern prop_fn sb_test_prop;
extern read_fn sb_test_read;
extern write_fn sb_test_write;
static int load(void) {
    S.lib = (void *)1; S.type = sb_test_type; S.prop = sb_test_prop; S.read = sb_test_read; S.write = sb_test_write;
    return 0;
}
#else
static int load(void) {
    if (S.lib) return 0;
    S.lib = dlopen("libcameraseq.so", RTLD_NOW | RTLD_LOCAL);
    if (!S.lib) { snprintf(s_err, sizeof s_err, "libcameraseq.so not available"); return -1; }
    S.type = (type_fn)dlsym(S.lib, "_ZN6scalar18DeviceMemoryDiadem7getTypeEv");
    S.prop = (prop_fn)dlsym(S.lib, "_ZN6scalar18DeviceMemoryDiadem17getPropertyDiademEiPi");
    S.read = (read_fn)dlsym(S.lib, "_ZN6scalar18DeviceMemoryDiadem10readBufferEPvjj");
    S.write = (write_fn)dlsym(S.lib, "_ZN6scalar18DeviceMemoryDiadem11writeBufferEPKvjj");
    if (!S.type || !S.prop || !S.read || !S.write) {
        snprintf(s_err, sizeof s_err, "a Sony memory function is missing");
        S.lib = NULL;
        return -1;
    }
    return 0;
}
#endif

/* The handle's real allocation size, or -1 if it is not a memory type we accept. */
static int checked_size(void *h, int image_only) {
    int type, size = 0;
    if (!h) return -1;
    type = S.type(h);
    if (type != 2 && (image_only || type != 1)) return -1;    /* 2 = image, 1 = plain buffer */
    if (!S.prop(h, PROP_SIZE, &size) || size <= 0) return -1;
    return size;
}

/* Bounded single transfer for the scratch-buffer calibration. Returns bytes moved or -1. */
static int transfer(JNIEnv *env, jint handle, jbyteArray arr, jint size, jint offset, int write) {
    void *h = (void *)(intptr_t)handle;
    jbyte *bytes;
    int total, rc;
    s_err[0] = 0;
    if (load() != 0) return -1;
    total = checked_size(h, 0);
    if (total < 0) { snprintf(s_err, sizeof s_err, "not a valid Sony buffer"); return -1; }
    if (size <= 0 || size > MAX_TRANSFER || offset < 0 || !arr || (*env)->GetArrayLength(env, arr) < size
            || (long long)offset + size > total) {
        snprintf(s_err, sizeof s_err, "transfer out of bounds (%d bytes at %d of %d)", size, offset, total);
        return -1;
    }
    bytes = (*env)->GetByteArrayElements(env, arr, NULL);
    rc = write ? S.write(h, bytes, (unsigned)size, (unsigned)offset) : S.read(h, bytes, (unsigned)size, (unsigned)offset);
    (*env)->ReleaseByteArrayElements(env, arr, bytes, write ? JNI_ABORT : 0);
    return rc == size ? size : -1;
}

JNIEXPORT jint JNICALL Java_com_latent_camera_SonyMemory_read(JNIEnv *env, jclass cls, jint handle, jbyteArray dst, jint size, jint offset) {
    (void)cls;
    return transfer(env, handle, dst, size, offset, 0);
}

JNIEXPORT jint JNICALL Java_com_latent_camera_SonyMemory_write(JNIEnv *env, jclass cls, jint handle, jbyteArray src, jint size, jint offset) {
    (void)cls;
    return transfer(env, handle, src, size, offset, 1);
}

/* geometry, as read through Sony's public DSP.getPropertyAsInt */
enum { G_TW, G_TH, G_CW, G_CH, G_OFF, G_X, G_Y, G_SIZE, G_COUNT };

typedef struct {
    void *src, *dst;
    int sg[G_COUNT], dg[G_COUNT];
    unsigned char *rbuf, *wbuf;      /* blocks of rows: read ahead from the source, gathered for the output */
    unsigned char *rb[2];            /* the two block buffers of the pipelined develop */
    int bfirst[2], bcount[2];        /* the block each holds */
    int rrows, rfirst, rcount;       /* rows per read block; the block held */
    int wrows, wfirst, wcount;       /* rows per write block; the block being gathered */
    int direct;                      /* output laid out exactly like the source, without padding: write blocks straight back */
} develop_ctx;

static int geometry_ok(const int *g, int real_size) {
    long long canvas = (long long)g[G_CW] * g[G_CH] * 2;
    return g[G_TW] > 0 && g[G_TH] > 0 && g[G_CW] >= g[G_TW] && g[G_CH] >= g[G_TH] && g[G_OFF] >= 0
            && g[G_X] >= 0 && g[G_Y] >= 0 && g[G_X] + g[G_TW] <= g[G_CW] && g[G_Y] + g[G_TH] <= g[G_CH]
            && g[G_SIZE] == real_size && g[G_OFF] + canvas <= real_size && (long long)g[G_CW] * 2 <= MAX_TRANSFER;
}

static long long row_offset(const int *g, int y) {
    return g[G_OFF] + ((long long)(g[G_Y] + y) * g[G_CW] + g[G_X]) * 2;
}

/* Writes the gathered rows [wfirst, wfirst + wcount) of the output in one call. */
static int flush(develop_ctx *c) {
    const int *g = c->dg;
    long long offset = row_offset(g, c->wfirst), stride = (long long)g[G_CW] * 2;
    unsigned bytes = (unsigned)((c->wcount - 1) * stride + (long long)g[G_TW] * 2);
    if (c->wcount == 0) return 0;
    if (offset < 0 || offset + bytes > g[G_SIZE]) return -1;   /* never outside the image */
    if (S.write(c->dst, c->wbuf, bytes, (unsigned)offset) != (int)bytes) return -1;
    c->wfirst += c->wcount;
    c->wcount = 0;
    return 0;
}

/*
 * Rows are read ahead in blocks of whole canvas rows and served from memory; output rows are
 * gathered into blocks and written in one call. Writing whole canvas rows would also overwrite the
 * output's padding, so when its canvas is wider than the picture the gathered block is first read
 * back from the output (padding kept as it was) and only the picture part of each row is replaced.
 */
static int row_io(void *p, int y, unsigned char *row, int write) {
    develop_ctx *c = (develop_ctx *)p;
    if (!write) {
        const int *g = c->sg;
        long long stride = (long long)g[G_CW] * 2;
        if (y < 0 || y >= g[G_TH]) return -1;
        if (y < c->rfirst || y >= c->rfirst + c->rcount) {
            int n = c->rrows < g[G_TH] - y ? c->rrows : g[G_TH] - y;
            long long offset = row_offset(g, y);
            unsigned bytes = (unsigned)((n - 1) * stride + (long long)g[G_TW] * 2);
            if (offset < 0 || offset + bytes > g[G_SIZE]) return -1;   /* never outside the image */
            if (S.read(c->src, c->rbuf, bytes, (unsigned)offset) != (int)bytes) return -1;
            c->rfirst = y;
            c->rcount = n;
        }
        memcpy(row, c->rbuf + (y - c->rfirst) * stride, (size_t)g[G_TW] * 2);
        return 0;
    } else {
        const int *g = c->dg;
        long long stride = (long long)g[G_CW] * 2;
        if (y != c->wfirst + c->wcount || y >= g[G_TH]) return -1;     /* rows arrive in order */
        if (c->wcount == 0 && g[G_CW] != g[G_TW]) {                     /* padded canvas: keep the padding */
            int n = c->wrows < g[G_TH] - y ? c->wrows : g[G_TH] - y;
            long long offset = row_offset(g, y);
            unsigned bytes = (unsigned)((n - 1) * stride + (long long)g[G_TW] * 2);
            if (offset < 0 || offset + bytes > g[G_SIZE]) return -1;
            if (S.read(c->dst, c->wbuf, bytes, (unsigned)offset) != (int)bytes) return -1;
        }
        memcpy(c->wbuf + c->wcount * stride, row, (size_t)g[G_TW] * 2);
        c->wcount++;
        if (c->wcount == c->wrows || y == g[G_TH] - 1) return flush(c);
        return 0;
    }
}

/*
 * Blocks: the next block of source rows is read once and handed over to be changed in place, then
 * sent on either whole, straight from the read block (direct: the output is laid out like the source,
 * without padding), or row by row into the output's gathered block (keeping its padding).
 */
static unsigned char *block_get(void *p, int y, int buf, int *n, int *stride) {
    develop_ctx *c = (develop_ctx *)p;
    const int *g = c->sg;
    long long st = (long long)g[G_CW] * 2;
    int count;
    long long offset;
    unsigned bytes;
    if (y < 0 || y >= g[G_TH] || buf < 0 || buf > 1) return NULL;
    count = c->rrows < g[G_TH] - y ? c->rrows : g[G_TH] - y;
    offset = row_offset(g, y);
    bytes = (unsigned)((count - 1) * st + (long long)g[G_TW] * 2);
    if (offset < 0 || offset + bytes > g[G_SIZE]) return NULL;   /* never outside the image */
    if (S.read(c->src, c->rb[buf], bytes, (unsigned)offset) != (int)bytes) return NULL;
    c->bfirst[buf] = y;
    c->bcount[buf] = count;
    *n = count;
    *stride = (int)st;
    return c->rb[buf];
}

static int block_put(void *p, int y, int n, int buf) {
    develop_ctx *c = (develop_ctx *)p;
    const int *g = c->dg;
    long long st = (long long)c->sg[G_CW] * 2;
    int i;
    if (buf < 0 || buf > 1 || y != c->bfirst[buf] || n != c->bcount[buf]) return -1;
    if (!c->direct) {
        for (i = 0; i < n; i++) if (row_io(p, y + i, c->rb[buf] + i * st, 1) != 0) return -1;
        return 0;
    }
    {
        long long offset = row_offset(g, y);
        unsigned bytes = (unsigned)((n - 1) * st + (long long)g[G_TW] * 2);
        if (offset < 0 || offset + bytes > g[G_SIZE]) return -1;   /* never outside the image */
        if (S.write(c->dst, c->rb[buf], bytes, (unsigned)offset) != (int)bytes) return -1;
    }
    return 0;
}

struct progress_ctx { JNIEnv *env; jobject listener; jmethodID method; };

static void on_progress(void *p, int pct) {
    struct progress_ctx *c = (struct progress_ctx *)p;
    if (c->listener && c->method) (*c->env)->CallVoidMethod(c->env, c->listener, c->method, (jint)pct);
}

static int s_last_direct;   /* the last develop wrote blocks straight back (1) or gathered rows (0) */

static int develop_any(void *src, const int *sg, void *dst, const int *dg, int block, const char *lut, const int *params,
                       fl_progress_fn progress, void *pctx);

/* Develops src into dst (two separate Sony images) through blocks of at most 'block' bytes. */
int sb_develop(void *src, const int *sg, void *dst, const int *dg, int block, const char *lut, const int *params,
               fl_progress_fn progress, void *pctx) {
    s_err[0] = 0;
    if (src == dst) { snprintf(s_err, sizeof s_err, "source and output must be separate images"); return -1; }
    return develop_any(src, sg, dst, dg, block, lut, params, progress, pctx);
}

/* The look applied to one image in place (see the top of this file). */
int sb_develop_inplace(void *img, const int *g, int block, const char *lut, const int *params, fl_progress_fn progress, void *pctx) {
    s_err[0] = 0;
    return develop_any(img, g, img, g, block, lut, params, progress, pctx);
}

/*
 * Before an in-place develop: the first 'size' bytes of the picture are read, written back
 * unchanged, and read again; they must match. Proves the transfers on this very image (no scratch
 * buffer can be had while the camera is open). Returns 0 when verified.
 */
int sb_verify_inplace(void *img, const int *g, int size) {
    int total;
    long long offset;
    unsigned char *a, *b;
    int ok;
    s_err[0] = 0;
    if (load() != 0) return -1;
    total = checked_size(img, 1);
    if (total < 0 || !geometry_ok(g, total)) { snprintf(s_err, sizeof s_err, "not a valid Sony image"); return -1; }
    offset = row_offset(g, 0);
    if (size <= 0 || size > MAX_TRANSFER || offset + size > total) { snprintf(s_err, sizeof s_err, "check out of bounds"); return -1; }
    a = (unsigned char *)malloc((size_t)size);
    b = (unsigned char *)malloc((size_t)size);
    if (!a || !b) { free(a); free(b); snprintf(s_err, sizeof s_err, "out of memory"); return -1; }
    ok = S.read(img, a, (unsigned)size, (unsigned)offset) == size
            && S.write(img, a, (unsigned)size, (unsigned)offset) == size
            && S.read(img, b, (unsigned)size, (unsigned)offset) == size
            && memcmp(a, b, (size_t)size) == 0;
    if (!ok) snprintf(s_err, sizeof s_err, "read / write back / read did not match at %d bytes", size);
    free(a);
    free(b);
    return ok ? 0 : -1;
}

static int develop_any(void *src, const int *sg, void *dst, const int *dg, int block, const char *lut, const int *params,
                       fl_progress_fn progress, void *pctx) {
    develop_ctx c;
    int ss, ds, rc;
    if (load() != 0) return -1;
    memcpy(c.sg, sg, sizeof c.sg);
    memcpy(c.dg, dg, sizeof c.dg);
    c.src = src;
    c.dst = dst;
    ss = checked_size(c.src, 1);
    ds = checked_size(c.dst, 1);
    if (ss < 0 || ds < 0) { snprintf(s_err, sizeof s_err, "not valid Sony images"); return -1; }
    if (!geometry_ok(c.sg, ss) || !geometry_ok(c.dg, ds)) {
        snprintf(s_err, sizeof s_err, "image layout does not fit its memory (src %dx%d in %d, dst %dx%d in %d)",
                 c.sg[G_TW], c.sg[G_TH], ss, c.dg[G_TW], c.dg[G_TH], ds);
        return -1;
    }
    if (c.sg[G_TW] != c.dg[G_TW] || c.sg[G_TH] != c.dg[G_TH] || (c.sg[G_TW] & 1)) {
        snprintf(s_err, sizeof s_err, "source and output sizes differ");
        return -1;
    }
    if (block > MAX_TRANSFER) block = MAX_TRANSFER;
    c.rrows = block / (c.sg[G_CW] * 2);
    c.wrows = block / (c.dg[G_CW] * 2);
    if (c.rrows < 1) c.rrows = 1;   /* one row always fits: geometry_ok capped the canvas width */
    if (c.wrows < 1) c.wrows = 1;
    c.rfirst = c.rcount = c.wfirst = c.wcount = 0;
    c.direct = c.dg[G_CW] == c.sg[G_CW] && c.dg[G_CW] == c.dg[G_TW];
    s_last_direct = c.direct;
    c.rbuf = (unsigned char *)malloc((size_t)c.rrows * c.sg[G_CW] * 2);
    c.wbuf = (unsigned char *)malloc((size_t)c.wrows * c.dg[G_CW] * 2);
    c.rb[0] = c.rbuf;
    c.rb[1] = (unsigned char *)malloc((size_t)c.rrows * c.sg[G_CW] * 2);
    c.bfirst[0] = c.bfirst[1] = c.bcount[0] = c.bcount[1] = -1;
    if (!c.rbuf || !c.wbuf || !c.rb[1]) { free(c.rbuf); free(c.wbuf); free(c.rb[1]); snprintf(s_err, sizeof s_err, "out of memory for blocks"); return -1; }
    rc = fl_develop_blocks(lut, params, c.sg[G_TW], c.sg[G_TH], block_get, block_put, &c, progress, pctx, s_err, sizeof s_err);
    free(c.rbuf);
    free(c.rb[1]);
    free(c.wbuf);
    return rc;
}

const char *sb_last_error(void) { return s_err; }

JNIEXPORT jboolean JNICALL Java_com_latent_camera_SonyMemory_lastDirect(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return s_last_direct ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_latent_camera_SonyMemory_develop(JNIEnv *env, jclass cls, jint src, jintArray jsg,
        jint dst, jintArray jdg, jint block, jstring jlut, jintArray jparams, jobject listener) {
    struct progress_ctx pc;
    int sg[G_COUNT], dg[G_COUNT], params[FL_PARAM_COUNT], rc;
    const char *lut = NULL;
    (void)cls;
    s_err[0] = 0;
    if (!jsg || !jdg || (*env)->GetArrayLength(env, jsg) < G_COUNT || (*env)->GetArrayLength(env, jdg) < G_COUNT
            || !jparams || (*env)->GetArrayLength(env, jparams) < FL_PARAM_COUNT) {
        snprintf(s_err, sizeof s_err, "bad arguments");
        return -1;
    }
    (*env)->GetIntArrayRegion(env, jsg, 0, G_COUNT, (jint *)sg);
    (*env)->GetIntArrayRegion(env, jdg, 0, G_COUNT, (jint *)dg);
    (*env)->GetIntArrayRegion(env, jparams, 0, FL_PARAM_COUNT, (jint *)params);
    pc.env = env;
    pc.listener = listener;
    pc.method = NULL;
    if (listener) {
        jclass lc = (*env)->GetObjectClass(env, listener);
        pc.method = (*env)->GetMethodID(env, lc, "onProgress", "(I)V");
        (*env)->DeleteLocalRef(env, lc);
    }
    if (jlut) lut = (*env)->GetStringUTFChars(env, jlut, NULL);
    rc = sb_develop((void *)(intptr_t)src, sg, (void *)(intptr_t)dst, dg, block, lut, params, on_progress, &pc);
    if (jlut) (*env)->ReleaseStringUTFChars(env, jlut, lut);
    return rc;
}

JNIEXPORT jint JNICALL Java_com_latent_camera_SonyMemory_developInPlace(JNIEnv *env, jclass cls, jint img, jintArray jg,
        jint block, jstring jlut, jintArray jparams, jobject listener) {
    struct progress_ctx pc;
    int g[G_COUNT], params[FL_PARAM_COUNT], rc;
    const char *lut = NULL;
    (void)cls;
    s_err[0] = 0;
    if (!jg || (*env)->GetArrayLength(env, jg) < G_COUNT || !jparams || (*env)->GetArrayLength(env, jparams) < FL_PARAM_COUNT) {
        snprintf(s_err, sizeof s_err, "bad arguments");
        return -1;
    }
    (*env)->GetIntArrayRegion(env, jg, 0, G_COUNT, (jint *)g);
    (*env)->GetIntArrayRegion(env, jparams, 0, FL_PARAM_COUNT, (jint *)params);
    pc.env = env;
    pc.listener = listener;
    pc.method = NULL;
    if (listener) {
        jclass lc = (*env)->GetObjectClass(env, listener);
        pc.method = (*env)->GetMethodID(env, lc, "onProgress", "(I)V");
        (*env)->DeleteLocalRef(env, lc);
    }
    if (jlut) lut = (*env)->GetStringUTFChars(env, jlut, NULL);
    rc = sb_develop_inplace((void *)(intptr_t)img, g, block, lut, params, on_progress, &pc);
    if (jlut) (*env)->ReleaseStringUTFChars(env, jlut, lut);
    return rc;
}

JNIEXPORT jint JNICALL Java_com_latent_camera_SonyMemory_verifyInPlace(JNIEnv *env, jclass cls, jint img, jintArray jg, jint size) {
    int g[G_COUNT];
    (void)cls;
    if (!jg || (*env)->GetArrayLength(env, jg) < G_COUNT) { snprintf(s_err, sizeof s_err, "bad arguments"); return -1; }
    (*env)->GetIntArrayRegion(env, jg, 0, G_COUNT, (jint *)g);
    return sb_verify_inplace((void *)(intptr_t)img, g, size);
}

JNIEXPORT jstring JNICALL Java_com_latent_camera_SonyMemory_lastError(JNIEnv *env, jclass cls) {
    (void)cls;
    return (*env)->NewStringUTF(env, s_err);
}
