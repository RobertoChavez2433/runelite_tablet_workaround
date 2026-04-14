package com.runelitetablet.di

import android.content.Context
import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.domain.installer.PackageChecker
import com.runelitetablet.logging.AppLog
import com.runelitetablet.termux.TermuxCommandRunner
import com.runelitetablet.termux.TermuxPackageHelper
import com.runelitetablet.termux.TermuxResultRegistry

class TermuxModule(context: Context) {
    val resultRegistry = TermuxResultRegistry().also {
        AppLog.d("DI", "TermuxModule: TermuxResultRegistry initialized")
    }
    val commandRunner: CommandRunner = TermuxCommandRunner(context, resultRegistry).also {
        AppLog.d("DI", "TermuxModule: TermuxCommandRunner initialized")
    }
    val packageChecker: PackageChecker = TermuxPackageHelper(context).also {
        AppLog.d("DI", "TermuxModule: TermuxPackageHelper initialized")
    }
}
