package com.github.kr328.clash

import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.design.R
import com.github.kr328.clash.util.ModuleConnection

class ModuleDisconnectedActivity : BaseActivity<com.github.kr328.clash.design.Design<Unit>>() {
    override suspend fun main() {
        val connected = ModuleConnection.isAvailable()
        if (connected) {
            finish()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.module_connection_required_title)
            .setMessage(R.string.module_connection_required_message)
            .setCancelable(false)
            .setPositiveButton(R.string.close) { _, _ -> finish() }
            .show()

        while (true) {
            events.receive()
        }
    }
}
