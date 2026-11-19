package com.chaide.m800sdk.platform

import kotlin.concurrent.AtomicInt
import platform.Foundation.NSDate
import platform.Foundation.NSThread
import platform.Foundation.timeIntervalSince1970
import platform.posix.usleep

/**
 * Respaldado por `NSThread.detachNewThreadWithBlock`, la forma mas simple de
 * correr un bucle bloqueante de larga vida (lectura/escritura de socket) en
 * Kotlin/Native para Apple sin cinterop adicional.
 */
actual class BackgroundThread actual constructor(name: String, private val block: () -> Unit) {
    private val threadName = name
    private val stopped = AtomicInt(0)
    private val running = AtomicInt(0)

    actual fun start() {
        running.value = 1
        NSThread.detachNewThreadWithBlock {
            NSThread.currentThread.name = threadName
            try {
                if (stopped.value == 0) block()
            } finally {
                running.value = 0
            }
        }
    }

    // Kotlin/Native no tiene `Thread.interrupt()`; todos los bucles de este
    // modulo (ver `SocketHelper`) revisan una bandera en cada iteracion, asi
    // que una parada cooperativa es equivalente.
    actual fun interrupt() { stopped.value = 1 }

    actual val isAlive: Boolean get() = running.value == 1
}

actual fun sleepMillis(ms: Long) {
    if (ms > 0) usleep((ms * 1000).toUInt())
}

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
