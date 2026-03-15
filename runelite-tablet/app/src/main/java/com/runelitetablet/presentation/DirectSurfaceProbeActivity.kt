package com.runelitetablet.presentation

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.runelitetablet.logging.AppLog
import java.util.Locale

class DirectSurfaceProbeActivity : ComponentActivity() {
    private lateinit var probeView: DirectSurfaceProbeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            preferredRefreshRate = DirectSurfaceProbeView.TARGET_REFRESH_HZ
        }

        probeView = DirectSurfaceProbeView(this)
        setContentView(probeView)

        AppLog.lifecycle("DirectSurfaceProbeActivity.onCreate")
    }

    override fun onResume() {
        super.onResume()
        probeView.startProbe()
        AppLog.lifecycle("DirectSurfaceProbeActivity.onResume")
    }

    override fun onPause() {
        probeView.stopProbe()
        AppLog.lifecycle("DirectSurfaceProbeActivity.onPause")
        super.onPause()
    }
}

private class DirectSurfaceProbeView(
    context: DirectSurfaceProbeActivity
) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {

    companion object {
        const val TARGET_REFRESH_HZ = 120f
        private const val SAMPLE_WINDOW_NS = 1_000_000_000L
    }

    private val backgroundPaint = Paint().apply { color = Color.rgb(10, 15, 20) }
    private val accentPaint = Paint().apply { color = Color.rgb(214, 176, 57) }
    private val secondaryPaint = Paint().apply { color = Color.rgb(78, 163, 223) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(200, 210, 220)
        textSize = 28f
    }

    private var surfaceReady = false
    private var running = false
    private var sampleStartNs = 0L
    private var framesThisWindow = 0
    private var latestFps = 0f

    init {
        holder.addCallback(this)
        setZOrderOnTop(false)
    }

    fun startProbe() {
        if (running) {
            return
        }
        running = true
        if (surfaceReady) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun stopProbe() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        requestSurfaceRefreshRate(holder.surface)
        AppLog.lifecycle("DirectSurfaceProbeView.surfaceCreated")
        if (running) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        AppLog.lifecycle("DirectSurfaceProbeView.surfaceChanged: ${width}x$height format=$format")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        Choreographer.getInstance().removeFrameCallback(this)
        AppLog.lifecycle("DirectSurfaceProbeView.surfaceDestroyed")
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || !surfaceReady) {
            return
        }

        if (sampleStartNs == 0L) {
            sampleStartNs = frameTimeNanos
        }

        drawFrame(frameTimeNanos)
        framesThisWindow++

        val sampleElapsedNs = frameTimeNanos - sampleStartNs
        if (sampleElapsedNs >= SAMPLE_WINDOW_NS) {
            latestFps = framesThisWindow * 1_000_000_000f / sampleElapsedNs.toFloat()
            AppLog.perf(
                "DirectSurfaceProbe: fps=${String.format(Locale.US, "%.1f", latestFps)} " +
                    "surface=${width}x$height"
            )
            sampleStartNs = frameTimeNanos
            framesThisWindow = 0
        }

        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun drawFrame(frameTimeNanos: Long) {
        val canvas = try {
            holder.lockHardwareCanvas()
        } catch (_: IllegalStateException) {
            holder.lockCanvas()
        }

        canvas ?: return
        try {
            render(canvas, frameTimeNanos / 1_000_000_000f)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun render(canvas: Canvas, timeSeconds: Float) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)

        val bandWidth = w / 7f
        for (i in 0..7) {
            val offset = ((timeSeconds * 380f) + (i * bandWidth * 1.15f)) % (w + bandWidth)
            canvas.drawRect(offset - bandWidth, 0f, offset, h, if (i % 2 == 0) accentPaint else secondaryPaint)
        }

        val orbRadius = h * 0.08f
        val orbX = (w * 0.18f) + (kotlin.math.sin(timeSeconds * 1.6f) * w * 0.1f).toFloat()
        val orbY = (h * 0.7f) + (kotlin.math.cos(timeSeconds * 1.1f) * h * 0.08f).toFloat()
        canvas.drawCircle(orbX, orbY, orbRadius, accentPaint)

        canvas.drawText("Direct Surface Probe", 48f, 84f, textPaint)
        canvas.drawText(
            "App-owned SurfaceView requesting ${TARGET_REFRESH_HZ.toInt()} Hz",
            48f,
            126f,
            subTextPaint
        )
        canvas.drawText(
            "Measured callback rate: ${String.format(Locale.US, "%.1f", latestFps)} FPS",
            48f,
            168f,
            subTextPaint
        )
        canvas.drawText(
            "Surface: ${w.toInt()}x${h.toInt()}",
            48f,
            210f,
            subTextPaint
        )
    }

    private fun requestSurfaceRefreshRate(surface: Surface) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            surface.setFrameRate(TARGET_REFRESH_HZ, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            AppLog.step("surface", "DirectSurfaceProbeView: requested ${TARGET_REFRESH_HZ.toInt()} Hz on Surface")
        }
    }
}
