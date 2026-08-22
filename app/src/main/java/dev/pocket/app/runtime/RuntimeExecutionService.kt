package dev.pocket.app.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.pocket.app.MainActivity
import dev.pocket.app.R

internal object RuntimeTaskController {
    @Volatile var stopAction: (() -> Unit)? = null

    fun requestStop() {
        stopAction?.invoke()
    }
}

class RuntimeExecutionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var projectName: String = "your project"

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        projectName = intent?.getStringExtra(EXTRA_PROJECT_NAME)?.takeIf(String::isNotBlank) ?: projectName
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                RuntimeTaskController.requestStop()
                getSystemService(NotificationManager::class.java).notify(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification("Stopping safely…", includeStop = false),
                )
            }
            ACTION_COMPLETE -> finishTask(
                title = "Task completed",
                detail = intent?.getStringExtra(EXTRA_DETAIL) ?: "Pocket Dev finished working in $projectName.",
                failed = false,
            )
            ACTION_FAILED -> finishTask(
                title = "Task needs attention",
                detail = intent?.getStringExtra(EXTRA_DETAIL) ?: "Pocket Dev could not finish the task.",
                failed = true,
            )
            ACTION_CANCELLED -> {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                acquireWakeLock()
                startForeground(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification("Claude Code is working in $projectName", includeStop = true),
                )
            }
        }
        return START_NOT_STICKY
    }

    private fun runningNotification(detail: String, includeStop: Boolean): android.app.Notification {
        val builder = NotificationCompat.Builder(this, RUNNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Pocket Dev is working")
            .setContentText(detail)
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (includeStop) {
            val stopIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, RuntimeExecutionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Stop task", stopIntent)
        }
        return builder.build()
    }

    private fun finishTask(title: String, detail: String, failed: Boolean) {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(if (failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, notification)
        stopSelf()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:active-coding-task")
            .apply { acquire(MAX_WAKE_LOCK_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "dev.pocket.app.START_RUNTIME"
        const val ACTION_STOP = "dev.pocket.app.STOP_RUNTIME"
        const val ACTION_COMPLETE = "dev.pocket.app.COMPLETE_RUNTIME"
        const val ACTION_FAILED = "dev.pocket.app.FAIL_RUNTIME"
        const val ACTION_CANCELLED = "dev.pocket.app.CANCEL_RUNTIME"
        const val EXTRA_PROJECT_NAME = "project_name"
        const val EXTRA_DETAIL = "detail"

        private const val RUNNING_CHANNEL_ID = "runtime"
        private const val RESULT_CHANNEL_ID = "task-results"
        private const val RUNNING_NOTIFICATION_ID = 41
        private const val RESULT_NOTIFICATION_ID = 42
        private const val MAX_WAKE_LOCK_MS = 2 * 60 * 60 * 1_000L

        fun ensureNotificationChannels(context: android.content.Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(RUNNING_CHANNEL_ID, "Running coding tasks", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows progress while Pocket Dev is working in the background"
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(RESULT_CHANNEL_ID, "Task results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Notifies you when a coding task finishes or needs attention"
                },
            )
        }
    }
}
