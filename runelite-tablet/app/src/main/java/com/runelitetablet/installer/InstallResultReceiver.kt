package com.runelitetablet.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CompletableDeferred

class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        var pendingResult: CompletableDeferred<InstallResult>? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            ?: "Unknown error"

        val result = when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                InstallResult.Success
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    InstallResult.NeedsUserAction(confirmIntent)
                } else {
                    InstallResult.Failure("User action required but no intent provided")
                }
            }
            else -> {
                InstallResult.Failure("Install failed (status $status): $message")
            }
        }

        pendingResult?.complete(result)
        pendingResult = null
    }
}
