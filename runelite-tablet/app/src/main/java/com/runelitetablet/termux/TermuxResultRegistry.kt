package com.runelitetablet.termux

import com.runelitetablet.domain.command.CommandResult
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Holds pending Termux command results. Shared between TermuxCommandRunner (producer)
 * and TermuxResultService (consumer). Lives in AppContainer — no more global static.
 */
class TermuxResultRegistry {
    val pendingResults = ConcurrentHashMap<Int, CompletableDeferred<CommandResult>>()
    private val counter = AtomicInteger(0)

    fun createExecutionId(): Int = counter.incrementAndGet()
}
