package com.runelitetablet.di

import android.content.Context
import com.runelitetablet.installer.ApkDownloader
import com.runelitetablet.installer.ApkInstaller
import com.runelitetablet.installer.InstallResultRegistry
import okhttp3.OkHttpClient

class InstallerModule(context: Context, httpClient: OkHttpClient) {
    val installResultRegistry = InstallResultRegistry()
    val apkDownloader = ApkDownloader(context, httpClient)
    val apkInstaller = ApkInstaller(context, installResultRegistry)
}
