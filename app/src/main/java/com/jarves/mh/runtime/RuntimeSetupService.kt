package com.jarves.mh.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.jarves.mh.MainActivity
import com.jarves.mh.R
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.model.DevStack
import java.io.File
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class RuntimeSetupStatus { IDLE, RUNNING, COMPLETE, ERROR, CANCELLED }

data class RuntimeSetupSnapshot(
    val status: RuntimeSetupStatus = RuntimeSetupStatus.IDLE,
    val message: String = "Preparing your private coding workspace",
    val progress: Float = 0f,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val indeterminate: Boolean = false,
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null,
    val offline: Boolean = false,
)

object RuntimeSetupController {
    private val mutableSnapshot = MutableStateFlow(RuntimeSetupSnapshot())
    val snapshot: StateFlow<RuntimeSetupSnapshot> = mutableSnapshot.asStateFlow()

    @Synchronized
    fun restore(context: Context) {
        val file = stateFile(context)
        if (!file.isFile) return
        runCatching {
            val json = JSONObject(file.readText())
            val logsJson = json.optJSONArray("logs") ?: JSONArray()
            // A heartbeat left behind by an interrupted download can be a few
            // ticks fresher than the full snapshot; overlay its counters.
            val heartbeat = runCatching { JSONObject(heartbeatFile(context).readText()) }.getOrNull()
            val status = runCatching { RuntimeSetupStatus.valueOf(json.optString("status")) }
                .getOrDefault(RuntimeSetupStatus.IDLE)
            val fresher = heartbeat != null && status == RuntimeSetupStatus.RUNNING
            mutableSnapshot.value = RuntimeSetupSnapshot(
                status = status,
                message = json.optString("message", "Preparing your private coding workspace"),
                progress = if (fresher) heartbeat.optDouble("progress", json.optDouble("progress", 0.0)).toFloat()
                else json.optDouble("progress", 0.0).toFloat(),
                downloadedBytes = (if (fresher) heartbeat.optLongOrNull("downloadedBytes") else null)
                    ?: json.optLongOrNull("downloadedBytes"),
                totalBytes = (if (fresher) heartbeat.optLongOrNull("totalBytes") else null)
                    ?: json.optLongOrNull("totalBytes"),
                indeterminate = if (fresher) heartbeat.optBoolean("indeterminate", json.optBoolean("indeterminate"))
                else json.optBoolean("indeterminate"),
                logs = logsJson.let { logs -> (0 until logs.length()).map { logs.optString(it) } }.takeLast(MAX_LOG_LINES),
                errorMessage = json.optString("errorMessage").takeIf(String::isNotBlank),
                offline = json.optBoolean("offline"),
            )
        }
    }

    @Synchronized
    fun begin(context: Context) {
        val previous = mutableSnapshot.value.logs
        val logs = (previous + "— Resuming Mobile Harness setup —").takeLast(MAX_LOG_LINES)
        set(context, RuntimeSetupSnapshot(status = RuntimeSetupStatus.RUNNING, progress = 0.01f, logs = logs))
    }

    @Synchronized
    fun update(context: Context, event: RuntimeInstallProgress) {
        val current = mutableSnapshot.value
        val line = event.terminalLine ?: when {
            event.downloadedBytes != null && event.totalBytes != null ->
                "Downloading: %.1f / %.1f MB".format(event.downloadedBytes / MB, event.totalBytes / MB)
            event.event == RuntimeInstallEvent.STAGE -> "• ${event.message}"
            else -> null
        }
        val sanitizedLine = line?.takeIf(String::isNotBlank)?.let(::sanitize)
        val isDownloadUpdate = event.downloadedBytes != null && event.totalBytes != null
        val replacingDownloadRow = isDownloadUpdate && current.logs.lastOrNull()?.startsWith(DOWNLOAD_PREFIX) == true
        val logs = when {
            sanitizedLine == null -> current.logs
            replacingDownloadRow ->
                (current.logs.dropLast(1) + sanitizedLine).takeLast(MAX_LOG_LINES)
            else -> (current.logs + sanitizedLine).takeLast(MAX_LOG_LINES)
        }
        // Persist the beginning of a transfer as a checkpoint, while the JSON snapshot
        // continuously replaces that row with the newest byte count.
        if (!isDownloadUpdate || !replacingDownloadRow) {
            appendLog(context, sanitizedLine)
        }
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.RUNNING,
                // Raw command output changes length constantly. Keep the headline stage
                // stable and show changing lines only in the live terminal panel.
                message = when (event.event) {
                    RuntimeInstallEvent.STAGE, RuntimeInstallEvent.DOWNLOAD -> event.message
                    else -> current.message
                },
                progress = maxOf(current.progress, event.fraction.coerceIn(0f, 1f)),
                downloadedBytes = event.downloadedBytes,
                totalBytes = event.totalBytes,
                indeterminate = event.indeterminate,
                logs = logs,
                errorMessage = null,
                offline = false,
            ),
            // ISSUE-035: a download tick that only replaces the live "Downloading:"
            // row must not rewrite the whole (up to 400-line) snapshot to flash.
            // It updates the in-memory state plus a tiny throttled heartbeat file;
            // the full snapshot is rewritten only when the log content changes.
            heartbeatOnly = replacingDownloadRow,
        )
    }

    @Synchronized
    fun complete(context: Context) {
        val current = mutableSnapshot.value
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.COMPLETE,
                message = "Mobile Harness is ready",
                progress = 1f,
                indeterminate = false,
                downloadedBytes = null,
                totalBytes = null,
                logs = (current.logs + "✓ Setup completed successfully").takeLast(MAX_LOG_LINES),
            ),
        )
        // The full setup log exists only for diagnostics; drop it once setup succeeded
        // so raw installer output does not linger on disk (ISSUE-004/023). It is kept
        // after a failure, where it is actually needed for troubleshooting.
        runCatching { logFile(context).delete() }
    }

    @Synchronized
    fun fail(context: Context, error: Throwable) {
        val causes = generateSequence(error as Throwable?) { it.cause }.toList()
        val offline = causes.any {
            it is UnknownHostException ||
                it.message.orEmpty().contains("unable to resolve host", true) ||
                it.message.orEmpty().contains("no address associated with hostname", true)
        }
        val interruptedDpkg = causes.any {
            it.message.orEmpty().contains("dpkg was interrupted", true) ||
                it.message.orEmpty().contains("dpkg --configure -a", true)
        }
        val friendly = when {
            offline -> "Connect to Wi-Fi or mobile data, then resume setup."
            interruptedDpkg -> "Android interrupted Linux setup. Mobile Harness will repair it when you try again."
            else -> error.message.orEmpty().lineSequence().lastOrNull { it.isNotBlank() }
                ?.take(220)
                ?: "Mobile Harness could not finish setup."
        }
        val current = mutableSnapshot.value
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.ERROR,
                errorMessage = friendly,
                offline = offline,
                indeterminate = false,
                logs = (current.logs + "✕ $friendly").takeLast(MAX_LOG_LINES),
            ),
        )
    }

    @Synchronized
    fun cancelled(context: Context) {
        val current = mutableSnapshot.value
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.CANCELLED,
                message = "Setup paused",
                indeterminate = false,
                logs = (current.logs + "• Setup paused safely").takeLast(MAX_LOG_LINES),
            ),
        )
    }

    fun fullLog(context: Context): String = logFile(context).takeIf(File::isFile)?.readText().orEmpty()

    private fun set(context: Context, value: RuntimeSetupSnapshot, heartbeatOnly: Boolean = false) {
        mutableSnapshot.value = value
        if (heartbeatOnly) {
            persistHeartbeat(context, value)
        } else {
            persistFull(context, value)
        }
    }

    private fun persistFull(context: Context, value: RuntimeSetupSnapshot) {
        val json = JSONObject()
            .put("status", value.status.name)
            .put("message", value.message)
            .put("progress", value.progress)
            .put("indeterminate", value.indeterminate)
            .put("offline", value.offline)
            .put("logs", JSONArray(value.logs))
        value.downloadedBytes?.let { json.put("downloadedBytes", it) }
        value.totalBytes?.let { json.put("totalBytes", it) }
        value.errorMessage?.let { json.put("errorMessage", it) }
        val target = stateFile(context)
        val staged = File(target.parentFile, "${target.name}.tmp")
        staged.writeText(json.toString())
        target.delete()
        staged.renameTo(target)
        heartbeatFile(context).delete()
    }

    /**
     * Tiny heartbeat file for download ticks (ISSUE-035): a handful of fields,
     * throttled to at most one write per [HEARTBEAT_MIN_INTERVAL_MS]. It only
     * ever exists while a download is running; a terminal state removes it.
     */
    private fun persistHeartbeat(context: Context, value: RuntimeSetupSnapshot) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastHeartbeatAt < HEARTBEAT_MIN_INTERVAL_MS) return
        lastHeartbeatAt = now
        runCatching {
            val json = JSONObject()
                .put("status", value.status.name)
                .put("progress", value.progress)
                .put("indeterminate", value.indeterminate)
            value.downloadedBytes?.let { json.put("downloadedBytes", it) }
            value.totalBytes?.let { json.put("totalBytes", it) }
            heartbeatFile(context).writeText(json.toString())
        }
    }

    private fun appendLog(context: Context, line: String?) {
        if (line.isNullOrBlank()) return
        val file = logFile(context)
        file.parentFile?.mkdirs()
        file.appendText(sanitize(line) + "\n")
        if (file.length() > MAX_LOG_BYTES) {
            val tail = file.readText().takeLast(MAX_LOG_BYTES.toInt())
            file.writeText(tail.substringAfter('\n', tail))
        }
    }

    private fun sanitize(line: String): String = line.filter { it == '\t' || it.code >= 32 }.take(500)
    private fun stateFile(context: Context) = File(context.filesDir, "setup/runtime-setup-state.json").apply { parentFile?.mkdirs() }
    private fun heartbeatFile(context: Context) = File(context.filesDir, "setup/runtime-setup-progress.json")
    private fun logFile(context: Context) = File(context.filesDir, "setup/runtime-setup.log")
    private fun JSONObject.optLongOrNull(name: String): Long? = if (has(name) && !isNull(name)) optLong(name) else null

    @Volatile private var lastHeartbeatAt = 0L
    private const val MAX_LOG_LINES = 400
    private const val MAX_LOG_BYTES = 1_000_000L
    private const val MB = 1_048_576.0
    private const val DOWNLOAD_PREFIX = "Downloading:"

    /** Heartbeat writes are throttled to at most ~2 per second (ISSUE-035). */
    private const val HEARTBEAT_MIN_INTERVAL_MS = 500L
}

class RuntimeSetupService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var installJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
        RuntimeSetupController.restore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            installJob?.cancel(CancellationException("Stopped by user"))
            RuntimeSetupController.cancelled(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, setupNotification(RuntimeSetupController.snapshot.value))
        acquireWakeLock()
        if (installJob?.isActive != true) {
            val stacks = intent?.getStringExtra(EXTRA_STACKS).orEmpty().split(',')
                .mapNotNull { name -> runCatching { DevStack.valueOf(name) }.getOrNull() }
                .toSet()
            val agent = runCatching {
                com.jarves.mh.model.AgentKind.valueOf(intent?.getStringExtra(EXTRA_AGENT).orEmpty())
            }.getOrDefault(com.jarves.mh.model.AgentKind.CLAUDE_CODE)
            RuntimeSetupController.begin(this)
            installJob = scope.launch {
                try {
                    RuntimeInstaller(this@RuntimeSetupService).ensureInstalled(stacks, agent) { progress ->
                        RuntimeSetupController.update(this@RuntimeSetupService, progress)
                        updateNotification(progress.event == RuntimeInstallEvent.COMMAND_COMPLETED)
                    }
                    AppPreferences(this@RuntimeSetupService).runtimeSetupComplete = true
                    RuntimeSetupController.complete(this@RuntimeSetupService)
                    showFinishedNotification(success = true)
                } catch (_: CancellationException) {
                    RuntimeSetupController.cancelled(this@RuntimeSetupService)
                } catch (error: Throwable) {
                    RuntimeSetupController.fail(this@RuntimeSetupService, error)
                    showFinishedNotification(success = false)
                } finally {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun updateNotification(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationAt < NOTIFICATION_THROTTLE_MS) return
        lastNotificationAt = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, setupNotification(RuntimeSetupController.snapshot.value))
    }

    private fun setupNotification(state: RuntimeSetupSnapshot): android.app.Notification {
        val latest = state.logs.lastOrNull().orEmpty().take(180)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Setting up Mobile Harness")
            .setContentText(latest.ifBlank { state.message })
            .setStyle(NotificationCompat.BigTextStyle().bigText(latest.ifBlank { state.message }))
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (state.indeterminate) builder.setProgress(0, 0, true)
        else builder.setProgress(100, (state.progress * 100).toInt().coerceIn(0, 100), false)
        builder.addAction(
            0,
            "Stop setup",
            PendingIntent.getService(
                this,
                102,
                Intent(this, RuntimeSetupService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return builder.build()
    }

    private fun showFinishedNotification(success: Boolean) {
        val state = RuntimeSetupController.snapshot.value
        val title = if (success) "Mobile Harness is ready" else "Setup needs attention"
        val detail = if (success) "Your private coding workspace is ready." else state.errorMessage.orEmpty()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        101,
        Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.jarves.mh:runtime-setup")
            .apply { acquire(MAX_WAKE_LOCK_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.jarves.mh.START_SETUP"
        const val ACTION_STOP = "com.jarves.mh.STOP_SETUP"
        const val EXTRA_STACKS = "selected_stacks"
        const val EXTRA_AGENT = "selected_agent"
        private const val CHANNEL_ID = "runtime-setup"
        private const val NOTIFICATION_ID = 51
        private const val RESULT_NOTIFICATION_ID = 52
        private const val NOTIFICATION_THROTTLE_MS = 750L
        private const val MAX_WAKE_LOCK_MS = 45 * 60 * 1_000L

        fun ensureNotificationChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mobile Harness setup", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows download and installation progress for the private coding environment"
                },
            )
        }
    }
}
