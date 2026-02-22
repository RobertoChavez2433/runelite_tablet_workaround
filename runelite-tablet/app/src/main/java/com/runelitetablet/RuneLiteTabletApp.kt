package com.runelitetablet

import android.app.Application
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class RuneLiteTabletApp : Application() {
    lateinit var httpClient: OkHttpClient
        private set

    override fun onCreate() {
        super.onCreate()
        httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
