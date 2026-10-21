package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider
import com.github.kr328.clash.service.model.AccessControlMode
import java.util.*

class ServiceStore(context: Context) {
    private val store = Store(
        PreferenceProvider
            .createSharedPreferencesFromContext(context)
            .asStoreProvider()
    )

    var activeProfile: UUID? by store.typedString(
        key = "active_profile",
        from = { if (it.isBlank()) null else UUID.fromString(it) },
        to = { it?.toString() ?: "" }
    )

    var bypassPrivateNetwork: Boolean by store.boolean(
        key = "bypass_private_network",
        defaultValue = true
    )

    var accessControlMode: AccessControlMode by store.enum(
        key = "access_control_mode",
        defaultValue = AccessControlMode.AcceptAll,
        values = AccessControlMode.values()
    )

    var accessControlPackages by store.stringSet(
        key = "access_control_packages",
        defaultValue = emptySet()
    )

    var dnsHijacking by store.boolean(
        key = "dns_hijacking",
        defaultValue = true
    )

    var systemProxy by store.boolean(
        key = "system_proxy",
        defaultValue = true
    )

    var allowBypass by store.boolean(
        key = "allow_bypass",
        defaultValue = true
    )

    var allowIpv6 by store.boolean(
        key = "allow_ipv6",
        defaultValue = false
    )

    var tunStackMode by store.string(
        key = "tun_stack_mode",
        defaultValue = "mips"
    )

    var dynamicNotification by store.boolean(
        key = "dynamic_notification",
        defaultValue = true
    )

    // Airplane mode recovery via Shizuku (no root required).
    // When all tested proxies keep failing, toggle airplane mode to reset the cellular radio.
    var airplaneRecoveryEnabled by store.boolean(
        key = "airplane_recovery_enabled",
        defaultValue = false
    )

    /** Consecutive failure window in seconds before triggering recovery (1–20). */
    var airplaneRecoveryFailureThreshold by store.int(
        key = "airplane_recovery_failure_threshold",
        defaultValue = 5
    )

    private var airplaneRecoveryCooldownRaw by store.int(
        key = "airplane_recovery_cooldown_sec",
        defaultValue = 30
    )

    /**
     * Base cooldown after a toggle attempt, in seconds ([COOLDOWN_MIN_SEC]..[COOLDOWN_MAX_SEC]).
     * Values saved by older builds (30–900 s) are clamped so they always match an option
     * in the settings list.
     */
    var airplaneRecoveryCooldownSec: Int
        get() = airplaneRecoveryCooldownRaw.coerceIn(COOLDOWN_MIN_SEC, COOLDOWN_MAX_SEC)
        set(value) {
            airplaneRecoveryCooldownRaw = value.coerceIn(COOLDOWN_MIN_SEC, COOLDOWN_MAX_SEC)
        }

    companion object {
        /** Cooldown bounds in seconds. Keep in sync with the options in NetworkSettingsDesign. */
        const val COOLDOWN_MIN_SEC = 1
        const val COOLDOWN_MAX_SEC = 60
    }
}
