package com.runelitetablet.session

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import com.runelitetablet.MainActivity
import com.runelitetablet.PendingIntentCompat
import com.runelitetablet.RuneLiteTabletApp
import com.runelitetablet.logging.AppLog

class SessionNotificationHelper(private val service: Service) {
    private var lastText: String? = null

    fun buildNotification(contentText: String): Notification {
        AppLog.d("SESSION", "SessionNotificationHelper.buildNotification: text=$contentText")
        lastText = contentText
        val contentIntent = PendingIntent.getActivity(
            service, 0,
            Intent(service, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntentCompat.IMMUTABLE_FLAGS
        )
        val switchIntent = PendingIntent.getService(
            service, 1,
            Intent(service, RuneLiteSessionService::class.java).apply { action = RuneLiteSessionService.ACTION_SWITCH_TO_GAME },
            PendingIntentCompat.IMMUTABLE_FLAGS
        )
        val stopIntent = PendingIntent.getService(
            service, 2,
            Intent(service, RuneLiteSessionService::class.java).apply { action = RuneLiteSessionService.ACTION_STOP_SESSION },
            PendingIntentCompat.IMMUTABLE_FLAGS
        )
        val notification = Notification.Builder(service, RuneLiteTabletApp.CHANNEL_SESSION)
            .setContentTitle("RuneLite is running")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Switch to Game", switchIntent).build())
            .addAction(Notification.Action.Builder(null, "Stop Game", stopIntent).build())
            .build()
        AppLog.d("SESSION", "SessionNotificationHelper: built notification title='RuneLite is running' actions=2 channel=${RuneLiteTabletApp.CHANNEL_SESSION}")
        return notification
    }

    fun promoteToForeground(contentText: String) {
        val notification = buildNotification(contentText)
        AppLog.i("SESSION", "SessionNotificationHelper: promoting to foreground service notificationId=$NOTIFICATION_ID")
        service.startForeground(NOTIFICATION_ID, notification)
    }

    fun updateIfChanged(text: String) {
        if (text == lastText) return
        val nm = service.getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val NOTIFICATION_ID = 1001
    }
}
