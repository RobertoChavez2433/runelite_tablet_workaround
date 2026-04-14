package com.runelitetablet.presentation.hybrid

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.content.Intent
import android.preference.PreferenceManager
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.runelitetablet.domain.presentation.FrameTimingTracker
import com.runelitetablet.BuildConfig
import com.runelitetablet.logging.AppLog
import com.runelitetablet.logging.FdTracker
import com.runelitetablet.logging.PerfSnapshots
import com.termux.x11.LorieView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class HybridX11HostActivity : ComponentActivity() {
    private lateinit var statusView: TextView
    private lateinit var lorieView: LorieView
    private lateinit var rootView: FrameLayout
    private lateinit var inputController: HybridInputController
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var attachController: X11AttachmentController
    private var lastHandledGeneration = 0
    private var lastStatusMessage: String? = null
    private var lifecycleActive = false
    private val frameTracker = FrameTimingTracker()
    private var frameCount = 0L
    private var lastFrameLogCount = 0L
    private var previousFrameTimeNanos = 0L
    private var frameSessionId: String? = null
    private val jankThresholdMs = 12.0
    private val jankThresholdNs = (jankThresholdMs * 1_000_000).toLong()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameTracker.recordFrame(frameTimeNanos)
            frameCount++
            if (BuildConfig.DEBUG && frameCount % 120 == 0L) {
                AppLog.thread("Choreographer", Thread.currentThread().name, "doFrame", "frame=$frameCount")
            }
            val sessionId = frameSessionId
            // Log frame stats every 120 frames (~1 second at 120fps)
            if (frameCount - lastFrameLogCount >= 120) {
                lastFrameLogCount = frameCount
                val fps = frameTracker.getAverageFps()
                val jankCount = frameTracker.getJankFrameCount()
                val p99 = frameTracker.getFrameTimePercentile(99.0)
                val heapMb = (Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } / 1_048_576L).toInt()
                AppLog.frame(fps, jankCount, p99, heapMb, "periodic", sessionId)
                val freeMemMb = (Runtime.getRuntime().freeMemory() / (1024 * 1024)).toInt()
                if (heapMb > 128 && jankCount > 2) {
                    AppLog.perf("MEMORY_PRESSURE: heap=${heapMb}MB free=${freeMemMb}MB concurrent_jank=$jankCount fps=$fps")
                }
            }
            // Log individual jank frames
            if (previousFrameTimeNanos > 0) {
                val deltaNs = frameTimeNanos - previousFrameTimeNanos
                if (deltaNs > jankThresholdNs) {
                    val frameTimeMs = deltaNs / 1_000_000.0
                    AppLog.jank(frameTimeMs, jankThresholdMs, frameCount, "exceeded threshold", sessionId)
                }
            }
            previousFrameTimeNanos = frameTimeNanos
            try {
                Choreographer.getInstance().postFrameCallback(this)
            } catch (e: Exception) {
                AppLog.e("FRAME", "Choreographer re-registration FAILED — frame loop dead", e)
            }
        }
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        if (!::lorieView.isInitialized || !lifecycleActive) {
            AppLog.w("HYBRID_X11", "SharedPrefs listener blocked — lifecycle inactive or lorieView uninitialized")
            return@OnSharedPreferenceChangeListener
        }
        AppLog.d("HYBRID_X11", "prefListener: preference changed, reloading")
        lorieView.reloadPreferences(prefs)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.attributes = window.attributes.apply { preferredRefreshRate = 120f }

        val loadResult = (application as com.runelitetablet.RuneLiteTabletApp).container.xlorieLoader.ensureLoaded(this)
        if (!loadResult.loaded) { showFailure(loadResult.summary()); return }

        initViews()
        initAttachController()
        observeBridgeState()
        AppLog.lifecycle("HybridX11HostActivity.onCreate")
        updateStatus("loaded Xlorie from ${loadResult.loadedFrom}")
    }

    private fun initViews() {
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0x88000000.toInt())
            setPadding(24, 24, 24, 24); textSize = 14f; text = "Hybrid host booting"
        }
        lorieView = LorieView(this).apply { setBackgroundColor(Color.BLACK) }
        @Suppress("DEPRECATION")
        run { sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this) }
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefListener)
        lorieView.reloadPreferences(sharedPrefs)
        rootView = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(lorieView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(statusView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        }
        inputController = HybridInputController(rootView = rootView, lorieView = lorieView)
        inputController.install()
        lorieView.setCallback(object : LorieView.Callback {
            override fun changed(surfaceWidth: Int, surfaceHeight: Int, screenWidth: Int, screenHeight: Int) {
                val framerate = display?.refreshRate?.toInt() ?: 120
                if (screenWidth > 0 && screenHeight > 0) {
                    AppLog.ipc("send", "jni_sendWindowChange", 0, "width=$screenWidth height=$screenHeight framerate=$framerate")
                    LorieView.sendWindowChange(screenWidth, screenHeight, framerate, "builtin")
                }
                inputController.onWindowMetricsChanged(surfaceWidth, surfaceHeight, screenWidth, screenHeight)
                updateStatus("surface=${surfaceWidth}x$surfaceHeight screen=${screenWidth}x$screenHeight refresh=$framerate")
            }
        })
        setContentView(rootView)
    }

    private fun initAttachController() {
        attachController = X11AttachmentController(
            connector = LorieServiceConnector(lorieView, sharedPrefs),
            onStatusUpdate = ::updateStatus, onStatusVisible = ::setStatusVisible,
            onAttached = {}, logger = AppLog
        )
        com.termux.x11.MainActivity.getInstance().setConnectionChangedListener { connected ->
            attachController.notifyConnectionStateChanged(connected)
            if (!connected && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                attachController.startAttachLoop(lifecycleScope)
        }
    }

    private fun observeBridgeState() {
        lifecycleScope.launch {
            AppLog.thread("launcher", Thread.currentThread().name, "observeBridgeState", "collecting bridge state")
            HybridX11Bridge.state.collectLatest { state ->
                if (state.generation <= 0 || state.generation == lastHandledGeneration) return@collectLatest
                lastHandledGeneration = state.generation
                attachController.notifyBridgeStateChanged(); setStatusVisible(true)
                updateStatus("bridge updated generation=${state.generation}, reconnecting")
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                    attachController.startAttachLoop(lifecycleScope)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (LorieView.connected() && HybridX11Bridge.state.value.binderAlive && !attachController.pendingBridgeReconnect) {
            setStatusVisible(false); return
        }
        attachController.notifyBridgeStateChanged()
        setStatusVisible(true)
        attachController.startAttachLoop(lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        lifecycleActive = true
        frameSessionId = "frames-" + UUID.randomUUID().toString().take(4)
        previousFrameTimeNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
        attachController.startAttachLoop(lifecycleScope)
        AppLog.lifecycle("HybridX11HostActivity.onResume: frame callback + attach loop started, sessionId=$frameSessionId")
    }

    override fun onPause() {
        lifecycleActive = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameSessionId = null
        attachController.cancelAttachLoop()
        FdTracker.dumpLeaks("onPause")
        AppLog.lifecycle("HybridX11HostActivity.onPause: frame callback + attach loop stopped")
        super.onPause()
    }

    override fun onDestroy() {
        com.termux.x11.MainActivity.getInstance().setConnectionChangedListener(null)
        if (::sharedPrefs.isInitialized) sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    private fun showFailure(message: String) {
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(Color.BLACK)
            setPadding(24, 24, 24, 24); textSize = 16f
            text = "Hybrid X11 host failed to load\n$message"
        }
        setContentView(statusView)
    }

    private fun setStatusVisible(visible: Boolean) {
        if (!::statusView.isInitialized) return
        val v = if (visible) View.VISIBLE else View.GONE
        if (statusView.visibility != v) runOnUiThread { statusView.visibility = v }
    }

    private fun updateStatus(message: String) {
        val bridgeState = HybridX11Bridge.state.value
        val t = String.format(Locale.US, "%.1fs", SystemClock.uptimeMillis() / 1000f)
        val full = "Hybrid X11 host\nt=$t\n$message\nbinderAlive=${bridgeState.binderAlive} broadcasts=${bridgeState.broadcastCount} last=${bridgeState.lastEvent}"
        if (full == lastStatusMessage) return
        lastStatusMessage = full
        if (::statusView.isInitialized) runOnUiThread { statusView.text = full }
        AppLog.step("hybrid_x11", "HybridX11HostActivity: $message")
    }
}
