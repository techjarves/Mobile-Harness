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

/** Installs a locally-built APK through Android's package manager, without ADB. */
object AndroidAppInstaller {
    fun install(context: Context, apk: File) {
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
            return
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setSize(apk.length())
                setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                setOriginatingUid(Process.myUid())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
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
    }

    const val ACTION_INSTALL_RESULT = "com.jarves.mh.action.APK_INSTALL_RESULT"

    private fun isMiuiDevice(): Boolean = android.os.Build.MANUFACTURER.lowercase() in
        setOf("xiaomi", "redmi", "poco")
}
