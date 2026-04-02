package com.github.kr328.clash.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object ModuleConnection {
    private const val MODULE_BIN_PATH = "/data/adb/modules/mihomo/bin/mihomo-android"
    private const val RUNTIME_DIR_PATH = "/data/adb/mihomo-cmfa"
    private const val CONTROLLER_URL = "http://127.0.0.1:16756/version"

    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        hasModuleRuntime() && canReachController()
    }

    private fun hasModuleRuntime(): Boolean {
        return runCatching {
            val process = ProcessBuilder(
                "su", "-c",
                "[ -x '$MODULE_BIN_PATH' ] && [ -d '$RUNTIME_DIR_PATH' ]"
            ).start()

            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        }.getOrDefault(false)
    }

    private fun canReachController(): Boolean {
        return runCatching {
            val conn = (URL(CONTROLLER_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 1200
                readTimeout = 1200
                requestMethod = "GET"
                instanceFollowRedirects = false
            }

            conn.use {
                val code = conn.responseCode
                code in 200..499
            }
        }.getOrDefault(false)
    }
}
