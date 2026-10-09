package com.jarves.mh.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import com.jarves.mh.R
import com.jarves.mh.ui.AppStrings

class AndroidAppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidAppInstaller.ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val userAction = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            userAction?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            userAction?.let(context::startActivity)
            return
        }
        if (status != PackageInstaller.STATUS_SUCCESS) {
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: AppStrings.get(context, R.string.rt_apk_install_failed)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return
        }
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                context.packageManager.getLaunchIntentSenderForPackage(packageName).sendIntent(
                    context, 0, null, null, null,
                )
            }.onFailure {
                Toast.makeText(context, AppStrings.get(context, R.string.rt_apk_installed_open_launcher, packageName), Toast.LENGTH_LONG).show()
            }
            return
        }
        context.packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        }
    }
}
