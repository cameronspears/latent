/*
 * A stand-in for the camera's libosal_uipc.so, for tools/settings-test.sh: answers the settings
 * service's size / attribute / read / write / sync requests from a small table, and logs every
 * write so the test can check exactly what reached the "camera".
 *
 * FAKE_CONTROL (a file, read again on every request) lets a test act as the camera:
 *   set ID V      the camera holds V (any value, even one Latent may not write)
 *   refuse ID     a write to ID is answered -3 (the service refuses it)
 *   hang ID       a write to ID never answers
 *   hangread ID   a read of ID never answers
 * FAKE_LOG (a file) gets a line for every write and sync that reached the "camera".
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dlfcn.h>

struct backup_msg { int function, result, arg_count, type, padding[2], args[10]; };

static struct { int id; signed char v; int attr; } store[] = {
    { 0x01070019, 1, 96 }, { 0x01070017, 2, 96 }, { 0x01070016, 2, 96 }, { 0x0107067f, 2, 96 }, { 0x0107067e, 2, 96 },
    { 0x01070175, 1, 96 }, { 0x01070178, 1, 96 }, { 0x01070187, 1, 96 }, { 0x0107018a, 0, 96 }, { 0x01070104, 1, 96 },
    { 0x01070775, 1, 96 }, { 0x01070018, 60, 96 }, { 0x01079999, 0, 97 /* read-only */ },
};
int fake_writes, fake_syncs;

/* the bridge's test build passes buffers through a slot (a Mac pointer doesn't fit in 32 bits) */
static void *buffer(int arg) {
    void **slot = (void **)dlsym(RTLD_DEFAULT, "settings_test_slot");
    return arg == 0x5107 && slot ? *slot : NULL;
}

static int find(int id) {
    unsigned i;
    for (i = 0; i < sizeof store / sizeof store[0]; i++) if (store[i].id == id) return (int)i;
    return -1;
}

static int refuse_id, hang_id, hangread_id;

static void control(void) {
    const char *path = getenv("FAKE_CONTROL");
    char w[16];
    unsigned id;
    int v;
    FILE *f;
    refuse_id = hang_id = hangread_id = 0;
    if (!path || !(f = fopen(path, "r"))) return;
    while (fscanf(f, "%15s %x", w, &id) == 2) {
        if (!strcmp(w, "set") && fscanf(f, "%d", &v) == 1) { int i = find((int)id); if (i >= 0) store[i].v = (signed char)v; }
        else if (!strcmp(w, "refuse")) refuse_id = (int)id;
        else if (!strcmp(w, "hang")) hang_id = (int)id;
        else if (!strcmp(w, "hangread")) hangread_id = (int)id;
    }
    fclose(f);
}

static void note(const char *what, int id, int v) {
    const char *path = getenv("FAKE_LOG");
    FILE *f;
    if (!path || !(f = fopen(path, "a"))) return;
    fprintf(f, "%s 0x%08x %d\n", what, id, v);
    fclose(f);
}

int osal_valloc_msg_wait(int type, void **addr, int len, int flag) { *addr = calloc(1, len); return *addr ? 0 : -1; }
int osal_free_msg(int type, void *addr) { free(addr); return 0; }
int osal_snd_sync_msg(int type, void *addr) {
    struct backup_msg *m = (struct backup_msg *)addr;
    int i;
    control();
    i = m->function == 15 ? 0 : find(m->function == 8 ? m->args[1] : m->args[0]);
    if (type != 0x3E014D) { m->result = -1; return 0; }
    if (i < 0) { m->result = -1; return 0; }
    switch (m->function) {
        case 0: m->result = 1; break;
        case 2: m->result = store[i].attr; break;
        case 3:
            if (store[i].id == hangread_id) for (;;) sleep(1000);
            *(signed char *)buffer(m->args[1]) = store[i].v; m->result = 1; break;
        case 8:
            if (m->args[0] != (m->args[1] >> 16)) { m->result = -4; break; }
            if (store[i].attr & 1) { m->result = -3; break; }
            if (store[i].id == hang_id) for (;;) sleep(1000);
            if (store[i].id == refuse_id) { m->result = -3; break; }
            store[i].v = *(signed char *)buffer(m->args[2]);
            fake_writes++;
            note("wrote", store[i].id, store[i].v);
            fprintf(stderr, "fake: wrote 0x%08x = %d\n", store[i].id, store[i].v);
            m->result = 1;
            break;
        case 15: fake_syncs++; note("sync", 0, 0); m->result = 0; break;
        default: m->result = -1;
    }
    return 0;
}
