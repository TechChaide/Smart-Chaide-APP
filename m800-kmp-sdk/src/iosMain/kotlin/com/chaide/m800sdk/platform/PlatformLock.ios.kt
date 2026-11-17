package com.chaide.m800sdk.platform

import platform.Foundation.NSLock

actual class PlatformLock actual constructor() {
    private val nsLock = NSLock()
    actual fun <T> withLock(block: () -> T): T {
        nsLock.lock()
        try {
            return block()
        } finally {
            nsLock.unlock()
        }
    }
}
