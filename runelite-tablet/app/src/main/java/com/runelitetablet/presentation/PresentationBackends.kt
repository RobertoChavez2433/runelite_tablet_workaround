package com.runelitetablet.presentation

object PresentationBackends {
    val stable: PresentationBackend = TermuxX11PresentationBackend
    val directSurfaceProbe: PresentationBackend = DirectSurfaceProbeBackend
}
