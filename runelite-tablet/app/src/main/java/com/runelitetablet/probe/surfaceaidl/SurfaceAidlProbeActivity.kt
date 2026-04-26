package com.runelitetablet.probe.surfaceaidl

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.runelitetablet.logging.AppLog
import com.runelitetablet.probe.ISurfaceRendererService
import java.io.File

/**
 * S82 task #26 — Surface-AIDL handoff de-risker activity.
 *
 * Hosts a SurfaceView, binds [SurfaceRendererService] (which runs in a separate
 * process — see manifest `android:process=":surfacerenderer"`), and on each
 * SurfaceHolder.Callback fires `attachSurface` over AIDL. Drives N synthetic
 * resize cycles via SurfaceHolder.setFixedSize() and verifies the renderer's
 * counters stay healthy across them. Logs a JSON-shaped summary at the end so
 * the test result is grep-able from logcat.
 *
 * Launch from adb:
 *   adb shell am start -n com.runelitetablet/.probe.surfaceaidl.SurfaceAidlProbeActivity \
 *       --ei resize_cycles 100
 *
 * Pass criterion: attach == cycles + 1, detach == cycles + 1, render >= cycles,
 * render_fail == 0, FD count delta on renderer process is 0 across the loop.
 */
class SurfaceAidlProbeActivity : ComponentActivity() {

    private val tag = "S26"
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var surfaceView: SurfaceView
    private var remote: ISurfaceRendererService? = null
    @Volatile private var bound: Boolean = false

    private var resizeCycles: Int = 100
    private var cycleIndex: Int = 0
    private var fdAtStart: Int = -1
    private var attachAtStart: Int = 0

    private val attachWidths = intArrayOf(640, 800, 960, 1024, 1280, 720, 1366, 1600)
    private val attachHeights = intArrayOf(480, 600, 540, 768, 720, 1280, 768, 900)
    private val triangleColors = intArrayOf(
        Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.CYAN
    )

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = ISurfaceRendererService.Stub.asInterface(service)
            bound = true
            fdAtStart = sampleRemoteFds()
            AppLog.state(
                "$tag onServiceConnected: name=$name remote_pid=${try { remote?.let { -1 } ?: -1 } catch (_: Throwable) { -1 }} " +
                    "fd_at_start=$fdAtStart"
            )
            mainHandler.postDelayed(::startResizeLoop, 200L)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false; remote = null
            AppLog.w(tag, "onServiceDisconnected: name=$name")
        }
    }

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            AppLog.lifecycle("$tag surfaceCreated: holder=$holder")
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            AppLog.lifecycle("$tag surfaceChanged: ${width}x$height format=$format")
            sendAttach(holder.surface, width, height)
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            AppLog.lifecycle("$tag surfaceDestroyed")
            try { remote?.detachSurface() } catch (t: Throwable) {
                AppLog.w(tag, "surfaceDestroyed: detachSurface threw ${t.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        resizeCycles = intent?.getIntExtra("resize_cycles", 100) ?: 100

        val root = FrameLayout(this).apply { setBackgroundColor(Color.DKGRAY) }
        surfaceView = SurfaceView(this).apply {
            holder.setFormat(PixelFormat.RGBA_8888)
            holder.addCallback(holderCallback)
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(800, 600))
        setContentView(root)

        AppLog.state(
            "$tag onCreate: pid=${Process.myPid()} resize_cycles=$resizeCycles fd_count=${countOpenFds()}"
        )

        val svcIntent = Intent(this, SurfaceRendererService::class.java)
        val started = bindService(svcIntent, conn, Context.BIND_AUTO_CREATE)
        AppLog.state("$tag onCreate: bindService=$started")
    }

    override fun onDestroy() {
        if (bound) try { unbindService(conn) } catch (t: Throwable) {
            AppLog.w(tag, "onDestroy: unbindService threw ${t.message}")
        }
        super.onDestroy()
    }

    private fun sendAttach(s: android.view.Surface?, w: Int, h: Int) {
        val r = remote ?: return
        if (s == null || !s.isValid) {
            AppLog.w(tag, "sendAttach: refused — surface=$s isValid=${s?.isValid}")
            return
        }
        try {
            r.attachSurface(s, w, h)
            // Render once immediately so the very first attach is end-to-end verified.
            val color = triangleColors[cycleIndex % triangleColors.size] or 0xFF000000.toInt()
            r.renderTriangle(color)
        } catch (t: Throwable) {
            AppLog.e(tag, "sendAttach: AIDL call threw ${t.message}", t)
        }
    }

    private fun startResizeLoop() {
        attachAtStart = 1  // we count the initial post-bind attach (from surfaceChanged)
        cycleIndex = 0
        AppLog.state("$tag startResizeLoop: cycles=$resizeCycles fd_at_start=$fdAtStart")
        scheduleNextResize()
    }

    private fun scheduleNextResize() {
        if (cycleIndex >= resizeCycles) {
            mainHandler.postDelayed(::reportSummary, 250L)
            return
        }
        val w = attachWidths[cycleIndex % attachWidths.size]
        val h = attachHeights[cycleIndex % attachHeights.size]
        // setFixedSize triggers surfaceChanged on the next frame, which re-fires
        // attachSurface over AIDL. The service-side renderer has to detach the
        // previous Surface and re-attach the new buffer-queue without leaking FDs.
        surfaceView.holder.setFixedSize(w, h)
        // 30ms is enough for SurfaceFlinger to finish the resize and surfaceChanged
        // to fire; if the loop ever races, we'll see attach < cycles in the summary.
        mainHandler.postDelayed({
            cycleIndex++
            scheduleNextResize()
        }, 30L)
    }

    private fun reportSummary() {
        val fdAtEnd = sampleRemoteFds()
        val fdDelta = if (fdAtStart >= 0 && fdAtEnd >= 0) fdAtEnd - fdAtStart else -1
        // We can't read the service's counters directly from a separate process —
        // they only show up in its logcat. So the activity emits its own counters
        // (cycles, FDs) and the service emits attach/detach/render in its own tag.
        val summary = "$tag SUMMARY: cycles_requested=$resizeCycles cycle_index=$cycleIndex " +
            "fd_at_start=$fdAtStart fd_at_end=$fdAtEnd fd_delta=$fdDelta"
        AppLog.state(summary)
        // Persist a marker file too so a follow-up adb pull can grab the verdict.
        try {
            val f = File(filesDir, "surface-aidl-probe-summary.txt")
            f.writeText(summary + "\n")
        } catch (_: Throwable) { /* logcat is the source of truth */ }
        finish()
    }

    private fun sampleRemoteFds(): Int =
        try { remote?.sampleOpenFdCount() ?: -1 } catch (t: Throwable) {
            AppLog.w(tag, "sampleRemoteFds threw ${t.message}"); -1
        }

    private fun countOpenFds(): Int =
        try { File("/proc/self/fd").list()?.size ?: -1 } catch (t: Throwable) { -1 }
}
