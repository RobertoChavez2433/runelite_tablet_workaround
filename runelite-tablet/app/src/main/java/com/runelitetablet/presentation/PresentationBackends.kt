package com.runelitetablet.presentation

import com.runelitetablet.logging.AppLog

object PresentationBackends {
    val stable: PresentationBackend = HybridX11PresentationBackend
    val externalTermuxX11: PresentationBackend = TermuxX11PresentationBackend
    val directSurfaceProbe: PresentationBackend = DirectSurfaceProbeBackend

    init {
        AppLog.i("PRESENTATION", "PresentationBackends initialized: " +
            "stable=${stable.id}, externalTermuxX11=${externalTermuxX11.id}, " +
            "directSurfaceProbe=${directSurfaceProbe.id}")
    }

    fun selectStable(): PresentationBackend {
        AppLog.d("PRESENTATION", "PresentationBackends.stable selected: id=${stable.id}, " +
            "displayName=${stable.displayName}")
        return stable
    }
}
