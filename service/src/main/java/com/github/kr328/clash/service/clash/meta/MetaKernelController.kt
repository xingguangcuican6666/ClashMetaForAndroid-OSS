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
        val runtimeConfig = MetaConfigPatcher.buildRuntimeConfig(profileDir, store, useTun)
        if (!MetaConfigPatcher.writeRuntimeConfig(runtimeConfig)) {
            return "Write runtime config failed"
        }

        val merged = MetaConfigPatcher.readOverride(Clash.OverrideSlot.Session, store)
        MetaConfigPatcher.writeSecretIfNeeded(merged.secret)

        val startCmd = buildString {
            append("mkdir -p ${MetaPaths.RUN_DIR}; ")
            append("if [ -f ${MetaPaths.PID_PATH} ] && kill -0 \"$(cat ${MetaPaths.PID_PATH})\" 2>/dev/null; then ")
            append("kill \"$(cat ${MetaPaths.PID_PATH})\"; sleep 1; ")
            append("fi; ")
            append("nohup ${MetaPaths.BIN_PATH} -f ${MetaPaths.CONFIG_PATH} > ${MetaPaths.LOG_PATH} 2>&1 & ")
            append("echo $! > ${MetaPaths.PID_PATH}")
        }
        val result = RootCmd.run(startCmd, 15)
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
