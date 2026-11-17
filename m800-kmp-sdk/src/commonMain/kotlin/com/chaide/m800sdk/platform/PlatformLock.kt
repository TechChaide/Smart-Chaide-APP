package com.chaide.m800sdk.platform

/**
 * Minimal cross-platform mutex. Original Java SDK used `synchronized`
 * (JVM-only); this is the smallest portable replacement.
 */
expect class PlatformLock() {
    fun <T> withLock(block: () -> T): T
}
