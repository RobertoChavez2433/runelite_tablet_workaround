package com.runelitetablet.domain.setup

/**
 * Script deployment abstraction. Pure Kotlin — no Android imports.
 * Production: ScriptManager (deploys from APK assets via Termux).
 * Tests: FakeScriptDeployer (always succeeds, returns fake paths).
 */
interface ScriptDeployer {
    suspend fun deployScripts(): Boolean
    suspend fun deployConfigs(): Boolean
    suspend fun deployJars(): Boolean
    fun getScriptPath(name: String): String
    fun getJarPath(name: String): String
    fun invalidateDeployCache()
}
