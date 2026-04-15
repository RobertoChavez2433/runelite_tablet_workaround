package com.runelitetablet.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelCapabilityTest {

    @Test
    fun `parseCapabilities detects seccomp viable when both filter and proot support present`() {
        val output = listOf(
            "KERNEL_CAPS: start",
            "KERNEL_CAPS: kernel_version=5.15.137-android14-11-g123abc",
            "KERNEL_CAPS: seccomp_filter=CONFIG_SECCOMP_FILTER=y",
            "KERNEL_CAPS: seccomp=CONFIG_SECCOMP=y",
            "KERNEL_CAPS: proot_seccomp=supported",
            "KERNEL_CAPS: user_ns=unsupported",
            "KERNEL_CAPS: seccomp_viable=true user_ns_viable=false",
            "KERNEL_CAPS: done"
        )

        val caps = KernelCapabilities.parse(output)
        assertTrue(caps.seccompViable)
        assertFalse(caps.userNsViable)
        assertEquals("5.15.137-android14-11-g123abc", caps.kernelVersion)
    }

    @Test
    fun `parseCapabilities detects user namespaces when supported`() {
        val output = listOf(
            "KERNEL_CAPS: kernel_version=6.1.0",
            "KERNEL_CAPS: proot_seccomp=unsupported",
            "KERNEL_CAPS: user_ns=supported",
            "KERNEL_CAPS: seccomp_viable=false user_ns_viable=true",
            "KERNEL_CAPS: done"
        )

        val caps = KernelCapabilities.parse(output)
        assertFalse(caps.seccompViable)
        assertTrue(caps.userNsViable)
    }

    @Test
    fun `parseCapabilities handles neither available`() {
        val output = listOf(
            "KERNEL_CAPS: kernel_version=5.10.0",
            "KERNEL_CAPS: seccomp_viable=false user_ns_viable=false",
            "KERNEL_CAPS: done"
        )

        val caps = KernelCapabilities.parse(output)
        assertFalse(caps.seccompViable)
        assertFalse(caps.userNsViable)
    }

    @Test
    fun `parseCapabilities handles empty output`() {
        val caps = KernelCapabilities.parse(emptyList())
        assertFalse(caps.seccompViable)
        assertFalse(caps.userNsViable)
        assertEquals("", caps.kernelVersion)
    }
}
