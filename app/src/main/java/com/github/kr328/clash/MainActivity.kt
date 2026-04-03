package com.github.kr328.clash

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.util.ModuleConnection
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.service.ClashService
import com.github.kr328.clash.core.bridge.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R

class MainActivity : BaseActivity<MainDesign>() {
    override suspend fun main() {
        if (!ModuleConnection.isAvailable()) {
            startActivity(ModuleDisconnectedActivity::class.intent)
            finish()
            return
        }

        // Auto-start: if the user previously started the service (shouldStartClashOnBoot)
        // but it isn't currently managing mihomo (clashRunning = false, which happens when
        // the module's service.sh started mihomo at boot before ClashService was running),
        // silently take over management.  Because prepareAndStart now sends SIGHUP instead
        // of killing mihomo, this is non-disruptive to existing connections.
        // We start ClashService directly (not TunService) to avoid triggering a VPN
        // permission dialog on auto-start; the user can explicitly tap Start for VPN mode.
        if (!clashRunning && StatusProvider.shouldStartClashOnBoot) {
            startForegroundServiceCompat(ClashService::class.intent)
        }

        val design = MainDesign(this)

        setContentDesign(design)

        design.fetch()

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        // Jobs for cancellable background operations. Child coroutines are automatically
        // cancelled when this activity's MainScope is cancelled (onDestroy).
        var fetchJob: Job? = null
        var trafficJob: Job? = null

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged -> {
                            // Launch in background so the select loop can immediately process
                            // the next event (e.g. button taps) without waiting for HTTP calls.
                            fetchJob?.cancel()
                            fetchJob = launch { design.fetch() }
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning) {
                                stopClashService()
                                // Give immediate visual feedback: the actual ACTION_CLASH_STOPPED
                                // broadcast only arrives after MetaKernelController.stop() finishes
                                // killing the mihomo process (root shell, may take several seconds).
                                fetchJob?.cancel()
                                fetchJob = launch { design.setClashRunning(false) }
                            } else {
                                launch {
                                    design.startClash()
                                    // After startClash() returns (VPN permission granted, service
                                    // requested), trigger a fetch so the button reflects the new
                                    // state without waiting for the next ticker tick.
                                    fetchJob?.cancel()
                                    fetchJob = launch { design.fetch() }
                                }
                            }
                        }
                        MainDesign.Request.OpenProxy ->
                            startActivity(ProxyActivity::class.intent)
                        MainDesign.Request.OpenProfiles ->
                            startActivity(ProfilesActivity::class.intent)
                        MainDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.OpenLogs -> {
                            if (LogcatService.running) {
                                startActivity(LogcatActivity::class.intent)
                            } else {
                                startActivity(LogsActivity::class.intent)
                            }
                        }
                        MainDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                        MainDesign.Request.OpenHelp ->
                            startActivity(HelpActivity::class.intent)
                        MainDesign.Request.OpenAbout ->
                            launch { design.showAbout(queryAppVersionName()) }
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        // Skip this tick if a traffic fetch is already in flight so we
                        // don't accumulate blocked IO threads when the API is slow.
                        if (trafficJob?.isActive != true) {
                            trafficJob = launch { design.fetchTraffic() }
                        }
                    }
                }
            }
        }
    }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        if (!clashRunning) {
            setHasProviders(false)
            withProfile {
                setProfileName(queryActive()?.name)
            }
            return
        }

        val state = withClash {
            queryTunnelState()
        }
        val providers = withClash {
            queryProviders()
        }

        setMode(state.mode)
        setHasProviders(providers.isNotEmpty())

        withProfile {
            setProfileName(queryActive()?.name)
        }
    }

    private suspend fun MainDesign.fetchTraffic() {
        withTimeoutOrNull(3_000) {
            withClash {
                setForwarded(queryTrafficTotal())
            }
        }
    }

    private suspend fun MainDesign.startClash() {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            showToast(R.string.no_profile_selected, ToastDuration.Long) {
                setAction(R.string.profiles) {
                    startActivity(ProfilesActivity::class.intent)
                }
            }

            return
        }

        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK)
                    startClashService()
            }
        } catch (e: Exception) {
            design?.showToast(R.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName + "\n" + Bridge.nativeCoreVersion().replace("_", "-")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()
                ) { isGranted: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

val mainActivityAlias = "${MainActivity::class.java.name}Alias"
