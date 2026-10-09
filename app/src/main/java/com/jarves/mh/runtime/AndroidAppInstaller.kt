package com.jarves.mh.runtime

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import com.jarves.mh.R
import com.jarves.mh.ui.AppStrings
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class AndroidInstallEvent(
    val operationId: String,
    val packageName: String?,
    val success: Boolean,
    val launched: Boolean,
    val message: String? = null,
)

/** Installs a locally-built APK through Android's package manager, without ADB. */
object AndroidAppInstaller {
    private val _events = MutableSharedFlow<AndroidInstallEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    fun openIfAlreadyInstalled(context: Context, apk: File): Boolean {
        val packageName = archivePackageName(context, apk) ?: return false
        if (installedFingerprint(context, packageName) != fingerprint(apk)) return false
        return launch(context, packageName)
    }

    fun install(
        context: Context,
        apk: File,
        launchAfterInstall: Boolean = true,
        operationId: String = UUID.randomUUID().toString(),
    ): String {
        require(apk.isFile && apk.extension.equals("apk", ignoreCase = true) && apk.length() > 0L) {
            AppStrings.get(context, R.string.rt_apk_invalid, apk.name)
        }
        if (isMiuiDevice()) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
            context.startActivity(
                Intent(Intent.ACTION_INSTALL_PACKAGE, uri).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            return operationId
        }
        val installer = context.packageManager.packageInstaller
        val packageName = archivePackageName(context, apk)
        val isUpdate = packageName?.let { isInstalled(context, it) } == true
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setSize(apk.length())
                setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                setOriginatingUid(Process.myUid())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(
                        if (isUpdate) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                        else PackageInstaller.SessionParams.USER_ACTION_REQUIRED,
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
                }
            }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(apk.name, 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callback = Intent(context, AndroidAppInstallReceiver::class.java)
                    .setAction(ACTION_INSTALL_RESULT)
                    .setPackage(context.packageName)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                    .putExtra(EXTRA_APK_FINGERPRINT, fingerprint(apk))
                    .putExtra(EXTRA_OPERATION_ID, operationId)
                    .putExtra(EXTRA_LAUNCH_AFTER_INSTALL, launchAfterInstall)
                val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                val pending = PendingIntent.getBroadcast(
                    context, sessionId, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag,
                )
                session.commit(pending.intentSender)
            }
        } catch (error: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
        return operationId
    }

    const val ACTION_INSTALL_RESULT = "com.jarves.mh.action.APK_INSTALL_RESULT"
    const val EXTRA_APK_FINGERPRINT = "com.jarves.mh.extra.APK_FINGERPRINT"
    const val EXTRA_OPERATION_ID = "com.jarves.mh.extra.OPERATION_ID"
    const val EXTRA_LAUNCH_AFTER_INSTALL = "com.jarves.mh.extra.LAUNCH_AFTER_INSTALL"

    internal fun publish(event: AndroidInstallEvent) {
        _events.tryEmit(event)
    }

    fun packageName(context: Context, apk: File): String? = archivePackageName(context, apk)

    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    fun installedMatches(context: Context, apk: File): Boolean {
        val packageName = archivePackageName(context, apk) ?: return false
        return installedFingerprint(context, packageName) == fingerprint(apk)
    }

    fun apkFingerprint(apk: File): String = fingerprint(apk)

    internal fun rememberInstalled(context: Context, packageName: String, fingerprint: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(packageName, fingerprint)
            .apply()
    }

    internal fun launch(context: Context, packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getLaunchIntentSenderForPackage(packageName).sendIntent(
                context, 0, null, null, null,
            )
        } else {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        true
    }.getOrDefault(false)

    private fun archivePackageName(context: Context, apk: File): String? =
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName

    private fun installedFingerprint(context: Context, packageName: String): String? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(packageName, null)

    private fun fingerprint(apk: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private const val PREFERENCES = "installed_android_apks"

    private fun isMiuiDevice(): Boolean = android.os.Build.MANUFACTURER.lowercase() in
        setOf("xiaomi", "redmi", "poco")
}
