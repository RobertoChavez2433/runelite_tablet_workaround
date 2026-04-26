package com.runelitetablet.directsurface

import android.view.Surface
import com.runelitetablet.logging.AppLog

/**
 * Thin Kotlin wrapper over the librlawtsurface JNI. The native side runs the
 * full server loop (AF_UNIX abstract socket → AHB pool → blit-to-Surface), so
 * Kotlin only needs to manage start/stop lifecycle keyed off SurfaceHolder
 * callbacks.
 *
 * The server listens on a Linux abstract-namespace UDS at
 * `\0RLAWT_SURFACE_ABSTRACT_NAME` (default "rlt-rlawt-surface"). The rlawt JVM
 * inside Termux/proot connects by the same abstract name; abstract sockets
 * cross UID boundaries without filesystem permission coordination.
 */
class RlawtSurfaceServer private constructor(private val handle: Long) {

    fun stop() {
        if (handle != 0L) {
            AppLog.i("DirectSurface", "RlawtSurfaceServer.stop() handle=0x${handle.toString(16)}")
            nativeStop(handle)
        } else {
            AppLog.w("DirectSurface", "RlawtSurfaceServer.stop() called with handle=0 — ignoring")
        }
    }

    companion object {
        private const val TAG = "DirectSurface"
        private var loaded = false

        fun start(surface: Surface, abstractName: String? = null): RlawtSurfaceServer? {
            AppLog.i(TAG, "RlawtSurfaceServer.start: surface=$surface abstractName=$abstractName")
            ensureLoaded()
            val h = try {
                nativeStart(surface, abstractName)
            } catch (t: Throwable) {
                AppLog.e(TAG, "nativeStart threw", t)
                return null
            }
            if (h == 0L) {
                AppLog.e(TAG, "nativeStart returned 0 (consumer worker did not start)")
                return null
            }
            AppLog.i(TAG, "RlawtSurfaceServer started handle=0x${h.toString(16)}")
            return RlawtSurfaceServer(h)
        }

        @Synchronized
        private fun ensureLoaded() {
            if (loaded) return
            try {
                System.loadLibrary("rlawtsurface")
                AppLog.i(TAG, "librlawtsurface.so loaded")
                loaded = true
            } catch (e: UnsatisfiedLinkError) {
                AppLog.e(TAG, "System.loadLibrary(rlawtsurface) failed", e)
                throw e
            }
        }

        @JvmStatic
        private external fun nativeStart(surface: Surface, abstractName: String?): Long

        @JvmStatic
        private external fun nativeStop(handle: Long)
    }
}
