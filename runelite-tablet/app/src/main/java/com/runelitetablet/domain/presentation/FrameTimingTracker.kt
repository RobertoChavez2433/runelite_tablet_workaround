package com.runelitetablet.domain.presentation

/**
 * Tracks frame intervals as pure math — no Android Surface needed.
 * Records nanosecond timestamps, calculates FPS, jank detection, percentiles.
 */
class FrameTimingTracker(private val capacity: Int = 240) {
    private val frameTimes = LongArray(capacity)
    private var index = 0
    private var count = 0

    fun recordFrame(timestampNs: Long) {
        frameTimes[index] = timestampNs
        index = (index + 1) % capacity
        if (count < capacity) count++
    }

    fun getAverageFps(windowSize: Int = 120): Double {
        val window = minOf(windowSize, count)
        if (window < 2) return 0.0

        val intervals = mutableListOf<Long>()
        for (i in 1 until window) {
            val curr = frameTimes[(index - i + capacity) % capacity]
            val prev = frameTimes[(index - i - 1 + capacity) % capacity]
            intervals.add(curr - prev)
        }

        val avgIntervalNs = intervals.average()
        return if (avgIntervalNs > 0) 1_000_000_000.0 / avgIntervalNs else 0.0
    }

    fun getFrameTimePercentile(p: Double): Double {
        if (count < 2) return 0.0

        val intervals = getIntervals()
        val sorted = intervals.sorted()
        val idx = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx] / 1_000_000.0 // ns -> ms
    }

    fun getJankFrameCount(thresholdMs: Double = 12.0): Int {
        if (count < 2) return 0
        val thresholdNs = (thresholdMs * 1_000_000).toLong()
        return getIntervals().count { it > thresholdNs }
    }

    fun getFrameCount(): Int = count

    private fun getIntervals(): List<Long> {
        val intervals = mutableListOf<Long>()
        for (i in 1 until count) {
            val curr = frameTimes[(index - i + capacity) % capacity]
            val prev = frameTimes[(index - i - 1 + capacity) % capacity]
            intervals.add(curr - prev)
        }
        return intervals
    }

    fun reset() {
        index = 0
        count = 0
    }
}
