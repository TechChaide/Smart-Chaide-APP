package com.chaide.m800sdk.platform

import kotlin.native.concurrent.AtomicReference
import platform.Foundation.NSThread
import platform.posix.usleep

/**
 * Backed by `NSThread.detachNewThreadWithBlock`, the simplest reliable way
 * to run a long-lived blocking loop (socket read/write) on Kotlin/Native
 * Apple targets without extra cinterop setup.
 */
actual class BackgroundThread actual constructor(name: String, private val block: () -> Unit) {
    private val threadName = name
    private val stopped = AtomicReference(false)
    private val running = AtomicReference(false)

    actual fun start() {
        running.value = true
        NSThread.detachNewThreadWithBlock {
            NSThread.currentThread.name = threadName
            if (!stopped.value) block()
            running.value = false
        }
    }

    // Kotlin/Native has no `Thread.interrupt()`; every loop in this module
    // (see `SocketHelper`) already checks an `isReading`/`isWriting`-style
    // flag each iteration, so a cooperative stop flag is equivalent here.
    actual fun interrupt() { stopped.value = true }

    actual val isAlive: Boolean get() = running.value
}

actual fun sleepMillis(ms: Long) {
    usleep((ms * 1000).toUInt())
}

actual fun currentTimeMillis(): Long {
    return (platform.Foundation.NSDate().timeIntervalSince1970 * 1000).toLong()
}
