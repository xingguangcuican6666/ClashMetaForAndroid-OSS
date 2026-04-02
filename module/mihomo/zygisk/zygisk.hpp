// Zygisk API — version 4  (matches Magisk >= 24 / Zygisk-Next)
//
// This is a vendored copy of the official header.
// Canonical source: https://github.com/topjohnwu/Magisk/blob/master/native/src/include/zygisk.hpp
// Licensed under the Apache 2.0 License, (c) topjohnwu.
//
// ONLY the declarations needed by this module are included below.
// Replace this file with the full upstream header if you need more features.

#pragma once

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <functional>
#include <type_traits>

#define ZYGISK_API_VERSION 4

namespace zygisk {

struct AppSpecifier {
    const char *process;   // process name / package name
    const char *name;      // same as process in most cases
    uid_t       uid;
    uint32_t    flags;
};

enum class Option : int {
    // Unload this library after postAppSpecifier() / postServerSpecifier()
    DLCLOSE_MODULE_LIBRARY = 0,
    // Delay unmapping self in certain scenarios
    DELAY_UNMAP_SELF       = 1,
};

struct Api;

struct ModuleBase {
    virtual void onLoad(Api *api, JNIEnv *env)          {}
    virtual void preAppSpecifier(AppSpecifier *spec)     {}
    virtual void postAppSpecifier(AppSpecifier *spec)    {}
    virtual void preServerSpecifier(AppSpecifier *spec)  {}
    virtual void postServerSpecifier(AppSpecifier *spec) {}
    virtual ~ModuleBase() = default;
};

// Internal function-table type (must match the Zygisk runtime ABI)
struct ApiTable {
    ModuleBase *module;
    uint32_t    apiVersion;

    // v1
    void (*setOption)(ApiTable *, Option);
    int  (*connectCompanion)(ApiTable *);
    int  (*getModuleDir)(ApiTable *);

    // v2
    void (*exemptFd)(ApiTable *, int);

    // v3 / v4 — may be null on older Magisk builds
    bool (*isModuleLoaded)(ApiTable *, const char *);
    bool (*isGlobalNamespaceIsolated)(ApiTable *);
};

struct Api {
    ApiTable *tbl_;

    explicit Api(ApiTable *t) : tbl_(t) {}

    void setOption(Option opt)             { tbl_->setOption(tbl_, opt); }
    int  connectCompanion()                { return tbl_->connectCompanion(tbl_); }
    int  getModuleDir()                    { return tbl_->getModuleDir(tbl_); }
    void exemptFd(int fd)                  { if (tbl_->exemptFd) tbl_->exemptFd(tbl_, fd); }
};

} // namespace zygisk

// ---------------------------------------------------------------------------
// Registration macros  (must be in the global namespace)
// ---------------------------------------------------------------------------

// Internal linkage helpers kept in an anonymous namespace to avoid ODR clashes
namespace {

template<typename T>
zygisk::ModuleBase *_make_module_() { return new T(); }

} // anonymous namespace

// Called by the Zygisk runtime to create the module object.
// The runtime looks for this symbol by name.
#define REGISTER_ZYGISK_MODULE(clazz)                                                \
    _Static_assert(std::is_base_of<zygisk::ModuleBase, clazz>::value,               \
                   #clazz " must extend zygisk::ModuleBase");                        \
    extern "C" __attribute__((visibility("default")))                                \
    void zygisk_module_entry(zygisk::ApiTable *table, JNIEnv *env) {                 \
        zygisk::Api api(table);                                                      \
        auto *mod = new clazz();                                                     \
        table->module = mod;                                                         \
        mod->onLoad(&api, env);                                                      \
    }

// Called by the Zygisk runtime in the companion (root) process.
#define REGISTER_ZYGISK_COMPANION(func)                                              \
    extern "C" __attribute__((visibility("default")))                                \
    void zygisk_companion_entry(int client) { func(client); }
