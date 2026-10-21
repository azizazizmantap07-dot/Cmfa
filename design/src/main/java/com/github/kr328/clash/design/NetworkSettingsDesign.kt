package com.github.kr328.clash.design

import android.content.Context
import android.os.Build
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.ShizukuShell
import kotlinx.coroutines.launch

class NetworkSettingsDesign(
    context: Context,
    uiStore: UiStore,
    private val srvStore: ServiceStore,
    running: Boolean,
) : Design<NetworkSettingsDesign.Request>(context) {
    enum class Request {
        StartAccessControlList,
        /** Ask Shizuku for permission (after user enables recovery). */
        RequestShizukuPermission,
        /** Open the Shizuku manager app (or store listing). */
        OpenShizukuApp,
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    private lateinit var shizukuStatusPref: ClickablePreference
    private lateinit var recoverySwitch: SwitchPreference
    private val recoveryDependencies = mutableListOf<Preference>()

    init {
        binding.surface = surface

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val screen = preferenceScreen(context) {
            val vpnDependencies: MutableList<Preference> = mutableListOf()

            val vpn = switch(
                value = uiStore::enableVpn,
                icon = R.drawable.ic_baseline_vpn_lock,
                title = R.string.route_system_traffic,
                summary = R.string.routing_via_vpn_service
            ) {
                listener = OnChangedListener {
                    vpnDependencies.forEach {
                        it.enabled = uiStore.enableVpn
                    }
                }
            }

            category(R.string.vpn_service_options)

            switch(
                value = srvStore::bypassPrivateNetwork,
                title = R.string.bypass_private_network,
                summary = R.string.bypass_private_network_summary,
                configure = vpnDependencies::add,
            )

            switch(
                value = srvStore::dnsHijacking,
                title = R.string.dns_hijacking,
                summary = R.string.dns_hijacking_summary,
                configure = vpnDependencies::add,
            )

            switch(
                value = srvStore::allowBypass,
                title = R.string.allow_bypass,
                summary = R.string.allow_bypass_summary,
                configure = vpnDependencies::add,
            )

            switch(
                value = srvStore::allowIpv6,
                title = R.string.allow_ipv6,
                summary = R.string.allow_ipv6_summary,
                configure = vpnDependencies::add,
            )

            if (Build.VERSION.SDK_INT >= 29) {
                switch(
                    value = srvStore::systemProxy,
                    title = R.string.system_proxy,
                    summary = R.string.system_proxy_summary,
                    configure = vpnDependencies::add,
                )
            }

            selectableList(
                value = srvStore::tunStackMode,
                values = arrayOf(
                    "system",
                    "gvisor",
                    "mixed",
                    "mips"
                ),
                valuesText = arrayOf(
                    R.string.tun_stack_system,
                    R.string.tun_stack_gvisor,
                    R.string.tun_stack_mixed,
                    R.string.tun_stack_mips
                ),
                title = R.string.tun_stack_mode,
                configure = vpnDependencies::add,
            )

            selectableList(
                value = srvStore::accessControlMode,
                values = AccessControlMode.values(),
                valuesText = arrayOf(
                    R.string.allow_all_apps,
                    R.string.allow_selected_apps,
                    R.string.deny_selected_apps
                ),
                title = R.string.access_control_mode,
                configure = vpnDependencies::add,
            )

            clickable(
                title = R.string.access_control_packages,
                summary = R.string.access_control_packages_summary,
            ) {
                clicked {
                    requests.trySend(Request.StartAccessControlList)
                }
            }

            // ── Airplane Recovery (Shizuku) ──────────────────────────────
            category(R.string.airplane_recovery)

            tips(R.string.airplane_recovery_tips)

            shizukuStatusPref = clickable(
                title = R.string.airplane_recovery_shizuku_status,
                icon = R.drawable.ic_baseline_adb,
                summary = R.string.airplane_recovery_shizuku_checking,
            ) {
                clicked {
                    when (ShizukuShell.status(context)) {
                        ShizukuShell.Status.Granted -> {
                            // Already fine — still allow opening Shizuku to manage apps
                            requests.trySend(Request.OpenShizukuApp)
                        }
                        ShizukuShell.Status.Denied -> {
                            requests.trySend(Request.RequestShizukuPermission)
                        }
                        ShizukuShell.Status.NotRunning,
                        ShizukuShell.Status.NotInstalled -> {
                            requests.trySend(Request.OpenShizukuApp)
                        }
                    }
                }
            }

            recoverySwitch = switch(
                value = srvStore::airplaneRecoveryEnabled,
                icon = R.drawable.ic_baseline_airplanemode_active,
                title = R.string.airplane_recovery_enabled,
                summary = R.string.airplane_recovery_enabled_summary,
            ) {
                listener = OnChangedListener {
                    val enabled = srvStore.airplaneRecoveryEnabled
                    if (enabled) {
                        val status = ShizukuShell.status(context)
                        if (status != ShizukuShell.Status.Granted) {
                            // Ask for permission / open Shizuku; Activity will
                            // re-evaluate and may turn the switch back off.
                            when (status) {
                                ShizukuShell.Status.Denied ->
                                    requests.trySend(Request.RequestShizukuPermission)
                                else ->
                                    requests.trySend(Request.OpenShizukuApp)
                            }
                        }
                    }
                    applyRecoveryDependencies()
                }
            }

            selectableList(
                value = srvStore::airplaneRecoveryFailureThreshold,
                values = arrayOf(3, 5, 8, 10, 15, 20),
                valuesText = arrayOf(
                    R.string.airplane_recovery_threshold_3,
                    R.string.airplane_recovery_threshold_5,
                    R.string.airplane_recovery_threshold_8,
                    R.string.airplane_recovery_threshold_10,
                    R.string.airplane_recovery_threshold_15,
                    R.string.airplane_recovery_threshold_20,
                ),
                icon = R.drawable.ic_baseline_flash_on,
                title = R.string.airplane_recovery_failure_threshold,
                configure = recoveryDependencies::add,
            )

            selectableList(
                value = srvStore::airplaneRecoveryCooldownSec,
                // Seconds. Lower bound 1 s, upper bound 60 s (must match
                // ServiceStore.COOLDOWN_MIN_SEC / COOLDOWN_MAX_SEC).
                values = arrayOf(1, 2, 3, 5, 10, 15, 20, 30, 45, 60),
                valuesText = arrayOf(
                    R.string.airplane_recovery_cooldown_1,
                    R.string.airplane_recovery_cooldown_2,
                    R.string.airplane_recovery_cooldown_3,
                    R.string.airplane_recovery_cooldown_5,
                    R.string.airplane_recovery_cooldown_10,
                    R.string.airplane_recovery_cooldown_15,
                    R.string.airplane_recovery_cooldown_20,
                    R.string.airplane_recovery_cooldown_30,
                    R.string.airplane_recovery_cooldown_45,
                    R.string.airplane_recovery_cooldown_60,
                ),
                icon = R.drawable.ic_baseline_restore,
                title = R.string.airplane_recovery_cooldown,
                configure = recoveryDependencies::add,
            )

            if (running) {
                vpn.enabled = false
                vpnDependencies.forEach { it.enabled = false }
            } else {
                vpn.listener?.onChanged()
            }
        }

        binding.content.addView(screen.root)

        refreshShizukuStatus()
        applyRecoveryDependencies()

        if (running) {
            launch {
                showToast(R.string.options_unavailable, ToastDuration.Indefinite)
            }
        }
    }

    /**
     * Refresh the Shizuku status row. Call from the Activity after permission
     * results, on resume, or after returning from the Shizuku app.
     */
    fun refreshShizukuStatus() {
        val status = ShizukuShell.status(context)
        shizukuStatusPref.summary = when (status) {
            ShizukuShell.Status.Granted ->
                context.getText(R.string.airplane_recovery_shizuku_granted)
            ShizukuShell.Status.Denied ->
                context.getText(R.string.airplane_recovery_shizuku_denied)
            ShizukuShell.Status.NotRunning ->
                context.getText(R.string.airplane_recovery_shizuku_not_running)
            ShizukuShell.Status.NotInstalled ->
                context.getText(R.string.airplane_recovery_shizuku_not_installed)
        }
        applyRecoveryDependencies()
    }

    /**
     * Force the recovery switch off when permission was denied, and refresh UI.
     */
    fun setRecoveryEnabled(enabled: Boolean) {
        srvStore.airplaneRecoveryEnabled = enabled
        // Preference switch reads store only at init; recreate is handled by Activity
        // when needed. Update dependent prefs immediately.
        applyRecoveryDependencies()
    }

    private fun applyRecoveryDependencies() {
        val enabled = srvStore.airplaneRecoveryEnabled &&
            ShizukuShell.status(context) == ShizukuShell.Status.Granted
        recoveryDependencies.forEach { it.enabled = enabled }
    }
}
