package com.runelitetablet.session

/**
 * Sealed class representing the state of the RuneLite game session.
 * Observed by ViewModel and UI to show appropriate controls.
 */
sealed class SessionState {
    /** No session running */
    object Idle : SessionState()
    /** Session is starting up (launch command sent, waiting for health check) */
    object Starting : SessionState()
    /** RuneLite is confirmed running */
    object Running : SessionState()
    /** RuneLite process has exited */
    object Stopped : SessionState()
    /** An error occurred during session management */
    data class Error(val message: String) : SessionState()
}
