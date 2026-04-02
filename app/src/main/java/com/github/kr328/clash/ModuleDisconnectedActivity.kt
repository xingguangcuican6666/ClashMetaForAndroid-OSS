package com.github.kr328.clash

import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.design.R
import com.github.kr328.clash.util.ModuleConnection
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ModuleDisconnectedActivity : BaseActivity<com.github.kr328.clash.design.Design<Unit>>() {
    override suspend fun main() {
        while (true) {
            val connected = ModuleConnection.isAvailable()
            if (connected) {
                finish()
                return
            }

            val shouldRetry = suspendCancellableCoroutine<Boolean> { c ->
                val dialog = AlertDialog.Builder(this)
                    .setTitle(R.string.module_connection_required_title)
                    .setMessage(R.string.module_connection_required_message)
                    .setCancelable(false)
                    .setNegativeButton(R.string.close) { _, _ -> c.resume(false) }
                    .setPositiveButton(R.string.module_connection_retry) { _, _ -> c.resume(true) }
                    .create()

                c.invokeOnCancellation { dialog.dismiss() }
                dialog.show()
            }

            if (!shouldRetry) {
                finish()
                return
            }
        }
    }
}
