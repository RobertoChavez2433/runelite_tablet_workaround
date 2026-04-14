package com.runelitetablet.logging

import java.util.concurrent.ConcurrentHashMap

/**
 * Debug-only file descriptor lifecycle tracker.
 * Registers every fd open/close/transfer/detach and logs leaks on demand.
 */
object FdTracker {
    data class FdEntry(val fd: Int, val path: String, val purpose: String, val openedAtMs: Long = System.currentTimeMillis(), val thread: String = Thread.currentThread().name)

    private val openFds = ConcurrentHashMap<Int, FdEntry>()

    fun opened(fd: Int, path: String, purpose: String) {
        openFds[fd] = FdEntry(fd, path, purpose)
        AppLog.fd("open", fd, path, "purpose=$purpose thread=${Thread.currentThread().name}")
    }

    fun closed(fd: Int, path: String, reason: String) {
        openFds.remove(fd)
        AppLog.fd("close", fd, path, reason)
    }

    fun transferred(oldFd: Int, newFd: Int, path: String) {
        openFds.remove(oldFd)
        openFds[newFd] = FdEntry(newFd, path, "transferred from fd=$oldFd")
        AppLog.fd("transfer", newFd, path, "oldFd=$oldFd")
    }

    fun detached(fd: Int, path: String, purpose: String) {
        // detachFd means the ParcelFileDescriptor gave up ownership — we now own the raw fd
        openFds[fd] = FdEntry(fd, path, purpose)
        AppLog.fd("detach", fd, path, purpose)
    }

    fun dumpLeaks(tag: String): Int {
        val snapshot = openFds.toMap()
        if (snapshot.isEmpty()) {
            AppLog.d("FD_TRACKER", "$tag: no open fds — clean")
            return 0
        }
        val now = System.currentTimeMillis()
        snapshot.forEach { (fd, entry) ->
            val ageMs = now - entry.openedAtMs
            AppLog.w("FD_TRACKER", "$tag: LEAK SUSPECT fd=$fd path=${entry.path} purpose=${entry.purpose} age=${ageMs}ms thread=${entry.thread}")
        }
        AppLog.w("FD_TRACKER", "$tag: ${snapshot.size} fds still open")
        return snapshot.size
    }

    fun clear() = openFds.clear()
}
