package com.runelitetablet.domain.logging

import java.util.UUID

/**
 * Generates short correlation IDs for cross-boundary tracing.
 * Format: {action}-{shortUuid} (e.g., launch-a3f7, auth-b2c1).
 */
object CorrelationId {
    fun generate(action: String): String = "$action-${UUID.randomUUID().toString().take(4)}"

    fun nested(parent: String, child: String): String = "$parent/$child-${UUID.randomUUID().toString().take(4)}"
}
