package com.github.kr328.clash.service.clash.meta

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object MetaConfigPatcher {
    private val overrideJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun buildRuntimeConfig(store: ServiceStore, useTun: Boolean): String {
        val persist = decodeOverride(store.persistOverrideJson)
        val session = decodeOverride(store.sessionOverrideJson)

        val merged = mergeOverrides(persist, session)
        val managed = buildManagedBlock(merged, useTun, store)

        // Prefer the imported subscription profile as the base config: it contains the
        // user's proxies, proxy-groups, DNS, and rules.  Without this, the user would
        // see an empty proxy list because BASE_CONFIG_PATH is the module's minimal
        // default (mixed-port / allow-lan / mode / log-level only).
        // Fall back to BASE_CONFIG_PATH when no subscription has been imported yet.
        val profileResult = RootCmd.run("cat ${MetaPaths.PROFILE_CONFIG_PATH} 2>/dev/null", 10)
        val coreConfig = if (profileResult.code == 0 && profileResult.stdout.isNotBlank()) {
            profileResult.stdout
        } else {
            val catResult = RootCmd.run("cat ${MetaPaths.BASE_CONFIG_PATH} 2>/dev/null", 10)
            if (catResult.code == 0 && catResult.stdout.isNotBlank()) {
                catResult.stdout
            } else {
                // Neither profile nor base config available yet (very first run).
                return managed
            }
        }

        // 1. Strip the entire cmfa managed block region (comment markers + all content
        //    between them).  The BASE_CONFIG_PATH backup is taken from config.yaml
        //    AFTER service.sh has already injected its own managed block, so the region
        //    is present in the base.  stripTopLevelKeys() only removes key:value lines
        //    and leaves comment lines in place, which would leave behind the marker
        //    comments and result in TWO managed-block marker pairs in the final
        //    config.yaml.  service.sh counts the markers and refuses to re-inject
        //    when start_count > 1 → "[cmfa] failed to inject managed controller block".
        // 2. Strip any remaining individual key definitions that the managed block will
        //    redefine (handles hand-edited configs that declare the same keys outside
        //    the block).
        val withoutBlock = stripManagedBlockRegion(coreConfig)
        val stripped = stripTopLevelKeys(withoutBlock, managedBlockKeys(merged))
        return stripped.trimEnd() + "\n\n" + managed
    }

    /**
     * Returns the set of top-level YAML keys that [buildManagedBlock] will emit for
     * the given [override].  Only keys that will actually appear in the managed block
     * are included so that optional settings from the core config are preserved when
     * no app-side override is active.
     */
    private fun managedBlockKeys(override: ConfigurationOverride): Set<String> {
        val keys = mutableSetOf(
            // Always written by the managed block.
            "external-controller", "external-controller-tls", "external-ui", "secret", "tun"
        )
        // Conditionally written — only strip from the core config if the managed block
        // will actually set them, so the core config value is preserved otherwise.
        if (override.mode != null)            keys += "mode"
        if (override.logLevel != null)        keys += "log-level"
        if (override.allowLan != null)        keys += "allow-lan"
        if (override.bindAddress != null)     keys += "bind-address"
        if (override.unifiedDelay != null)    keys += "unified-delay"
        if (override.ipv6 != null)            keys += "ipv6"
        if (override.geodataMode != null)     keys += "geodata-mode"
        if (override.tcpConcurrent != null)   keys += "tcp-concurrent"
        if (override.findProcessMode != null) keys += "find-process-mode"
        return keys
    }

    /**
     * Removes the entire `# ===== cmfa managed block =====` … `# ===== end cmfa managed
     * block =====` region from [yaml], including the marker comment lines themselves and
     * all lines between them.
     *
     * This is necessary because [BASE_CONFIG_PATH] is created by copying config.yaml
     * AFTER service.sh has already injected its own managed block.  Without this step,
     * [stripTopLevelKeys] would remove the inner key:value lines but leave the comment
     * markers behind, causing the final config.yaml to have two managed-block marker
     * pairs — which makes service.sh's `start_count > 1` guard reject the file.
     */
    private fun stripManagedBlockRegion(yaml: String): String {
        val sb = StringBuilder()
        var skipping = false
        for (line in yaml.lines()) {
            if (line.trimEnd() == "# ===== cmfa managed block =====") {
                skipping = true
                continue
            }
            if (line.trimEnd() == "# ===== end cmfa managed block =====") {
                skipping = false
                continue
            }
            if (!skipping) sb.appendLine(line)
        }
        return sb.toString().trimEnd()
    }

    /**
     * Returns [yaml] with every top-level block whose key is in [keys] removed.
     *
     * A top-level block starts at column 0 with a non-comment YAML key.  All
     * continuation lines (indented lines, list items starting with `-`, blank lines
     * that belong to the block) are also removed.  The function uses a conservative
     * line-based approach that handles real-world clash/mihomo configs correctly.
     */
    private fun stripTopLevelKeys(yaml: String, keys: Set<String>): String {
        val sb = StringBuilder()
        var skipping = false

        for (line in yaml.lines()) {
            if (isTopLevelKey(line)) {
                val key = line.substringBefore(':').trim()
                skipping = key in keys
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

    private fun buildManagedBlock(override: ConfigurationOverride, useTun: Boolean, store: ServiceStore): String {
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
            appendLine("  stack: ${store.tunStackMode}")
            appendLine("  mtu: ${store.tunMtu}")
            appendLine("  auto-route: $useTun")
            appendLine("  auto-detect-interface: $useTun")
            appendLine("  strict-route: ${store.tunStrictRoute}")
            val hijackEntries = store.tunDnsHijackList
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (hijackEntries.isNotEmpty()) {
                appendLine("  dns-hijack:")
                for (entry in hijackEntries) {
                    appendLine("    - $entry")
                }
            }
            appendLine("# ===== end cmfa managed block =====")
        }
    }

    private fun escapeYaml(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
