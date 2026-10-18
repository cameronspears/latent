/*
 * The camera's stored settings (white balance, Creative Style, ...): the values the camera's own
 * menus change. Requests go to the camera's settings service as messages through libosal_uipc, the
 * same way OpenMemories-Tweak and Recipe Lab do it.
 *
 * Reading any setting is allowed: requests 0 (size), 2 (attributes) and 3 (read).
 * Writing (request 8) is fenced in: only the eleven settings in WRITABLE below, only one byte, only
 * a value in that setting's menu range, and never a setting the camera marks read-only. Request 15
 * asks the service to store what was written. Nothing else can be sent.
 */
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define OSAL_MSG_BACKUP 0x3E014D
#define REQ_SIZE 0
#define REQ_ATTR 2
#define REQ_READ 3
#define REQ_WRITE 8
#define REQ_SYNC 15
#define ATTR_READ_ONLY 1
#define MAX_SETTING 256          /* settings are single bytes; anything bigger is refused */

struct backup_msg {              /* the service's message layout (OpenMemories-Platform drivers/backup.c) */
    int function;
    int result;
    int arg_count;
    int type;
    int padding[2];
    int args[10];
};

typedef int (*valloc_fn)(int type, void **addr, int len, int flag);
typedef int (*msg_fn)(int type, void *addr);

static valloc_fn p_valloc;
static msg_fn p_sync, p_free;
static char g_err[200];

static int load(void) {
    void *h;
    if (p_valloc && p_sync && p_free) return 0;
    h = dlopen("libosal_uipc.so", RTLD_NOW);
    if (!h) { snprintf(g_err, sizeof g_err, "libosal_uipc.so: %s", dlerror()); return -1; }
    p_valloc = (valloc_fn)dlsym(h, "osal_valloc_msg_wait");
    p_sync = (msg_fn)dlsym(h, "osal_snd_sync_msg");
    p_free = (msg_fn)dlsym(h, "osal_free_msg");
    if (!p_valloc || !p_sync || !p_free) {
        snprintf(g_err, sizeof g_err, "libosal_uipc.so lacks %s", !p_valloc ? "osal_valloc_msg_wait" : !p_sync ? "osal_snd_sync_msg" : "osal_free_msg");
        p_valloc = NULL; p_sync = NULL; p_free = NULL;
        return -1;
    }
    return 0;
}

/* The service takes buffer addresses as 32-bit ints (the camera is 32-bit). A Mac test passes a slot instead. */
#ifdef SETTINGS_HOST_TEST
void *settings_test_slot;
static int as_arg(void *p) { settings_test_slot = p; return 0x5107; }
#else
static int as_arg(void *p) { return (int)(intptr_t)p; }
#endif

/* The only settings Latent may write, each with the range its menu allows. */
static const struct { int id, lo, hi; } WRITABLE[] = {
    { 0x01070019, 0, 31 },   /* white balance mode (Auto = 1) */
    { 0x01070017, -7, 7 },   /* WB fine-tune amber-blue */
    { 0x01070016, -7, 7 },   /* WB fine-tune green-magenta */
    { 0x0107067f, -7, 7 },   /* ... the amber-blue copy applied in Auto */
    { 0x0107067e, -7, 7 },   /* ... the green-magenta copy applied in Auto */
    { 0x01070175, 1, 14 },   /* Creative Style */
    { 0x01070178, -3, 3 },   /* contrast */
    { 0x01070187, -3, 3 },   /* saturation */
    { 0x0107018a, -3, 3 },   /* sharpness */
    { 0x01070104, 0, 6 },    /* DRO: off 0, auto 1, Lv1..5 = 2..6 */
    { 0x01070775, 1, 6 },    /* DRO level: 1 for off / auto, Lv n = n + 1 */
};

static int writable(int id, int v) {
    unsigned i;
    for (i = 0; i < sizeof WRITABLE / sizeof WRITABLE[0]; i++)
        if (WRITABLE[i].id == id) return v >= WRITABLE[i].lo && v <= WRITABLE[i].hi;
    return 0;
}

/* One request to the settings service; only size, attribute, read, a checked write and sync. */
static int ask(int function, int nargs, int a0, int a1, int a2) {
    struct backup_msg *m = NULL;
    int r;
    if (function != REQ_SIZE && function != REQ_ATTR && function != REQ_READ && function != REQ_WRITE && function != REQ_SYNC) {
        snprintf(g_err, sizeof g_err, "request %d refused: read only", function);
        return -1000;
    }
    if (load() != 0) return -1001;
    if (p_valloc(OSAL_MSG_BACKUP, (void **)&m, sizeof *m, 1) != 0 || !m) {
        snprintf(g_err, sizeof g_err, "no message buffer from the camera");
        return -1002;
    }
    memset(m, 0, sizeof *m);
    m->function = function;
    m->arg_count = nargs;
    m->type = OSAL_MSG_BACKUP;
    m->args[0] = a0;
    m->args[1] = a1;
    m->args[2] = a2;
    if (p_sync(OSAL_MSG_BACKUP, m) != 0) {
        snprintf(g_err, sizeof g_err, "the settings service did not answer");
        return -1003;
    }
    r = m->result;
    p_free(OSAL_MSG_BACKUP, m);
    return r;
}

JNIEXPORT jboolean JNICALL Java_com_latent_camera_CameraSettings_available(JNIEnv *env, jclass cls) {
    return load() == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL Java_com_latent_camera_CameraSettings_read(JNIEnv *env, jclass cls, jint id) {
    unsigned char *buf;
    jbyteArray out;
    int size = ask(REQ_SIZE, 1, id, 0, 0), r;
    if (size <= 0 || size > MAX_SETTING) {
        if (size > 0) snprintf(g_err, sizeof g_err, "setting 0x%08x is %d bytes; refused", (unsigned)id, size);
        else if (size > -1000) snprintf(g_err, sizeof g_err, "setting 0x%08x: size answered %d", (unsigned)id, size);
        return NULL;
    }
    buf = (unsigned char *)calloc(1, MAX_SETTING);   /* room for the largest answer we accept */
    if (!buf) { snprintf(g_err, sizeof g_err, "out of memory"); return NULL; }
    r = ask(REQ_READ, 2, id, as_arg(buf), 0);
    if (r != size) {
        if (r > -1000) snprintf(g_err, sizeof g_err, "setting 0x%08x: read answered %d (size %d)", (unsigned)id, r, size);
        free(buf);
        return NULL;
    }
    out = (*env)->NewByteArray(env, size);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, size, (const jbyte *)buf);
    free(buf);
    return out;
}

/*
 * Writes one setting's single byte. Refused (negative) unless the setting is in WRITABLE, the value is
 * in its range, the setting is one byte and not read-only. Returns 0 when the service took it.
 */
JNIEXPORT jint JNICALL Java_com_latent_camera_CameraSettings_write(JNIEnv *env, jclass cls, jint id, jint value) {
    unsigned char *buf;
    int size, attr, r;
    if (!writable(id, value)) {
        snprintf(g_err, sizeof g_err, "setting 0x%08x = %d refused: not one Latent may write", (unsigned)id, value);
        return -2000;
    }
    size = ask(REQ_SIZE, 1, id, 0, 0);
    if (size != 1) {
        if (size > -1000) snprintf(g_err, sizeof g_err, "setting 0x%08x is %d bytes; refused", (unsigned)id, size);
        return -2001;
    }
    attr = ask(REQ_ATTR, 1, id, 0, 0);
    if (attr < 0 || (attr & ATTR_READ_ONLY)) {
        if (attr > -1000) snprintf(g_err, sizeof g_err, "setting 0x%08x is read-only (attr %d)", (unsigned)id, attr);
        return -2002;
    }
    buf = (unsigned char *)calloc(1, MAX_SETTING);
    if (!buf) { snprintf(g_err, sizeof g_err, "out of memory"); return -2003; }
    buf[0] = (unsigned char)(signed char)value;
    r = ask(REQ_WRITE, 3, id >> 16, id, as_arg(buf));
    free(buf);
    if (r != 1) {
        if (r > -1000) snprintf(g_err, sizeof g_err, "setting 0x%08x: write answered %d", (unsigned)id, r);
        return -2004;
    }
    return 0;
}

/* Asks the service to store everything written (the camera keeps it after power-off). */
JNIEXPORT void JNICALL Java_com_latent_camera_CameraSettings_sync(JNIEnv *env, jclass cls) {
    ask(REQ_SYNC, 0, 0, 0, 0);
}

JNIEXPORT jstring JNICALL Java_com_latent_camera_CameraSettings_lastError(JNIEnv *env, jclass cls) {
    return (*env)->NewStringUTF(env, g_err);
}
