package com.runelitetablet.di

import android.content.Context
import com.runelitetablet.presentation.PresentationBackends
import com.runelitetablet.presentation.hybrid.ExternalXlorieLoader
import com.runelitetablet.ui.DisplayPreferences

class AppContainer(context: Context) {
    val core = CoreModule(context)
    val termux = TermuxModule(context)
    val auth = AuthModule(context, core.httpClient)
    val installer = InstallerModule(context, core.httpClient)
    val setup = SetupModule(
        context, termux.commandRunner, termux.packageChecker,
        installer.apkDownloader, installer.apkInstaller, core.logger
    )
    val session = SessionModule(termux.commandRunner)
    val displayPreferences = DisplayPreferences(context)
    val presentationBackend = PresentationBackends.stable
    val xlorieLoader = ExternalXlorieLoader()
}
