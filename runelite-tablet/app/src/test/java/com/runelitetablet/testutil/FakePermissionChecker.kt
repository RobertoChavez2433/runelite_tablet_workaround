package com.runelitetablet.testutil

import com.runelitetablet.domain.setup.PermissionChecker

class FakePermissionChecker : PermissionChecker {
    var permissionsVerified = true
    var notificationPermission = true
    var batteryExemptionsComplete = true
    var nextBatteryPackage: String? = null

    override suspend fun verifyPermissions(): Boolean = permissionsVerified
    override fun hasNotificationPermission(): Boolean = notificationPermission
    override fun hasRequiredBatteryExemptions(): Boolean = batteryExemptionsComplete
    override fun getNextBatteryOptimizationPackage(): String? = nextBatteryPackage
}
