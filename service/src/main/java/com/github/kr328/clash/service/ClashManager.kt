package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.service.clash.meta.MetaConfigPatcher
import com.github.kr328.clash.service.clash.meta.MetaState
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.ILogObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel

class ClashManager(private val context: Context) : IClashManager,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val store = ServiceStore(context)
    private var logReceiver: ReceiveChannel<LogMessage>? = null

    override fun queryTunnelState(): TunnelState {
        MetaState.refreshSnapshot()
        return MetaState.queryTunnelState()
    }

    override fun queryTrafficTotal(): Long {
        MetaState.refreshSnapshot()
        return MetaState.queryTrafficTotal()
    }

    override fun queryProxyGroupNames(excludeNotSelectable: Boolean): List<String> {
        return MetaState.queryGroupNames(excludeNotSelectable)
    }

    override fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup {
        return MetaState.queryGroup(name, proxySort)
    }

    override fun queryConfiguration(): UiConfiguration {
        return Clash.queryConfiguration()
    }

    override fun queryProviders(): ProviderList {
        MetaState.refreshSnapshot()
        return MetaState.queryProviders()
    }

    override fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride {
        return MetaConfigPatcher.readOverride(slot, store)
    }

    override fun patchSelector(group: String, name: String): Boolean {
        return MetaState.patchSelector(group, name).also {
            val current = store.activeProfile ?: return@also

            if (it) {
                SelectionDao().setSelected(Selection(current, group, name))
            } else {
                SelectionDao().removeSelected(current, group)
            }
        }
    }

    override fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride) {
        MetaConfigPatcher.persistOverride(slot, configuration, store)
        applySessionOverride(configuration, slot)

        context.sendOverrideChanged()
    }

    override fun clearOverride(slot: Clash.OverrideSlot) {
        MetaConfigPatcher.clearOverride(slot, store)
        context.sendOverrideChanged()
    }

    override suspend fun healthCheck(group: String) {
        return MetaState.healthCheck(group)
    }

    override suspend fun updateProvider(type: Provider.Type, name: String) {
        return MetaState.updateProvider(type, name)
    }

    override fun setLogObserver(observer: ILogObserver?) {
        synchronized(this) {
            logReceiver?.apply {
                cancel()
            }

            if (observer != null) {
                logReceiver = MetaState.openLogStream().also { c ->
                    launch {
                        try {
                            while (isActive) {
                                observer.newItem(c.receive())
                            }
                        } catch (e: CancellationException) {
                            // intended behavior
                            // ignore
                        } catch (e: Exception) {
                            Log.w("UI crashed", e)
                        } finally {
                            withContext(NonCancellable) {
                                c.cancel()
                                MetaState.closeLogStream()
                            }
                        }
                    }
                }
            } else {
                MetaState.closeLogStream()
            }
        }
    }

    private fun applySessionOverride(configuration: ConfigurationOverride, slot: Clash.OverrideSlot) {
        if (slot != Clash.OverrideSlot.Session) return
        MetaState.setMode(configuration.mode)
        MetaState.setLogLevel(configuration.logLevel)
    }
}
