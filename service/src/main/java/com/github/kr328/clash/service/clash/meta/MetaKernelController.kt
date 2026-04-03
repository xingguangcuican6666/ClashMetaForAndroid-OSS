package com.github.kr328.clash.service.clash.meta

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import java.io.File
import android.content.Context

internal object MetaKernelController {
    fun prepareAndStart(context: Context, profileDir: File, useTun: Boolean): String? {
        val store = ServiceStore(context)

        // One-time backup: save the module-managed core config before we first overwrite
        // config.yaml.  All subsequent calls use this backup as the read-only source so
        // that TUN/DNS/rules are never lost regardless of what the imported profile says.
        RootCmd.run(
            "[ ! -f ${MetaPaths.BASE_CONFIG_PATH} ] && [ -f ${MetaPaths.CONFIG_PATH} ] && " +
            "cp ${MetaPaths.CONFIG_PATH} ${MetaPaths.BASE_CONFIG_PATH} || true",
            10
        )

        // Write the imported profile to PROFILE_CONFIG_PATH.
        // buildRuntimeConfig() uses this as the base for config.yaml so that the
        // user's proxies, proxy-groups, DNS, and rules are preserved.  If no profile
        // has been imported yet, buildRuntimeConfig() falls back to BASE_CONFIG_PATH.
        val profileYaml = runCatching { profileDir.resolve("config.yaml").readText() }.getOrElse { "" }
        if (profileYaml.isNotBlank()) {
            val delimiter = "__CMFA_PROFILE_EOF_${System.currentTimeMillis()}__"
            val writeProfile = RootCmd.run(
                "cat > ${MetaPaths.PROFILE_CONFIG_PATH} <<'$delimiter'\n$profileYaml\n$delimiter",
                10
            )
            if (writeProfile.code != 0) {
                Log.w("Write profile config failed: ${writeProfile.stderr}")
            }
        }

        val runtimeConfig = MetaConfigPatcher.buildRuntimeConfig(store, useTun)
        if (!MetaConfigPatcher.writeRuntimeConfig(runtimeConfig)) {
            return "Write runtime config failed"
        }

        val merged = MetaConfigPatcher.readOverride(Clash.OverrideSlot.Session, store)
        MetaConfigPatcher.writeSecretIfNeeded(merged.secret)

        val ensure = RootCmd.run("mkdir -p ${MetaPaths.RUN_DIR}", 10)
        if (ensure.code != 0) {
            return "Prepare run dir failed"
        }

        // Detect any running mihomo process.  The module's service.sh does NOT write to
        // our PID_PATH, so a PID-file-only check would always miss the module-started
        // process, causing the app to attempt a fresh start while mihomo is already
        // listening on port 16756.  That port conflict means the old process survives
        // with the module's original secret, making every API call return 401 (silently
        // deserialized as empty collections → "no proxy groups").
        // Strategy: check our PID file first (fastest), then fall back to searching by
        // the process name via pidof (available on Android ≥7 via toybox), then scan
        // /proc as a last resort.  Whichever PID is found is written back to PID_PATH so
        // subsequent calls use the fast path.
        val findPid = RootCmd.run(
            """
            pid=""
            if [ -f ${MetaPaths.PID_PATH} ]; then
              p=$(cat ${MetaPaths.PID_PATH} 2>/dev/null)
              kill -0 "${'$'}p" 2>/dev/null && pid="${'$'}p"
            fi
            if [ -z "${'$'}pid" ]; then
              pid=$(pidof mihomo-android 2>/dev/null | awk '{print ${'$'}1}')
            fi
            if [ -z "${'$'}pid" ]; then
              for d in /proc/[0-9]*; do
                [ -r "${'$'}d/cmdline" ] && grep -qa 'mihomo-android' "${'$'}d/cmdline" 2>/dev/null && pid="${'$'}{d##*/}" && break
              done
            fi
            if [ -n "${'$'}pid" ] && kill -0 "${'$'}pid" 2>/dev/null; then
              printf '%s' "${'$'}pid" > ${MetaPaths.PID_PATH}
              echo "1"
            else
              echo "0"
            fi
            """.trimIndent(),
            10
        )
        val alreadyRunning = findPid.stdout.trim() == "1"

        val needsFreshStart = if (alreadyRunning) {
            val reload = RootCmd.run("kill -HUP \"\$(cat ${MetaPaths.PID_PATH})\"", 5)
            if (reload.code != 0) {
                Log.w("SIGHUP reload failed (${reload.stderr}), will restart")
                true
            } else {
                false
            }
        } else {
            true
        }

        if (needsFreshStart) {
            // Kill any stale mihomo process before starting a new one.
            RootCmd.run(
                """
                if [ -f ${MetaPaths.PID_PATH} ]; then
                  kill "$(cat ${MetaPaths.PID_PATH})" 2>/dev/null; true
                fi
                p=$(pidof mihomo-android 2>/dev/null | awk '{print $1}')
                [ -n "${'$'}p" ] && kill "${'$'}p" 2>/dev/null; true
                sleep 0.3
                rm -f ${MetaPaths.PID_PATH}
                """.trimIndent(),
                10
            )
            val result = RootCmd.run(
                """
                nohup ${MetaPaths.BIN_PATH} -d ${MetaPaths.RUN_DIR} -f ${MetaPaths.CONFIG_PATH} > ${MetaPaths.LOG_PATH} 2>&1 &
                echo $! > ${MetaPaths.PID_PATH}
                """.trimIndent(),
                15
            )
            if (result.code != 0) {
                Log.w("Start mihomo failed: ${result.stderr}")
                return "Start mihomo failed: ${result.stderr.ifBlank { "exit ${result.code}" }}"
            }
        }
        return null
    }

    fun reload(): String? {
        val result = RootCmd.run("if [ -f ${MetaPaths.PID_PATH} ]; then kill -HUP \"$(cat ${MetaPaths.PID_PATH})\"; else exit 2; fi", 10)
        return if (result.code == 0) null else "Reload mihomo failed"
    }

    fun stop() {
        RootCmd.run("if [ -f ${MetaPaths.PID_PATH} ]; then kill \"$(cat ${MetaPaths.PID_PATH})\" 2>/dev/null || true; rm -f ${MetaPaths.PID_PATH}; fi", 10)
    }

    fun ensureProfileDir(context: Context): File? {
        val uuid = ServiceStore(context).activeProfile ?: return null
        val dir = context.importedDir.resolve(uuid.toString())
        return dir.takeIf { it.exists() }
    }
}
