// Zygisk module for ClashMetaForAndroid-OSS
//
// Role: companion process (runs as root) listens on a Unix domain socket that
// relays HTTP traffic to mihomo's external-controller at 127.0.0.1:16756,
// completely bypassing Android's Java network stack.
//
// Build with NDK (API 21+):
//   cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 \
//         -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
//         -DCMAKE_BUILD_TYPE=Release -B build_arm64 && cmake --build build_arm64
//   Repeat for armeabi-v7a / x86 / x86_64.
//   Place the resulting .so files in the module zip as:
//     zygisk/arm64-v8a.so, zygisk/armeabi-v7a.so, zygisk/x86.so, zygisk/x86_64.so

#include "zygisk.hpp"

#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>

#define LOG_TAG  "MihomoZygisk"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// The abstract Unix socket name the relay listens on.
// Abstract namespace sockets need no filesystem path, so there are no
// directory-traversal or chmod issues; any process on the device can connect.
static constexpr const char *RELAY_ABSTRACT = "mihomo_cmfa_relay";

// mihomo external-controller TCP address
static constexpr const char *CTRL_HOST = "127.0.0.1";
static constexpr uint16_t    CTRL_PORT = 16756;

// Target application package
static constexpr const char *TARGET_PKG = "com.github.kr328.clash";

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

static int connectTCP() {
    int s = socket(AF_INET, SOCK_STREAM, 0);
    if (s < 0) return -1;

    struct sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port   = htons(CTRL_PORT);
    inet_pton(AF_INET, CTRL_HOST, &addr.sin_addr);

    struct timeval tv{3, 0};
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    if (connect(s, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) != 0) {
        close(s);
        return -1;
    }
    return s;
}

// Bidirectional splice between two fds (runs in its own thread).
struct SplicePair { int a, b; };

static void *spliceThread(void *arg) {
    auto *p = static_cast<SplicePair *>(arg);
    int a = p->a, b = p->b;
    delete p;

    char buf[4096];
    while (true) {
        ssize_t n = read(a, buf, sizeof(buf));
        if (n <= 0) break;
        ssize_t written = 0;
        while (written < n) {
            ssize_t w = write(b, buf + written, n - written);
            if (w <= 0) goto done;
            written += w;
        }
    }
done:
    shutdown(a, SHUT_RDWR);
    shutdown(b, SHUT_RDWR);
    close(a);
    close(b);
    return nullptr;
}

static void bridgeConnection(int client) {
    int backend = connectTCP();
    if (backend < 0) {
        LOGE("relay: cannot connect to %s:%d – %s", CTRL_HOST, CTRL_PORT, strerror(errno));
        close(client);
        return;
    }

    // Two threads: client→backend and backend→client.
    // Each thread owns one fd pair and closes both when done.
    pthread_t t1, t2;
    auto *p1 = new SplicePair{client,  backend};
    auto *p2 = new SplicePair{backend, client};
    pthread_create(&t1, nullptr, spliceThread, p1);
    pthread_create(&t2, nullptr, spliceThread, p2);
    pthread_detach(t1);
    pthread_detach(t2);
}

struct RelayServerArgs { int serverFd; };

static void *relayServerThread(void *arg) {
    auto *a = static_cast<RelayServerArgs *>(arg);
    int serverFd = a->serverFd;
    delete a;

    LOGI("relay: listening on @%s", RELAY_ABSTRACT);
    while (true) {
        int client = accept(serverFd, nullptr, nullptr);
        if (client < 0) {
            if (errno == EINTR) continue;
            LOGE("relay: accept failed: %s", strerror(errno));
            break;
        }
        bridgeConnection(client);
    }
    close(serverFd);
    return nullptr;
}

// ---------------------------------------------------------------------------
// Start relay (called once in the companion process)
// ---------------------------------------------------------------------------

static void startRelay() {
    int serverFd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (serverFd < 0) {
        LOGE("relay: socket() failed: %s", strerror(errno));
        return;
    }

    // Abstract Unix socket: sun_path[0] == '\0', rest is the name.
    // No filesystem path is created, no chmod needed, and any process
    // on the device can connect regardless of directory permissions.
    struct sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    size_t nameLen = strlen(RELAY_ABSTRACT);
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, RELAY_ABSTRACT, nameLen);
    socklen_t addrLen = static_cast<socklen_t>(offsetof(struct sockaddr_un, sun_path) + 1 + nameLen);

    if (bind(serverFd, reinterpret_cast<sockaddr *>(&addr), addrLen) != 0) {
        LOGE("relay: bind(@%s) failed: %s", RELAY_ABSTRACT, strerror(errno));
        close(serverFd);
        return;
    }

    if (listen(serverFd, 16) != 0) {
        LOGE("relay: listen() failed: %s", strerror(errno));
        close(serverFd);
        return;
    }

    auto *args = new RelayServerArgs{serverFd};
    pthread_t t;
    pthread_create(&t, nullptr, relayServerThread, args);
    pthread_detach(t);
}

// ---------------------------------------------------------------------------
// Companion entry point
// ---------------------------------------------------------------------------

// Called by Zygisk in the root companion process.
// The client fd is only used to signal readiness; actual relay runs in its own thread.
static void companionHandler(int client) {
    static bool started = false;
    if (!started) {
        startRelay();
        started = true;
    }
    // Send ACK
    uint8_t ack = 1;
    write(client, &ack, 1);
    close(client);
}

// ---------------------------------------------------------------------------
// Zygisk Module class
// ---------------------------------------------------------------------------

class MihomoModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_  = api;
        env_  = env;
    }

    void preAppSpecifier(zygisk::AppSpecifier *spec) override {
        if (strcmp(spec->process, TARGET_PKG) != 0) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
        }
    }

    void postAppSpecifier(zygisk::AppSpecifier *spec) override {
        if (strcmp(spec->process, TARGET_PKG) != 0) return;

        // Wake companion so it starts (or confirms) the relay
        int fd = api_->connectCompanion();
        if (fd >= 0) {
            uint8_t ack = 0;
            read(fd, &ack, 1);
            close(fd);
            if (ack == 1) {
                LOGI("module: relay confirmed ready at %s", RELAY_SOCK_PATH);
            }
        } else {
            LOGE("module: connectCompanion() failed");
        }
    }

private:
    zygisk::Api *api_{nullptr};
    JNIEnv      *env_{nullptr};
};

REGISTER_ZYGISK_MODULE(MihomoModule)
REGISTER_ZYGISK_COMPANION(companionHandler)
