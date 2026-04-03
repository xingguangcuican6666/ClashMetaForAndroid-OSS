package com.github.kr328.clash.service.clash.meta

internal object MetaPaths {
    const val MODULE_DIR = "/data/adb/modules/mihomo"
    const val BIN_PATH = "$MODULE_DIR/bin/mihomo-android"
    const val RUN_DIR = "/data/adb/mihomo-cmfa"
    const val CONFIG_PATH = "$RUN_DIR/config.yaml"
    // One-time backup of the original module-managed core config (TUN/DNS/rules).
    // Created on the first run before the app ever overwrites config.yaml.
    // Proxies from the imported profile are merged on top of this base at runtime.
    const val BASE_CONFIG_PATH = "$RUN_DIR/config.base.yaml"
    const val OVERRIDE_PATH = "$RUN_DIR/override.yaml"
    const val SECRET_PATH = "$RUN_DIR/secret"
    const val PID_PATH = "$RUN_DIR/mihomo.pid"
    const val LOG_PATH = "$RUN_DIR/mihomo.log"
    const val EXTERNAL_CONTROLLER = "127.0.0.1:16756"
}
