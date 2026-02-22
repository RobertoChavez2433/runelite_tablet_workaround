package com.runelitetablet.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.runelitetablet.logging.AppLog
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        val pendingResults = ConcurrentHashMap<Int, CompletableDeferred<InstallResult>>()
    }

    override fun onReceive(context: Context, intent: Intent?) {
        AppLog.install("onReceive: callback at ${System.currentTimeMillis()}")
        if (intent == null) {
            AppLog.e("INSTALL", "onReceive: null intent received — ignoring")
            return
        }

        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val statusName = statusName(status)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Unknown error"

        // Build a dump of all intent extras for full context
        val extrasBundle = intent.extras
        val extrasDump = extrasBundle?.keySet()?.joinToString(", ") { key ->
            "$key=${extrasBundle.get(key)}"
        } ?: "<none>"

        AppLog.install(
            "onReceive: sessionId=$sessionId status=$status statusName=$statusName " +
                "message='$message' extras=[$extrasDump]"
        )

        if (sessionId == -1) {
            AppLog.e("INSTALL", "onReceive: missing session_id in intent extras — cannot complete deferred")
            return
        }

        val deferred = pendingResults.remove(sessionId)
        if (deferred == null) {
            AppLog.install(
                "onReceive: deferred NOT found in pendingResults for sessionId=$sessionId " +
                    "mapSize=${pendingResults.size}"
            )
            return
        }
        AppLog.install(
            "onReceive: deferred found and removed for sessionId=$sessionId " +
                "mapSizeAfter=${pendingResults.size}"
        )

        val result = when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                AppLog.install("onReceive: install STATUS_SUCCESS sessionId=$sessionId")
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
                    AppLog.install("onReceive: STATUS_PENDING_USER_ACTION sessionId=$sessionId confirmIntent present")
                    InstallResult.NeedsUserAction(confirmIntent)
                } else {
                    AppLog.e("INSTALL", "onReceive: STATUS_PENDING_USER_ACTION but confirmIntent is null sessionId=$sessionId")
                    InstallResult.Failure("User action required but no intent provided")
                }
            }
            else -> {
                AppLog.install("onReceive: install FAILURE status=$status statusName=$statusName message='$message' sessionId=$sessionId")
                InstallResult.Failure("Install failed (status $status): $message")
            }
        }

        deferred.complete(result)
    }

    private fun statusName(status: Int): String = when (status) {
        PackageInstaller.STATUS_SUCCESS -> "STATUS_SUCCESS"
        PackageInstaller.STATUS_FAILURE -> "STATUS_FAILURE"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "STATUS_FAILURE_ABORTED"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "STATUS_FAILURE_BLOCKED"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "STATUS_FAILURE_CONFLICT"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "STATUS_FAILURE_INCOMPATIBLE"
        PackageInstaller.STATUS_FAILURE_INVALID -> "STATUS_FAILURE_INVALID"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "STATUS_FAILURE_STORAGE"
        PackageInstaller.STATUS_PENDING_USER_ACTION -> "STATUS_PENDING_USER_ACTION"
        else -> "UNKNOWN($status)"
    }
}
