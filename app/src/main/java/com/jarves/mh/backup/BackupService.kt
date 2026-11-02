package com.jarves.mh.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarves.mh.BuildConfig
import com.jarves.mh.MainActivity
import com.jarves.mh.R
import com.jarves.mh.data.ApiKeyVault
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.ui.AppStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class BackupOperation { BACKUP, RESTORE }

enum class BackupJobStatus { IDLE, RUNNING, COMPLETE, ERROR, CANCELLED }

data class BackupJobState(
    val operation: BackupOperation = BackupOperation.BACKUP,
    val status: BackupJobStatus = BackupJobStatus.IDLE,
    val stage: BackupStage = BackupStage.SCANNING,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val files: Int = 0,
    val summary: BackupSummary? = null,
    val error: String? = null,
) {
    /** 0–100, or null while the total is not known yet (scanning, opening the file). */
    val percent: Int?
        get() = when {
            status == BackupJobStatus.COMPLETE -> 100
            stage == BackupStage.APPLYING -> 99
            stage == BackupStage.SCANNING || totalBytes <= 0 -> if (stage == BackupStage.WRITING) 0 else null
            else -> ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 99)
        }

    val running: Boolean get() = status == BackupJobStatus.RUNNING
}

/** Process-wide state of the one backup or restore that can run at a time. */
object BackupController {
    private val mutableState = MutableStateFlow(BackupJobState())
    val state: StateFlow<BackupJobState> = mutableState.asStateFlow()

    // Kept in memory only, never in the service Intent, so the password is not
    // written into the system's saved intent state.
    @Volatile private var pendingPassword: CharArray? = null

    fun start(context: Context, operation: BackupOperation, uri: Uri, password: CharArray?, includeSettings: Boolean = false) {
        if (mutableState.value.running) return
        pendingPassword = password
        mutableState.value = BackupJobState(operation = operation, status = BackupJobStatus.RUNNING)
        ContextCompat.startForegroundService(
            context,
            Intent(context, BackupService::class.java)
                .setAction(BackupService.ACTION_START)
                .setData(uri)
                .putExtra(BackupService.EXTRA_OPERATION, operation.name)
                .putExtra(BackupService.EXTRA_INCLUDE_SETTINGS, includeSettings),
        )
    }

    fun cancel(context: Context) {
        if (!mutableState.value.running) return
        context.startService(Intent(context, BackupService::class.java).setAction(BackupService.ACTION_STOP))
    }

    /** Clears a finished job's result once the UI has shown it. */
    fun dismiss() {
        if (!mutableState.value.running) mutableState.value = BackupJobState()
    }

    internal fun takePassword(): CharArray? = pendingPassword.also { pendingPassword = null }

    internal fun update(transform: (BackupJobState) -> BackupJobState) {
        mutableState.value = transform(mutableState.value)
    }
}

/**
 * Foreground service that runs a backup or restore with a wake lock, so it keeps going
 * with the screen off or the app in the background, and shows live progress.
 */
class BackupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationAt = 0L
    private var lastPercent: Int? = -1

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Restore ignores this once it starts replacing data; see BackupManager.restore.
            job?.cancel(CancellationException("Stopped by user"))
            return START_NOT_STICKY
        }
        val operation = runCatching { BackupOperation.valueOf(intent?.getStringExtra(EXTRA_OPERATION).orEmpty()) }.getOrNull()
        val uri = intent?.data
        startInForeground()
        if (operation == null || uri == null || job?.isActive == true) {
            if (job?.isActive != true) finish()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        val password = BackupController.takePassword()
        val includeSettings = intent.getBooleanExtra(EXTRA_INCLUDE_SETTINGS, false)
        job = scope.launch {
            val checkCancelled = { coroutineContext.ensureActive() }
            val onProgress = { progress: BackupProgress ->
                BackupController.update {
                    it.copy(stage = progress.stage, doneBytes = progress.doneBytes, totalBytes = progress.totalBytes, files = progress.files)
                }
                updateNotification()
            }
            try {
                val manager = manager()
                val summary = when (operation) {
                    BackupOperation.BACKUP -> {
                        val output = contentResolver.openOutputStream(uri, "wt") ?: error(str(R.string.backup_error_open_output))
                        output.use { manager.createBackup(it, password, checkCancelled, onProgress) }
                    }
                    BackupOperation.RESTORE -> {
                        val input = contentResolver.openInputStream(uri) ?: error(str(R.string.backup_error_open_input))
                        input.use { manager.restore(it, password, includeSettings, checkCancelled, onProgress) }
                    }
                }
                BackupController.update { it.copy(status = BackupJobStatus.COMPLETE, summary = summary) }
                showResult(success = true)
            } catch (_: CancellationException) {
                if (operation == BackupOperation.BACKUP) deletePartial(uri)
                BackupController.update { it.copy(status = BackupJobStatus.CANCELLED) }
            } catch (error: Throwable) {
                if (operation == BackupOperation.BACKUP) deletePartial(uri)
                BackupController.update { it.copy(status = BackupJobStatus.ERROR, error = describe(error)) }
                showResult(success = false)
            } finally {
                password?.fill('\u0000')
                finish()
            }
        }
        return START_NOT_STICKY
    }

    private fun manager(): BackupManager {
        val preferences = AppPreferences(this)
        val vault = ApiKeyVault(this)
        return BackupManager(
            filesDir = filesDir,
            stores = object : BackupStores {
                override fun exportPrefs(): JSONObject = preferences.exportAll()
                override fun importPrefs(prefs: JSONObject) = preferences.importAll(prefs)
                override fun exportSecrets(): JSONObject = vault.exportAll()
                override fun importSecrets(secrets: JSONObject) = vault.importAll(secrets)
            },
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            restoredNameSuffix = str(R.string.backup_restored_suffix),
        )
    }

    private fun describe(error: Throwable): String = when (error) {
        is WrongBackupPasswordException -> str(R.string.backup_error_wrong_password)
        is NewerBackupException -> str(R.string.backup_error_newer)
        is NotEnoughSpaceException -> str(R.string.backup_error_space, android.text.format.Formatter.formatShortFileSize(this, error.requiredBytes))
        is InvalidBackupException -> str(R.string.backup_error_invalid)
        else -> error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
    }

    private fun deletePartial(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
    }

    private fun finish() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground() {
        val notification = progressNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val percent = BackupController.state.value.percent
        val now = SystemClock.elapsedRealtime()
        if (percent == lastPercent || now - lastNotificationAt < NOTIFICATION_THROTTLE_MS) return
        lastPercent = percent
        lastNotificationAt = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, progressNotification())
    }

    private fun progressNotification(): android.app.Notification {
        val state = BackupController.state.value
        val percent = state.percent
        val title = str(if (state.operation == BackupOperation.BACKUP) R.string.backup_notification_backing_up else R.string.backup_notification_restoring)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(percent?.let { "$it%" } ?: str(R.string.backup_stage_preparing))
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent ?: 0, percent == null)
        if (state.stage != BackupStage.APPLYING) {
            builder.addAction(
                0,
                str(R.string.action_cancel),
                PendingIntent.getService(
                    this,
                    202,
                    Intent(this, BackupService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        return builder.build()
    }

    private fun showResult(success: Boolean) {
        val state = BackupController.state.value
        val title = str(
            when {
                !success && state.operation == BackupOperation.BACKUP -> R.string.backup_notification_backup_failed
                !success -> R.string.backup_notification_restore_failed
                state.operation == BackupOperation.BACKUP -> R.string.backup_notification_backup_done
                else -> R.string.backup_notification_restore_done
            },
        )
        val detail = if (success) {
            state.summary?.let { str(R.string.backup_summary, it.projects, it.files) }.orEmpty()
        } else state.error.orEmpty()
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
        201,
        Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.jarves.mh:backup")
            .apply { acquire(MAX_WAKE_LOCK_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun str(id: Int, vararg args: Any): String = AppStrings.get(this, id, *args)

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.jarves.mh.START_BACKUP"
        const val ACTION_STOP = "com.jarves.mh.STOP_BACKUP"
        const val EXTRA_OPERATION = "operation"
        const val EXTRA_INCLUDE_SETTINGS = "include_settings"
        private const val CHANNEL_ID = "backup"
        private const val NOTIFICATION_ID = 61
        private const val RESULT_NOTIFICATION_ID = 62
        private const val NOTIFICATION_THROTTLE_MS = 500L
        private const val MAX_WAKE_LOCK_MS = 3 * 60 * 60 * 1_000L

        fun ensureNotificationChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    AppStrings.get(context, R.string.backup_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}
