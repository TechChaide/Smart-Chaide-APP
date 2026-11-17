package com.chaide.m800sdk.protocol

/** Port of `com.sleepace.sdk.manager.Queue` — fixed-size ring buffer used to detect the "$_@-" frame terminator. */
class Queue(private val size: Int) {
    var front: Int = 0
    var rear: Int = 0
    val data: ByteArray = ByteArray(size)
    private val endValues: ByteArray = "\$_@-".encodeToByteArray()

    fun size(): Int = data.size

    fun clear() {
        rear = 0
        front = 0
        for (i in 0 until size()) data[i] = 0
    }

    fun full(): Boolean = (rear + 1) % size() == front
    fun empty(): Boolean = rear == front

    fun get(index: Int): Byte = data[(front + index) % size()]

    fun read(): Int {
        if (!empty()) {
            val value = data[front]
            front = (front + 1) % size()
            return value.toInt() and 0xFF
        }
        return 65535
    }

    fun write(value: Byte) {
        if (!full()) {
            data[rear] = value
            rear = (rear + 1) % size()
        }
    }

    fun tryMatchEnd(): Boolean {
        if (full()) {
            for (i in 0 until size) {
                if (get(i) != endValues[i]) {
                    read()
                    return false
                }
            }
            return true
        }
        return false
    }
}
