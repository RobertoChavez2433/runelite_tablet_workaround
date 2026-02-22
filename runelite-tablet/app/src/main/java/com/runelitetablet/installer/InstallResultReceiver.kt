package com.runelitetablet.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        val pendingResults = ConcurrentHashMap<Int, CompletableDeferred<InstallResult>>()
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val deferred = pendingResults.remove(sessionId) ?: return

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

        deferred.complete(result)
    }
}
