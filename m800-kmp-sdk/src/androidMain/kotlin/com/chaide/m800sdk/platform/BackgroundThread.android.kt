package com.chaide.m800sdk.platform

actual class BackgroundThread actual constructor(name: String, private val block: () -> Unit) {
    private val thread = Thread(block, name)
    actual fun start() { thread.start() }
    actual fun interrupt() { thread.interrupt() }
    actual val isAlive: Boolean get() = thread.isAlive
}

actual fun sleepMillis(ms: Long) {
    try { Thread.sleep(ms) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
}

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
