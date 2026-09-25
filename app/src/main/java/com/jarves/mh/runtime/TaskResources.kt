package com.jarves.mh.runtime

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped partial wake locks (ISSUE-013).
 *
 * Previously the notification service held one flat 90-minute lock whether or
 * not anything was running, while the real work lived in the bridges. The lock
 * now lives exactly as long as the coding session that needs it: bridges
 * acquire on session start and release on every exit path. A hard timeout
 * remains as a leak safety net, not as the expected lifetime.
 */
internal object TaskWakeLocks {
    private const val MAX_SESSION_MS = 2 * 60 * 60 * 1_000L
    private const val TAG_PREFIX = "com.jarves.mh:"

    private val locks = ConcurrentHashMap<String, PowerManager.WakeLock>()

    fun acquire(context: Context, key: String) {
        synchronized(this) {
            if (locks.containsKey(key)) return
            runCatching {
                locks[key] = context.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG_PREFIX + key)
                    .apply {
                        setReferenceCounted(false)
                        acquire(MAX_SESSION_MS)
                    }
            }.onFailure { error ->
                AppLog.w("TaskWakeLocks", "Could not acquire wake lock for $key", error)
                locks.remove(key)
            }
        }
    }

    /** Idempotent; safe to call on every exit path. */
    fun release(key: String) {
        synchronized(this) {
            locks.remove(key)?.let { lock ->
                runCatching { if (lock.isHeld) lock.release() }
            }
        }
    }

    fun isHeld(key: String): Boolean = locks.containsKey(key)
}

/**
 * Thermal-throttling visibility (ISSUE-013, API 29+). Long native builds on
 * phones throttle silently; the monitor surfaces sustained thermal escalation
 * to the running session so users understand why an agent slows down.
 */
internal object ThermalMonitor {
    @Volatile private var power: PowerManager? = null
    @Volatile private var listener: PowerManager.OnThermalStatusChangedListener? = null
    @Volatile private var lastReported = PowerManager.THERMAL_STATUS_NONE
    private val lock = Any()

    /**
     * Registers the monitor and reports status changes at MODERATE or above
     * via [onThermal]. Returns false when the API level or the platform does
     * not support it.
     */
    fun start(context: Context, onThermal: (status: Int) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        synchronized(lock) {
            stopLocked()
            val manager = context.getSystemService(PowerManager::class.java) ?: return false
            val registered = PowerManager.OnThermalStatusChangedListener { status ->
                if (status == PowerManager.THERMAL_STATUS_NONE) {
                    lastReported = status
                } else if (status >= PowerManager.THERMAL_STATUS_MODERATE && status != lastReported) {
                    lastReported = status
                    onThermal(status)
                }
            }
            val ok = registerThermalListener(manager, context.mainExecutor, registered)
            return if (ok) {
                power = manager
                listener = registered
                true
            } else {
                AppLog.w("ThermalMonitor", "Could not register the thermal status listener")
                false
            }
        }
    }

    /** Idempotent; safe to call on every exit path. */
    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        listener?.let { registered ->
            power?.let { manager ->
                unregisterThermalListener(manager, registered)
            }
        }
        listener = null
        power = null
        lastReported = PowerManager.THERMAL_STATUS_NONE
    }

    /**
     * API 29-35 expose addOnThermalStatusChangedListener; SDK 36 renamed the
     * pair to add/removeThermalStatusListener. The listener interface itself
     * is stable across all of them, so reflective registration by either name
     * works on every runtime without compile-time linkage problems.
     */
    private fun registerThermalListener(
        manager: PowerManager,
        executor: java.util.concurrent.Executor,
        listener: PowerManager.OnThermalStatusChangedListener,
    ): Boolean {
        val add = manager.javaClass.methods.firstOrNull { method ->
            method.parameterCount == 2 &&
                method.parameterTypes[1] == PowerManager.OnThermalStatusChangedListener::class.java &&
                (method.name == "addOnThermalStatusChangedListener" || method.name == "addThermalStatusListener")
        } ?: return false
        return runCatching { add.invoke(manager, executor, listener) }.isSuccess
    }

    private fun unregisterThermalListener(
        manager: PowerManager,
        listener: PowerManager.OnThermalStatusChangedListener,
    ) {
        val remove = manager.javaClass.methods.firstOrNull { method ->
            method.parameterCount == 1 &&
                method.parameterTypes[0] == PowerManager.OnThermalStatusChangedListener::class.java &&
                (method.name == "removeOnThermalStatusChangedListener" || method.name == "removeThermalStatusListener")
        } ?: return
        runCatching { remove.invoke(manager, listener) }
    }
}
