package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.clash.meta.MetaState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

class SuspendModule(service: Service) : Module<Unit>(service) {
    override suspend fun run() {
        val screenToggle = receiveBroadcast(false, Channel.CONFLATED) {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        try {
            while (true) {
                when (screenToggle.receive().action) {
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d("Clash resumed")
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d("Clash suspended")
                    }
                    else -> Log.w("SuspendModule received unexpected broadcast action")
                }
            }
        } finally {
            withContext(NonCancellable) {
                MetaState.refreshSnapshot()
            }
        }
    }
}
