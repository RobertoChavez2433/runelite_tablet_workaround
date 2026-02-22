package com.runelitetablet

import android.app.Application
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

        AppLog.lifecycle("RuneLiteTabletApp.onCreate complete: OkHttpClient built")
    }
}
