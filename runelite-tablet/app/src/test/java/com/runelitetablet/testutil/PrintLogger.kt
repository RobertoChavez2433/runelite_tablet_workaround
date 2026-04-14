package com.runelitetablet.testutil

import com.runelitetablet.domain.logging.Logger

/**
 * Test Logger that prints to stdout and collects entries for assertions.
 * Android boundary fake — replaces AppLog which needs Android Log + SystemClock.
 */
class PrintLogger : Logger {
    data class Entry(
        val level: String, val tag: String, val message: String,
        val throwable: Throwable? = null, val correlationId: String? = null
    )

    val entries = mutableListOf<Entry>()

    override fun d(tag: String, message: String, correlationId: String?) {
        entries.add(Entry("DEBUG", tag, message, correlationId = correlationId))
        println("D/$tag: $message${corrSuffix(correlationId)}")
    }

    override fun i(tag: String, message: String, correlationId: String?) {
        entries.add(Entry("INFO", tag, message, correlationId = correlationId))
        println("I/$tag: $message${corrSuffix(correlationId)}")
    }

    override fun w(tag: String, message: String, correlationId: String?) {
        entries.add(Entry("WARN", tag, message, correlationId = correlationId))
        println("W/$tag: $message${corrSuffix(correlationId)}")
    }

    override fun e(tag: String, message: String, throwable: Throwable?, correlationId: String?) {
        entries.add(Entry("ERROR", tag, message, throwable, correlationId))
        println("E/$tag: $message${corrSuffix(correlationId)}")
        throwable?.printStackTrace()
    }

    fun clear() = entries.clear()

    fun entriesWithTag(tag: String) = entries.filter { it.tag == tag }
    fun errorsOnly() = entries.filter { it.level == "ERROR" }

    private fun corrSuffix(correlationId: String?) = if (correlationId != null) " corr=$correlationId" else ""
}
