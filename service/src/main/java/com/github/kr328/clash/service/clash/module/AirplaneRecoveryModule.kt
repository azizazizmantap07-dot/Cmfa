package com.github.kr328.clash.service.clash.module

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ProxySort
import com.github.kr328.clash.service.R
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.ShizukuShell
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Watches proxy health via Clash URL tests. When every outbound stays failed for a sustained
 * period, toggles airplane mode through Shizuku to reset the cellular radio and posts a
 * user-visible notification.
 */
class AirplaneRecoveryModule(service: Service) : Module<Unit>(service) {

    private val store = ServiceStore(service)
    private val toggling = AtomicBoolean(false)
    private val recoveryAttempts = AtomicInteger(0)
    private val notifications = NotificationManagerCompat.from(service)

    @Volatile private var failingSince = 0L
    @Volatile private var startupGraceUntil = 0L
    @Volatile private var cooldownUntil = 0L

    override suspend fun run() {
        ensureChannel()
        startupGraceUntil = SystemClock.elapsedRealtime() + STARTUP_GRACE_MS
        Log.i("AirplaneRecovery: module started")

        try {
            while (true) {
                if (!store.airplaneRecoveryEnabled) {
                    failingSince = 0L
                    delay(WATCH_INTERVAL_MS)
                    continue
                }

                // The Shizuku binder is delivered to the main process only; make sure this
                // (:background) process has it before a recovery is ever needed.
                ShizukuShell.ensureBinder()

                if (isDeviceIdle()) {
                    failingSince = 0L
                    delay(WATCH_INTERVAL_MS)
                    continue
                }

                evaluateOnce()
                delay(WATCH_INTERVAL_MS)
            }
        } finally {
            Log.i("AirplaneRecovery: module stopped")
        }
    }

    private suspend fun evaluateOnce() {
        val now = SystemClock.elapsedRealtime()
        if (now < cooldownUntil) return
        if (toggling.get()) return

        runCatching { Clash.healthCheckAll() }
        delay(HEALTH_CHECK_SETTLE_MS)

        if (hasAnyHealthyProxy()) {
            if (failingSince != 0L || recoveryAttempts.get() > 0) {
                Log.i("AirplaneRecovery: proxies healthy again, backoff reset")
            }
            failingSince = 0L
            startupGraceUntil = 0L
            recoveryAttempts.set(0)
            return
        }

        val t = SystemClock.elapsedRealtime()
        if (t < startupGraceUntil) {
            failingSince = 0L
            return
        }

        if (failingSince == 0L) {
            failingSince = t
            Log.i(
                "AirplaneRecovery: all tests failing, recovery in " +
                    "${debounceMs() / 1000}s if it persists"
            )
            return
        }

        val failingFor = t - failingSince
        if (failingFor < debounceMs()) return

        failingSince = 0L
        if (ShizukuShell.isAirplaneModeOn()) return

        if (!toggling.compareAndSet(false, true)) return
        try {
            runRecovery("urltest_fail_${failingFor / 1000}s")
        } finally {
            toggling.set(false)
        }
    }

    private fun hasAnyHealthyProxy(): Boolean {
        return try {
            val names = Clash.queryGroupNames(false)
            for (name in names) {
                val group = Clash.queryGroup(name, ProxySort.Default)
                for (proxy in group.proxies) {
                    val d = proxy.delay
                    if (d > 0 && d < FAILURE_DELAY_MS) return true
                }
            }
            false
        } catch (e: Exception) {
            Log.w("AirplaneRecovery: query groups failed: ${e.message}")
            false
        }
    }

    private fun debounceMs(): Long {
        val threshold = store.airplaneRecoveryFailureThreshold.coerceIn(1, 20)
        return (threshold - 1).coerceAtLeast(1) * 1000L
    }

    /**
     * Cooldown in seconds. The configured value is the lower bound; every consecutive failed
     * attempt doubles it (up to [MAX_BACKOFF_STEPS] times) but never beyond
     * [ServiceStore.COOLDOWN_MAX_SEC].
     */
    private fun cooldownSec(): Long {
        val base = store.airplaneRecoveryCooldownSec.toLong()
        val steps = (recoveryAttempts.get() - 1).coerceIn(0, MAX_BACKOFF_STEPS)
        return minOf(base shl steps, ServiceStore.COOLDOWN_MAX_SEC.toLong())
    }

    private fun enterCooldown() {
        failingSince = 0L
        val seconds = cooldownSec()
        cooldownUntil = SystemClock.elapsedRealtime() + seconds * 1000L
        Log.i("AirplaneRecovery: cooldown ${seconds}s")
    }

    private suspend fun runRecovery(reason: String) {
        if (!ShizukuShell.isReady()) {
            Log.w(
                "AirplaneRecovery: Shizuku not ready (${ShizukuShell.status()}), " +
                    "skip toggle ($reason)"
            )
            notifyShizukuMissing()
            recoveryAttempts.incrementAndGet()
            enterCooldown()
            return
        }

        runCatching { Clash.healthCheckAll() }
        delay(HEALTH_CHECK_SETTLE_MS)

        if (hasAnyHealthyProxy()) {
            Log.i("AirplaneRecovery: recovered before toggle, skip ($reason)")
            failingSince = 0L
            return
        }

        if (ShizukuShell.isAirplaneModeOn()) {
            Log.i("AirplaneRecovery: airplane already on, skip")
            enterCooldown()
            return
        }

        val attempt = recoveryAttempts.incrementAndGet()
        Log.w("AirplaneRecovery: executing airplane toggle: $reason (attempt $attempt)")
        notifyRecoveryStarted(attempt, reason)

        try {
            executeAirplaneToggle()
            notifyRecoveryFinished(attempt)
        } finally {
            enterCooldown()
        }
    }

    private suspend fun executeAirplaneToggle() {
        if (!ShizukuShell.setAirplaneMode(true)) {
            Log.e("AirplaneRecovery: failed to enable airplane mode")
            notifyRecoveryFailed()
            return
        }
        try {
            delay(AIRPLANE_OFF_DELAY_MS)
        } finally {
            leaveAirplaneMode()
        }
        Log.i("AirplaneRecovery: airplane toggle finished")
    }

    private suspend fun leaveAirplaneMode() {
        repeat(3) {
            if (!ShizukuShell.isAirplaneModeOn()) return
            if (ShizukuShell.setAirplaneMode(false)) return
            delay(500)
        }
        if (ShizukuShell.isAirplaneModeOn()) {
            Log.e("AirplaneRecovery: FAILED to turn airplane mode off, manual action needed")
            notifyStuckInAirplane()
        }
    }

    private fun isDeviceIdle(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            service.getSystemService<PowerManager>()?.isDeviceIdleMode == true
        } catch (e: Exception) {
            false
        }
    }

    // region notifications

    private fun ensureChannel() {
        notifications.createNotificationChannel(
            NotificationChannelCompat.Builder(
                CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_DEFAULT,
            )
                .setName(service.getText(R.string.airplane_recovery_channel))
                .setDescription(service.getString(R.string.airplane_recovery_channel_desc))
                .build()
        )
    }

    private fun baseBuilder(): NotificationCompat.Builder {
        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_service)
            .setColor(service.getColorCompat(R.color.color_clash))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    service,
                    R.id.nf_airplane_recovery,
                    Intent().setComponent(Components.MAIN_ACTIVITY)
                        .setFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        ),
                    pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
                )
            )
    }

    private fun notify(id: Int, title: CharSequence, text: CharSequence) {
        try {
            notifications.notify(
                id,
                baseBuilder()
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .build(),
            )
        } catch (e: SecurityException) {
            Log.w("AirplaneRecovery: notify blocked: ${e.message}")
        }
    }

    private fun notifyRecoveryStarted(attempt: Int, reason: String) {
        notify(
            R.id.nf_airplane_recovery,
            service.getText(R.string.airplane_recovery_notify_title),
            service.getString(R.string.airplane_recovery_notify_started, attempt, reason),
        )
    }

    private fun notifyRecoveryFinished(attempt: Int) {
        notify(
            R.id.nf_airplane_recovery,
            service.getText(R.string.airplane_recovery_notify_title),
            service.getString(R.string.airplane_recovery_notify_finished, attempt),
        )
    }

    private fun notifyRecoveryFailed() {
        notify(
            R.id.nf_airplane_recovery,
            service.getText(R.string.airplane_recovery_notify_title),
            service.getText(R.string.airplane_recovery_notify_failed),
        )
    }

    private fun notifyStuckInAirplane() {
        notify(
            R.id.nf_airplane_recovery,
            service.getText(R.string.airplane_recovery_notify_title),
            service.getText(R.string.airplane_recovery_notify_stuck),
        )
    }

    private fun notifyShizukuMissing() {
        notify(
            R.id.nf_airplane_recovery,
            service.getText(R.string.airplane_recovery_notify_title),
            service.getText(R.string.airplane_recovery_notify_shizuku),
        )
    }

    // endregion

    companion object {
        private const val CHANNEL_ID = "airplane_recovery_channel"
        private const val FAILURE_DELAY_MS = 10_000
        private const val STARTUP_GRACE_MS = 25_000L
        private const val WATCH_INTERVAL_MS = 8_000L
        private const val HEALTH_CHECK_SETTLE_MS = 4_000L
        private const val AIRPLANE_OFF_DELAY_MS = 1_750L
        private const val MAX_BACKOFF_STEPS = 4
    }
}
