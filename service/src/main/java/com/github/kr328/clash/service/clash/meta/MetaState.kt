package com.github.kr328.clash.service.clash.meta

import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.core.model.Provider
import com.github.kr328.clash.core.model.ProviderList
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.Traffic
import com.github.kr328.clash.core.model.TunnelState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.atomic.AtomicReference
import okhttp3.WebSocket

internal object MetaState {
    private val nowTraffic = AtomicReference<Traffic>(0L)
    private val totalTraffic = AtomicReference<Traffic>(0L)
    private val providers = AtomicReference<List<Provider>>(emptyList())
    private val tunnelState = AtomicReference(TunnelState(TunnelState.Mode.Rule))

    private var logChannel: Channel<LogMessage>? = null
    private var logSocket: WebSocket? = null

    /** Throws if the API is not reachable or not yet ready. Used for startup polling. */
    fun pingApi() {
        MetaApiClient.queryTunnelState()
    }

    fun refreshSnapshot() {
        runCatching { tunnelState.set(MetaApiClient.queryTunnelState()) }
        // queryTrafficNow and queryTrafficTotal return identical data; one HTTP call suffices.
        runCatching {
            val traffic = MetaApiClient.queryTrafficNow()
            nowTraffic.set(traffic)
            totalTraffic.set(traffic)
        }
        runCatching { providers.set(MetaApiClient.queryProviders()) }
    }

    /** Lightweight refresh that only fetches current traffic speed. */
    fun refreshTraffic() {
        runCatching {
            val traffic = MetaApiClient.queryTrafficNow()
            nowTraffic.set(traffic)
            totalTraffic.set(traffic)
        }
    }

    fun queryTunnelState(): TunnelState = tunnelState.get()
    fun queryTrafficNow(): Traffic = nowTraffic.get()
    fun queryTrafficTotal(): Traffic = totalTraffic.get()
    fun queryProviders(): ProviderList = ProviderList(providers.get())

    fun queryGroupNames(excludeNotSelectable: Boolean): List<String> {
        return MetaApiClient.queryGroupNames(excludeNotSelectable)
    }

    fun queryGroup(name: String, sort: ProxySort): ProxyGroup {
        return MetaApiClient.queryGroup(name, sort)
    }

    fun patchSelector(group: String, name: String): Boolean {
        return MetaApiClient.patchSelector(group, name)
    }

    fun healthCheck(group: String) {
        MetaApiClient.healthCheck(group)
    }

    fun updateProvider(type: Provider.Type, name: String) {
        MetaApiClient.updateProvider(type, name)
    }

    fun setMode(mode: TunnelState.Mode?) {
        MetaApiClient.setMode(mode)
    }

    fun setLogLevel(level: LogMessage.Level?) {
        MetaApiClient.setLogLevel(level)
    }

    fun openLogStream(): ReceiveChannel<LogMessage> {
        val channel = Channel<LogMessage>(128)
        closeLogStream()
        logChannel = channel
        logSocket = MetaApiClient.connectLogStream(onLog = { msg ->
            channel.trySend(msg)
        })
        return channel
    }

    fun closeLogStream() {
        logSocket?.close(1000, "close")
        logSocket = null
        logChannel?.close()
        logChannel = null
    }
}
