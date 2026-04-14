package com.runelitetablet.di

import android.content.Context
import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.domain.installer.PackageChecker
import com.runelitetablet.termux.TermuxCommandRunner
import com.runelitetablet.termux.TermuxPackageHelper
import com.runelitetablet.termux.TermuxResultRegistry

class TermuxModule(context: Context) {
    val resultRegistry = TermuxResultRegistry()
    val commandRunner: CommandRunner = TermuxCommandRunner(context, resultRegistry)
    val packageChecker: PackageChecker = TermuxPackageHelper(context)
}
