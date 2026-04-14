package com.runelitetablet.termux

import com.runelitetablet.domain.command.CommandResult
import com.runelitetablet.logging.AppLog
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

    fun createExecutionId(): Int = counter.incrementAndGet().also { id ->
        AppLog.d("CMD", "TermuxResultRegistry: created executionId=$id", correlationId = id.toString())
    }

    fun register(executionId: Int): CompletableDeferred<CommandResult> {
        val deferred = CompletableDeferred<CommandResult>()
        pendingResults[executionId] = deferred
        AppLog.d("CMD", "TermuxResultRegistry: registered pending=${pendingResults.size}", correlationId = executionId.toString())
        return deferred
    }

    fun complete(executionId: Int, result: CommandResult) {
        val deferred = pendingResults.remove(executionId)
        if (deferred != null) {
            AppLog.d("CMD", "TermuxResultRegistry: completed exitCode=${result.exitCode} pending=${pendingResults.size}", correlationId = executionId.toString())
            deferred.complete(result)
        } else {
            AppLog.w("CMD", "TermuxResultRegistry: orphan result (no waiting deferred)", correlationId = executionId.toString())
        }
    }
}
