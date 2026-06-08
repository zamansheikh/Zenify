package com.pn.zenify.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.pn.zenify.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Single entry point for everything Shizuku. Tracks availability + permission
 * as a [StateFlow] the UI can observe, and lazily binds the privileged
 * [UserService] the first time hibernation is requested.
 */
object ShizukuManager {

    private const val TAG = "ZenifyShizuku"
    private const val PERMISSION_REQUEST_CODE = 4711

    enum class State {
        /** Shizuku app/binder not present or not running. */
        UNAVAILABLE,
        /** Running, but the user hasn't granted Zenify access yet. */
        PERMISSION_REQUIRED,
        /** Granted and ready to hibernate. */
        READY,
    }

    private val _state = MutableStateFlow(State.UNAVAILABLE)
    val state: StateFlow<State> = _state

    @Volatile private var userService: IUserService? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshState() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        userService = null
        refreshState()
    }
    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refreshState() }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, UserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("hibernate")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    fun init() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refreshState()
    }

    fun refreshState() {
        _state.value = computeState()
    }

    private fun computeState(): State {
        if (!Shizuku.pingBinder()) return State.UNAVAILABLE
        return if (hasPermission()) State.READY else State.PERMISSION_REQUIRED
    }

    private fun hasPermission(): Boolean {
        return try {
            if (Shizuku.isPreV11()) false
            else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    /** Triggers Shizuku's grant dialog. Result arrives via [permissionListener]. */
    fun requestPermission() {
        if (!Shizuku.pingBinder()) {
            refreshState()
            return
        }
        if (hasPermission()) {
            refreshState()
            return
        }
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission failed", t)
        }
    }

    /** Whether we believe we can hibernate right now. */
    fun isReady(): Boolean = computeState() == State.READY

    private suspend fun ensureUserService(): IUserService? {
        userService?.let { return it }
        if (!isReady()) return null

        return suspendCancellableCoroutine { cont ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val svc = if (binder != null && binder.pingBinder()) {
                        IUserService.Stub.asInterface(binder)
                    } else null
                    userService = svc
                    if (cont.isActive) cont.resume(svc)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    userService = null
                }
            }
            try {
                Shizuku.bindUserService(userServiceArgs, connection)
            } catch (t: Throwable) {
                Log.e(TAG, "bindUserService failed", t)
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /**
     * Live process importance per package (ActivityManager importance values).
     * Empty when Shizuku isn't ready. Lets us label apps Greenify-style.
     */
    suspend fun runningImportance(): Map<String, Int> {
        val svc = ensureUserService() ?: return emptyMap()
        return try {
            val dump = svc.dumpProcesses()
            if (dump.isBlank() || dump.startsWith("error", ignoreCase = true)) return emptyMap()
            dump.lineSequence()
                .mapNotNull { line ->
                    val idx = line.lastIndexOf('=')
                    if (idx <= 0) return@mapNotNull null
                    val pkg = line.substring(0, idx)
                    val imp = line.substring(idx + 1).trim().toIntOrNull() ?: return@mapNotNull null
                    pkg to imp
                }
                .toMap()
        } catch (t: Throwable) {
            Log.w(TAG, "runningImportance failed", t)
            emptyMap()
        }
    }

    /**
     * Hibernate one package. Returns true on success.
     */
    suspend fun hibernate(packageName: String): Boolean {
        val svc = ensureUserService() ?: return false
        return try {
            val result = svc.forceStop(packageName)
            val ok = result.isBlank() || !result.startsWith("error", ignoreCase = true)
            if (!ok) Log.w(TAG, "forceStop($packageName) -> $result")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "hibernate($packageName) failed", t)
            false
        }
    }
}
