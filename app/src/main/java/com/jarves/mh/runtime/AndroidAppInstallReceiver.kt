package com.jarves.mh.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

class AndroidAppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidAppInstaller.ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val operationId = intent.getStringExtra(AndroidAppInstaller.EXTRA_OPERATION_ID).orEmpty()
        val launchAfterInstall = intent.getBooleanExtra(AndroidAppInstaller.EXTRA_LAUNCH_AFTER_INSTALL, true)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val userAction = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            userAction?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            userAction?.let(context::startActivity)
            return
        }
        if (status != PackageInstaller.STATUS_SUCCESS) {
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Installation failed"
            AndroidAppInstaller.publish(AndroidInstallEvent(operationId, null, false, false, message))
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return
        }
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: return
        intent.getStringExtra(AndroidAppInstaller.EXTRA_APK_FINGERPRINT)?.let { fingerprint ->
            AndroidAppInstaller.rememberInstalled(context, packageName, fingerprint)
        }
        val launched = launchAfterInstall && AndroidAppInstaller.launch(context, packageName)
        AndroidAppInstaller.publish(AndroidInstallEvent(operationId, packageName, true, launched))
        if (launchAfterInstall && !launched) {
            Toast.makeText(context, "Installed $packageName. Open it from your launcher.", Toast.LENGTH_LONG).show()
        }
    }
}
