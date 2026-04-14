package com.runelitetablet.presentation.hybrid

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.content.Intent
import android.preference.PreferenceManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.runelitetablet.logging.AppLog
import com.termux.x11.LorieView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class HybridX11HostActivity : ComponentActivity() {
    private lateinit var statusView: TextView
    private lateinit var lorieView: LorieView
    private lateinit var rootView: FrameLayout
    private lateinit var inputController: HybridInputController
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var attachController: X11AttachmentController
    private var lastHandledGeneration = 0
    private var lastStatusMessage: String? = null
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        if (::lorieView.isInitialized) lorieView.reloadPreferences(prefs)
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
                if (screenWidth > 0 && screenHeight > 0) LorieView.sendWindowChange(screenWidth, screenHeight, framerate, "builtin")
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
        attachController.startAttachLoop(lifecycleScope)
    }

    override fun onPause() {
        attachController.cancelAttachLoop()
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
