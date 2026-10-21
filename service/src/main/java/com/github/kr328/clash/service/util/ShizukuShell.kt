package com.github.kr328.clash.service.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.currentProcessName
import com.github.kr328.clash.common.log.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thin helper to run shell commands through Shizuku (shell UID) without root.
 * Also exposes install / permission status for the UI.
 */
object ShizukuShell {
    /** Shizuku+ (thejaustin/ShizukuPlus) manager package. Preferred. */
    const val SHIZUKU_PLUS_PACKAGE = "af.shizuku.plus.api"

    /** Stock Shizuku / Shizuku+ Compat Hub / Shizuku+ drop-in build. Fallback only. */
    const val SHIZUKU_STOCK_PACKAGE = "moe.shizuku.privileged.api"

    private const val SHIZUKU_PLUS_RELEASES = "https://github.com/thejaustin/ShizukuPlus/releases"

    /** Lookup order: Shizuku+ first, then stock Shizuku. */
    private val MANAGER_PACKAGES = arrayOf(SHIZUKU_PLUS_PACKAGE, SHIZUKU_STOCK_PACKAGE)
    const val PERMISSION_REQUEST_CODE = 0x534B // 'SK'

    enum class Status {
        /** Shizuku app is not installed. */
        NotInstalled,
        /** Installed but the service is not running. */
        NotRunning,
        /** Running, but this app has not been authorized. */
        Denied,
        /** Running and authorized — airplane toggle can proceed. */
        Granted,
    }

    private const val BINDER_RETRY_INTERVAL_MS = 20_000L
    private const val BINDER_MAX_ATTEMPTS = 5

    private val binderAttempts = AtomicInteger(0)
    @Volatile private var lastBinderRequest = 0L

    /** ShizukuProvider lives in the main process; that is the only process Shizuku talks to. */
    private val isProviderProcess: Boolean by lazy {
        val app = Global.application
        app.currentProcessName == app.packageName
    }

    /**
     * Shizuku hands its binder only to [ShizukuProvider], which runs in the main process. The
     * airplane watchdog runs in `:background`, where [Shizuku.pingBinder] stays false unless the
     * binder is shared across processes. That made the settings screen (main process) report
     * "Authorized" while the watchdog saw Shizuku as unavailable.
     *
     * Requires `ShizukuProvider.enableMultiProcessSupport()` to be called in every process
     * (see MainApplication). Cheap and safe to call repeatedly: it is a no-op in the provider
     * process or once the binder is alive, and retries are throttled and capped because each
     * request registers a broadcast receiver (which also catches later binder broadcasts).
     */
    fun ensureBinder(context: Context = Global.application) {
        if (isProviderProcess) return

        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            binderAttempts.set(0)
            return
        }

        if (binderAttempts.get() >= BINDER_MAX_ATTEMPTS) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBinderRequest < BINDER_RETRY_INTERVAL_MS) return

        lastBinderRequest = now
        binderAttempts.incrementAndGet()
        try {
            ShizukuProvider.requestBinderForNonProviderProcess(context)
        } catch (e: Throwable) {
            Log.w("Shizuku binder request failed: ${e.message}")
        }
    }

    fun status(context: Context = Global.application): Status {
        if (!isInstalled(context)) return Status.NotInstalled
        ensureBinder(context)
        return try {
            if (!Shizuku.pingBinder()) return Status.NotRunning
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Status.Granted
            } else {
                Status.Denied
            }
        } catch (e: Throwable) {
            Status.NotRunning
        }
    }

    fun isReady(context: Context = Global.application): Boolean =
        status(context) == Status.Granted

    /** Package name of the installed Shizuku manager, preferring Shizuku+. Null if none. */
    fun managerPackage(context: Context = Global.application): String? =
        MANAGER_PACKAGES.firstOrNull { isPackageInstalled(context, it) }

    fun isInstalled(context: Context = Global.application): Boolean =
        managerPackage(context) != null

    private fun isPackageInstalled(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Launch the Shizuku+ (or stock Shizuku) manager app, or open the Shizuku+ download page. */
    fun openShizukuApp(context: Context): Boolean {
        val pm = context.packageManager
        val pkg = managerPackage(context)
        val launch = pkg?.let { pm.getLaunchIntentForPackage(it) }
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            return true
        }
        // Shizuku+ is distributed via GitHub releases (not Play Store).
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SHIZUKU_PLUS_RELEASES))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission() {
        try {
            if (Shizuku.isPreV11()) return
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Throwable) {
            Log.w("Shizuku requestPermission failed: ${e.message}")
        }
    }

    /**
     * Shizuku API 13+ made `Shizuku.newProcess()` private (it is deprecated in
     * favour of UserService), so it can no longer be called directly. It is still
     * present in the library, so resolve it once via reflection. The keep rule in
     * service/consumer-rules.pro stops R8 from stripping it in release builds.
     */
    private val newProcessMethod: Method by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
    }

    private fun newShizukuProcess(cmd: Array<String>): Process {
        try {
            return newProcessMethod.invoke(null, cmd, null, null) as Process
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    fun exec(vararg commands: String): Boolean {
        if (!isReady()) return false
        return try {
            val script = commands.joinToString(" ; ")
            val process = newShizukuProcess(arrayOf("sh", "-c", script))
            val code = process.waitFor()
            if (code != 0) {
                val err = process.errorStream.bufferedReader().readText()
                Log.w("ShizukuShell exit=$code cmd=$script err=$err")
            }
            code == 0
        } catch (e: Throwable) {
            Log.w("ShizukuShell failed: ${e.message}", e)
            false
        }
    }

    fun isAirplaneModeOn(): Boolean {
        return try {
            Settings.Global.getInt(
                Global.application.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0,
            ) != 0
        } catch (e: Exception) {
            false
        }
    }

    fun setAirplaneMode(enabled: Boolean): Boolean {
        val value = if (enabled) 1 else 0
        val state = if (enabled) "true" else "false"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val action = if (enabled) "enable" else "disable"
            if (exec("cmd connectivity airplane-mode $action")) {
                Thread.sleep(250)
                if (isAirplaneModeOn() == enabled) return true
            }
        }

        val ok = exec(
            "settings put global airplane_mode_on $value",
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $state",
        )
        Thread.sleep(250)
        return ok && isAirplaneModeOn() == enabled
    }
}
