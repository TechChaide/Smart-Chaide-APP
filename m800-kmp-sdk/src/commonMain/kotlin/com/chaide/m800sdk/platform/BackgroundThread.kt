package com.chaide.m800sdk.platform

/** Starts `block` on a background worker/thread and returns a handle that can interrupt it. */
expect class BackgroundThread(name: String, block: () -> Unit) {
    fun start()
    fun interrupt()
    val isAlive: Boolean
}

expect fun sleepMillis(ms: Long)

expect fun currentTimeMillis(): Long
