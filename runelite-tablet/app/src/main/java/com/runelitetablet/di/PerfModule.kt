package com.runelitetablet.di

import com.runelitetablet.domain.logging.Logger
import com.runelitetablet.perf.CpuAffinityMonitor
import com.runelitetablet.perf.CpuBooster
import com.runelitetablet.perf.PerfDashboard
import com.runelitetablet.perf.PipelineBenchmark
import com.runelitetablet.perf.PtraceOverheadEstimator
import com.runelitetablet.perf.VirglEnvironmentProfiler

/**
 * Phase 0 observability components. Wired into AppContainer.
 * All classes use /proc filesystem readers that only work on Android/Linux.
 */
class PerfModule(logger: Logger) {
    val affinityMonitor = CpuAffinityMonitor(logger)
    val ptraceEstimator = PtraceOverheadEstimator(logger)
    val dashboard = PerfDashboard(logger, affinityMonitor, ptraceEstimator)
    val benchmark = PipelineBenchmark(logger)
    val virglProfiler = VirglEnvironmentProfiler(logger)
    val cpuBooster = CpuBooster(logger)
}
