/* JNI glue between com.latent.camera.FilmCore and filmcore.c. Calls are serialised by the Java
 * side (one worker thread), so the shared error buffer is safe. */
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "filmcore.h"

static char g_err[256];

struct progress_ctx {
    JNIEnv *env;
    jobject listener;
    jmethodID method;
};

static void on_progress(void *p, int pct) {
    struct progress_ctx *c = (struct progress_ctx *)p;
    if (c->listener && c->method) (*c->env)->CallVoidMethod(c->env, c->listener, c->method, (jint)pct);
}

static int read_params(JNIEnv *env, jintArray arr, int *out) {
    if (!arr || (*env)->GetArrayLength(env, arr) < FL_PARAM_COUNT) return -1;
    (*env)->GetIntArrayRegion(env, arr, 0, FL_PARAM_COUNT, (jint *)out);
    return 0;
}

JNIEXPORT jint JNICALL Java_com_latent_camera_FilmCore_process(JNIEnv *env, jclass cls, jstring jin,
        jstring jout, jstring jlut, jstring jpreview, jintArray jparams, jobject listener) {
    int params[FL_PARAM_COUNT];
    const char *in, *out, *lut = NULL, *preview = NULL;
    struct progress_ctx ctx;
    int rc;
    (void)cls;
    g_err[0] = 0;
    if (read_params(env, jparams, params) != 0) { strcpy(g_err, "bad parameters"); return -1; }
    in = (*env)->GetStringUTFChars(env, jin, NULL);
    out = (*env)->GetStringUTFChars(env, jout, NULL);
    if (jlut) lut = (*env)->GetStringUTFChars(env, jlut, NULL);
    if (jpreview) preview = (*env)->GetStringUTFChars(env, jpreview, NULL);
    ctx.env = env;
    ctx.listener = listener;
    ctx.method = NULL;
    if (listener) {
        jclass lc = (*env)->GetObjectClass(env, listener);
        ctx.method = (*env)->GetMethodID(env, lc, "onProgress", "(I)V");
        (*env)->DeleteLocalRef(env, lc);
    }
    rc = fl_process_file(in, out, lut, preview, params, on_progress, &ctx, g_err, sizeof g_err);
    (*env)->ReleaseStringUTFChars(env, jin, in);
    (*env)->ReleaseStringUTFChars(env, jout, out);
    if (jlut) (*env)->ReleaseStringUTFChars(env, jlut, lut);
    if (jpreview) (*env)->ReleaseStringUTFChars(env, jpreview, preview);
    return rc;
}

JNIEXPORT jint JNICALL Java_com_latent_camera_FilmCore_makePreview(JNIEnv *env, jclass cls, jstring jin, jstring jout) {
    const char *in, *out;
    int rc;
    (void)cls;
    g_err[0] = 0;
    in = (*env)->GetStringUTFChars(env, jin, NULL);
    out = (*env)->GetStringUTFChars(env, jout, NULL);
    rc = fl_make_preview(in, out, g_err, sizeof g_err);
    (*env)->ReleaseStringUTFChars(env, jin, in);
    (*env)->ReleaseStringUTFChars(env, jout, out);
    return rc;
}

JNIEXPORT jint JNICALL Java_com_latent_camera_FilmCore_preview(JNIEnv *env, jclass cls, jstring jin,
        jstring jlut, jintArray jparams, jint mode, jintArray jpixels, jint w, jint h, jintArray jwh) {
    int params[FL_PARAM_COUNT], wh[2] = { 0, 0 };
    const char *in, *lut = NULL;
    jint *px;
    int rc;
    (void)cls;
    g_err[0] = 0;
    if (read_params(env, jparams, params) != 0) { strcpy(g_err, "bad parameters"); return -1; }
    if (!jpixels || (*env)->GetArrayLength(env, jpixels) < w * h || !jwh || (*env)->GetArrayLength(env, jwh) < 2) {
        strcpy(g_err, "bad preview buffer");
        return -1;
    }
    in = (*env)->GetStringUTFChars(env, jin, NULL);
    if (jlut) lut = (*env)->GetStringUTFChars(env, jlut, NULL);
    px = (*env)->GetIntArrayElements(env, jpixels, NULL);
    rc = fl_preview(in, lut, params, mode, (unsigned int *)px, w, h, wh, g_err, sizeof g_err);
    (*env)->ReleaseIntArrayElements(env, jpixels, px, 0);
    (*env)->ReleaseStringUTFChars(env, jin, in);
    if (jlut) (*env)->ReleaseStringUTFChars(env, jlut, lut);
    if (rc == 0) (*env)->SetIntArrayRegion(env, jwh, 0, 2, (const jint *)wh);
    return rc;
}

JNIEXPORT jint JNICALL Java_com_latent_camera_FilmCore_live(JNIEnv *env, jclass cls, jbyteArray jjpeg, jint len,
        jstring jlut, jintArray jparams, jintArray jpixels, jint w, jint h, jintArray jinfo) {
    int params[FL_PARAM_COUNT], info[FL_LIVE_INFO];
    const char *lut = NULL;
    jbyte *jpeg;
    jint *px;
    int rc;
    (void)cls;
    g_err[0] = 0;
    if (read_params(env, jparams, params) != 0) { strcpy(g_err, "bad parameters"); return -1; }
    if (!jjpeg || len <= 0 || (*env)->GetArrayLength(env, jjpeg) < len || !jpixels || (*env)->GetArrayLength(env, jpixels) < w * h
            || !jinfo || (*env)->GetArrayLength(env, jinfo) < FL_LIVE_INFO) {
        strcpy(g_err, "bad live frame buffers");
        return -1;
    }
    if (jlut) lut = (*env)->GetStringUTFChars(env, jlut, NULL);
    jpeg = (*env)->GetByteArrayElements(env, jjpeg, NULL);
    px = (*env)->GetIntArrayElements(env, jpixels, NULL);
    rc = fl_live((const unsigned char *)jpeg, (unsigned long)len, lut, params, (unsigned int *)px, w, h, info, g_err, sizeof g_err);
    (*env)->ReleaseIntArrayElements(env, jpixels, px, 0);
    (*env)->ReleaseByteArrayElements(env, jjpeg, jpeg, JNI_ABORT);
    if (jlut) (*env)->ReleaseStringUTFChars(env, jlut, lut);
    if (rc == 0) (*env)->SetIntArrayRegion(env, jinfo, 0, FL_LIVE_INFO, (const jint *)info);
    return rc;
}

/* ---- the viewfinder's frame composer: each look thread has its own context (frame calls run on two
 * threads at once, so errors go through a local buffer) */

/* Android's bitmap pixels, written in place: libjnigraphics, opened when first asked for (not linked, so a
 * body without it still loads Latent; the frame then goes through a short[] and a copy). */
typedef struct { uint32_t width, height, stride; int32_t format; uint32_t flags; } fl_bitmap_info;
static int (*bm_info)(JNIEnv *, jobject, fl_bitmap_info *);
static int (*bm_lock)(JNIEnv *, jobject, void **);
static int (*bm_unlock)(JNIEnv *, jobject);
static int bm_state;   /* 0 not tried, 1 ready, -1 unavailable */

static int bitmaps(void) {
    if (bm_state == 0) {
        void *h = dlopen("libjnigraphics.so", RTLD_NOW);
        if (h) {
            bm_info = (int (*)(JNIEnv *, jobject, fl_bitmap_info *))dlsym(h, "AndroidBitmap_getInfo");
            bm_lock = (int (*)(JNIEnv *, jobject, void **))dlsym(h, "AndroidBitmap_lockPixels");
            bm_unlock = (int (*)(JNIEnv *, jobject))dlsym(h, "AndroidBitmap_unlockPixels");
        }
        bm_state = bm_info && bm_lock && bm_unlock ? 1 : -1;
    }
    return bm_state == 1;
}

JNIEXPORT jlong JNICALL Java_com_latent_camera_FilmCore_frameNew(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)fl_frame_new();
}

JNIEXPORT void JNICALL Java_com_latent_camera_FilmCore_frameFree(JNIEnv *env, jclass cls, jlong ctx) {
    (void)env; (void)cls;
    fl_frame_free((fl_frame_ctx *)(intptr_t)ctx);
}

/* Returns 0, -1 (lastError), or -2: no Bitmap access here (pass a short[] instead). */
JNIEXPORT jint JNICALL Java_com_latent_camera_FilmCore_frame(JNIEnv *env, jclass cls, jlong jctx, jbyteArray jjpeg, jint len,
        jstring jlut, jintArray jparams, jint flags, jint seq, jshortArray jout, jobject jbitmap, jint ow, jint oh,
        jint aspect1000, jint format, jintArray jshifts, jint max_w, jint max_h, jintArray jinfo, jintArray jtm) {
    int params[FL_PARAM_COUNT], info[FL_FRAME_INFO], tm[FL_FRAME_TIMES], shifts[4], rc, stride = ow;
    char err[256];
    const char *lut = NULL;
    jbyte *jpeg = NULL;
    jshort *out = NULL;
    void *pixels = NULL;
    fl_frame_ctx *ctx = (fl_frame_ctx *)(intptr_t)jctx;
    (void)cls;
    err[0] = 0;
    if (!ctx || read_params(env, jparams, params) != 0 || ow <= 0 || oh <= 0
            || !jinfo || (*env)->GetArrayLength(env, jinfo) < FL_FRAME_INFO
            || (jtm && (*env)->GetArrayLength(env, jtm) < FL_FRAME_TIMES)
            || (jjpeg && (len <= 0 || (*env)->GetArrayLength(env, jjpeg) < len))
            || (format == FL_FMT_4444 && (!jshifts || (*env)->GetArrayLength(env, jshifts) < 4))
            || (!jbitmap && (!jout || (*env)->GetArrayLength(env, jout) < ow * oh))) {
        strcpy(g_err, "bad frame buffers");
        return -1;
    }
    if (format == FL_FMT_4444) (*env)->GetIntArrayRegion(env, jshifts, 0, 4, (jint *)shifts);
    if (jbitmap) {
        fl_bitmap_info bi;
        int want = format == FL_FMT_565 ? 4 : 7;   /* ANDROID_BITMAP_FORMAT_RGB_565, _RGBA_4444 */
        if (!bitmaps()) return -2;
        if (bm_info(env, jbitmap, &bi) != 0 || (int)bi.width != ow || (int)bi.height != oh || bi.format != want || (bi.stride & 1)) {
            strcpy(g_err, "the screen bitmap doesn't match");
            return -1;
        }
        stride = (int)bi.stride / 2;
    }
    if (jlut) lut = (*env)->GetStringUTFChars(env, jlut, NULL);
    if (jjpeg) jpeg = (*env)->GetByteArrayElements(env, jjpeg, NULL);
    if (jbitmap) {
        if (bm_lock(env, jbitmap, &pixels) != 0 || !pixels) { pixels = NULL; }
    } else out = (*env)->GetShortArrayElements(env, jout, NULL);
    if (jbitmap && !pixels) { strcpy(err, "the screen bitmap couldn't be locked"); rc = -1; }
    else rc = fl_frame(ctx, (const unsigned char *)jpeg, (unsigned long)len, lut, params, flags, seq,
                       jbitmap ? (unsigned short *)pixels : (unsigned short *)out, ow, oh, stride, aspect1000, format,
                       shifts, max_w, max_h, info, tm, err, sizeof err);
    if (pixels) bm_unlock(env, jbitmap);
    if (out) (*env)->ReleaseShortArrayElements(env, jout, out, rc == 1 ? JNI_ABORT : 0);   /* a still frame wrote nothing */
    if (jpeg) (*env)->ReleaseByteArrayElements(env, jjpeg, jpeg, JNI_ABORT);
    if (jlut) (*env)->ReleaseStringUTFChars(env, jlut, lut);
    if (rc >= 0) {
        (*env)->SetIntArrayRegion(env, jinfo, 0, FL_FRAME_INFO, (const jint *)info);
        if (jtm) (*env)->SetIntArrayRegion(env, jtm, 0, FL_FRAME_TIMES, (const jint *)tm);
    } else snprintf(g_err, sizeof g_err, "%s", err);
    return rc;
}

JNIEXPORT jstring JNICALL Java_com_latent_camera_FilmCore_probe(JNIEnv *env, jclass cls, jobject jbitmap) {
    char rep[512];
    void *pixels = NULL;
    fl_bitmap_info bi;
    int bytes = 0;
    (void)cls;
    if (jbitmap && bitmaps() && bm_info(env, jbitmap, &bi) == 0 && bm_lock(env, jbitmap, &pixels) == 0 && pixels) bytes = (int)(bi.stride * bi.height);
    else pixels = NULL;
    fl_probe((unsigned char *)pixels, bytes, rep, sizeof rep);
    if (pixels) bm_unlock(env, jbitmap);
    return (*env)->NewStringUTF(env, rep);
}

JNIEXPORT void JNICALL Java_com_latent_camera_FilmCore_setStillRef(JNIEnv *env, jclass cls, jintArray jsig, jint threshold) {
    int sig[FL_FI_SIG_N];
    (void)cls;
    if (!jsig || (*env)->GetArrayLength(env, jsig) < FL_FI_SIG_N) { fl_set_still_ref(NULL, 0); return; }
    (*env)->GetIntArrayRegion(env, jsig, 0, FL_FI_SIG_N, (jint *)sig);
    fl_set_still_ref(sig, threshold);
}

JNIEXPORT jint JNICALL Java_com_latent_camera_FilmCore_setOverlay(JNIEnv *env, jclass cls, jintArray jargb, jint w, jint h) {
    jint *px;
    int gen;
    (void)cls;
    if (!jargb) return fl_set_overlay(NULL, 0, 0);
    if (w <= 0 || h <= 0 || (*env)->GetArrayLength(env, jargb) < w * h) return -1;
    px = (*env)->GetIntArrayElements(env, jargb, NULL);
    gen = fl_set_overlay((const unsigned int *)px, w, h);
    (*env)->ReleaseIntArrayElements(env, jargb, px, JNI_ABORT);
    return gen;
}

JNIEXPORT jint JNICALL Java_com_latent_camera_FilmCore_pinCore(JNIEnv *env, jclass cls, jint core) {
    int e = 0;
    (void)env; (void)cls;
    if (fl_pin_core(core, &e) == 0) return 0;
    snprintf(g_err, sizeof g_err, "core %d refused (errno %d)", core, e);
    return -1;
}

JNIEXPORT jint JNICALL Java_com_latent_camera_FilmCore_to4444(JNIEnv *env, jclass cls, jintArray jargb, jint w, jint h,
        jshortArray jout, jint ow, jint oh, jint aspect1000, jintArray jshifts) {
    jint *argb;
    jshort *out;
    int shifts[4], rc;
    (void)cls;
    g_err[0] = 0;
    if (!jargb || !jout || !jshifts || w <= 1 || h <= 1 || ow <= 0 || oh <= 0 || (*env)->GetArrayLength(env, jargb) < w * h
            || (*env)->GetArrayLength(env, jout) < ow * oh || (*env)->GetArrayLength(env, jshifts) < 4) {
        strcpy(g_err, "bad 16-bit frame buffers");
        return -1;
    }
    (*env)->GetIntArrayRegion(env, jshifts, 0, 4, (jint *)shifts);
    argb = (*env)->GetIntArrayElements(env, jargb, NULL);
    out = (*env)->GetShortArrayElements(env, jout, NULL);
    rc = fl_to4444((const unsigned int *)argb, w, h, (unsigned short *)out, ow, oh, aspect1000, shifts);
    (*env)->ReleaseShortArrayElements(env, jout, out, 0);
    (*env)->ReleaseIntArrayElements(env, jargb, argb, JNI_ABORT);
    if (rc != 0) strcpy(g_err, "bad 16-bit frame sizes");
    return rc;
}

JNIEXPORT jstring JNICALL Java_com_latent_camera_FilmCore_lastError(JNIEnv *env, jclass cls) {
    (void)cls;
    return (*env)->NewStringUTF(env, g_err);
}

JNIEXPORT void JNICALL Java_com_latent_camera_FilmCore_timing(JNIEnv *env, jclass cls, jintArray jout) {
    int t[FL_TIME_COUNT];
    (void)cls;
    if (!jout || (*env)->GetArrayLength(env, jout) < FL_TIME_COUNT) return;
    fl_last_timing(t);
    (*env)->SetIntArrayRegion(env, jout, 0, FL_TIME_COUNT, (const jint *)t);
}

JNIEXPORT jstring JNICALL Java_com_latent_camera_FilmCore_bench(JNIEnv *env, jclass cls, jstring jin, jstring jlut,
        jintArray jparams, jint scale) {
    static char report[2048];
    int params[FL_PARAM_COUNT], rc;
    const char *in, *lut = NULL;
    (void)cls;
    if (read_params(env, jparams, params) != 0) return NULL;
    in = (*env)->GetStringUTFChars(env, jin, NULL);
    if (jlut) lut = (*env)->GetStringUTFChars(env, jlut, NULL);
    rc = fl_bench(in, lut, params, scale, report, sizeof report);
    (*env)->ReleaseStringUTFChars(env, jin, in);
    if (lut) (*env)->ReleaseStringUTFChars(env, jlut, lut);
    if (rc != 0) { strncpy(g_err, report, sizeof g_err - 1); g_err[sizeof g_err - 1] = 0; return NULL; }
    return (*env)->NewStringUTF(env, report);
}

JNIEXPORT void JNICALL Java_com_latent_camera_FilmCore_setVariant(JNIEnv *env, jclass cls, jint v) {
    (void)env; (void)cls;
    fl_set_variant(v);
}

JNIEXPORT void JNICALL Java_com_latent_camera_FilmCore_prewarm(JNIEnv *env, jclass cls, jstring jlut, jintArray jparams) {
    int params[FL_PARAM_COUNT];
    const char *lut = NULL;
    (void)cls;
    if (read_params(env, jparams, params) != 0) return;
    if (jlut) lut = (*env)->GetStringUTFChars(env, jlut, NULL);
    fl_prewarm(lut, params);
    if (jlut) (*env)->ReleaseStringUTFChars(env, jlut, lut);
}

JNIEXPORT void JNICALL Java_com_latent_camera_FilmCore_setFastCube(JNIEnv *env, jclass cls, jboolean on) {
    (void)env; (void)cls;
    fl_set_fast_cube(on == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_latent_camera_FilmCore_setPairMemo(JNIEnv *env, jclass cls, jint bits) {
    (void)env; (void)cls;
    fl_set_pair_memo(bits);
}

JNIEXPORT void JNICALL Java_com_latent_camera_FilmCore_forgetLut(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    fl_forget_lut();
}

/* Any thread (the viewfinder's own, while the worker previews): its error goes through a local buffer first. */
JNIEXPORT jint JNICALL Java_com_latent_camera_FilmCore_cacheLut(JNIEnv *env, jclass cls, jstring jlut) {
    char err[sizeof g_err];
    const char *lut;
    int rc;
    (void)cls;
    if (!jlut) return 0;   /* no film: nothing to read */
    lut = (*env)->GetStringUTFChars(env, jlut, NULL);
    if (!lut) return -1;
    err[0] = 0;
    rc = fl_cache_lut(lut, err, sizeof err);
    (*env)->ReleaseStringUTFChars(env, jlut, lut);
    if (rc != 0) snprintf(g_err, sizeof g_err, "%s", err);
    return rc;
}

JNIEXPORT jint JNICALL Java_com_latent_camera_FilmCore_cubeCheck(JNIEnv *env, jclass cls, jstring jpath, jintArray jms) {
    int ms[4], rc;
    const char *path;
    (void)cls;
    g_err[0] = 0;
    if (!jpath || !jms || (*env)->GetArrayLength(env, jms) < 4) { strcpy(g_err, "bad arguments"); return -1; }
    path = (*env)->GetStringUTFChars(env, jpath, NULL);
    rc = fl_cube_check(path, ms, g_err, sizeof g_err);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    (*env)->SetIntArrayRegion(env, jms, 0, 4, (const jint *)ms);
    return rc;
}

JNIEXPORT jstring JNICALL Java_com_latent_camera_FilmCore_cores(JNIEnv *env, jclass cls) {
    static char report[1024];
    (void)cls;
    fl_cores(report, sizeof report);
    return (*env)->NewStringUTF(env, report);
}

JNIEXPORT jstring JNICALL Java_com_latent_camera_FilmCore_benchCores(JNIEnv *env, jclass cls, jstring jin, jstring jlut,
        jintArray jparams, jint scale) {
    static char report[1024];
    int params[FL_PARAM_COUNT], rc;
    const char *in, *lut = NULL;
    (void)cls;
    if (read_params(env, jparams, params) != 0) return NULL;
    in = (*env)->GetStringUTFChars(env, jin, NULL);
    if (jlut) lut = (*env)->GetStringUTFChars(env, jlut, NULL);
    rc = fl_bench_cores(in, lut, params, scale, report, sizeof report);
    (*env)->ReleaseStringUTFChars(env, jin, in);
    if (lut) (*env)->ReleaseStringUTFChars(env, jlut, lut);
    if (rc != 0) { strncpy(g_err, report, sizeof g_err - 1); g_err[sizeof g_err - 1] = 0; return NULL; }
    return (*env)->NewStringUTF(env, report);
}

JNIEXPORT jstring JNICALL Java_com_latent_camera_FilmCore_helperNote(JNIEnv *env, jclass cls) {
    char note[128];
    (void)cls;
    fl_last_threads(note, sizeof note);
    return (*env)->NewStringUTF(env, note);
}
