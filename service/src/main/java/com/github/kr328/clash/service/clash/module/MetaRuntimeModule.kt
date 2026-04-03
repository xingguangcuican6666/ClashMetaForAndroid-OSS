package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.clash.meta.MetaKernelController
import com.github.kr328.clash.service.clash.meta.MetaState
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendProfileLoaded
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

class MetaRuntimeModule(
    service: Service,
    private val useTun: Boolean
) : Module<MetaRuntimeModule.LoadException>(service) {
    data class LoadException(val message: String)

    override suspend fun run() {
        val reload = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_PROFILE_CHANGED)
            addAction(Intents.ACTION_OVERRIDE_CHANGED)
        }

        if (!startOrReload()) {
            return
        }

        while (true) {
            reload.receive()
            startOrReload()
        }
    }

    private suspend fun startOrReload(): Boolean {
        val profileDir = MetaKernelController.ensureProfileDir(service)
            ?: run {
                StatusProvider.currentProfile = null
                enqueueEvent(LoadException("No profile selected"))
                return false
            }

        val error = MetaKernelController.prepareAndStart(service, profileDir, useTun)
        if (error != null) {
            Log.w(error)
            StatusProvider.currentProfile = null
            enqueueEvent(LoadException(error))
            return false
        }

        delay(800)
        MetaState.refreshSnapshot()

        val activeUuid = ServiceStore(service).activeProfile
        // Publish the running profile name so StatusProvider.currentProfile() != null,
        // which is the signal used by Broadcasts.register() to set clashRunning = true
        // when the app returns to the foreground after being backgrounded.
        if (activeUuid != null) {
            val profile = runCatching { ImportedDao().queryByUUID(activeUuid) }.getOrNull()
            StatusProvider.currentProfile = profile?.name ?: activeUuid.toString()
        }

        activeUuid?.let { service.sendProfileLoaded(it) }
        return true
    }
}
