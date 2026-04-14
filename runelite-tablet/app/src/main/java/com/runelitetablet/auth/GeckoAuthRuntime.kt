package com.runelitetablet.auth

import android.content.Context
import com.runelitetablet.BuildConfig
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * GeckoRuntime singleton — must be one per process, never shut down.
 */
object GeckoAuthRuntime {
    @Volatile private var runtime: GeckoRuntime? = null

    @Synchronized
    fun getOrCreate(context: Context): GeckoRuntime {
        return runtime ?: GeckoRuntime.create(
            context.applicationContext,
            GeckoRuntimeSettings.Builder()
                .consoleOutput(BuildConfig.DEBUG)
                .build()
        ).also { runtime = it }
    }
}
