package com.github.kr328.clash.service.clash.meta

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

internal object MetaConfigPatcher {
    private val overrideJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun buildRuntimeConfig(profileDir: File, store: ServiceStore, useTun: Boolean): String {
        val profileYaml = profileDir.resolve("config.yaml").readText()

        val persist = decodeOverride(store.persistOverrideJson)
        val session = decodeOverride(store.sessionOverrideJson)

        val merged = mergeOverrides(persist, session)
        val managed = buildManagedBlock(merged, useTun)

        // Try to read the one-time backup of the original module-managed core config.
        // If it exists, use it as the base (provides TUN / DNS / rules / listeners) and
        // overlay only the proxies and proxy-groups from the imported profile on top.
        // This keeps "proxy config" and "core config" strictly separate.
        // If no backup exists (first boot before the module wrote its own config), fall
        // back to using the imported profile as the full config — original behaviour.
        val raw = buildBaseWithProxies(profileYaml)

        return raw.trimEnd() + "\n\n" + managed
    }

    private fun buildBaseWithProxies(profileYaml: String): String {
        val catResult = RootCmd.run("cat ${MetaPaths.BASE_CONFIG_PATH} 2>/dev/null", 10)
        val baseYaml = if (catResult.code == 0 && catResult.stdout.isNotBlank()) {
            catResult.stdout
        } else {
            // No base config yet — use the imported profile as-is (backwards compatible).
            return profileYaml
        }

        val proxiesFromProfile = extractProxyBlocks(profileYaml)
        if (proxiesFromProfile.isBlank()) {
            // Profile has no proxies: use the base config unchanged.
            return baseYaml
        }

        // Strip any proxies/proxy-groups already in the base config and replace with
        // those from the imported profile.
        val baseWithoutProxies = stripProxyBlocks(baseYaml)
        return baseWithoutProxies.trimEnd() + "\n\n" + proxiesFromProfile
    }

    /**
     * Extracts the top-level `proxies:` and `proxy-groups:` YAML blocks from [yaml].
     *
     * Uses a simple line-based parser: a top-level key starts at column 0 (no leading
     * whitespace) and is not a comment.  Continuation lines are indented (or start with
     * `-`).  This handles the overwhelming majority of real clash/mihomo subscription
     * files without requiring a full YAML library.
     */
    private fun extractProxyBlocks(yaml: String): String {
        val sb = StringBuilder()
        var collecting = false

        for (line in yaml.lines()) {
            if (isTopLevelKey(line)) {
                val key = line.substringBefore(':').trim()
                collecting = key == "proxies" || key == "proxy-groups"
            }
            if (collecting) sb.appendLine(line)
        }

        return sb.toString().trimEnd()
    }

    /**
     * Returns [yaml] with the top-level `proxies:` and `proxy-groups:` blocks removed.
     * Used to clear existing proxy definitions from the base config before overlaying
     * fresh ones from the imported profile.
     */
    private fun stripProxyBlocks(yaml: String): String {
        val sb = StringBuilder()
        var skipping = false

        for (line in yaml.lines()) {
            if (isTopLevelKey(line)) {
                val key = line.substringBefore(':').trim()
                skipping = key == "proxies" || key == "proxy-groups"
            }
            if (!skipping) sb.appendLine(line)
        }

        return sb.toString().trimEnd()
    }

    /** True when [line] begins a new top-level YAML key (column 0, not a comment). */
    private fun isTopLevelKey(line: String): Boolean {
        if (line.isEmpty() || line[0].isWhitespace() || line[0] == '#') return false
        val colonIdx = line.indexOf(':')
        if (colonIdx < 0) return false
        // If '#' appears before ':', the colon is inside a comment — not a real key.
        val hashIdx = line.indexOf('#')
        return hashIdx < 0 || colonIdx < hashIdx
    }

    fun persistOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride, store: ServiceStore) {
        val encoded = overrideJson.encodeToString(ConfigurationOverride.serializer(), configuration)
        when (slot) {
            Clash.OverrideSlot.Persist -> store.persistOverrideJson = encoded
            Clash.OverrideSlot.Session -> store.sessionOverrideJson = encoded
        }
    }

    fun readOverride(slot: Clash.OverrideSlot, store: ServiceStore): ConfigurationOverride {
        val content = when (slot) {
            Clash.OverrideSlot.Persist -> store.persistOverrideJson
            Clash.OverrideSlot.Session -> store.sessionOverrideJson
        }
        return decodeOverride(content)
    }

    fun clearOverride(slot: Clash.OverrideSlot, store: ServiceStore) {
        when (slot) {
            Clash.OverrideSlot.Persist -> store.persistOverrideJson = "{}"
            Clash.OverrideSlot.Session -> store.sessionOverrideJson = "{}"
        }
    }

    fun ensureRuntimeDirs() {
        RootCmd.run("mkdir -p ${MetaPaths.RUN_DIR}", 10)
    }

    fun writeRuntimeConfig(content: String): Boolean {
        ensureRuntimeDirs()
        val delimiter = "__CMFA_EOF_${System.currentTimeMillis()}__"
        val result = RootCmd.run("cat > ${MetaPaths.CONFIG_PATH} <<'$delimiter'\n$content\n$delimiter", 10)
        if (result.code != 0) {
            Log.w("Write runtime config failed: ${result.stderr}")
            return false
        }
        return true
    }

    fun writeSecretIfNeeded(secret: String?) {
        ensureRuntimeDirs()
        val s = secret ?: ""
        val delimiter = "__CMFA_SECRET_EOF_${System.currentTimeMillis()}__"
        RootCmd.run("cat > ${MetaPaths.SECRET_PATH} <<'$delimiter'\n$s\n$delimiter", 10)
    }

    private fun decodeOverride(content: String): ConfigurationOverride {
        return runCatching {
            overrideJson.decodeFromString(ConfigurationOverride.serializer(), content)
        }.getOrElse {
            ConfigurationOverride()
        }
    }

    private fun mergeOverrides(
        persist: ConfigurationOverride,
        session: ConfigurationOverride
    ): ConfigurationOverride {
        val p = persist.copy()
        val s = session
        if (s.mode != null) p.mode = s.mode
        if (s.logLevel != null) p.logLevel = s.logLevel
        if (s.externalController != null) p.externalController = s.externalController
        if (s.externalControllerTLS != null) p.externalControllerTLS = s.externalControllerTLS
        if (s.secret != null) p.secret = s.secret
        if (s.allowLan != null) p.allowLan = s.allowLan
        if (s.bindAddress != null) p.bindAddress = s.bindAddress
        if (s.unifiedDelay != null) p.unifiedDelay = s.unifiedDelay
        if (s.tcpConcurrent != null) p.tcpConcurrent = s.tcpConcurrent
        if (s.findProcessMode != null) p.findProcessMode = s.findProcessMode
        if (s.ipv6 != null) p.ipv6 = s.ipv6
        if (s.geodataMode != null) p.geodataMode = s.geodataMode
        return p
    }

    private fun buildManagedBlock(override: ConfigurationOverride, useTun: Boolean): String {
        return buildString {
            appendLine("# ===== cmfa managed block =====")
            appendLine("external-controller: \"${MetaPaths.EXTERNAL_CONTROLLER}\"")
            appendLine("external-controller-tls: \"\"")
            appendLine("external-ui: \"\"")
            appendLine("secret: \"${escapeYaml(override.secret ?: "")}\"")
            override.mode?.let { appendLine("mode: ${it.name.lowercase()}") }
            override.logLevel?.let { appendLine("log-level: ${it.name.lowercase()}") }
            override.allowLan?.let { appendLine("allow-lan: $it") }
            override.bindAddress?.let { appendLine("bind-address: \"${escapeYaml(it)}\"") }
            override.unifiedDelay?.let { appendLine("unified-delay: $it") }
            override.ipv6?.let { appendLine("ipv6: $it") }
            override.geodataMode?.let { appendLine("geodata-mode: $it") }
            override.tcpConcurrent?.let { appendLine("tcp-concurrent: $it") }
            override.findProcessMode?.let { appendLine("find-process-mode: ${it.name.lowercase()}") }
            appendLine("tun:")
            appendLine("  enable: $useTun")
            appendLine("  auto-route: false")
            appendLine("  auto-detect-interface: false")
            appendLine("# ===== end cmfa managed block =====")
        }
    }

    private fun escapeYaml(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
