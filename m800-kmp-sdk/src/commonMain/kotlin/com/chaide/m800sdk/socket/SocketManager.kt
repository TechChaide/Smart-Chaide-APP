package com.chaide.m800sdk.socket

import com.chaide.m800sdk.callback.IDeviceCallback
import com.chaide.m800sdk.callback.IDeviceManager
import com.chaide.m800sdk.log.SdkLog
import com.chaide.m800sdk.manager.DeviceManager
import com.chaide.m800sdk.platform.NetworkMonitor
import com.chaide.m800sdk.platform.currentTimeMillis
import com.chaide.m800sdk.platform.sleepMillis
import com.chaide.m800sdk.protocol.CallbackData
import com.chaide.m800sdk.protocol.ConnectionState
import com.chaide.m800sdk.protocol.DataPackBlockingQueue
import com.chaide.m800sdk.util.ByteUtil
import com.chaide.m800sdk.util.PortableBuffer

/**
 * Port of `com.sleepace.sdk.manager.socket.SocketManager`. `Context`
 * parameters from the original (`checkSocketConnectState(Context, ...)`)
 * are dropped — their only use was an optional connectivity pre-check,
 * now served by [NetworkMonitor] directly.
 */
abstract class SocketManager protected constructor() : DeviceManager(), IDeviceCallback {

    protected val helper: SocketHelper = SocketHelper.getInstance()
    protected var currentFrame: SocketFrame? = null
    protected var receiveQueue: DataPackBlockingQueue<SocketFrame>? = DataPackBlockingQueue()
    private var needLogin = true
    private var receivedBytes: PortableBuffer? = null

    private val mEndValues = "\$_@-".encodeToByteArray()

    private val socketCallback = object : IDeviceCallback {
        override fun handleData(data: ByteArray) = this@SocketManager.handleData(data)
        override fun onStateChanged(manager: IDeviceManager?, state: ConnectionState) = this@SocketManager.callbackState(state)
        override fun getMaster(): IDeviceManager = this@SocketManager
    }

    init {
        helper.registCallback(socketCallback)
    }

    fun isNeedLogin() = needLogin
    fun setNeedLogin(value: Boolean) { needLogin = value }

    override fun getMaster(): IDeviceManager = this

    override fun release() {
        SocketFrame.channelId = 0
        helper.disConnect(false)
        helper.unregistCallback(socketCallback)
        receiveQueue = null
    }

    override fun disconnect() = disconnect(true)

    fun disconnect(needCallback: Boolean) {
        SocketFrame.channelId = 0
        helper.disConnect(needCallback)
    }

    /**
     * Implements `IDeviceManager.connectDevice(address, timeout)`, but for every
     * TCP manager in this SDK the second argument is actually the server port
     * (matches the original Java `SocketManager.connectDevice(String ip, int port)`,
     * which satisfies the same interface method).
     */
    override fun connectDevice(address: String, timeout: Int) {
        SdkLog.log("$tag connectDevice----------ip:$address,port:$timeout,connS:$connectionStateField")
        connectType = ConnectType.TCP
        callbackState(ConnectionState.CONNECTING)
        helper.connect(this, address, timeout)
    }

    override fun connectDevice(address: String, deviceType: com.chaide.m800sdk.protocol.DeviceType?, timeout: Int) {}

    override fun onStateChanged(manager: IDeviceManager?, state: ConnectionState) {}

    fun loginByUser(msgType: Short, userId: Int, pwd: String?): Boolean {
        sleepMillis(1000)
        val pwdData = (pwd ?: "").encodeToByteArray()
        val buffer = PortableBuffer.allocate(1024)
        buffer.put(0).putInt(userId).put(pwdData)
        val data = requestServer(toArray(buffer), msgType)
        SdkLog.log("$tag login by user cd:$data")
        return data.isSuccess
    }

    fun loginBySid(msgType: Short, sidIn: String?): Boolean {
        val sid = sidIn ?: ""
        var data: CallbackData<Any?>? = null
        try {
            val buffer = PortableBuffer.allocate(1024)
            buffer.put(0).put(sid.encodeToByteArray())
            for (i in 0 until 3) {
                data = requestServer(toArray(buffer), msgType)
                SdkLog.log("$tag login by sid i:$i,cd:$data")
                if (data.isSuccess) break
                sleepMillis(500)
            }
        } catch (e: Exception) {
            // matches original
        }
        return data?.isSuccess ?: false
    }

    protected fun toArray(buffer: PortableBuffer): ByteArray {
        buffer.limit(buffer.position)
        return buffer.array.copyOfRange(0, buffer.limit)
    }

    override fun getConnectionState(): ConnectionState {
        connectionStateField = if (helper.isSocketConnected()) ConnectionState.CONNECTED else helper.getConnectState()
        return connectionStateField
    }

    protected fun sendFrameList(frames: List<SocketFrame>) {
        for (f in frames) sendData(SocketFrame.frame2Byte(f))
    }

    protected open fun sendData(data: ByteArray) {
        helper.sendByteBuffer2Server(data)
    }

    fun requestServerAync(msgType: Short, deviceType: Short = 0, deviceId: String = "") {
        requestServerAync(ByteArray(0), msgType, deviceType, deviceId)
    }

    fun requestServerAync(msg: ByteArray, msgType: Short, deviceType: Short = 0, deviceId: String = "") {
        sendMsg2ServerSync(msg, com.chaide.m800sdk.protocol.FrameType.REQUEST, msgType, deviceType, deviceId)
    }

    fun requestServer(msgType: Short, deviceType: Short = 0, deviceId: String = ""): CallbackData<Any?> =
        requestServer(ByteArray(0), msgType, deviceType, deviceId)

    fun requestServer(msg: ByteArray, msgType: Short, deviceId: String): CallbackData<Any?> =
        requestServer(msg, msgType, 0, deviceId)

    fun requestServer(msg: ByteArray, msgType: Short, deviceType: Short = 0, deviceId: String = ""): CallbackData<Any?> =
        sendMsg2Server(msg, com.chaide.m800sdk.protocol.FrameType.REQUEST, msgType, deviceType, deviceId)

    fun postNoticeAync(msg: ByteArray, deviceType: Short = 0, deviceId: String = "") {
        sendMsg2ServerSync(msg, com.chaide.m800sdk.protocol.FrameType.POST, SocketFrame.REQUEST_NOTICE, deviceType, deviceId)
    }

    fun postMsg(msgType: Short, deviceType: Short = 0, deviceId: String = ""): CallbackData<Any?> =
        postMsg(ByteArray(0), msgType, deviceType, deviceId)

    fun postMsg(msg: ByteArray, msgType: Short, deviceType: Short = 0, deviceId: String = ""): CallbackData<Any?> =
        sendMsg2Server(msg, com.chaide.m800sdk.protocol.FrameType.POST, msgType, deviceType, deviceId)

    fun postNotice(noticeCode: Short, deviceType: Short = 0, deviceId: String = ""): CallbackData<Any?> =
        postNotice(ByteUtil.short2byte(noticeCode), deviceType, deviceId)

    fun postNotice(msg: ByteArray, deviceType: Short = 0, deviceId: String = ""): CallbackData<Any?> =
        sendMsg2Server(msg, com.chaide.m800sdk.protocol.FrameType.POST, SocketFrame.REQUEST_NOTICE, deviceType, deviceId)

    fun sendMsg2ServerSync(msg: ByteArray, type: Byte, msgType: Short, deviceType: Short, deviceId: String) {
        val data = sendMsg2Server(msg, type, msgType, deviceType, deviceId)
        dataCallback(data)
    }

    override fun isConnected(): Boolean = helper.isSocketConnected()

    fun sendMsg2Server(msg: ByteArray, frameType: Byte, msgType: Short, deviceType: Short, deviceId: String): CallbackData<Any?> {
        val data = CallbackData<Any?>()
        try {
            if (isConnected()) {
                val serialNo = sendDataInternal(msg, frameType, msgType, deviceType, deviceId)
                SdkLog.log("$tag putMsg2Server type:$frameType,msgType:$msgType,deviceType:$deviceType,serailNo:$serialNo")
                val startTime = currentTimeMillis()
                while (true) {
                    val frame = receiveQueue?.peek(serialNo)
                    if (frame != null) {
                        val status = ByteUtil.byte2short(frame.msgContent, 0)
                        if (status.toInt() == 0) {
                            data.status = 0
                            when (frame.frameType) {
                                com.chaide.m800sdk.protocol.FrameType.ACK -> parseAck(data, frame)
                                com.chaide.m800sdk.protocol.FrameType.POST -> parsePost(frame)
                                com.chaide.m800sdk.protocol.FrameType.RESPONSE -> parseRespone(data, frame)
                            }
                        } else {
                            data.status = status
                        }
                        return data
                    }
                    if (currentTimeMillis() - startTime >= 3000L) break
                    sleepMillis(10)
                }
                data.status = com.chaide.m800sdk.protocol.StatusCode.TIMEOUT
            } else {
                data.status = com.chaide.m800sdk.protocol.StatusCode.DISCONNECT
            }
        } catch (e: Exception) {
            // matches original
        }
        return data
    }

    fun sendMsg2Server(data: ByteArray, frameSeq: Int): CallbackData<Any?> {
        SdkLog.log("$tag sendMsg2Server connS:${isConnected()},frameSeq:$frameSeq")
        val callbackData = CallbackData<Any?>()
        try {
            if (isConnected()) {
                receiveQueue?.addSendPack(frameSeq)
                sendData(data)
                val startTime = currentTimeMillis()
                while (true) {
                    val frame = receiveQueue?.peek(frameSeq)
                    if (frame != null) {
                        callbackData.status = frame.msgStatus
                        callbackData.result = frame.resObj
                        return callbackData
                    }
                    if (currentTimeMillis() - startTime >= 3000L) break
                    sleepMillis(10)
                }
                callbackData.status = com.chaide.m800sdk.protocol.StatusCode.TIMEOUT
            } else {
                callbackData.status = com.chaide.m800sdk.protocol.StatusCode.DISCONNECT
            }
        } catch (e: Exception) {
            // matches original
        }
        return callbackData
    }

    override fun handleData(data: ByteArray) {
        var buf: PortableBuffer = receivedBytes ?: PortableBuffer.allocate(1024)
        receivedBytes = buf
        for (i in data.indices) {
            val by = data[i]
            buf.put(by)
            if (by == mEndValues[mEndValues.size - 1]) {
                var isEnd = i >= mEndValues.size - 1
                if (isEnd) {
                    for (j in 1 until mEndValues.size) {
                        if (data[i - j] != mEndValues[mEndValues.size - 1 - j]) { isEnd = false; break }
                    }
                }
                if (isEnd) {
                    val src = buf.array.copyOfRange(0, buf.position)
                    if (src.size <= 8) {
                        receivedBytes = null
                        return
                    }
                    val crc32 = com.chaide.m800sdk.util.PortableCRC32()
                    crc32.update(src, 0, src.size - 8)
                    val c = crc32.getValue()
                    if (c == (ByteUtil.byte2Int(src, src.size - 8).toLong() and 0xFFFFFFFFL)) {
                        buf = PortableBuffer.allocate(1024)
                        receivedBytes = buf
                        val bean = SocketFrame.byte2Frame(src)
                        val existing = currentFrame
                        currentFrame = when {
                            existing == null || bean.frameNum.toInt() == 0 -> bean
                            existing.frameNum < existing.frameCount - 1 -> {
                                existing.msgContent = existing.msgContent + bean.msgContent
                                existing.frameNum = bean.frameNum
                                existing
                            }
                            else -> bean
                        }
                        val frame = currentFrame!!
                        if (frame.frameNum + 1 == frame.frameCount.toInt()) {
                            when (frame.frameType) {
                                com.chaide.m800sdk.protocol.FrameType.POST -> {
                                    if (frame.msgType.toInt() == 0) {
                                        sendFrameList(SocketFrame.getResponeHeartFrames(frame.frameSeq))
                                    } else {
                                        parsePost(frame)
                                    }
                                }
                                com.chaide.m800sdk.protocol.FrameType.REQUEST -> {
                                    val cd = CallbackData<Any?>()
                                    cd.status = 0
                                    cd.msgType = frame.msgType
                                    parseRequest(cd, frame)
                                    dataCallback(cd)
                                }
                                else -> receiveQueue?.offer(frame, frame.frameSeq)
                            }
                        }
                    } else {
                        receivedBytes = PortableBuffer.allocate(1024)
                    }
                }
            }
        }
    }

    protected open fun parseRequest(data: CallbackData<Any?>, frame: SocketFrame) {}
    protected open fun parseRespone(data: CallbackData<Any?>, frame: SocketFrame) {}
    protected open fun parseAck(data: CallbackData<Any?>, frame: SocketFrame) {}
    protected open fun parsePost(frame: SocketFrame) {}

    fun checkSocketConnectState(callbackType: String, notifyType: Short = 0): Boolean =
        checkSocketConnectState(callbackType, notifyType, null)

    fun checkSocketConnectState(callbackType: String, notifyType: Short, result: Any?): Boolean {
        if (!checkNetworkState(callbackType, notifyType, result)) return false
        if (!helper.isSocketConnected()) {
            val data = CallbackData<Any?>()
            data.callbackType = callbackType
            data.result = result
            data.status = com.chaide.m800sdk.protocol.StatusCode.DISCONNECT
            dataCallback(data)
            return false
        }
        return true
    }

    fun checkNetworkState(callbackType: String, notifyType: Short, result: Any?): Boolean {
        if (!NetworkMonitor.isConnected()) {
            val data = CallbackData<Any?>()
            data.callbackType = callbackType
            data.result = result
            data.status = com.chaide.m800sdk.protocol.StatusCode.NOT_ENABLE
            dataCallback(data)
            return false
        }
        return true
    }

    private fun sendDataInternal(msg: ByteArray, frameType: Byte, msgType: Short, deviceType: Short, deviceId: String): Int {
        if (!isConnected() && connectType == ConnectType.TCP) {
            helper.connect(this)
        }
        var frameSeq = 0
        try {
            if (isConnected()) {
                val list = SocketFrame.createFrames(msg, frameType, msgType, deviceType, deviceId)
                if (list.isNotEmpty()) {
                    frameSeq = list[0].frameSeq.toInt()
                    receiveQueue?.addSendPack(frameSeq)
                }
                sendFrameList(list)
            }
        } catch (e: Exception) {
            // matches original
        }
        return frameSeq
    }
}
