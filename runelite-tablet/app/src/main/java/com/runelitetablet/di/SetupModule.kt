package com.runelitetablet.di

import android.content.Context
import com.runelitetablet.cleanup.CleanupManager
import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.domain.installer.PackageChecker
import com.runelitetablet.domain.logging.Logger
import com.runelitetablet.domain.setup.SetupStateStore
import com.runelitetablet.installer.ApkDownloader
import com.runelitetablet.installer.ApkInstaller
import com.runelitetablet.setup.MarkerReconciler
import com.runelitetablet.setup.PermissionHandler
import com.runelitetablet.setup.ScriptManager
import com.runelitetablet.setup.SetupOrchestrator
import com.runelitetablet.setup.SetupStepRunner
import com.runelitetablet.setup.SetupVerifier
import com.runelitetablet.setup.SharedPrefsSetupStateStore
import com.runelitetablet.termux.TermuxCommandRunner

class SetupModule(
    context: Context,
    commandRunner: CommandRunner,
    packageChecker: PackageChecker,
    apkDownloader: ApkDownloader,
    apkInstaller: ApkInstaller,
    logger: Logger
) {
    val stateStore: SetupStateStore = SharedPrefsSetupStateStore(context)
    val scriptManager = ScriptManager(context, commandRunner as TermuxCommandRunner)
    private val cleanupManager = CleanupManager(context)
    private val verifier = SetupVerifier(commandRunner, logger)
    private val reconciler = MarkerReconciler(commandRunner, scriptManager, logger)
    private val permissionHandler = PermissionHandler(context, commandRunner)
    private val stepRunner = SetupStepRunner(
        commandRunner, packageChecker, apkDownloader, apkInstaller,
        scriptManager, stateStore, logger
    )
    val orchestrator = SetupOrchestrator(
        packageChecker, stepRunner, verifier, reconciler,
        cleanupManager, stateStore, permissionHandler, apkInstaller, logger = logger
    )
}
