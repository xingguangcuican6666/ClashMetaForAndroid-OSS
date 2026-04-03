package com.github.kr328.clash.util

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object ModuleConnection {
    private const val CONTROLLER_HOST    = "127.0.0.1"
    private const val CONTROLLER_PORT    = 16756
    private const val CONNECT_TIMEOUT_MS = 3000
    // Abstract Unix socket relay created by the Zygisk companion (matches RELAY_ABSTRACT in main.cpp)
    private const val RELAY_ABSTRACT_NAME = "mihomo_cmfa_relay"
    // Module directory that exists whenever the Magisk/KernelSU module is installed
    private const val MODULE_DIR = "/data/adb/modules/mihomo"

    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        // 1. Fastest: direct TCP connection to mihomo's REST API port.
        // 2. Fallback: abstract Unix socket relay kept by the Zygisk companion.
        // 3. Last resort: check whether the module binary directory is present via a
        //    root shell.  This returns true even when mihomo itself has been stopped
        //    (e.g., killed by the app service's onDestroy), preventing a false
        //    "Module not loaded" screen when the module is still installed and the app
        //    should simply start a fresh mihomo process.
        canReachControllerTCP() || canReachControllerRelay() || isModuleInstalled()
    }

    /**
     * Raw TCP socket check.
     * Unlike HttpURLConnection/OkHttp, a plain Socket does NOT honour system HTTP-proxy
     * settings, so it succeeds even when a system proxy is configured.
     */
    private fun canReachControllerTCP(): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(CONTROLLER_HOST, CONTROLLER_PORT), CONNECT_TIMEOUT_MS)
                true
            }
        }.getOrDefault(false)
    }

    /**
     * Unix socket fallback via the Zygisk companion relay.
     * The Zygisk module creates an abstract Unix socket (@mihomo_cmfa_relay) that
     * forwards connections to the mihomo external-controller.  Connecting here
     * bypasses Android's networking stack entirely, so it works even if direct TCP
     * from the app process is blocked by SELinux or other restrictions.
     */
    private fun canReachControllerRelay(): Boolean {
        return runCatching {
            LocalSocket().use { socket ->
                socket.connect(
                    LocalSocketAddress(RELAY_ABSTRACT_NAME, LocalSocketAddress.Namespace.ABSTRACT)
                )
                true
            }
        }.getOrDefault(false)
    }

    /**
     * Check whether the Magisk/KernelSU module directory is present using a root
     * shell command.  This succeeds even after mihomo has been stopped, so that the
     * app can distinguish "module not installed" (show disconnect screen) from
     * "module installed but mihomo not running" (auto-start mihomo).
     *
     * A 2-second timeout prevents startup hangs if su is not pre-authorised.
     */
    private fun isModuleInstalled(): Boolean {
        return runCatching {
            val proc = ProcessBuilder("su", "-c",
                "[ -d $MODULE_DIR ] && echo 1 || echo 0")
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) { proc.destroyForcibly(); return@runCatching false }
            proc.inputStream.bufferedReader().readLine()?.trim() == "1"
        }.getOrDefault(false)
    }
}
