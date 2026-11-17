package com.chaide.m800sdk.ble

import com.chaide.m800sdk.util.PortableBuffer

/** Port of `com.sleepace.sdk.manager.ble.DataPacket` (BLE packet base classes), using [PortableBuffer] instead of `java.nio.ByteBuffer`. */
abstract class DataPacket {
    var head: BasePacketHead? = null
    var msg: BasePacketBody? = null
    var crc32: Int = 0
    var buffer: PortableBuffer? = null

    abstract fun buildCallbackKey(): Any?
    abstract fun check(buffer: PortableBuffer): Boolean
    abstract fun parse(buffer: PortableBuffer): Boolean
    abstract fun parseBuffer(buffer: PortableBuffer): PortableBuffer?
    abstract fun fillBuffer(buffer: PortableBuffer): PortableBuffer

    open class BasePacket {
        var obj: Any? = null
        open fun parseBuffer(buffer: PortableBuffer): PortableBuffer = buffer
        open fun fillBuffer(buffer: PortableBuffer): PortableBuffer = buffer
    }

    open class BasePacketBody(var type: Byte = 0, var content: BasePacket? = null) {
        private var packetBody: ShortArray? = null
        open fun parseBuffer(head: BasePacketHead, buffer: PortableBuffer): PortableBuffer? = buffer
        open fun fillBuffer(head: BasePacketHead, buffer: PortableBuffer): PortableBuffer = buffer
        fun setPacketBody(data: ShortArray?) { packetBody = data }
        fun getPacketBody(): ShortArray? = packetBody
        override fun toString(): String = "type:0x${type.toString(16)}"
    }

    abstract class BasePacketHead {
        var version: Byte = 0
        var frameType: Byte = 0
        var frameCount: Byte = 0
        var frameNum: Byte = 0
        var msgSeq: Byte = 0
        var deviceType: Short = 0

        abstract fun parseBuffer(buffer: PortableBuffer): PortableBuffer
        abstract fun fillBuffer(buffer: PortableBuffer): PortableBuffer

        override fun toString(): String = "frameType:$frameType,frameNum:$frameNum,msgSeq:$msgSeq"

        companion object {
            private var sequence: Byte = 0
            fun getSequence(): Byte {
                val b = sequence
                sequence = (sequence + 1).toByte()
                return b
            }
        }
    }

    open class BaseRspPacket(var rspCode: Int = 0, var byteOrShort: Boolean = false) : BasePacket() {
        override fun toString(): String = "BaseRspPack{rspCode=$rspCode}"
        override fun parseBuffer(buffer: PortableBuffer): PortableBuffer {
            rspCode = if (byteOrShort) buffer.get().toInt() else buffer.getShort().toInt()
            return buffer
        }
        override fun fillBuffer(buffer: PortableBuffer): PortableBuffer {
            if (byteOrShort) buffer.put(rspCode.toByte()) else buffer.putShort(rspCode.toShort())
            return buffer
        }
    }

    class CommonReq(private val data: ByteArray? = null) : BasePacket() {
        override fun fillBuffer(buffer: PortableBuffer): PortableBuffer {
            if (data != null && data.isNotEmpty()) buffer.put(data)
            return buffer
        }
    }

    object PacketType {
        const val FA_ACK: Byte = 0
        const val FA_POST: Byte = 1
        const val FA_REQUEST: Byte = 2
        const val FA_RESPONSE: Byte = 3
    }

    companion object {
        const val MAX_WRITE_SIZE: Byte = 20
    }
}
