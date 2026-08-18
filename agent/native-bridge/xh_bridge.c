/*
 * ModSpec native hook bridge v2: ShadowHook (bytedance) + per-hook AArch64
 * trampolines. Observe mode: original runs first, Java may override result.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <pthread.h>
#include "shadowhook.h"

#define MAX_HOOKS 256

typedef struct hook_ctx {
    uint64_t hook_id;
    uint64_t args[8];
    jmethodID on_hook;
    void *trampoline;
    void *original;
    void *handle;
    char symbol[128];
} hook_ctx;

static JavaVM *g_vm = NULL;
static jclass g_clazz = NULL;
static jmethodID g_on_hook = NULL;
static hook_ctx *g_ctxs[MAX_HOOKS];
static int g_ctx_count = 0;
static pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;

static uint64_t dispatch(void *ctx_raw) {
    hook_ctx *ctx = (hook_ctx *) ctx_raw;
    uint64_t result;
    asm volatile(
        "stp x29, x30, [sp, #-16]!\n"
        "mov x29, sp\n"
        "ldp x0, x1, [%1, #16]\n"
        "ldp x2, x3, [%1, #32]\n"
        "ldp x4, x5, [%1, #48]\n"
        "ldp x6, x7, [%1, #64]\n"
        "blr %2\n"
        "mov %0, x0\n"
        "ldp x29, x30, [sp], #16\n"
        : "=r"(result)
        : "r"(ctx), "r"(ctx->original)
        : "x0", "x1", "x2", "x3", "x4", "x5", "x6", "x7", "cc", "memory");
    JNIEnv *env = NULL;
    int attached = 0;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK)
            return result;
        attached = 1;
    }
    jlongArray arr = (*env)->NewLongArray(env, 8);
    (*env)->SetLongArrayRegion(env, arr, 0, 8, (jlong *) ctx->args);
    jlong overridden = (*env)->CallStaticLongMethod(env, g_clazz, ctx->on_hook,
                                                    (jlong) ctx->hook_id, arr,
                                                    (jlong) result);
    if (attached)
        (*g_vm)->DetachCurrentThread(g_vm);
    return (uint64_t) overridden;
}

static int build_trampoline(hook_ctx *ctx) {
    uint8_t code[] = {
        0x10, 0x00, 0x00, 0x90, /* adr x16, #0  (patched)                 */
        0x10, 0x02, 0x40, 0xF9, /* ldr x16, [x16]                          */
        0x90, 0x00, 0x00, 0xF9, /* str x0,  [x16, #16]                     */
        0xb1, 0x00, 0x00, 0xF9, /* str x1,  [x16, #24]                     */
        0xd2, 0x00, 0x00, 0xF9, /* str x2,  [x16, #32]                     */
        0xf3, 0x00, 0x00, 0xF9, /* str x3,  [x16, #40]                     */
        0x14, 0x01, 0x00, 0xF9, /* str x4,  [x16, #48]                     */
        0x35, 0x01, 0x00, 0xF9, /* str x5,  [x16, #56]                     */
        0x56, 0x01, 0x00, 0xF9, /* str x6,  [x16, #64]                     */
        0x77, 0x01, 0x00, 0xF9, /* str x7,  [x16, #72]                     */
        0xfd, 0x7b, 0xbf, 0xa9, /* stp x29, x30, [sp, #-16]!               */
        0xfd, 0x03, 0x00, 0x91, /* mov x29, sp                             */
        0xe0, 0x03, 0x10, 0xaa, /* mov x0, x16                             */
        0x00, 0x00, 0x00, 0x94, /* bl dispatch (patched)                   */
        0xfd, 0x7b, 0xc1, 0xa8, /* ldp x29, x30, [sp], #16                 */
        0xc0, 0x03, 0x5f, 0xd6, /* ret                                     */
    };
    size_t code_len = sizeof(code);
    uint8_t *mem = mmap(NULL, code_len + 16, PROT_READ | PROT_WRITE | PROT_EXEC,
                        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mem == MAP_FAILED) return -1;
    memcpy(mem, code, code_len);
    uint8_t *data = mem + code_len;
    memcpy(data, &ctx, sizeof(void *));
    uint32_t adr = 0x90000010u;
    int64_t offset = (int64_t)(data - mem);
    adr |= ((offset >> 2) & 0x3ffff) << 5;
    memcpy(mem, &adr, 4);
    uint32_t bl = 0x94000000u;
    int64_t bl_offset = (int64_t)((uint8_t *) dispatch - (mem + 72));
    bl |= (bl_offset >> 2) & 0x3ffffff;
    memcpy(mem + 72, &bl, 4);
    ctx->trampoline = mem;
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_modspec_agent_NativeHookBridge_register(JNIEnv *env, jclass clazz,
                                                 jstring lib_regex, jstring symbol,
                                                 jlong hook_id) {
    const char *lib = (*env)->GetStringUTFChars(env, lib_regex, NULL);
    const char *sym = (*env)->GetStringUTFChars(env, symbol, NULL);

    pthread_mutex_lock(&g_mutex);
    if (g_ctx_count >= MAX_HOOKS) {
        pthread_mutex_unlock(&g_mutex);
        (*env)->ReleaseStringUTFChars(env, lib_regex, lib);
        (*env)->ReleaseStringUTFChars(env, symbol, sym);
        return JNI_FALSE;
    }
    hook_ctx *ctx = calloc(1, sizeof(hook_ctx));
    ctx->hook_id = (uint64_t) hook_id;
    ctx->on_hook = g_on_hook;
    strncpy(ctx->symbol, sym, sizeof(ctx->symbol) - 1);

    int ok = build_trampoline(ctx) == 0;
    if (ok) {
        ctx->handle = shadowhook_hook_sym_name(lib, sym, ctx->trampoline, &ctx->original);
        ok = ctx->handle != NULL;
    }
    if (!ok) {
        if (ctx->trampoline != NULL) munmap(ctx->trampoline, 128);
        free(ctx);
        pthread_mutex_unlock(&g_mutex);
        (*env)->ReleaseStringUTFChars(env, lib_regex, lib);
        (*env)->ReleaseStringUTFChars(env, symbol, sym);
        return JNI_FALSE;
    }
    g_ctxs[g_ctx_count++] = ctx;
    pthread_mutex_unlock(&g_mutex);

    (*env)->ReleaseStringUTFChars(env, lib_regex, lib);
    (*env)->ReleaseStringUTFChars(env, symbol, sym);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_modspec_agent_NativeHookBridge_refresh(JNIEnv *env, jclass clazz, jboolean rebuild) {
    /* ShadowHook hooks apply immediately; kept for API parity. */
}

JNIEXPORT void JNICALL
Java_com_modspec_agent_NativeHookBridge_clear(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_mutex);
    for (int i = 0; i < g_ctx_count; i++) {
        shadowhook_unhook(g_ctxs[i]->handle);
        munmap(g_ctxs[i]->trampoline, 128);
        free(g_ctxs[i]);
    }
    g_ctx_count = 0;
    pthread_mutex_unlock(&g_mutex);
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;
    jclass clazz = (*env)->FindClass(env, "com/modspec/agent/NativeHookBridge");
    if (clazz == NULL) return JNI_ERR;
    g_clazz = (jclass) (*env)->NewGlobalRef(env, clazz);
    g_on_hook = (*env)->GetStaticMethodID(env, g_clazz, "onHook", "(J[JJ)J");
    if (g_on_hook == NULL) return JNI_ERR;
    if (shadowhook_init(0, 0) != 0)
        return JNI_ERR;
    return JNI_VERSION_1_6;
}
