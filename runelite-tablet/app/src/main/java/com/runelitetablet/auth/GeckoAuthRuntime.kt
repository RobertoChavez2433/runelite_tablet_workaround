package com.runelitetablet.auth

import android.content.Context
import com.runelitetablet.BuildConfig
import com.runelitetablet.logging.AppLog
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * GeckoRuntime singleton — must be one per process, never shut down.
 */
object GeckoAuthRuntime {
    @Volatile private var runtime: GeckoRuntime? = null

    @Synchronized
    fun getOrCreate(context: Context): GeckoRuntime {
        runtime?.let {
            AppLog.d("AUTH", "GeckoAuthRuntime: returning existing runtime")
            return it
        }
        return try {
            AppLog.lifecycle("GeckoAuthRuntime: creating new GeckoRuntime")
            GeckoRuntime.create(
                context.applicationContext,
                GeckoRuntimeSettings.Builder()
                    .consoleOutput(BuildConfig.DEBUG)
                    .build()
            ).also {
                runtime = it
                AppLog.lifecycle("GeckoAuthRuntime: created successfully")
            }
        } catch (e: Exception) {
            AppLog.e("AUTH", "GeckoAuthRuntime: failed to create runtime", e)
            throw e
        }
    }

    /**
     * Log a page load event from GeckoView navigation delegate.
     * Call from onPageStart / onPageStop in the activity.
     */
    fun logPageLoad(url: String, completed: Boolean) {
        val safeUrl = url.substringBefore("?").substringBefore("#")
        val status = if (completed) "complete" else "start"
        AppLog.i("AUTH", "GeckoAuthRuntime: page load $status url=$safeUrl")
    }

    /**
     * Log a redirect interception from GeckoView navigation delegate.
     */
    fun logRedirectIntercepted(url: String, patternMatched: String) {
        val safeUrl = url.substringBefore("?").substringBefore("#")
        AppLog.i("AUTH", "GeckoAuthRuntime: redirect intercepted url=$safeUrl pattern=$patternMatched")
    }

    /**
     * Log a GeckoView error from the content or navigation delegate.
     */
    fun logGeckoError(category: String, errorCode: Int?, message: String?) {
        AppLog.e("AUTH", "GeckoAuthRuntime: error category=$category code=$errorCode msg=$message")
    }
}
