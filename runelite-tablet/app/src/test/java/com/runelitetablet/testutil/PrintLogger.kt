package com.runelitetablet.testutil

import com.runelitetablet.domain.logging.Logger

/**
 * Test Logger that prints to stdout and collects entries for assertions.
 * Android boundary fake — replaces AppLog which needs Android Log + SystemClock.
 */
class PrintLogger : Logger {
    data class Entry(val level: String, val tag: String, val message: String, val throwable: Throwable? = null)

    val entries = mutableListOf<Entry>()

    override fun d(tag: String, message: String) {
        entries.add(Entry("DEBUG", tag, message))
        println("D/$tag: $message")
    }

    override fun i(tag: String, message: String) {
        entries.add(Entry("INFO", tag, message))
        println("I/$tag: $message")
    }

    override fun w(tag: String, message: String) {
        entries.add(Entry("WARN", tag, message))
        println("W/$tag: $message")
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        entries.add(Entry("ERROR", tag, message, throwable))
        println("E/$tag: $message")
        throwable?.printStackTrace()
    }

    fun clear() = entries.clear()

    fun entriesWithTag(tag: String) = entries.filter { it.tag == tag }
    fun errorsOnly() = entries.filter { it.level == "ERROR" }
}
