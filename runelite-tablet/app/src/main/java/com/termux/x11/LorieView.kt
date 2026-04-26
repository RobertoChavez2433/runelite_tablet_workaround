package com.termux.x11

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.graphics.Point
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.InputMethodManager
import androidx.annotation.Keep
import com.runelitetablet.logging.AppLog

/**
 * Minimal host-side subset of upstream LorieView.
 *
 * This is intentionally trimmed to the rendering and connection lifecycle
 * needed to validate option C before porting the full input stack.
 */
@Keep
class LorieView : SurfaceView {
    interface Callback {
        fun changed(surfaceWidth: Int, surfaceHeight: Int, screenWidth: Int, screenHeight: Int)
    }

    private val clipboard by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    private val inputMethodManager by lazy {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    private val size = Point()
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleClipboardChange()
    }

    private var callback: Callback? = null
    private var lastClipboardText: String? = null
    private var clipboardSyncEnabled = true
    private var clipboardListenerRegistered = false

    private var surfaceCreatedAtMs = 0L
    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            holder.setFormat(BGRA_8888)
            surfaceCreatedAtMs = System.currentTimeMillis()
            val frame = holder.surfaceFrame
            AppLog.surface("created", frame.width(), frame.height(), "BGRA_8888",
                "holder=$holder requestedFormat=BGRA_8888($BGRA_8888) — format matters for Xlorie legacy drawing path")
            // S81 audit follow-up: tell SurfaceFlinger we want 120Hz so adaptive-refresh
            // doesn't DRR the panel down under low-content periods. Hint only — SF still
            // chooses based on global policy, but the hint flips the default from
            // FRAME_RATE_COMPATIBILITY_DEFAULT-without-set (which the panel may interpret
            // as "anything is fine, drop to 60/30 to save power") to a specific request.
            val surf = holder.surface
            if (surf != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    surf.setFrameRate(120f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                    AppLog.d("DISPLAY", "LorieView.surfaceCreated: setFrameRate(120, DEFAULT) hint posted")
                } catch (e: Throwable) {
                    AppLog.w("DISPLAY", "LorieView.surfaceCreated: setFrameRate threw ${e.message}")
                }
            }
            // DISPLAY per-surface dump: the Display attached to THIS SurfaceView.
            // Paired with HybridX11HostActivity.onCreate's DISPLAY log — if the two
            // diverge, the surface is on a different display than the activity
            // (e.g. secondary/external panel), which would explain mismatched
            // refresh rates between RLT Choreographer and Xlorie AChoreographer.
            val disp = display
            if (disp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val m = disp.mode
                val frameRateHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "120Hz_DEFAULT" else "n/a(<API30)"
                AppLog.d(
                    "DISPLAY",
                    "LorieView.surfaceCreated: activeMode=id=${m.modeId}:" +
                        "${m.physicalWidth}x${m.physicalHeight}@${"%.1f".format(m.refreshRate)}Hz " +
                        "refreshRate=${"%.1f".format(disp.refreshRate)}Hz displayId=${disp.displayId} " +
                        "frameRateSetOnSurface=$frameRateHint"
                )
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            val startNs = System.nanoTime()
            surfaceChanged(holder.surface)
            val jniLatencyMs = (System.nanoTime() - startNs) / 1_000_000
            size.set(measuredWidth, measuredHeight)
            val formatName = if (format == BGRA_8888) "BGRA_8888" else "format=$format"
            if (size.x != width || size.y != height) {
                AppLog.w("SURFACE", "surfaceChanged: measured=${size.x}x${size.y} != holder=${width}x$height — rotation race?")
            }
            val actualFormat = try { holder.surface?.let { "actualFormat=${format}" } ?: "surface=null" } catch (_: Exception) { "actualFormat=unknown" }
            AppLog.surface("changed", width, height, formatName,
                "measured=${size.x}x${size.y} jniLatency=${jniLatencyMs}ms $actualFormat requestedFormat=BGRA_8888($BGRA_8888) — format mismatch blocks Xlorie legacy drawing")
            AppLog.ipc("send", "jni_surfaceChanged", jniLatencyMs, "surface=${holder.surface != null}")
            callback?.changed(size.x, size.y, size.x, size.y)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            val lifetimeMs = if (surfaceCreatedAtMs > 0) System.currentTimeMillis() - surfaceCreatedAtMs else 0
            surfaceChanged(null)
            AppLog.surface("destroyed", 0, 0, "", "lifetime=${lifetimeMs}ms")
            callback?.changed(0, 0, 0, 0)
        }
    }

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init()
    }

    private fun init() {
        holder.addCallback(surfaceCallback)
        nativeInit()
    }

    fun setCallback(callback: Callback?) {
        this.callback = callback
        triggerCallback()
    }

    fun triggerCallback() {
        setFocusable(true)
        setFocusableInTouchMode(true)
        requestFocus()
        background = object : ColorDrawable(0) {
            override fun isStateful(): Boolean = true
            override fun hasFocusStateSpecified(): Boolean = true
        }

        val frame: Rect = holder.surfaceFrame
        val postTimeNs = System.nanoTime()
        post {
            val execDelayMs = (System.nanoTime() - postTimeNs) / 1_000_000
            if (execDelayMs > 16) AppLog.w("SURFACE", "triggerCallback: post delayed ${execDelayMs}ms (UI thread saturated?)")
            surfaceCallback.surfaceChanged(holder, BGRA_8888, frame.width(), frame.height())
        }
    }

    fun reloadPreferences(prefs: SharedPreferences) {
        clipboardSyncEnabled = prefs.getBoolean("clipboardEnable", true)
        setClipboardSyncEnabled(clipboardSyncEnabled, clipboardSyncEnabled)
        updateClipboardListener(hasWindowFocus())
        AppLog.step("hybrid_x11", "Hybrid LorieView.reloadPreferences clipboardSync=$clipboardSyncEnabled")
    }

    @Keep
    fun resetIme() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            inputMethodManager.invalidateInput(this)
        } else {
            @Suppress("DEPRECATION")
            inputMethodManager.restartInput(this)
        }
    }

    @Keep
    fun setClipboardText(text: String) {
        lastClipboardText = text
        clipboard.setPrimaryClip(ClipData.newPlainText("X11 clipboard", text))
    }

    @Keep
    fun requestClipboard() {
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (!text.isNullOrEmpty() && text != lastClipboardText) {
            sendClipboardEvent(text.toByteArray(Charsets.UTF_8))
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        updateClipboardListener(hasWindowFocus)
    }

    override fun onDetachedFromWindow() {
        updateClipboardListener(false)
        super.onDetachedFromWindow()
    }

    private fun handleClipboardChange() {
        if (clipboardSyncEnabled) {
            requestClipboard()
        }
    }

    private fun updateClipboardListener(hasWindowFocus: Boolean) {
        val shouldListen = clipboardSyncEnabled && hasWindowFocus
        if (shouldListen && !clipboardListenerRegistered) {
            clipboard.addPrimaryClipChangedListener(clipboardListener)
            clipboardListenerRegistered = true
        } else if (!shouldListen && clipboardListenerRegistered) {
            clipboard.removePrimaryClipChangedListener(clipboardListener)
            clipboardListenerRegistered = false
        }

        if (shouldListen) {
            handleClipboardChange()
        }
    }

    private external fun nativeInit()
    private external fun surfaceChanged(surface: Surface?)
    external fun sendClipboardAnnounce()
    private external fun sendClipboardEvent(text: ByteArray)
    external fun sendMouseEvent(x: Float, y: Float, whichButton: Int, buttonDown: Boolean, relative: Boolean)
    fun sendMouseWheelEvent(deltaX: Float, deltaY: Float) {
        sendMouseEvent(deltaX, deltaY, 4, false, true)
    }
    external fun sendTouchEvent(action: Int, id: Int, x: Int, y: Int)
    external fun sendStylusEvent(
        x: Float,
        y: Float,
        pressure: Int,
        tiltX: Int,
        tiltY: Int,
        orientation: Int,
        buttons: Int,
        eraser: Boolean,
        mouseMode: Boolean
    )
    external fun sendKeyEvent(scanCode: Int, keyCode: Int, keyDown: Boolean, a: Int): Boolean
    external fun sendTextEvent(text: ByteArray)

    companion object {
        private const val BGRA_8888 = 5

        @JvmStatic external fun connect(fd: Int)
        @JvmStatic external fun connected(): Boolean
        @JvmStatic external fun startLogcat(fd: Int)
        @JvmStatic external fun setClipboardSyncEnabled(enabled: Boolean, ignored: Boolean)
        @JvmStatic external fun sendWindowChange(width: Int, height: Int, framerate: Int, name: String)
        @JvmStatic external fun requestStylusEnabled(enabled: Boolean)
        @JvmStatic external fun requestConnection(): Boolean
    }
}
