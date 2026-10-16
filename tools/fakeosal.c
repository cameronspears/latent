/*
 * A stand-in for the camera's libosal_uipc.so, for tools/settings-test.sh: answers the settings
 * service's size / attribute / read / write / sync requests from a small table, and logs every
 * write so the test can check exactly what reached the "camera".
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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

int osal_valloc_msg_wait(int type, void **addr, int len, int flag) { *addr = calloc(1, len); return *addr ? 0 : -1; }
int osal_free_msg(int type, void *addr) { free(addr); return 0; }
int osal_snd_sync_msg(int type, void *addr) {
    struct backup_msg *m = (struct backup_msg *)addr;
    int i = m->function == 15 ? 0 : find(m->function == 8 ? m->args[1] : m->args[0]);
    if (type != 0x3E014D) { m->result = -1; return 0; }
    if (i < 0) { m->result = -1; return 0; }
    switch (m->function) {
        case 0: m->result = 1; break;
        case 2: m->result = store[i].attr; break;
        case 3: *(signed char *)buffer(m->args[1]) = store[i].v; m->result = 1; break;
        case 8:
            if (m->args[0] != (m->args[1] >> 16)) { m->result = -4; break; }
            if (store[i].attr & 1) { m->result = -3; break; }
            store[i].v = *(signed char *)buffer(m->args[2]);
            fake_writes++;
            fprintf(stderr, "fake: wrote 0x%08x = %d\n", store[i].id, store[i].v);
            m->result = 1;
            break;
        case 15: fake_syncs++; m->result = 0; break;
        default: m->result = -1;
    }
    return 0;
}
