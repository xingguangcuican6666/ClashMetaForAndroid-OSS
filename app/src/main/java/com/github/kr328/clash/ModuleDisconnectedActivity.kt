package com.github.kr328.clash

import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.design.R
import com.github.kr328.clash.util.ModuleConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class ModuleDisconnectedActivity : BaseActivity<com.github.kr328.clash.design.Design<Unit>>() {

    private enum class Action { Close, Retry, ImportConfig }

    override suspend fun main() {
        while (true) {
            val connected = ModuleConnection.isAvailable()
            if (connected) {
                finish()
                return
            }

            val action = suspendCancellableCoroutine<Action> { c ->
                val dialog = AlertDialog.Builder(this)
                    .setTitle(R.string.module_connection_required_title)
                    .setMessage(R.string.module_connection_required_message)
                    .setCancelable(false)
                    .setNegativeButton(R.string.close) { _, _ -> c.resume(Action.Close) }
                    .setNeutralButton(R.string.import_config_to_module) { _, _ -> c.resume(Action.ImportConfig) }
                    .setPositiveButton(R.string.module_connection_retry) { _, _ -> c.resume(Action.Retry) }
                    .create()

                c.invokeOnCancellation { dialog.dismiss() }
                dialog.show()
            }

            when (action) {
                Action.Close -> {
                    finish()
                    return
                }
                Action.Retry -> continue
                Action.ImportConfig -> {
                    val uri: Uri? = startActivityForResult(
                        ActivityResultContracts.GetContent(),
                        "*/*"
                    )
                    if (uri != null) {
                        val ok = importConfigFromUri(uri)
                        val msg = if (ok)
                            getString(R.string.import_config_to_module_success)
                        else
                            getString(R.string.import_config_to_module_failed)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private suspend fun importConfigFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder(
                "su", "-c",
                "mkdir -p /data/adb/mihomo-cmfa && cat > /data/adb/mihomo-cmfa/config.yaml"
            ).start()

            contentResolver.openInputStream(uri)?.use { input ->
                process.outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        }.getOrDefault(false)
    }
}
