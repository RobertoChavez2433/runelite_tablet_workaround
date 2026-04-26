package com.runelitetablet.directsurface

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import com.runelitetablet.logging.AppLog

/**
 * Hosts a SurfaceView for the rlawt direct-Android-surface path.
 *
 * On surfaceCreated, spawns the librlawtsurface worker thread which binds
 * an abstract-namespace UDS, accepts the rlawt-JVM producer, hands it an
 * AHB pool, and blits each FRAME_READY onto this SurfaceView via EGL.
 *
 * Gated behind feature flag `EXTRA_FORCE_DIRECT_SURFACE`; the rlawt JVM-side
 * librlawt.so must be built with -DRLAWT_DIRECT_SURFACE=ON for the producer
 * to attempt connection. Until we ship that .so via the launcher, this
 * activity is reachable only via adb-launched intent for validation runs.
 */
class DirectSurfaceHostActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ABSTRACT_NAME = "rlt.abstract_name"
        private const val TAG = "DirectSurface"
    }

    private lateinit var surfaceView: SurfaceView
    private var server: RlawtSurfaceServer? = null
    private var abstractName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        abstractName = intent.getStringExtra(EXTRA_ABSTRACT_NAME)
        AppLog.lifecycle("DirectSurfaceHost.onCreate intent=$intent abstractName=$abstractName")

        surfaceView = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    val s = holder.surface
                    AppLog.lifecycle("DirectSurfaceHost.surfaceCreated holder=$holder surface=$s isValid=${s.isValid}")
                    val srv = RlawtSurfaceServer.start(s, abstractName)
                    if (srv == null) {
                        AppLog.e(TAG, "RlawtSurfaceServer.start returned null in surfaceCreated")
                    } else {
                        AppLog.i(TAG, "RlawtSurfaceServer running")
                    }
                    server = srv
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    AppLog.lifecycle("DirectSurfaceHost.surfaceChanged ${width}x$height format=$format holder=$holder")
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    AppLog.lifecycle("DirectSurfaceHost.surfaceDestroyed holder=$holder — stopping server")
                    server?.stop()
                    server = null
                }
            })
        }
        setContentView(surfaceView)
    }

    override fun onStart() {
        super.onStart()
        AppLog.lifecycle("DirectSurfaceHost.onStart")
    }

    override fun onResume() {
        super.onResume()
        AppLog.lifecycle("DirectSurfaceHost.onResume")
    }

    override fun onPause() {
        AppLog.lifecycle("DirectSurfaceHost.onPause")
        super.onPause()
    }

    override fun onStop() {
        AppLog.lifecycle("DirectSurfaceHost.onStop")
        super.onStop()
    }

    override fun onDestroy() {
        AppLog.lifecycle("DirectSurfaceHost.onDestroy — stopping server if still running")
        server?.stop()
        server = null
        super.onDestroy()
    }
}
