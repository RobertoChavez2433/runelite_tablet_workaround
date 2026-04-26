package com.runelitetablet.probe.surfaceaidl

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.os.IBinder
import android.os.Process
import android.view.Surface
import com.runelitetablet.logging.AppLog
import com.runelitetablet.probe.ISurfaceRendererService
import java.io.File

/**
 * S82 task #26 — Surface-AIDL handoff de-risker (leg 2).
 *
 * Runs in a separate process (`:surfacerenderer`, declared in the manifest)
 * so the Surface really has to travel cross-process via Binder/AIDL. The
 * rlawt-on-Surface end goal needs a non-Android-framework JVM (Termux's
 * OpenJDK) to receive a Surface; this de-risker is one process step removed
 * from that, but exercises the same Binder marshaling layer.
 *
 * Render path is intentionally Canvas-only — EGL+AHB cross-process import
 * was already cleared in task #25 (`docs/s82-capture/cross-process-surface-probe.json`).
 * Composing the two confirms the full pipeline.
 */
class SurfaceRendererService : Service() {

    private val tag = "S26"

    @Volatile private var surface: Surface? = null
    @Volatile private var surfaceW: Int = 0
    @Volatile private var surfaceH: Int = 0
    @Volatile private var attachCount: Int = 0
    @Volatile private var detachCount: Int = 0
    @Volatile private var renderCount: Int = 0
    @Volatile private var renderFailCount: Int = 0

    override fun onCreate() {
        super.onCreate()
        AppLog.lifecycle("$tag SurfaceRendererService.onCreate: pid=${Process.myPid()} fd_count=${countOpenFds()}")
    }

    override fun onDestroy() {
        AppLog.lifecycle(
            "$tag SurfaceRendererService.onDestroy: pid=${Process.myPid()} attach=$attachCount " +
                "detach=$detachCount render=$renderCount render_fail=$renderFailCount fd_count=${countOpenFds()}"
        )
        synchronized(this) { releaseSurfaceLocked("onDestroy") }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : ISurfaceRendererService.Stub() {

        override fun attachSurface(s: Surface?, width: Int, height: Int) {
            synchronized(this@SurfaceRendererService) {
                releaseSurfaceLocked("attachSurface(reattach)")
                if (s == null || !s.isValid) {
                    AppLog.w(tag, "attachSurface: refused — surface=$s isValid=${s?.isValid}")
                    return
                }
                surface = s
                surfaceW = width
                surfaceH = height
                attachCount++
                AppLog.state(
                    "$tag attachSurface: ${width}x$height attach=$attachCount fd_count=${countOpenFds()} " +
                        "remote_pid=${getCallingPid()}"
                )
            }
        }

        override fun renderTriangle(argbColor: Int) {
            val s = synchronized(this@SurfaceRendererService) { surface }
            if (s == null || !s.isValid) {
                renderFailCount++
                AppLog.w(tag, "renderTriangle: no surface or invalid (fail=$renderFailCount)")
                return
            }
            try {
                drawTriangle(s, surfaceW, surfaceH, argbColor)
                renderCount++
            } catch (t: Throwable) {
                renderFailCount++
                AppLog.e(tag, "renderTriangle: failed (fail=$renderFailCount): ${t.message}", t)
            }
        }

        override fun detachSurface() {
            synchronized(this@SurfaceRendererService) {
                releaseSurfaceLocked("detachSurface")
            }
        }

        override fun sampleOpenFdCount(): Int = countOpenFds()
    }

    private fun releaseSurfaceLocked(reason: String) {
        val s = surface ?: return
        try {
            s.release()
        } catch (t: Throwable) {
            AppLog.w(tag, "releaseSurfaceLocked[$reason]: release threw ${t.message}")
        }
        surface = null
        detachCount++
        AppLog.state("$tag releaseSurfaceLocked[$reason]: detach=$detachCount fd_count=${countOpenFds()}")
    }

    private fun drawTriangle(s: Surface, w: Int, h: Int, argbColor: Int) {
        val canvas: Canvas = s.lockCanvas(null) ?: run {
            renderFailCount++
            AppLog.w(tag, "drawTriangle: lockCanvas returned null (fail=$renderFailCount)")
            return
        }
        try {
            canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC)
            val paint = Paint().apply { color = argbColor; isAntiAlias = true }
            val path = Path().apply {
                moveTo(w * 0.5f, h * 0.1f)
                lineTo(w * 0.1f, h * 0.9f)
                lineTo(w * 0.9f, h * 0.9f)
                close()
            }
            canvas.drawPath(path, paint)
        } finally {
            try { s.unlockCanvasAndPost(canvas) } catch (t: Throwable) {
                AppLog.w(tag, "drawTriangle: unlockCanvasAndPost threw ${t.message}")
            }
        }
    }

    private fun countOpenFds(): Int =
        try { File("/proc/self/fd").list()?.size ?: -1 } catch (t: Throwable) { -1 }
}
