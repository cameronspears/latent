/* JNI glue between com.latent.camera.FilmCore and filmcore.c. Calls are serialised by the Java
 * side (one worker thread), so the shared error buffer is safe. */
#include <jni.h>
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
