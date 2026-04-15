package com.runelitetablet.di

import android.content.Context
import android.os.SystemClock
import com.runelitetablet.BuildConfig
import com.runelitetablet.logging.AppLog
import com.runelitetablet.logging.DebugLogServer
import com.runelitetablet.presentation.PresentationBackends
import com.runelitetablet.presentation.hybrid.ExternalXlorieLoader
import com.runelitetablet.ui.DisplayPreferences

class AppContainer(context: Context) {
    private val creationStart = SystemClock.elapsedRealtime()

    val core = CoreModule(context).also {
        it.logger.lifecycle("AppContainer: creation started")
        AppLog.d("DI", "AppContainer: CoreModule instantiated")
    }
    val termux = TermuxModule(context).also {
        AppLog.d("DI", "AppContainer: TermuxModule instantiated")
    }
    val auth = AuthModule(context, core.httpClient).also {
        AppLog.d("DI", "AppContainer: AuthModule instantiated")
    }
    val installer = InstallerModule(context, core.httpClient).also {
        AppLog.d("DI", "AppContainer: InstallerModule instantiated")
    }
    val setup = SetupModule(
        context, termux.commandRunner, termux.packageChecker,
        installer.apkDownloader, installer.apkInstaller, core.logger
    ).also {
        AppLog.d("DI", "AppContainer: SetupModule instantiated")
    }
    val session = SessionModule(termux.commandRunner).also {
        AppLog.d("DI", "AppContainer: SessionModule instantiated")
    }
    val perf = PerfModule(core.logger).also {
        AppLog.d("DI", "AppContainer: PerfModule instantiated")
    }
    val displayPreferences = DisplayPreferences(context)
    val presentationBackend = PresentationBackends.stable
    val xlorieLoader = ExternalXlorieLoader()

    val debugLogServer: DebugLogServer? = if (BuildConfig.DEBUG) {
        DebugLogServer().also { AppLog.startDebugServer(it) }
    } else null

    init {
        val elapsed = SystemClock.elapsedRealtime() - creationStart
        AppLog.i("DI", "AppContainer: creation completed in ${elapsed}ms, 6 modules wired")
    }

    fun destroy() {
        AppLog.stopDebugServer()
    }
}
