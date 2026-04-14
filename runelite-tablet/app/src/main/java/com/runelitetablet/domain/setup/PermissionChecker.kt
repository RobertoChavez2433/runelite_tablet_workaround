package com.runelitetablet.domain.setup

interface PermissionChecker {
    suspend fun verifyPermissions(): Boolean
    fun hasNotificationPermission(): Boolean
    fun hasRequiredBatteryExemptions(): Boolean
    fun getNextBatteryOptimizationPackage(): String?
}
