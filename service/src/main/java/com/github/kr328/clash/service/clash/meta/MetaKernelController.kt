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

        // Write the imported profile to PROFILE_CONFIG_PATH so the module's core config
        // can reference it via proxy-providers without any app-side YAML merging:
        //
        //   proxy-providers:
        //     app-imported:
        //       type: file
        //       path: ./app-profile.yaml
        //       health-check: { enable: true, url: "https://www.gstatic.com/generate_204", interval: 300 }
        //
        // The app never injects proxy sections into the core config.
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

        val stopOld = RootCmd.run(
            """
            if [ -f ${MetaPaths.PID_PATH} ]; then
              old_pid=$(cat ${MetaPaths.PID_PATH})
              if kill -0 "${'$'}old_pid" 2>/dev/null; then
                kill "${'$'}old_pid"
                poll_attempts=0
                while kill -0 "${'$'}old_pid" 2>/dev/null && [ "${'$'}poll_attempts" -lt 30 ]; do
                  sleep 0.1
                  poll_attempts=$((poll_attempts+1))
                done
              fi
              rm -f ${MetaPaths.PID_PATH}
            fi
            """.trimIndent(),
            10
        )
        if (stopOld.code != 0) {
            Log.w("Stop old mihomo failed: ${stopOld.stderr}")
        }

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
