package com.runelitetablet.presentation

object PresentationBackends {
    val stable: PresentationBackend = HybridX11PresentationBackend
    val externalTermuxX11: PresentationBackend = TermuxX11PresentationBackend
    val directSurfaceProbe: PresentationBackend = DirectSurfaceProbeBackend
}
