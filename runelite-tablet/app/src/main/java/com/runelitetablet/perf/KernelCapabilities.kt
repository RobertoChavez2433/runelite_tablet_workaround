package com.runelitetablet.perf

/**
 * Parses kernel capability check output to determine ptrace reduction options.
 */
data class KernelCapabilities(
    val kernelVersion: String,
    val seccompViable: Boolean,
    val userNsViable: Boolean
) {
    companion object {
        fun parse(lines: List<String>): KernelCapabilities {
            var kernelVersion = ""
            var seccompViable = false
            var userNsViable = false

            for (line in lines) {
                if (!line.startsWith("KERNEL_CAPS:")) continue
                val payload = line.substringAfter("KERNEL_CAPS:").trim()

                when {
                    payload.startsWith("kernel_version=") ->
                        kernelVersion = payload.substringAfter("kernel_version=")
                    payload.startsWith("seccomp_viable=") -> {
                        seccompViable = payload.contains("seccomp_viable=true")
                        userNsViable = payload.contains("user_ns_viable=true")
                    }
                }
            }

            return KernelCapabilities(kernelVersion, seccompViable, userNsViable)
        }
    }
}
