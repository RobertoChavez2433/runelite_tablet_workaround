package com.runelitetablet.testutil

import com.runelitetablet.domain.setup.ScriptDeployer

class FakeScriptDeployer : ScriptDeployer {
    var deployScriptsResult = true
    var deployConfigsResult = true
    var deployJarsResult = true
    var deployScriptsCalled = 0
    var deployConfigsCalled = 0
    var deployJarsCalled = 0
    var invalidateCalled = false

    override suspend fun deployScripts(): Boolean {
        deployScriptsCalled++
        return deployScriptsResult
    }

    override suspend fun deployConfigs(): Boolean {
        deployConfigsCalled++
        return deployConfigsResult
    }

    override suspend fun deployJars(): Boolean {
        deployJarsCalled++
        return deployJarsResult
    }

    override fun getScriptPath(name: String): String = "/fake/scripts/$name"

    override fun getJarPath(name: String): String = "/fake/jars/$name"

    override fun invalidateDeployCache() {
        invalidateCalled = true
    }
}
