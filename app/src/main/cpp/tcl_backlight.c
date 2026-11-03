#include <android/dlext.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>

/* TCL's command service executes inside sitatvservice without starting Settings UI.
 * Only two fixed command names are reachable here; no arbitrary command/shell API. */
typedef void (*OutputFn)(const char *, void *);
typedef int (*ExecuteFn)(const char *, OutputFn, void *);
typedef struct android_namespace_t *(*NamespaceFn)(const char *);
typedef void *(*LoadFn)(const char *, int, const android_dlextinfo *);

typedef struct {
    char line[256];
    size_t used;
    int value;
    int found;
    int invalid;
    int overflow;
} BacklightReply;

/* TclBacklight runs every native call on the same single worker. */
static ExecuteFn execute;
static int initialized;
static BacklightReply reply;

static void discard_output(const char *text, void *user) {
    (void)text;
    (void)user;
}

static void finish_line(BacklightReply *reply) {
    if (!reply->overflow) {
        reply->line[reply->used] = '\0';
        const char *value_line = strstr(reply->line, "tos_picture_get_backlight e_mode=");
        if (value_line) {
            int mode = -1, value = -1;
            char trailing;
            const int fields = sscanf(value_line,
                "tos_picture_get_backlight e_mode=%d value=%d %c", &mode, &value, &trailing);
            if (fields == 2 && mode == 0 && value >= 0 && value <= 100) {
                if (reply->found && reply->value != value) reply->invalid = 1;
                reply->found = 1;
                reply->value = value;
            } else if (mode == 0 || fields < 2) {
                reply->invalid = 1;
            }
        }
    }
    reply->used = 0;
    reply->overflow = 0;
}

static void capture_reply(const char *text, void *user) {
    BacklightReply *reply = user;
    if (!text) return;
    for (; *text; ++text) {
        if (*text == '\n' || *text == '\r') {
            finish_line(reply);
        } else if (reply->used + 1 < sizeof(reply->line)) {
            reply->line[reply->used++] = *text;
        } else {
            reply->overflow = 1;
        }
    }
}

static int initialize(void) {
    if (initialized) return execute != NULL;
    initialized = 1;
    NamespaceFn get_namespace = (NamespaceFn)dlsym(RTLD_DEFAULT, "android_get_exported_namespace");
    LoadFn load = (LoadFn)dlsym(RTLD_DEFAULT, "android_dlopen_ext");
    if (!get_namespace || !load) return 0;
    android_dlextinfo info = {0};
    info.flags = ANDROID_DLEXT_USE_NAMESPACE;
    info.library_namespace = get_namespace("sphal");
    if (!info.library_namespace) return 0;
    void *library = load("/vendor/lib/libsitatv.so", RTLD_NOW | RTLD_LOCAL, &info);
    if (!library) return 0;
    void (*init)(void) = (void (*)(void))dlsym(library, "proxy_init");
    ExecuteFn command = (ExecuteFn)dlsym(library, "tos_tcli_execute");
    if (!init || !command) return 0;
    /* Starts the existing sbinder callback pool, not a TCP listener. Never call
     * proxy_exit: the library and pool are shared for this process's lifetime. */
    init();
    execute = command;
    return 1;
}

static int read_level(void) {
    reply = (BacklightReply){.value = -1};
    const int status = execute("tos.picture.get_backlight 0", capture_reply, &reply);
    if (reply.used || reply.overflow) finish_line(&reply);
    return status == 0 && reply.found && !reply.invalid ? reply.value : -1;
}

JNIEXPORT jint JNICALL
Java_app_orchard_tv_system_TclBacklight_nativeBacklight(JNIEnv *env, jobject self, jint level) {
    (void)env;
    (void)self;
    if (level != -1 && (level < 1 || level > 100)) return -1;
    if (!initialize()) return -1;
    if (level != -1) {
        char command[64];
        /* ACT=2 saves and executes; ACT=0 leaves the getter returning the old saved value. */
        snprintf(command, sizeof(command), "tos.picture.set_backlight 0 %d 2", level);
        if (execute(command, discard_output, NULL) != 0) return -1;
    }
    const int actual = read_level();
    return level == -1 || actual == level ? actual : -1;
}
