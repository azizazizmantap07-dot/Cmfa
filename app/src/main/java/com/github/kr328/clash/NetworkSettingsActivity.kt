package com.github.kr328.clash

import android.content.pm.PackageManager
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.NetworkSettingsDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.ShizukuShell
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import rikka.shizuku.Shizuku

class NetworkSettingsActivity : BaseActivity<NetworkSettingsDesign>() {

    private val store by lazy { ServiceStore(this) }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != ShizukuShell.PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener

            launch {
                val design = design ?: return@launch
                design.refreshShizukuStatus()

                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    design.showToast(
                        R.string.airplane_recovery_shizuku_granted_toast,
                        ToastDuration.Short,
                    )
                } else {
                    // Permission denied — turn recovery off so the switch matches reality.
                    store.airplaneRecoveryEnabled = false
                    design.showToast(
                        R.string.airplane_recovery_shizuku_denied_toast,
                        ToastDuration.Long,
                    )
                    // Recreate so the switch preference re-reads the store value.
                    recreate()
                }
            }
        }

    override suspend fun main() {
        val design = NetworkSettingsDesign(
            this,
            uiStore,
            store,
            clashRunning,
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
                        Event.ActivityStart ->
                            design.refreshShizukuStatus()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        NetworkSettingsDesign.Request.StartAccessControlList ->
                            startActivity(AccessControlActivity::class.intent)

                        NetworkSettingsDesign.Request.RequestShizukuPermission ->
                            handleRequestPermission(design)

                        NetworkSettingsDesign.Request.OpenShizukuApp ->
                            handleOpenShizuku(design)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (_: Throwable) {
        }
        design?.refreshShizukuStatus()
    }

    override fun onStop() {
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (_: Throwable) {
        }
        super.onStop()
    }

    private suspend fun handleRequestPermission(design: NetworkSettingsDesign) {
        when (val status = ShizukuShell.status(this)) {
            ShizukuShell.Status.Granted -> {
                design.refreshShizukuStatus()
                design.showToast(
                    R.string.airplane_recovery_shizuku_granted_toast,
                    ToastDuration.Short,
                )
            }
            ShizukuShell.Status.Denied -> {
                // Service is up — ask the user to authorize this app.
                try {
                    ShizukuShell.requestPermission()
                    design.showToast(
                        R.string.airplane_recovery_shizuku_requesting,
                        ToastDuration.Short,
                    )
                } catch (e: Exception) {
                    design.showToast(
                        R.string.airplane_recovery_shizuku_denied_toast,
                        ToastDuration.Long,
                    )
                    store.airplaneRecoveryEnabled = false
                    recreate()
                }
            }
            ShizukuShell.Status.NotRunning,
            ShizukuShell.Status.NotInstalled -> {
                handleOpenShizuku(design)
            }
        }
    }

    private suspend fun handleOpenShizuku(design: NetworkSettingsDesign) {
        val status = ShizukuShell.status(this)
        if (status == ShizukuShell.Status.NotInstalled) {
            design.showToast(
                R.string.airplane_recovery_shizuku_install_hint,
                ToastDuration.Long,
            )
        } else if (status == ShizukuShell.Status.NotRunning) {
            design.showToast(
                R.string.airplane_recovery_shizuku_start_hint,
                ToastDuration.Long,
            )
        }

        val opened = ShizukuShell.openShizukuApp(this)
        if (!opened) {
            design.showToast(
                R.string.airplane_recovery_shizuku_open_failed,
                ToastDuration.Long,
            )
        }

        // If recovery was turned on without Shizuku, turn it back off until ready.
        if (store.airplaneRecoveryEnabled && status != ShizukuShell.Status.Granted) {
            store.airplaneRecoveryEnabled = false
            recreate()
        }
    }
}
