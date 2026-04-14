package com.runelitetablet.logging

import android.content.Context
import android.os.StatFs

/**
 * Performance snapshot helpers for memory and disk metrics.
 * Extracted from AppLog for separation of concerns.
 */
object PerfSnapshots {
    fun memorySnapshot(): String {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L
        val freeMb = rt.freeMemory() / 1_048_576L
        val maxMb = rt.maxMemory() / 1_048_576L
        return "heap: used=${usedMb}MB free=${freeMb}MB max=${maxMb}MB"
    }

    fun diskSnapshot(context: Context): String {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            "disk: free=${freeBytes / 1_048_576L}MB total=${totalBytes / 1_048_576L}MB"
        } catch (e: Exception) {
            "disk: unavailable (${e.message})"
        }
    }

    fun perfSnapshot(context: Context): String =
        "${memorySnapshot()} | ${diskSnapshot(context)}"
}
