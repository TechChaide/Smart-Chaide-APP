package com.chaide.m800sdk.m800

import com.chaide.m800sdk.callback.IBaseCallback
import com.chaide.m800sdk.callback.IDeviceManager
import com.chaide.m800sdk.callback.IResultCallback
import com.chaide.m800sdk.log.SdkLog
import com.chaide.m800sdk.m800.domain.CollectState
import com.chaide.m800sdk.m800.domain.EnvironmentData
import com.chaide.m800sdk.m800.domain.OriginalData
import com.chaide.m800sdk.m800.domain.RealTimeData
import com.chaide.m800sdk.m800.domain.SleepState
import com.chaide.m800sdk.platform.PlatformLock
import com.chaide.m800sdk.protocol.CallbackData
import com.chaide.m800sdk.protocol.ConnectionState
import com.chaide.m800sdk.protocol.DeviceType
import com.chaide.m800sdk.protocol.StatusCode
import com.chaide.m800sdk.socket.SocketFrame
import com.chaide.m800sdk.socket.SocketManager
import com.chaide.m800sdk.util.ByteUtil
import com.chaide.m800sdk.util.PortableBuffer

fun interface OnlineStateListener { fun onlineStateChanged(deviceType: Short, deviceId: String, onlineState: Byte) }
fun interface RawDataListener { fun onReceive(data: OriginalData) }
fun interface RealtimeDataListener { fun onReceive(data: RealTimeData) }
fun interface RealtimeSleepStateListener { fun onReceive(data: SleepState) }
fun interface SleepReportUploadStateListener { fun onStateChanged(state: Byte) }
fun interface WiFiSignalListener { fun onWiFiSignalChanged(deviceId: String, signal: Byte) }

/**
 * Port of `com.sleepace.sdk.m800.M800Manager` — the TCP gateway protocol
 * logic exercised live by this repo's `Gateway.java`. `Context` plumbing
 * from the original is gone; everything else (login, heartbeat, request/
 * post framing, listener fan-out) is a direct port.
 */
class M800Manager private constructor() : SocketManager(), IBaseCallback {

    private val onlineStateListeners = ArrayList<OnlineStateListener>()
    private val sleepReportStateListeners = ArrayList<SleepReportUploadStateListener>()
    private val realtimeDataListeners = ArrayList<RealtimeDataListener>()
    private val rawDataListeners = ArrayList<RawDataListener>()
    private val realtimeSleepStateListeners = ArrayList<RealtimeSleepStateListener>()
    private val wiFiSignalListeners = ArrayList<WiFiSignalListener>()

    init {
        registCallBack(this)
    }

    fun registOnlineStateListener(l: OnlineStateListener) { if (!onlineStateListeners.contains(l)) onlineStateListeners.add(l) }
    fun unregistOnlineStateListener(l: OnlineStateListener) { onlineStateListeners.remove(l) }
    fun registSleepReportUploadStateListener(l: SleepReportUploadStateListener) { if (!sleepReportStateListeners.contains(l)) sleepReportStateListeners.add(l) }
    fun unregistSleepReportUploadStateListener(l: SleepReportUploadStateListener) { sleepReportStateListeners.remove(l) }
    fun registRealtimeDataListener(l: RealtimeDataListener) { if (!realtimeDataListeners.contains(l)) realtimeDataListeners.add(l) }
    fun unregistRealtimeDataListener(l: RealtimeDataListener) { realtimeDataListeners.remove(l) }
    fun registRawDataListener(l: RawDataListener) { if (!rawDataListeners.contains(l)) rawDataListeners.add(l) }
    fun unregistRawDataListener(l: RawDataListener) { rawDataListeners.remove(l) }
    fun registRealtimeSleepStateListener(l: RealtimeSleepStateListener) { if (!realtimeSleepStateListeners.contains(l)) realtimeSleepStateListeners.add(l) }
    fun unregistRealtimeSleepStateListener(l: RealtimeSleepStateListener) { realtimeSleepStateListeners.remove(l) }
    fun registWiFiSignalListener(l: WiFiSignalListener) { if (!wiFiSignalListeners.contains(l)) wiFiSignalListeners.add(l) }
    fun unregistWiFiSignalListener(l: WiFiSignalListener) { wiFiSignalListeners.remove(l) }

    fun queryDeviceOnlineState(deviceType: Short, deviceId: String) {
        if (deviceId.isEmpty()) { parameterErr(CallbackType.ONLINE_STATE_GET); return }
        if (!checkSocketConnectState(CallbackType.ONLINE_STATE_GET)) return
        val buf = ByteArray(16)
        ByteUtil.str2Byte(deviceId).copyInto(buf)
        ByteUtil.short2byte(deviceType).copyInto(buf, 14)
        val data = requestServer(buf, M800DataPacket.MsgType.QUERY_ONLINE_STATE, deviceId)
        data.callbackType = CallbackType.ONLINE_STATE_GET
        dataCallback(data)
    }

    fun queryCollectState(deviceType: Short, deviceId: String, num: Int) {
        if (deviceId.isEmpty()) { parameterErr(CallbackType.COLLECT_STATUS_GET); return }
        if (!checkSocketConnectState(CallbackType.COLLECT_STATUS_GET)) return
        val buf = ByteArray(17)
        ByteUtil.str2Byte(deviceId).copyInto(buf)
        ByteUtil.short2byte(deviceType).copyInto(buf, 14)
        buf[buf.size - 1] = num.toByte()
        val data = requestServer(buf, M800DataPacket.MsgType.QUERY_COLLECT_STATE, deviceId)
        data.callbackType = CallbackType.COLLECT_STATUS_GET
        dataCallback(data)
    }

    fun querySleepState(deviceType: Short, deviceId: String, num: Int) {
        if (deviceId.isEmpty()) { parameterErr(CallbackType.SLEEP_STATE_GET); return }
        if (!checkSocketConnectState(CallbackType.SLEEP_STATE_GET)) return
        val buf = ByteArray(17)
        ByteUtil.str2Byte(deviceId).copyInto(buf)
        ByteUtil.short2byte(deviceType).copyInto(buf, 14)
        buf[buf.size - 1] = num.toByte()
        val data = requestServer(buf, M800DataPacket.MsgType.QUERY_SLEEP_STATE, deviceId)
        data.callbackType = CallbackType.SLEEP_STATE_GET
        dataCallback(data)
    }

    fun queryEnvironmentData(deviceId: String) {
        if (deviceId.isEmpty()) { parameterErr(CallbackType.ENVIRONMENT_DATA_GET); return }
        if (!checkSocketConnectState(CallbackType.ENVIRONMENT_DATA_GET)) return
        val data = requestServer(M800DataPacket.MsgType.QUERY_ENVIRONMENT_DATA, deviceId = deviceId)
        data.callbackType = CallbackType.ENVIRONMENT_DATA_GET
        dataCallback(data)
    }

    fun startRealTimeData(deviceType: Short, deviceId: String, num: Int) {
        if (deviceId.isEmpty()) { parameterErr(CallbackType.REALTIME_DATA_START); return }
        if (!checkSocketConnectState(CallbackType.REALTIME_DATA_START)) return
        val buf = ByteArray(19)
        ByteUtil.short2byte(M800DataPacket.MsgType.REALTIME_DATA_OPEN).copyInto(buf)
        ByteUtil.str2Byte(deviceId).copyInto(buf, 2)
        ByteUtil.short2byte(deviceType).copyInto(buf, 16)
        buf[18] = num.toByte()
        val data = postNotice(buf, deviceId = deviceId)
        data.callbackType = CallbackType.REALTIME_DATA_START
        dataCallback(data)
    }

    fun stopRealTimeData(deviceType: Short, deviceId: String, num: Int) {
        if (deviceId.isEmpty()) { parameterErr(CallbackType.REALTIME_DATA_STOP); return }
        if (!checkSocketConnectState(CallbackType.REALTIME_DATA_STOP)) return
        val buf = ByteArray(19)
        ByteUtil.short2byte(M800DataPacket.MsgType.REALTIME_DATA_CLOSE).copyInto(buf)
        ByteUtil.str2Byte(deviceId).copyInto(buf, 2)
        ByteUtil.short2byte(deviceType).copyInto(buf, 16)
        buf[18] = num.toByte()
        val data = postNotice(buf, deviceId = deviceId)
        data.callbackType = CallbackType.REALTIME_DATA_STOP
        dataCallback(data)
    }

    fun startSeeRawData(deviceType: Short, deviceId: String, num: Int) {
        if (!checkSocketConnectState(CallbackType.RAW_DATA_START)) return
        val buf = ByteArray(19)
        ByteUtil.short2byte(M800DataPacket.MsgType.RAW_DATA_OPEN).copyInto(buf)
        ByteUtil.str2Byte(deviceId).copyInto(buf, 2)
        ByteUtil.short2byte(deviceType).copyInto(buf, 16)
        buf[18] = num.toByte()
        val data = postNotice(buf, deviceId = deviceId)
        data.callbackType = CallbackType.RAW_DATA_START
        dataCallback(data)
    }

    fun stopSeeRawData(deviceType: Short, deviceId: String, num: Int) {
        if (!checkSocketConnectState(CallbackType.RAW_DATA_STOP)) return
        val buf = ByteArray(19)
        ByteUtil.short2byte(M800DataPacket.MsgType.RAW_DATA_CLOSE).copyInto(buf)
        ByteUtil.str2Byte(deviceId).copyInto(buf, 2)
        ByteUtil.short2byte(deviceType).copyInto(buf, 16)
        buf[18] = num.toByte()
        val data = postNotice(buf, deviceId = deviceId)
        data.callbackType = CallbackType.RAW_DATA_STOP
        dataCallback(data)
    }

    fun stopCollection(userId: Int, deviceType: Short, deviceId: String, num: Int, timestamp: Int) {
        if (deviceId.isEmpty()) { parameterErr(CallbackType.COLLECT_STOP); return }
        if (!checkSocketConnectState(CallbackType.COLLECT_STOP)) return
        val buf = ByteArray(27)
        ByteUtil.short2byte(M800DataPacket.MsgType.COLLECT_STOP).copyInto(buf)
        ByteUtil.str2Byte(deviceId).copyInto(buf, 2)
        ByteUtil.short2byte(deviceType).copyInto(buf, 16)
        buf[18] = num.toByte()
        ByteUtil.int2byte(userId).copyInto(buf, 19)
        ByteUtil.int2byte(timestamp).copyInto(buf, 23)
        val data = postNotice(buf, deviceId = deviceId)
        data.callbackType = CallbackType.COLLECT_STOP
        dataCallback(data)
    }

    fun upgradeDevice(deviceVersion: Float, deviceType: Short, deviceId: String) {
        if (deviceVersion < 0f || deviceId.isEmpty()) { parameterErr(CallbackType.DEVICE_UPGRADE); return }
        if (!checkSocketConnectState(CallbackType.DEVICE_UPGRADE)) return
        val buf = ByteArray(21)
        ByteUtil.short2byte(M800DataPacket.MsgType.DEVICE_UPGRADE).copyInto(buf)
        ByteUtil.str2Byte(deviceId).copyInto(buf, 2)
        ByteUtil.short2byte(deviceType).copyInto(buf, 16)
        buf[18] = 3
        val versionCode = (deviceVersion * 100f).toInt().toShort()
        ByteUtil.short2byte(versionCode).copyInto(buf, 19)
        val data = postNotice(buf, deviceId = deviceId)
        data.callbackType = CallbackType.DEVICE_UPGRADE
        data.result = 0
        dataCallback(data)
    }

    private fun parameterErr(callbackType: String) {
        val data = CallbackData<Any?>()
        data.callbackType = callbackType
        data.status = StatusCode.PARAMETER_ERROR
        dataCallback(data)
    }

    override fun onResultCallback(data: CallbackData<Any?>) {}

    override fun release() {
        super.release()
        unRegistCallBack(this)
    }

    override fun parseRespone(data: CallbackData<Any?>, frame: SocketFrame) {
        val buffer = PortableBuffer.wrap(frame.msgContent)
        val status = frame.msgStatus
        val msgType = frame.msgType
        SdkLog.log("$tag parse response msgType:$msgType,status:$status")
        when (msgType.toInt()) {
            517 -> {
                SocketFrame.channelId = frame.gallery
                data.result = status
            }
            1025 -> {
                buffer.position(2)
                val idBytes = ByteArray(14)
                buffer.get(idBytes)
                buffer.position(2 + idBytes.size)
                buffer.getShort()
                buffer.position(2 + idBytes.size + 2)
                val onlineState = buffer.get()
                data.result = onlineState
            }
            1188 -> {
                buffer.position(2)
                val idBytes = ByteArray(14)
                buffer.get(idBytes)
                buffer.position(2 + idBytes.size)
                buffer.getShort()
                buffer.position(2 + idBytes.size + 2)
                val signal = buffer.get()
                data.result = signal
            }
            1031 -> {
                buffer.position(2)
                val env = EnvironmentData()
                env.temperature = buffer.get()
                env.humidity = buffer.get()
                data.result = env
            }
            1026 -> {
                buffer.position(2 + 14 + 2 + 1)
                val state = buffer.get()
                val startTime = buffer.getInt()
                data.result = CollectState(state, startTime)
            }
            1030 -> {
                buffer.position(2 + 14 + 2 + 1)
                val sleepState = SleepState()
                sleepState.asleepFlag = buffer.get()
                sleepState.wakeupFlag = buffer.get()
                sleepState.outOfBedFlag = buffer.get()
                buffer.get() // state (unused, matches original)
                if (buffer.hasRemaining()) sleepState.situpFlag = buffer.get()
                data.result = sleepState
            }
            else -> data.result = status
        }
    }

    override fun parsePost(frame: SocketFrame) {
        val buffer = PortableBuffer.wrap(frame.msgContent)
        val msgType = frame.msgType
        SdkLog.log("$tag parse post msgType:$msgType")
        when (msgType.toInt()) {
            1188 -> {
                val idBytes = ByteArray(14)
                buffer.get(idBytes)
                val deviceId = idBytes.decodeToString().trim()
                buffer.position(idBytes.size)
                buffer.getShort()
                val signal = buffer.get()
                callbackWiFiSignal(deviceId, signal)
            }
            1536 -> {
                val idBytes = ByteArray(14)
                buffer.get(idBytes)
                buffer.position(idBytes.size)
                buffer.getShort()
                buffer.get() // leftRight
                val count = buffer.get().toInt() and 0xFF
                var first: RealTimeData? = null
                for (i in 0 until count) {
                    val bean = RealTimeData.fromBuffer(buffer)
                    if (first == null) first = bean
                }
                first?.let { callbackRealtimeData(it) }
            }
            1286 -> {
                val idBytes = ByteArray(14)
                buffer.get(idBytes)
                buffer.position(idBytes.size)
                buffer.getShort()
                buffer.get() // leftRight
                buffer.getInt() // startIndex
                buffer.getShort() // interval
                val count = buffer.getShort().toInt() and 0xFF
                val rawData = IntArray(count) { buffer.getShort().toInt() and 0xFF }
                callbackRawData(OriginalData(rawData = rawData))
            }
            1030 -> {
                val idBytes = ByteArray(14)
                buffer.get(idBytes)
                buffer.position(idBytes.size)
                buffer.getShort()
                buffer.get() // leftRight
                val sleepState = SleepState()
                sleepState.asleepFlag = buffer.get()
                sleepState.wakeupFlag = buffer.get()
                sleepState.outOfBedFlag = buffer.get()
                buffer.get() // state
                sleepState.situpFlag = buffer.get()
                callbackRealtimeSleepState(sleepState)
            }
            256 -> {
                when (buffer.getShort().toInt()) {
                    34 -> buffer.getInt() // sleepdata, unused
                    33 -> {
                        val idBytes = ByteArray(14)
                        buffer.get(idBytes)
                        buffer.getShort() // deviceType
                        val upgradeState = buffer.get()
                        val progress = buffer.get()
                        val data = CallbackData<Any?>()
                        data.status = if (upgradeState.toInt() != 2) 0 else StatusCode.FAIL
                        data.callbackType = CallbackType.DEVICE_UPGRADE
                        data.result = progress
                        dataCallback(data)
                    }
                    80 -> sleepReportUploadStateChanged(buffer.get())
                }
            }
            1025 -> {
                val idBytes = ByteArray(14)
                buffer.get(idBytes)
                val deviceId = idBytes.decodeToString().trim()
                buffer.position(idBytes.size)
                val deviceType = buffer.getShort()
                val onlineState = buffer.get()
                onlineStateChanged(deviceType, deviceId, onlineState)
            }
        }
    }

    private fun onlineStateChanged(deviceType: Short, deviceId: String, onlineState: Byte) {
        for (l in onlineStateListeners) l.onlineStateChanged(deviceType, deviceId, onlineState)
    }

    private fun sleepReportUploadStateChanged(state: Byte) {
        for (l in sleepReportStateListeners) l.onStateChanged(state)
    }

    private fun callbackRealtimeData(data: RealTimeData) {
        for (l in realtimeDataListeners) l.onReceive(data)
    }

    private fun callbackRawData(data: OriginalData) {
        for (l in rawDataListeners) l.onReceive(data)
    }

    private fun callbackWiFiSignal(deviceId: String, signal: Byte) {
        for (l in wiFiSignalListeners) l.onWiFiSignalChanged(deviceId, signal)
    }

    private fun callbackRealtimeSleepState(data: SleepState) {
        for (l in realtimeSleepStateListeners) l.onReceive(data)
    }

    override fun deviceOper(frameType: Byte, msgType: Byte, payload: ByteArray, timeout: Int, callback: IResultCallback<Any?>?) {}
    override fun deviceOper(frameType: Byte, msgType: Byte, payload: ByteArray, timeout: Int): CallbackData<Any?>? = null
    override fun getDeviceVersion(timeout: Int) {}
    override fun getDeviceInfo(timeout: Int) {}

    override fun getHeartbeatData(): ByteArray {
        val frames = SocketFrame.createFrames(ByteArray(0), com.chaide.m800sdk.protocol.FrameType.POST, 0, 0, SocketFrame.createNewSerialCode(), "")
        return SocketFrame.frame2Byte(frames[0])
    }

    companion object {
        private var instance: M800Manager? = null
        private val lock = PlatformLock()

        fun getInstance(): M800Manager = lock.withLock {
            instance ?: M800Manager().also { instance = it }
        }
    }
}
