package com.chaide.m800sdk.protocol

import com.chaide.m800sdk.platform.PlatformLock

/**
 * Port of `com.sleepace.sdk.manager.DataPackBlockingQueue`. Original extended
 * `HashMap` directly and relied on `synchronized`; here composition + an
 * explicit [PlatformLock] stand in for both.
 */
class DataPackBlockingQueue<T> {
    private val store = HashMap<String, T>()
    private val pendingKeys = ArrayList<String>()
    private val lock = PlatformLock()

    fun addSendPack(key: Any) {
        val k = key.toString()
        lock.withLock {
            if (!pendingKeys.contains(k)) pendingKeys.add(k)
        }
    }

    fun offer(value: T, key: Any): Boolean {
        val k = key.toString()
        lock.withLock {
            if (pendingKeys.contains(k)) {
                store[k] = value
                pendingKeys.remove(k)
            }
        }
        return false // matches original's (likely unintentional) always-false return
    }

    fun peek(key: Any): T? {
        val k = key.toString()
        return lock.withLock { store.remove(k) }
    }

    fun clear() {
        lock.withLock {
            store.clear()
            pendingKeys.clear()
        }
    }
}
