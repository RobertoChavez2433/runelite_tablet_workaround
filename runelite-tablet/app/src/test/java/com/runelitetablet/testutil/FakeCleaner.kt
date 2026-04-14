package com.runelitetablet.testutil

import com.runelitetablet.domain.setup.Cleaner

class FakeCleaner : Cleaner {
    var cleanupCalled = false

    override suspend fun cleanup() {
        cleanupCalled = true
    }
}
