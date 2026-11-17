package com.chaide.m800sdk.socket

import com.chaide.m800sdk.protocol.Queue
import com.chaide.m800sdk.util.ByteUtil
import com.chaide.m800sdk.util.PortableCRC32

/** Faithful port of `com.sleepace.sdk.manager.socket.SocketFrame`. */
class SocketFrame {
    var gallery: Int = 0
    var versonCode: Byte = 0
    var from: Byte = 0
    var frameType: Byte = 0
    var frameCount: Short = 0
    var frameNum: Short = 0
    var frameSeq: Short = 0
    var msgType: Short = 0
    var msgStatus: Short = 0
    var msgContent: ByteArray = ByteArray(0)
    var resObj: Any? = null
    var specialIndentify: Short = 0
    var deviceType: Short = 0

    fun getPackageSize(): Int = 23 + msgContent.size

    override fun toString(): String =
        "{verCode:$versonCode,from:$from,gallery:$gallery,frameType:$frameType,fCount:$frameCount," +
            "fNum:$frameNum,frameSeq:$frameSeq,msgType:0x${msgType.toString(16)},deviceType:0x${deviceType.toString(16)}," +
            "msgContent:${msgContent.contentToString()}}"

    object Z3MsgType {
        const val GET_DEVICE_INFO: Short = 514
    }

    object Z4MsgType {
        const val GET_DEVICE_INFO: Short = 518
    }

    companion object {
        var channelId: Int = 0
        const val SUCCESS: Short = 0
        const val FAIL: Short = 256
        const val HEARTPACKAGE: Short = 0
        const val FRAME_END = "\$_@-"
        const val REQUEST_SOCKET_ADDRESS_GET: Short = 1028
        const val REQUEST_REALDATA: Short = 1280
        const val REQUEST_RAWDATA: Short = 1286
        const val REQUEST_NOTICE: Short = 256
        const val REQUEST_GET_UPDATEINFO: Int = 4552855
        const val REQUEST_LOGIN_BY_USER: Short = 512
        const val REQUEST_LOGIN: Short = 517
        const val REQUEST_GET_COLLECT_STATE: Short = 1026
        const val REQUEST_GET_ONLINE_STATE: Short = 1025
        const val REQUEST_GET_DEVICE_WIFI_STATE: Short = 1184
        const val REQUEST_GET_DEVICE_WIFI_SSID: Short = 929
        const val REQUEST_SET_WIFI_CONFIG: Short = 928
        const val REQUEST_SET_SERVER_ADDRESS: Short = 930

        private var serialCode: Short = 0
        var fromCodeValue: Byte = 1

        fun setFromCode(connNetCode: Byte) {
            fromCodeValue = ((connNetCode.toInt() shl 3) or 1).toByte()
        }

        fun createNewSerialCode(): Short {
            serialCode = (serialCode + 1).toShort()
            if (serialCode < 0) serialCode = 1
            return serialCode
        }

        fun frame2Byte(bean: SocketFrame): ByteArray {
            val bb = ArrayList<Byte>()
            bb.add(bean.versonCode)
            bb.add(fromCodeValue)
            addInt2List(bean.gallery, bb)
            bb.add(bean.frameType)
            addShort2List(bean.frameCount, bb)
            addShort2List(bean.frameNum, bb)
            addShort2List(bean.frameSeq, bb)
            if (bean.specialIndentify.toInt() != 0) addShort2List(bean.specialIndentify, bb)
            if (bean.deviceType.toInt() != 0) addShort2List(bean.deviceType, bb)
            addShort2List(bean.msgType, bb)
            for (b in bean.msgContent) bb.add(b)

            val raw = bb.toByteArray()
            val crc32 = PortableCRC32()
            crc32.update(raw)
            val c = (crc32.getValue() and 0xFFFFFFFFL).toInt()
            addInt2List(c, bb)
            for (b in FRAME_END.encodeToByteArray()) bb.add(b)
            return bb.toByteArray()
        }

        fun createFrames(msg: ByteArray, frameType: Byte, msgType: Short, deviceType: Short, deviceId: String): List<SocketFrame> =
            createFrames(msg, frameType, msgType, deviceType, createNewSerialCode(), deviceId)

        fun createFrames(
            msg: ByteArray,
            frameType: Byte,
            msgType: Short,
            deviceType: Short,
            frameSeq: Short,
            deviceId: String
        ): List<SocketFrame> {
            val pages = ArrayList<ByteArray>()
            val queue = Queue(4)
            val splitPoints = ArrayList<Int>()
            for (i in msg.indices) {
                queue.write(msg[i])
                if (queue.full()) {
                    if (queue.tryMatchEnd()) {
                        splitPoints.add(i - 2)
                        queue.clear()
                    } else {
                        queue.read()
                    }
                }
            }
            var p = 0
            for (index in splitPoints) {
                pages.add(msg.copyOfRange(p, index))
                p = index
            }
            if (p < msg.size) pages.add(msg.copyOfRange(p, msg.size))

            val results = ArrayList<SocketFrame>()
            if (pages.isNotEmpty()) {
                for (i in pages.indices) {
                    results.add(createFrame(deviceType, deviceId, frameType, msgType, frameSeq, pages[i], pages.size.toShort(), i.toShort()))
                }
            } else {
                results.add(createFrame(deviceType, deviceId, frameType, msgType, frameSeq, null, 1, 0))
            }
            return results
        }

        private fun createFrame(
            deviceType: Short,
            deviceId: String,
            frameType: Byte,
            msgType: Short,
            frameSeq: Short,
            page: ByteArray?,
            count: Short,
            num: Short
        ): SocketFrame {
            val bean = SocketFrame()
            if (deviceType.toInt() != 0) {
                bean.deviceType = deviceType
                bean.specialIndentify = -15536
            }
            bean.frameSeq = frameSeq
            bean.frameCount = count
            bean.frameNum = num
            bean.from = fromCodeValue
            bean.gallery = if (deviceId.isNotEmpty()) deviceId.hashCode() else 0
            bean.msgContent = page ?: ByteArray(0)
            bean.msgType = msgType
            bean.frameType = frameType
            bean.versonCode = 0
            return bean
        }

        fun addInt2List(source: Int, to: MutableList<Byte>) {
            for (b in ByteUtil.int2byte(source)) to.add(b)
        }

        fun addShort2List(source: Short, to: MutableList<Byte>) {
            for (b in ByteUtil.short2byte(source)) to.add(b)
        }

        fun byte2Frame(pcg: ByteArray): SocketFrame {
            val bean = SocketFrame()
            bean.versonCode = pcg[0]
            bean.from = pcg[1]
            bean.gallery = ByteUtil.byte2Int(pcg, 2)
            bean.frameType = pcg[6]
            bean.frameCount = ByteUtil.byte2short(pcg, 7)
            bean.frameNum = ByteUtil.byte2short(pcg, 9)
            bean.frameSeq = ByteUtil.byte2short(pcg, 11)
            val specialIndentify = ByteUtil.byte2short(pcg, 13)
            if (specialIndentify.toInt() == -15536) {
                bean.deviceType = ByteUtil.byte2short(pcg, 15)
                bean.specialIndentify = -15536
                bean.msgType = ByteUtil.byte2short(pcg, 17)
                val content = pcg.copyOfRange(19, pcg.size - 8)
                bean.msgStatus = ByteUtil.byte2short(content, 0)
                bean.msgContent = content
            } else {
                bean.msgType = ByteUtil.byte2short(pcg, 13)
                val content = pcg.copyOfRange(15, pcg.size - 8)
                bean.msgStatus = ByteUtil.byte2short(content, 0)
                bean.msgContent = content
            }
            return bean
        }

        fun sendHeartFrames(helper: SocketHelper) {
            val frames = createFrames(ByteArray(0), FrameTypePost, 0, 0, createNewSerialCode(), "")
            for (f in frames) helper.writeBytes(frame2Byte(f))
        }

        fun getResponeHeartFrames(serialNo: Short): List<SocketFrame> =
            createFrames(ByteUtil.short2byte(0), FrameTypeAck, 0, 0, serialNo, "")

        // Named to avoid clashing with the FrameType object's constants while keeping call sites short.
        private const val FrameTypePost: Byte = 1
        private const val FrameTypeAck: Byte = 0

        fun needDeviceIdCode(msgType: Short, noticeType: Short): Boolean {
            return when (msgType.toInt()) {
                256 -> noticeType.toInt() != 66
                32, 34, 66, 512, 517, 1025, 1027, 1184 -> false
                0, 1, 2, 3, 4, 7, 8, 9, 10, 15, 16, 48, 49, 50, 51, 52, 55, 56, 57, 58,
                67, 68, 69, 80, 81, 82, 83, 84, 768, 770, 800, 802, 803, 804, 805, 806,
                813, 815, 817, 819, 820, 928, 929, 930, 1026, 1028, 1044, 1280, 1286 -> true
                else -> false
            }
        }
    }
}
