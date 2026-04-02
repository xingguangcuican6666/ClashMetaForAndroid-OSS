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

    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        canReachControllerTCP() || canReachControllerRelay()
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
}
