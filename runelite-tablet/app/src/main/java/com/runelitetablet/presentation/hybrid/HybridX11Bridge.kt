package com.runelitetablet.presentation.hybrid

import android.os.IBinder
import com.runelitetablet.logging.AppLog
import com.termux.x11.ICmdEntryInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HybridX11BridgeState(
    val broadcastCount: Int = 0,
    val generation: Int = 0,
    val binderAlive: Boolean = false,
    val binderToken: Int = 0,
    val lastEvent: String = "idle",
    val lastUpdatedAtMs: Long = 0L
)

/**
 * Test-only bridge state for the hybrid same-path experiment:
 * stock `termux-x11` command keeps running, but its ACTION_START binder handoff
 * is redirected into our package.
 */
object HybridX11Bridge {
    private val lock = Any()

    @Volatile
    private var service: ICmdEntryInterface? = null

    private val _state = MutableStateFlow(HybridX11BridgeState())
    val state: StateFlow<HybridX11BridgeState> = _state.asStateFlow()

    fun attach(newService: ICmdEntryInterface) {
        val binder = newService.asBinder()
        if (!binder.isBinderAlive) {
            AppLog.w("HYBRID_X11", "HybridX11Bridge: ignoring dead binder attach")
            return
        }

        var sameBinder = false
        synchronized(lock) {
            val previousBinder = service?.asBinder()
            sameBinder = previousBinder != null && previousBinder == binder
            val nextGeneration = if (sameBinder) _state.value.generation else _state.value.generation + 1
            service = newService
            _state.value = HybridX11BridgeState(
                broadcastCount = _state.value.broadcastCount + 1,
                generation = nextGeneration,
                binderAlive = binder.isBinderAlive,
                binderToken = binder.hashCode(),
                lastEvent = if (sameBinder) "duplicate_binder" else "attached",
                lastUpdatedAtMs = System.currentTimeMillis()
            )
        }

        try {
            binder.linkToDeath(
                {
                    AppLog.w("HYBRID_X11", "HybridX11Bridge: remote binder died")
                    clear("binder_died")
                },
                0
            )
        } catch (e: Exception) {
            AppLog.w("HYBRID_X11", "HybridX11Bridge: linkToDeath failed: ${e.message}")
        }

        if (!sameBinder) {
            AppLog.step(
                "hybrid_x11",
                "HybridX11Bridge: attached broadcastCount=${_state.value.broadcastCount} generation=${_state.value.generation} " +
                    "binderToken=${binder.hashCode()} binderAlive=${binder.isBinderAlive}"
            )
        }
    }

    fun currentService(): ICmdEntryInterface? = service

    fun clear(reason: String) {
        synchronized(lock) {
            service = null
            _state.value = _state.value.copy(
                binderAlive = false,
                lastEvent = reason,
                lastUpdatedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun describeBinder(binder: IBinder?): String =
        when {
            binder == null -> "null"
            binder.isBinderAlive -> "alive"
            else -> "dead"
        }
}
