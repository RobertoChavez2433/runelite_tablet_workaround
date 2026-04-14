package com.runelitetablet.setup

import com.runelitetablet.auth.GameCharacter

sealed class AppScreen {
    object Setup : AppScreen()
    object Login : AppScreen()
    data class CharacterSelect(val characters: List<GameCharacter>) : AppScreen()
    object Launch : AppScreen()
    data class AuthError(val message: String) : AppScreen()
    object Settings : AppScreen()
    object LogViewer : AppScreen()
}

sealed class LaunchState {
    object Idle : LaunchState()
    object CheckingUpdate : LaunchState()
    data class Updating(val fromVersion: String, val toVersion: String) : LaunchState()
    object CheckingHealth : LaunchState()
    object RefreshingTokens : LaunchState()
    object ValidatingSession : LaunchState()
    object Launching : LaunchState()
    data class Failed(val message: String) : LaunchState()
}

sealed class HealthCheckResult {
    object Healthy : HealthCheckResult()
    data class Degraded(val failures: List<String>) : HealthCheckResult()
    object Inconclusive : HealthCheckResult()
}
