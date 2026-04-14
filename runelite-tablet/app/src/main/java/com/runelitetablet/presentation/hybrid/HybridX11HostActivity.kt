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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class HybridX11HostActivity : ComponentActivity() {
    private lateinit var statusView: TextView
    private lateinit var lorieView: LorieView
    private lateinit var rootView: FrameLayout
    private lateinit var inputController: HybridInputController
    private lateinit var sharedPrefs: SharedPreferences

    private var attachJob: Job? = null
    private var lastConnectionRequestAtMs = 0L
    private var logcatAttached = false
    private var xConnectionAttached = false
    private var lastStatusMessage: String? = null
    private var lastHandledGeneration = 0
    private var pendingBridgeReconnect = false
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        if (::lorieView.isInitialized) {
            lorieView.reloadPreferences(prefs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.attributes = window.attributes.apply {
            preferredRefreshRate = 120f
        }

        val loadResult = ExternalXlorieLoader.ensureLoaded(this)
        if (!loadResult.loaded) {
            showFailure(loadResult.summary())
            return
        }

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(24, 24, 24, 24)
            textSize = 14f
            text = "Hybrid host booting"
        }

        lorieView = LorieView(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        @Suppress("DEPRECATION")
        run {
            sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefListener)
        lorieView.reloadPreferences(sharedPrefs)

        rootView = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                lorieView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                statusView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                )
            )
        }
        inputController = HybridInputController(
            rootView = rootView,
            lorieView = lorieView
        )
        inputController.install()
        lorieView.setCallback(
            object : LorieView.Callback {
                override fun changed(
                    surfaceWidth: Int,
                    surfaceHeight: Int,
                    screenWidth: Int,
                    screenHeight: Int
                ) {
                    val framerate = display?.refreshRate?.toInt() ?: 120
                    val name = "builtin"
                    if (screenWidth > 0 && screenHeight > 0) {
                        LorieView.sendWindowChange(screenWidth, screenHeight, framerate, name)
                    }
                    inputController.onWindowMetricsChanged(
                        surfaceWidth = surfaceWidth,
                        surfaceHeight = surfaceHeight,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight
                    )
                    updateStatus(
                        "surface=${surfaceWidth}x$surfaceHeight " +
                            "screen=${screenWidth}x$screenHeight refresh=$framerate connected=${LorieView.connected()}"
                    )
                }
            }
        )
        setContentView(rootView)

        com.termux.x11.MainActivity.getInstance().setConnectionChangedListener { connected ->
            handleConnectionStateChange("native callback", connected)
        }

        lifecycleScope.launch {
            HybridX11Bridge.state.collectLatest { state ->
                if (state.generation <= 0 || state.generation == lastHandledGeneration) {
                    return@collectLatest
                }
                lastHandledGeneration = state.generation
                pendingBridgeReconnect = true
                logcatAttached = false
                xConnectionAttached = false
                setStatusVisible(true)
                updateStatus(
                    "bridge updated generation=${state.generation} token=${state.binderToken}, reconnecting"
                )
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    startAttachLoop()
                }
            }
        }

        AppLog.lifecycle("HybridX11HostActivity.onCreate")
        updateStatus("loaded Xlorie from ${loadResult.loadedFrom}")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val bridgeState = HybridX11Bridge.state.value
        if (LorieView.connected() && bridgeState.binderAlive && !pendingBridgeReconnect) {
            updateStatus("received new intent while connected, keeping existing session")
            setStatusVisible(false)
            AppLog.lifecycle("HybridX11HostActivity.onNewIntent")
            return
        }
        pendingBridgeReconnect = true
        logcatAttached = false
        xConnectionAttached = false
        setStatusVisible(true)
        updateStatus("received new intent, reconnecting")
        startAttachLoop()
        AppLog.lifecycle("HybridX11HostActivity.onNewIntent")
    }

    override fun onResume() {
        super.onResume()
        startAttachLoop()
        AppLog.lifecycle("HybridX11HostActivity.onResume")
    }

    override fun onPause() {
        attachJob?.cancel()
        AppLog.lifecycle("HybridX11HostActivity.onPause")
        super.onPause()
    }

    override fun onDestroy() {
        com.termux.x11.MainActivity.getInstance().setConnectionChangedListener(null)
        if (::sharedPrefs.isInitialized) {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        }
        super.onDestroy()
    }

    private fun startAttachLoop() {
        attachJob?.cancel()
        attachJob = lifecycleScope.launch {
            while (isActive) {
                tryAttach()
                delay(250)
            }
        }
    }

    private fun tryAttach() {
        val forceReconnect = pendingBridgeReconnect
        if (!forceReconnect && LorieView.connected()) {
            if (!xConnectionAttached) {
                xConnectionAttached = true
                updateStatus("connected=true binder=${HybridX11Bridge.state.value.binderAlive}")
                setStatusVisible(false)
                attachJob?.cancel()
            }
            return
        }

        xConnectionAttached = false
        setStatusVisible(true)

        val service = HybridX11Bridge.currentService()
        if (service == null) {
            maybeRequestConnection()
            updateStatus("waiting for binder")
            return
        }
        if (!service.asBinder().isBinderAlive) {
            maybeRequestConnection()
            updateStatus("binder dead, waiting for replacement")
            return
        }

        if (!logcatAttached) {
            try {
                val logcat = service.getLogcatOutput()
                if (logcat != null) {
                    LorieView.startLogcat(logcat.detachFd())
                    logcatAttached = true
                    AppLog.step("hybrid_x11", "HybridX11HostActivity: attached logcat fd")
                } else {
                    AppLog.step("hybrid_x11", "HybridX11HostActivity: logcat fd unavailable")
                    logcatAttached = true
                }
            } catch (e: Exception) {
                AppLog.w("HYBRID_X11", "HybridX11HostActivity: failed to attach logcat fd: ${e.message}")
                logcatAttached = true
            }
        }

        try {
            val fd = service.getXConnection()
            if (fd == null) {
                maybeRequestConnection()
                updateStatus("binder alive, waiting for xConnection fd")
                return
            }

            val detached = fd.detachFd()
            LorieView.connect(detached)
            lorieView.triggerCallback()
            com.termux.x11.MainActivity.getInstance().clientConnectedStateChanged()
            lorieView.reloadPreferences(sharedPrefs)
            xConnectionAttached = true
            pendingBridgeReconnect = false
            updateStatus("attached xConnection fd=$detached")
            setStatusVisible(false)
            attachJob?.cancel()
            AppLog.step("hybrid_x11", "HybridX11HostActivity: attached xConnection fd=$detached")
        } catch (e: Exception) {
            AppLog.e("HYBRID_X11", "HybridX11HostActivity: attach failed: ${e.message}", e)
            updateStatus("attach failed: ${e.message}")
        }
    }

    private fun maybeRequestConnection() {
        val now = SystemClock.uptimeMillis()
        if (now - lastConnectionRequestAtMs < 1_000L) {
            return
        }
        lastConnectionRequestAtMs = now
        try {
            val requested = LorieView.requestConnection()
            AppLog.step("hybrid_x11", "HybridX11HostActivity: requestConnection requested=$requested")
        } catch (e: Exception) {
            AppLog.w("HYBRID_X11", "HybridX11HostActivity: requestConnection failed: ${e.message}")
        }
    }

    private fun showFailure(message: String) {
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            setPadding(24, 24, 24, 24)
            textSize = 16f
            text = "Hybrid X11 host failed to load\n$message"
        }
        setContentView(statusView)
        AppLog.w("HYBRID_X11", "HybridX11HostActivity: $message")
    }

    private fun handleConnectionStateChange(source: String, connected: Boolean = LorieView.connected()) {
        lorieView.onWindowFocusChanged(hasWindowFocus())
        if (connected) {
            xConnectionAttached = true
            updateStatus("$source connected=true")
            setStatusVisible(false)
            attachJob?.cancel()
        } else {
            xConnectionAttached = false
            setStatusVisible(true)
            updateStatus("$source connected=false")
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                startAttachLoop()
            }
        }
    }

    private fun setStatusVisible(visible: Boolean) {
        if (!::statusView.isInitialized) {
            return
        }
        val nextVisibility = if (visible) View.VISIBLE else View.GONE
        if (statusView.visibility != nextVisibility) {
            runOnUiThread {
                statusView.visibility = nextVisibility
            }
        }
    }

    private fun updateStatus(message: String) {
        val bridgeState = HybridX11Bridge.state.value
        val timestamp = String.format(Locale.US, "%.1fs", SystemClock.uptimeMillis() / 1000f)
        val fullMessage =
            "Hybrid X11 host\n" +
                "t=$timestamp\n" +
                "$message\n" +
                "binderAlive=${bridgeState.binderAlive} broadcasts=${bridgeState.broadcastCount} last=${bridgeState.lastEvent}"
        if (fullMessage == lastStatusMessage) {
            return
        }
        lastStatusMessage = fullMessage
        if (::statusView.isInitialized) {
            runOnUiThread {
                statusView.text = fullMessage
            }
        }
        AppLog.step("hybrid_x11", "HybridX11HostActivity: $message")
    }
}
