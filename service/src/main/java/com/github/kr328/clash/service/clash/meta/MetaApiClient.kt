package com.github.kr328.clash.service.clash.meta

import com.github.kr328.clash.core.model.LogMessage
import com.github.kr328.clash.core.model.Provider
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.core.model.Traffic
import com.github.kr328.clash.core.model.TunnelState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.URLEncoder
import java.util.Date

internal object MetaApiClient {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val plain = "application/json; charset=utf-8".toMediaType()

    private fun api(path: String): String = "http://${MetaPaths.EXTERNAL_CONTROLLER}$path"
    private fun ws(path: String): String = "ws://${MetaPaths.EXTERNAL_CONTROLLER}$path"

    fun queryTunnelState(): TunnelState {
        val cfg = get<ConfigResp>("/configs")
        return TunnelState(
            mode = when (cfg.mode.lowercase()) {
                "global" -> TunnelState.Mode.Global
                "rule" -> TunnelState.Mode.Rule
                "script" -> TunnelState.Mode.Script
                else -> TunnelState.Mode.Direct
            }
        )
    }

    fun queryTrafficNow(): Traffic {
        val t = get<TrafficResp>("/traffic")
        return encodeTraffic(t.up, t.down)
    }

    fun queryTrafficTotal(): Traffic {
        return queryTrafficNow()
    }

    fun queryGroupNames(excludeNotSelectable: Boolean): List<String> {
        val all = get<ProxiesResp>("/proxies").proxies
        return all.entries
            .filter { it.key != "GLOBAL" }
            .filter {
                if (!excludeNotSelectable) true
                else isGroupType(it.value.type)
            }
            .map { it.key }
            .sorted()
    }

    fun queryGroup(name: String, sort: ProxySort): ProxyGroup {
        val encodedName = URLEncoder.encode(name, "UTF-8")
        val group = get<ProxyResp>("/proxies/$encodedName")
        val all = get<ProxiesResp>("/proxies").proxies

        val proxies = (group.all ?: emptyList()).map { child ->
            val c = all[child]
            Proxy(
                name = child,
                title = child,
                subtitle = "",
                type = c?.type?.toProxyType() ?: Proxy.Type.Unknown,
                delay = c?.history?.lastOrNull()?.delay ?: 0
            )
        }

        val sorted = when (sort) {
            ProxySort.Default -> proxies
            ProxySort.Title -> proxies.sortedBy { it.title.lowercase() }
            ProxySort.Delay -> proxies.sortedBy { if (it.delay <= 0) Int.MAX_VALUE else it.delay }
        }

        return ProxyGroup(
            type = group.type.toProxyType(),
            proxies = sorted,
            now = group.now ?: ""
        )
    }

    fun patchSelector(group: String, selected: String): Boolean {
        val encodedName = URLEncoder.encode(group, "UTF-8")
        val body = """{"name":"${escapeJson(selected)}"}"""
        val req = Request.Builder()
            .url(api("/proxies/$encodedName"))
            .put(body.toRequestBody(plain))
            .build()
        http.newCall(req).execute().use { return it.isSuccessful }
    }

    fun healthCheck(group: String) {
        val encodedName = URLEncoder.encode(group, "UTF-8")
        val req = Request.Builder()
            .url(api("/proxies/$encodedName/healthcheck?url=https://www.gstatic.com/generate_204"))
            .get()
            .build()
        http.newCall(req).execute().close()
    }

    fun patchConfigRaw(jsonBody: String) {
        val req = Request.Builder()
            .url(api("/configs"))
            .patch(jsonBody.toRequestBody(plain))
            .build()
        http.newCall(req).execute().close()
    }

    fun queryProviders(): List<Provider> {
        val proxyProviders = runCatching { get<ProvidersResp>("/providers/proxies").providers }.getOrDefault(emptyMap())
        val ruleProviders = runCatching { get<ProvidersResp>("/providers/rules").providers }.getOrDefault(emptyMap())

        val proxies = proxyProviders.map { (name, p) ->
            Provider(
                name = name,
                type = Provider.Type.Proxy,
                vehicleType = p.vehicleType.toVehicleType(),
                updatedAt = p.updatedAt ?: 0L
            )
        }
        val rules = ruleProviders.map { (name, p) ->
            Provider(
                name = name,
                type = Provider.Type.Rule,
                vehicleType = p.vehicleType.toVehicleType(),
                updatedAt = p.updatedAt ?: 0L
            )
        }
        return proxies + rules
    }

    fun updateProvider(type: Provider.Type, name: String) {
        val path = if (type == Provider.Type.Proxy) "/providers/proxies/" else "/providers/rules/"
        val encodedName = URLEncoder.encode(name, "UTF-8")
        val req = Request.Builder()
            .url(api("$path$encodedName"))
            .put("{}".toRequestBody(plain))
            .build()
        http.newCall(req).execute().close()
    }

    fun setMode(mode: TunnelState.Mode?) {
        if (mode == null) return
        patchConfigRaw("""{"mode":"${mode.name.lowercase()}"}""")
    }

    fun setLogLevel(level: LogMessage.Level?) {
        if (level == null) return
        patchConfigRaw("""{"log-level":"${level.name.lowercase()}"}""")
    }

    fun connectLogStream(onLog: (LogMessage) -> Unit, level: LogMessage.Level = LogMessage.Level.Info): WebSocket {
        val req = Request.Builder()
            .url(ws("/logs?level=${level.name.lowercase()}"))
            .build()

        return http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val event = json.decodeFromString(LogEvent.serializer(), text)
                    val lv = when (event.type.lowercase()) {
                        "debug" -> LogMessage.Level.Debug
                        "warning", "warn" -> LogMessage.Level.Warning
                        "error" -> LogMessage.Level.Error
                        "silent" -> LogMessage.Level.Silent
                        else -> LogMessage.Level.Info
                    }
                    onLog(LogMessage(lv, event.payload, Date()))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit
        })
    }

    private inline fun <reified T> get(path: String): T {
        val req = Request.Builder().url(api(path)).get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return when (T::class) {
                ConfigResp::class -> json.decodeFromString(ConfigResp.serializer(), body) as T
                TrafficResp::class -> json.decodeFromString(TrafficResp.serializer(), body) as T
                ProxyResp::class -> json.decodeFromString(ProxyResp.serializer(), body) as T
                ProxiesResp::class -> json.decodeFromString(ProxiesResp.serializer(), body) as T
                ProvidersResp::class -> json.decodeFromString(ProvidersResp.serializer(), body) as T
                else -> throw IllegalArgumentException("Unsupported decode type: ${T::class}")
            }
        }
    }

    private fun isGroupType(type: String): Boolean {
        return type.lowercase() in setOf("selector", "fallback", "urltest", "loadbalance", "relay")
    }

    private fun String.toProxyType(): Proxy.Type {
        return when (lowercase()) {
            "direct" -> Proxy.Type.Direct
            "reject" -> Proxy.Type.Reject
            "rejectdrop" -> Proxy.Type.RejectDrop
            "compatible" -> Proxy.Type.Compatible
            "pass" -> Proxy.Type.Pass
            "ss", "shadowsocks" -> Proxy.Type.Shadowsocks
            "ssr", "shadowsocksr" -> Proxy.Type.ShadowsocksR
            "snell" -> Proxy.Type.Snell
            "socks5", "socks" -> Proxy.Type.Socks5
            "http" -> Proxy.Type.Http
            "vmess" -> Proxy.Type.Vmess
            "vless" -> Proxy.Type.Vless
            "trojan" -> Proxy.Type.Trojan
            "hysteria" -> Proxy.Type.Hysteria
            "hysteria2" -> Proxy.Type.Hysteria2
            "tuic" -> Proxy.Type.Tuic
            "wireguard" -> Proxy.Type.WireGuard
            "dns" -> Proxy.Type.Dns
            "ssh" -> Proxy.Type.Ssh
            "anytls" -> Proxy.Type.AnyTLS
            "relay" -> Proxy.Type.Relay
            "selector" -> Proxy.Type.Selector
            "fallback" -> Proxy.Type.Fallback
            "urltest" -> Proxy.Type.URLTest
            "loadbalance" -> Proxy.Type.LoadBalance
            else -> Proxy.Type.Unknown
        }
    }

    private fun String.toVehicleType(): Provider.VehicleType {
        return when (lowercase()) {
            "http" -> Provider.VehicleType.HTTP
            "file" -> Provider.VehicleType.File
            "inline" -> Provider.VehicleType.Inline
            else -> Provider.VehicleType.Compatible
        }
    }

    private fun encodeTraffic(up: Long, down: Long): Long {
        val upPart = ((up.coerceAtLeast(0) and 0x3FFFFFFF) shl 32)
        val downPart = (down.coerceAtLeast(0) and 0x3FFFFFFF)
        return upPart or downPart
    }

    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    @Serializable
    private data class ConfigResp(val mode: String = "rule")

    @Serializable
    private data class TrafficResp(val up: Long = 0, val down: Long = 0)

    @Serializable
    private data class HistoryItem(val delay: Int = 0)

    @Serializable
    private data class ProxyResp(
        val type: String = "Unknown",
        val now: String? = null,
        val all: List<String>? = null,
        val history: List<HistoryItem>? = null
    )

    @Serializable
    private data class ProxiesResp(val proxies: Map<String, ProxyResp> = emptyMap())

    @Serializable
    private data class ProviderResp(
        val vehicleType: String = "Compatible",
        val updatedAt: Long? = null
    )

    @Serializable
    private data class ProvidersResp(val providers: Map<String, ProviderResp> = emptyMap())

    @Serializable
    private data class LogEvent(val type: String = "info", val payload: String = "")
}
