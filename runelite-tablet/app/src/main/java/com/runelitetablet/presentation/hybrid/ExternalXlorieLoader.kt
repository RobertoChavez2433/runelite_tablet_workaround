package com.runelitetablet.presentation.hybrid

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.runelitetablet.logging.AppLog
import java.io.File

data class ExternalXlorieLoadResult(
    val loaded: Boolean,
    val loadedFrom: String? = null,
    val attempts: List<String> = emptyList(),
    val errors: List<String> = emptyList()
) {
    fun summary(): String = buildString {
        append("loaded=").append(loaded)
        loadedFrom?.let { append(" from=").append(it) }
        if (attempts.isNotEmpty()) {
            append(" attempts=").append(attempts.joinToString())
        }
        if (errors.isNotEmpty()) {
            append(" errors=").append(errors.joinToString(" | "))
        }
    }
}

/**
 * Attempts to load the stock Termux:X11 native renderer into our app process.
 *
 * This keeps option C on the same runtime path: stock `termux-x11` command and
 * binder stay in place, while we experiment with moving only the visible host
 * surface into our app.
 */
class ExternalXlorieLoader {
    @Volatile
    private var result: ExternalXlorieLoadResult? = null

    companion object {
        private const val APP_PACKAGE = "com.runelitetablet"
        private const val TERMUX_X11_PACKAGE = "com.termux.x11"
        private const val XLORIE_SO = "libXlorie.so"
    }

    fun ensureLoaded(context: Context): ExternalXlorieLoadResult {
        result?.let { return it }
        synchronized(this) {
            result?.let { return it }

            val attempts = mutableListOf<String>()
            val errors = mutableListOf<String>()

            try {
                System.loadLibrary("Xlorie")
                val success = ExternalXlorieLoadResult(
                    loaded = true,
                    loadedFrom = "packaged:Xlorie",
                    attempts = listOf("packaged:Xlorie"),
                    errors = emptyList()
                )
                AppLog.step("hybrid_x11", "ExternalXlorieLoader: loaded packaged Xlorie")
                result = success
                return success
            } catch (e: UnsatisfiedLinkError) {
                val message = "packaged:Xlorie: ${e.message}"
                attempts += "packaged:Xlorie"
                errors += message
                AppLog.w("HYBRID_X11", "ExternalXlorieLoader: packaged load failed: $message")
            }

            tryOwnNativeLibCandidate(context.packageManager.getApplicationInfoCompat(APP_PACKAGE))?.let { ownCandidate ->
                attempts += ownCandidate
                try {
                    System.load(ownCandidate)
                    val success = ExternalXlorieLoadResult(
                        loaded = true,
                        loadedFrom = ownCandidate,
                        attempts = attempts.toList(),
                        errors = errors.toList()
                    )
                    AppLog.step("hybrid_x11", "ExternalXlorieLoader: loaded packaged Xlorie from $ownCandidate")
                    result = success
                    return success
                } catch (e: UnsatisfiedLinkError) {
                    val message = "${ownCandidate}: ${e.message}"
                    errors += message
                    AppLog.w("HYBRID_X11", "ExternalXlorieLoader: packaged path load failed: $message")
                } catch (e: SecurityException) {
                    val message = "${ownCandidate}: ${e.message}"
                    errors += message
                    AppLog.w("HYBRID_X11", "ExternalXlorieLoader: packaged path blocked: $message")
                }
            }

            val appInfo = try {
                context.packageManager.getApplicationInfoCompat(TERMUX_X11_PACKAGE)
            } catch (e: Exception) {
                val failure = ExternalXlorieLoadResult(
                    loaded = false,
                    attempts = attempts,
                    errors = listOf("applicationInfo: ${e.message}")
                )
                AppLog.e("HYBRID_X11", "ExternalXlorieLoader: failed to resolve Termux:X11 package", e)
                result = failure
                return failure
            }

            val candidates = buildCandidates(appInfo)
            for (candidate in candidates) {
                attempts += candidate
                try {
                    System.load(candidate)
                    val success = ExternalXlorieLoadResult(
                        loaded = true,
                        loadedFrom = candidate,
                        attempts = attempts.toList(),
                        errors = errors.toList()
                    )
                    AppLog.step("hybrid_x11", "ExternalXlorieLoader: loaded Xlorie from $candidate")
                    result = success
                    return success
                } catch (e: UnsatisfiedLinkError) {
                    val message = "${candidate}: ${e.message}"
                    errors += message
                    AppLog.w("HYBRID_X11", "ExternalXlorieLoader: load failed: $message")
                } catch (e: SecurityException) {
                    val message = "${candidate}: ${e.message}"
                    errors += message
                    AppLog.w("HYBRID_X11", "ExternalXlorieLoader: load blocked: $message")
                }
            }

            val failure = ExternalXlorieLoadResult(
                loaded = false,
                attempts = attempts.toList(),
                errors = errors.toList()
            )
            AppLog.w("HYBRID_X11", "ExternalXlorieLoader: failed to load Xlorie ${failure.summary()}")
            result = failure
            return failure
        }
    }

    private fun tryOwnNativeLibCandidate(appInfo: ApplicationInfo): String? {
        val candidate = appInfo.nativeLibraryDir?.let { File(it, XLORIE_SO).absolutePath } ?: return null
        val file = File(candidate)
        return candidate.takeIf { file.exists() && file.canRead() }
    }

    private fun buildCandidates(appInfo: ApplicationInfo): List<String> {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val candidates = linkedSetOf<String>()

        appInfo.nativeLibraryDir?.let { nativeLibDir ->
            candidates += File(nativeLibDir, XLORIE_SO).absolutePath
        }

        listOf(appInfo.sourceDir, appInfo.publicSourceDir)
            .filterNotNull()
            .forEach { apkPath ->
                candidates += "$apkPath!/lib/$abi/$XLORIE_SO"
            }

        return candidates.toList()
    }
}

private fun android.content.pm.PackageManager.getApplicationInfoCompat(packageName: String): ApplicationInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getApplicationInfo(packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getApplicationInfo(packageName, 0)
    }
