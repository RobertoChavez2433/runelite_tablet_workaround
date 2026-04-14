package com.runelitetablet.domain.logging

/**
 * Logging abstraction. Implementations: AppLog (production), PrintLogger (tests).
 * No Android imports — pure Kotlin interface.
 */
interface Logger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)

    fun lifecycle(message: String) = i("LIFECYCLE", message)
    fun step(stepId: String, message: String) = i("STEP", "[$stepId] $message")
    fun cmd(execId: Int, message: String) = i("CMD", "[id=$execId] $message")
    fun http(message: String) = i("HTTP", message)
    fun install(message: String) = i("INSTALL", message)
    fun cleanup(message: String) = i("CLEANUP", message)
    fun script(message: String) = i("SCRIPT", message)
    fun verify(message: String) = i("VERIFY", message)
    fun state(message: String) = i("STATE", message)
    fun ui(message: String) = i("UI", message)
    fun perf(message: String) = i("PERF", message)
}
