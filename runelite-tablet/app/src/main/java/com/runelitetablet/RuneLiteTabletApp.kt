package com.runelitetablet

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.runelitetablet.logging.AppLog
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class RuneLiteTabletApp : Application() {
    lateinit var httpClient: OkHttpClient
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.lifecycle(
            "RuneLiteTabletApp.onCreate: PID=${android.os.Process.myPid()} " +
                "thread=${Thread.currentThread().name} | ${AppLog.perfSnapshot(this)}"
        )

        httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        createNotificationChannels()

        AppLog.lifecycle("RuneLiteTabletApp.onCreate complete: OkHttpClient built, notification channels created")
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            CHANNEL_SESSION,
            "RuneLite Session",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when RuneLite is running"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_SESSION = "runelite_session"
    }
}
