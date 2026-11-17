package com.chaide.m800sdk.util

/**
 * Standard zlib CRC-32 (polynomial 0xEDB88320), the same algorithm as
 * `java.util.zip.CRC32`. That class is JVM-only and unavailable on
 * Kotlin/Native, so `SocketFrame`/`BlePacket` framing (which both call it
 * directly, with no `android.*` import — another hidden JVM dependency)
 * needed a portable replacement with identical output.
 */
@OptIn(ExperimentalUnsignedTypes::class)
class PortableCRC32 {
    private var crc: UInt = 0xFFFFFFFFu

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        for (i in offset until offset + length) {
            val b = bytes[i].toUInt() and 0xFFu
            crc = TABLE[((crc xor b) and 0xFFu).toInt()] xor (crc shr 8)
        }
    }

    fun getValue(): Long = ((crc.inv()) and 0xFFFFFFFFu).toLong()

    companion object {
        private val TABLE: UIntArray = UIntArray(256).also { table ->
            for (n in 0 until 256) {
                var c = n.toUInt()
                repeat(8) {
                    c = if (c and 1u != 0u) (0xEDB88320u xor (c shr 1)) else (c shr 1)
                }
                table[n] = c
            }
        }
    }
}
