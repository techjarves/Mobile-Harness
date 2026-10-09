package com.jarves.mh.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.jarves.mh.MainActivity
import com.jarves.mh.R
import com.jarves.mh.ui.AppStrings

internal object RuntimeTaskController {
    @Volatile var stopAction: (() -> Unit)? = null

    fun requestStop() {
        stopAction?.invoke()
    }
}

class RuntimeExecutionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    // Resolved lazily: string resources are not reachable before the Service is attached.
    private var projectNameOverride: String? = null
    private var notificationTitleOverride: String? = null
    private val projectName: String
        get() = projectNameOverride ?: AppStrings.get(this, R.string.rt_exec_default_project)
    private val notificationTitle: String
        get() = notificationTitleOverride ?: AppStrings.get(this, R.string.rt_exec_default_title)
    private var canStop: Boolean = true
    private var taskRunning: Boolean = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_PROJECT_NAME)?.takeIf(String::isNotBlank)?.let { projectNameOverride = it }
        intent?.getStringExtra(EXTRA_TITLE)?.takeIf(String::isNotBlank)?.let { notificationTitleOverride = it }
        if (intent?.hasExtra(EXTRA_CAN_STOP) == true) canStop = intent.getBooleanExtra(EXTRA_CAN_STOP, true)
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                RuntimeTaskController.requestStop()
                getSystemService(NotificationManager::class.java).notify(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification(AppStrings.get(this, R.string.rt_exec_stopping), includeStop = false),
                )
            }
            ACTION_PROGRESS -> {
                // Live step updates only matter while a task is actually running.
                if (!taskRunning) return START_NOT_STICKY
                val detail = intent?.getStringExtra(EXTRA_DETAIL)?.takeIf { it.isNotBlank() }
                    ?: AppStrings.get(this, R.string.rt_exec_working_in, projectName)
                getSystemService(NotificationManager::class.java).notify(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification(detail, includeStop = canStop),
                )
            }
            ACTION_COMPLETE -> finishTask(
                title = AppStrings.get(this, R.string.rt_exec_task_completed),
                detail = intent?.getStringExtra(EXTRA_DETAIL) ?: AppStrings.get(this, R.string.rt_exec_finished_in, projectName),
                failed = false,
            )
            ACTION_FAILED -> finishTask(
                title = AppStrings.get(this, R.string.rt_exec_task_attention),
                detail = intent?.getStringExtra(EXTRA_DETAIL) ?: AppStrings.get(this, R.string.rt_exec_could_not_finish),
                failed = true,
            )
            ACTION_CANCELLED -> {
                taskRunning = false
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                taskRunning = true
                startForeground(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification(AppStrings.get(this, R.string.rt_exec_working_in, projectName), includeStop = canStop),
                )
                acquireWakeLock()
            }
        }
        return START_NOT_STICKY
    }

    private fun runningNotification(detail: String, includeStop: Boolean): android.app.Notification {
        val builder = NotificationCompat.Builder(this, RUNNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notificationTitle)
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
            builder.addAction(0, AppStrings.get(this, R.string.rt_exec_stop_task), stopIntent)
        }
        return builder.build()
    }

    private fun finishTask(title: String, detail: String, failed: Boolean) {
        taskRunning = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
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
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.jarves.mh:active-coding-task")
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
        const val ACTION_START = "com.jarves.mh.START_RUNTIME"
        const val ACTION_STOP = "com.jarves.mh.STOP_RUNTIME"
        const val ACTION_PROGRESS = "com.jarves.mh.PROGRESS_RUNTIME"
        const val ACTION_COMPLETE = "com.jarves.mh.COMPLETE_RUNTIME"
        const val ACTION_FAILED = "com.jarves.mh.FAIL_RUNTIME"
        const val ACTION_CANCELLED = "com.jarves.mh.CANCEL_RUNTIME"
        const val EXTRA_PROJECT_NAME = "project_name"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CAN_STOP = "can_stop"

        private const val RUNNING_CHANNEL_ID = "runtime"
        private const val RESULT_CHANNEL_ID = "task-results"
        private const val RUNNING_NOTIFICATION_ID = 41
        private const val RESULT_NOTIFICATION_ID = 42
        private const val MAX_WAKE_LOCK_MS = 90 * 60 * 1_000L

        fun ensureNotificationChannels(context: android.content.Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    RUNNING_CHANNEL_ID,
                    AppStrings.get(context, R.string.rt_exec_channel_running),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = AppStrings.get(context, R.string.rt_exec_channel_running_description)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    RESULT_CHANNEL_ID,
                    AppStrings.get(context, R.string.rt_exec_channel_results),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = AppStrings.get(context, R.string.rt_exec_channel_results_description)
                },
            )
        }
    }
}
