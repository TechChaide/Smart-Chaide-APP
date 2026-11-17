package com.chaide.m800sdk.util

/**
 * Minimal stand-in for `java.nio.ByteBuffer` (big-endian), which is JVM-only
 * and unavailable on Kotlin/Native (iOS) `commonMain`. The original SDK used
 * `ByteBuffer` heavily for framing even in files with no `android.*` import,
 * so this — not just Android — was the second hidden non-portable dependency.
 * Implements only the subset of the API the ported SDK actually calls.
 */
class PortableBuffer private constructor(
    val array: ByteArray,
    var limit: Int
) {
    var position: Int = 0
        private set

    companion object {
        fun allocate(capacity: Int): PortableBuffer = PortableBuffer(ByteArray(capacity), capacity)
        fun wrap(bytes: ByteArray): PortableBuffer = PortableBuffer(bytes, bytes.size)
    }

    fun position(newPosition: Int): PortableBuffer {
        position = newPosition
        return this
    }

    fun limit(newLimit: Int): PortableBuffer {
        limit = newLimit
        return this
    }

    fun hasRemaining(): Boolean = position < limit

    fun get(): Byte = array[position++]

    fun get(dst: ByteArray, offset: Int, length: Int) {
        for (i in 0 until length) dst[offset + i] = array[position++]
    }

    fun get(dst: ByteArray) = get(dst, 0, dst.size)

    fun getShort(): Short {
        val v = ByteUtil.byte2short(array, position)
        position += 2
        return v
    }

    fun getShort(index: Int): Short = ByteUtil.byte2short(array, index)

    fun getInt(): Int {
        val v = ByteUtil.byte2Int(array, position)
        position += 4
        return v
    }

    fun getInt(index: Int): Int = ByteUtil.byte2Int(array, index)

    fun put(b: Byte): PortableBuffer {
        array[position++] = b
        return this
    }

    fun put(src: ByteArray): PortableBuffer {
        src.copyInto(array, position)
        position += src.size
        return this
    }

    fun put(src: ByteArray, offset: Int, length: Int): PortableBuffer {
        src.copyInto(array, position, offset, offset + length)
        position += length
        return this
    }

    fun putShort(value: Short): PortableBuffer {
        ByteUtil.short2byte(value).copyInto(array, position)
        position += 2
        return this
    }

    fun putInt(value: Int): PortableBuffer {
        ByteUtil.int2byte(value).copyInto(array, position)
        position += 4
        return this
    }

    /** Equivalent of `ByteBuffer.flip()`: limit = position, position = 0. */
    fun flip(): PortableBuffer {
        limit = position
        position = 0
        return this
    }
}
