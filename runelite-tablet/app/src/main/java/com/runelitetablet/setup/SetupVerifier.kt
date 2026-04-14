package com.runelitetablet.setup

import com.runelitetablet.domain.command.CommandRunner
import com.runelitetablet.domain.logging.Logger

/**
 * Verifies that proot, java, runelite, and x11 are properly installed.
 * Pure logic: runs commands and interprets output.
 */
class SetupVerifier(private val commandRunner: CommandRunner, private val logger: Logger? = null) {

    suspend fun verify(): VerifyResult {
        val checks = listOf(
            "[ -d \"\$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu\" ] && echo 'PASS: proot' || echo 'FAIL: proot'",
            "proot-distro login ubuntu -- which java < /dev/null && echo 'PASS: java' || echo 'FAIL: java'",
            "proot-distro login ubuntu -- test -f /root/runelite/RuneLite.jar < /dev/null && echo 'PASS: runelite' || echo 'FAIL: runelite'",
            "{ command -v termux-x11 >/dev/null 2>&1 || [ -f \"\$PREFIX/bin/termux-x11\" ]; } && echo 'PASS: x11' || echo 'FAIL: x11'"
        )

        val result = commandRunner.execute(
            commandPath = "${CommandRunner.TERMUX_BIN_PATH}/bash",
            arguments = arrayOf("-c", checks.joinToString("\n")),
            background = true,
            timeoutMs = 60L * 1000
        )

        val output = result.stdout ?: ""
        if (output.contains("FAIL")) {
            val failures = output.lines().filter { it.startsWith("FAIL") }
            return VerifyResult(false, output, failures)
        }

        if (result.exitCode != 0) {
            logger?.w("STEP", "verify: non-zero exit (${result.exitCode}) but all checks passed")
        }
        return VerifyResult(true, output, emptyList())
    }

    data class VerifyResult(
        val success: Boolean,
        val output: String,
        val failures: List<String>
    )
}
