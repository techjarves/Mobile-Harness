package com.jarves.mh.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.jarves.mh.MainActivity
import com.jarves.mh.R
import com.jarves.mh.data.SessionJournal
import com.jarves.mh.model.AgentKind
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal object RuntimeTaskController {
    // Session-keyed stop registry (ISSUE-025): the three bridges used to share one
    // volatile slot, so a second session could clobber the first one's stop handler.
    private val stopActions = ConcurrentHashMap<String, () -> Unit>()

    fun register(sessionId: String, action: () -> Unit) {
        stopActions[sessionId] = action
    }

    fun unregister(sessionId: String) {
        stopActions.remove(sessionId)
    }

    /** Stops one session, or every registered session when [sessionId] is null. */
    fun requestStop(sessionId: String? = null) {
        val actions = if (sessionId != null) listOfNotNull(stopActions[sessionId]) else stopActions.values.toList()
        actions.forEach { action -> runCatching(action) }
    }
}

class RuntimeExecutionService : Service() {
    private var projectName: String = "your project"
    private var notificationTitle: String = "Mobile Harness is working"
    private var canStop: Boolean = true
    private var taskRunning: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent is the START_STICKY redelivery after the OS killed the
        // process while a task was in flight (ISSUE-007): the notification shell
        // restarts alone, with no app-side session attached. If the session
        // journal shows a task was interrupted, post one actionable notification
        // so the user can reopen the app and resume; otherwise there is nothing
        // left to do.
        if (intent == null) {
            handleRestartAfterProcessDeath()
            return START_NOT_STICKY
        }
        intent.getStringExtra(EXTRA_PROJECT_NAME)?.takeIf(String::isNotBlank)?.let { projectName = it }
        intent.getStringExtra(EXTRA_TITLE)?.takeIf(String::isNotBlank)?.let { notificationTitle = it }
        if (intent.hasExtra(EXTRA_CAN_STOP)) canStop = intent.getBooleanExtra(EXTRA_CAN_STOP, true)
        val action = intent.action ?: ACTION_START
        when (action) {
            ACTION_STOP -> {
                RuntimeTaskController.requestStop(intent.getStringExtra(EXTRA_SESSION_ID))
                getSystemService(NotificationManager::class.java).notify(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification("Stopping safely…", includeStop = false),
                )
                scheduleStopSafeguard()
            }
            ACTION_PROGRESS -> {
                // Live step updates only matter while a task is actually running.
                if (!taskRunning) return START_NOT_STICKY
                val detail = intent?.getStringExtra(EXTRA_DETAIL)?.takeIf { it.isNotBlank() }
                    ?: "Claude Code is working in $projectName"
                getSystemService(NotificationManager::class.java).notify(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification(detail, includeStop = canStop),
                )
            }
            ACTION_COMPLETE -> finishTask(
                title = "Task completed",
                detail = intent?.getStringExtra(EXTRA_DETAIL) ?: "Mobile Harness finished working in $projectName.",
                failed = false,
            )
            ACTION_FAILED -> finishTask(
                title = "Task needs attention",
                detail = intent?.getStringExtra(EXTRA_DETAIL) ?: "Mobile Harness could not finish the task.",
                failed = true,
            )
            ACTION_CANCELLED -> {
                taskRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                mainHandler.removeCallbacksAndMessages(null)
                taskRunning = true
                startForeground(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification("Claude Code is working in $projectName", includeStop = canStop),
                )
                // ISSUE-013: this service is a notification shell only. The CPU
                // wake lock is held by the bridge running the session
                // (TaskWakeLocks), so it lives exactly as long as real work —
                // not a flat 90 minutes per notification.
            }
        }
        // Sticky while real work may still be in flight (ISSUE-007): if the OS
        // kills the process mid-task, the system restarts this service and the
        // null-intent path above tells the user their task was interrupted.
        // Terminal actions all stopSelf(), which never triggers a restart.
        return when (action) {
            ACTION_STOP, ACTION_COMPLETE, ACTION_FAILED, ACTION_CANCELLED -> START_NOT_STICKY
            else -> START_STICKY
        }
    }

    /**
     * START_STICKY restart path (ISSUE-007): the process died mid-task. One
     * notification reconnects the user with the interrupted task; the app's
     * startup banner then offers the actual one-tap resume.
     */
    private fun handleRestartAfterProcessDeath() {
        // Satisfy any pending foreground-start contract first, then swap the
        // silent running notification for the actionable result one.
        startForeground(RUNNING_NOTIFICATION_ID, runningNotification("Task interrupted", includeStop = false))
        val entry = runCatching { SessionJournal(File(filesDir, "sessions")).read() }.getOrNull()
        if (entry != null) {
            val agent = AgentKind.fromStored(entry.agentKind)
            val detail = "${agent.title} was working in ${entry.projectSlug} when Mobile Harness " +
                "was stopped by the system. Tap to resume the task."
            val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Task interrupted")
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, notification)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Safety net for a STOP that arrives with no live session to confirm completion
     * (ISSUE-036): drop the transient "Stopping safely…" notification and stop the
     * service so neither can linger as an orphan.
     */
    private fun scheduleStopSafeguard() {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed(
            {
                if (!taskRunning) {
                    getSystemService(NotificationManager::class.java).cancel(RUNNING_NOTIFICATION_ID)
                    stopSelf()
                }
            },
            STOP_SAFEGUARD_MS,
        )
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
            builder.addAction(0, "Stop task", stopIntent)
        }
        return builder.build()
    }

    private fun finishTask(title: String, detail: String, failed: Boolean) {
        taskRunning = false
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

    override fun onDestroy() {
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
        const val EXTRA_SESSION_ID = "session_id"

        private const val RUNNING_CHANNEL_ID = "runtime"
        private const val RESULT_CHANNEL_ID = "task-results"
        private const val RUNNING_NOTIFICATION_ID = 41
        private const val RESULT_NOTIFICATION_ID = 42
        private const val STOP_SAFEGUARD_MS = 10_000L

        fun ensureNotificationChannels(context: android.content.Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(RUNNING_CHANNEL_ID, "Running coding tasks", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows progress while Mobile Harness is working in the background"
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
