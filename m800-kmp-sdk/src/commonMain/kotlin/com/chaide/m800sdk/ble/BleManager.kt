package com.chaide.m800sdk.ble

import com.chaide.m800sdk.callback.IDeviceCallback
import com.chaide.m800sdk.callback.IDeviceManager
import com.chaide.m800sdk.callback.IResultCallback
import com.chaide.m800sdk.log.SdkLog
import com.chaide.m800sdk.manager.DeviceManager
import com.chaide.m800sdk.platform.BackgroundThread
import com.chaide.m800sdk.platform.currentTimeMillis
import com.chaide.m800sdk.platform.sleepMillis
import com.chaide.m800sdk.protocol.CallbackData
import com.chaide.m800sdk.protocol.ConnectionState
import com.chaide.m800sdk.protocol.DataPackBlockingQueue
import com.chaide.m800sdk.protocol.DeviceType
import com.chaide.m800sdk.protocol.StatusCode

/**
 * Port of `com.sleepace.sdk.manager.ble.BleManager`. The original wrapped
 * the `BleHelper` GATT singleton directly; this wraps the common
 * [BleTransport] contract instead, so the retry/timeout/queueing logic
 * (all of it, genuinely common) runs identically on every platform.
 */
abstract class BleManager protected constructor(
    protected val transport: BleTransport
) : DeviceManager(), IDeviceCallback {

    private val receiveDataPack = DataPackBlockingQueue<DataPacket>()
    private var writeThread: BackgroundThread? = null
    private val waitSendQueue = ArrayDeque<DataPacket>()
    private var timeoutFlag = true

    private val listener = object : BleTransportListener {
        override fun onDataReceived(data: ByteArray) = handleData(data)
        override fun onConnectionStateChanged(state: BleConnState) {
            val mapped = when (state) {
                BleConnState.CONNECTING -> ConnectionState.CONNECTING
                BleConnState.CONNECTED -> ConnectionState.CONNECTED
                BleConnState.DISCONNECTED -> ConnectionState.DISCONNECT
            }
            onStateChanged(this@BleManager, mapped)
        }
    }

    init {
        transport.setListener(listener)
    }

    fun scanBleDevice() = transport.scan()
    fun stopScan() = transport.stopScan()
    fun setMtu(mtu: Int) = transport.setMtu(mtu)

    fun start() {
        SdkLog.log("$tag start----------")
        writeThread = BackgroundThread("ble-write") { writeLoop() }.also { it.start() }
    }

    override fun release() {
        transport.setListener(null)
        disconnect(false)
    }

    override fun handleData(data: ByteArray) {
        timeoutFlag = false
    }

    private fun writeLoop() {
        while (isConnected()) {
            val packet = waitSendQueue.removeFirstOrNull()
            if (packet == null) {
                sleepMillis(10)
                continue
            }
            sendPurePack(packet)
        }
    }

    fun checkBluetoothState(callbackType: String, needCallback: Boolean = true): Boolean {
        if (!isBluetoothOpen()) {
            if (needCallback) {
                val cd = CallbackData<Any?>()
                cd.callbackType = callbackType
                cd.status = StatusCode.NOT_ENABLE
                dataCallback(cd)
            }
            return false
        }
        if (!isConnected()) {
            if (needCallback) {
                val cd = CallbackData<Any?>()
                cd.callbackType = callbackType
                cd.status = StatusCode.DISCONNECT
                dataCallback(cd)
            }
            return false
        }
        return true
    }

    override fun onStateChanged(manager: IDeviceManager?, state: ConnectionState) {
        if (state == ConnectionState.CONNECTED) start()
        callbackState(state)
    }

    override fun getMaster(): IDeviceManager = this

    fun requestDevice(msgType: Short, basePack: DataPacket.BasePacket = DataPacket.BasePacket(), timeout: Int = 3000): CallbackData<Any?> =
        sendDevice(DataPacket.PacketType.FA_REQUEST, msgType, basePack, timeout)

    fun postDevice(msgType: Short, basePack: DataPacket.BasePacket = DataPacket.BasePacket(), timeout: Int = 3000): CallbackData<Any?> =
        sendDevice(DataPacket.PacketType.FA_POST, msgType, basePack, timeout)

    fun ackDevice(frameSeq: Byte, msgType: Short, msgSeq: Short, basePack: DataPacket.BasePacket = DataPacket.BasePacket()) {
        val pack = buildDataPacket(DataPacket.PacketType.FA_ACK, frameSeq.toShort(), msgType, msgSeq, basePack)
        waitSendQueue.addLast(pack)
    }

    fun responseDevice(frameNum: Short, frameSeq: Short, msgType: Short, statusCode: Short = 0) {
        sendDevice(DataPacket.PacketType.FA_RESPONSE, frameNum, msgType, frameSeq, DataPacket.BaseRspPacket(statusCode.toInt()))
    }

    fun requestAsycDevice(msgType: Short, basePack: DataPacket.BasePacket = DataPacket.BasePacket(), callbackType: String) {
        val result = sendDevice(DataPacket.PacketType.FA_REQUEST, msgType, basePack)
        result.callbackType = callbackType
        dataCallback(result)
    }

    fun requestPureDevice(msgType: Short, basePack: DataPacket.BasePacket = DataPacket.BasePacket()) {
        if (isConnected()) {
            val pack = buildDataPacket(DataPacket.PacketType.FA_REQUEST, msgType, basePack)
            waitSendQueue.addLast(pack)
        }
    }

    fun postAsycDevice(msgType: Short, basePack: DataPacket.BasePacket = DataPacket.BasePacket()) {
        val result = sendDevice(DataPacket.PacketType.FA_POST, msgType, basePack)
        dataCallback(result)
    }

    protected fun sendDevice(frameType: Byte, msgType: Short, basePack: DataPacket.BasePacket, timeout: Int = 3000): CallbackData<Any?> {
        val pack = buildDataPacket(frameType, msgType, basePack)
        return sendPacket(pack, timeout)
    }

    protected fun sendDevice(frameType: Byte, frameNum: Short, msgType: Short, frameSeq: Short, basePack: DataPacket.BasePacket) {
        val pack = buildDataPacket(frameType, frameNum, msgType, frameSeq, basePack)
        sendPurePack(pack)
    }

    override fun isConnected(): Boolean {
        if (!transport.isConnected() || transport.getBondedAddress() != getAddress()) {
            connectionStateField = ConnectionState.DISCONNECT
        }
        return super.isConnected()
    }

    fun sendPacket(pack: DataPacket, timeout: Int): CallbackData<Any?> {
        val callbackData = CallbackData<Any?>()
        callbackData.msgType = pack.msg?.type?.toShort() ?: 0
        if (!isConnected()) {
            callbackData.status = StatusCode.DISCONNECT
            return callbackData
        }
        val effectiveTimeout = if (timeout == 0) 3000 else timeout
        timeoutFlag = true
        val key = pack.buildCallbackKey()
        if (key != null) receiveDataPack.addSendPack(key)
        waitSendQueue.addLast(pack)
        val startTime = currentTimeMillis()
        while (true) {
            val result = key?.let { receiveDataPack.peek(it) }
            if (result != null) {
                callbackData.data = result.msg?.getPacketBody()
                val rsp = result.msg?.content as? DataPacket.BaseRspPacket
                callbackData.status = if (rsp != null && rsp.rspCode == 0) 0 else StatusCode.FAIL
                callbackData.result = rsp
                return callbackData
            }
            if (currentTimeMillis() - startTime >= effectiveTimeout && timeoutFlag) break
            sleepMillis(10)
        }
        callbackData.status = StatusCode.TIMEOUT
        return callbackData
    }

    protected fun sendPurePack(pack: DataPacket?) {
        val buf = pack?.buffer ?: return
        val total = buf.position
        val data = buf.array.copyOfRange(0, total)
        var offset = 0
        var remaining = total
        while (true) {
            val count = if (remaining < transport.getMtu()) remaining else transport.getMtu()
            transport.send(data.copyOfRange(offset, offset + count))
            offset += count
            remaining -= count
            if (remaining <= 0) break
        }
    }

    fun isBluetoothOpen(): Boolean = transport.isBluetoothOpen()
    fun isSupportBle(): Boolean = transport.isSupportBle()

    final override fun connectDevice(address: String, timeout: Int) = connectDevice(address, null, timeout)

    override fun connectDevice(address: String, deviceType: DeviceType?, timeout: Int) {
        SdkLog.log("$tag connectDevice connS:$connectionStateField,address:$address")
        if (!isBluetoothOpen()) {
            callbackState(ConnectionState.DISCONNECT)
            return
        }
        connectType = ConnectType.BLE
        this.addressField = address
        setDeviceType(deviceType)
        if (isConnected()) {
            callbackState(ConnectionState.CONNECTED)
            return
        }
        disconnect(false)
        transport.connect(address, timeout)
    }

    override fun disconnect() = disconnect(true)

    fun disconnect(needCallback: Boolean) {
        transport.disconnect()
        writeThread = null
        waitSendQueue.clear()
    }

    override fun deviceOper(frameType: Byte, msgType: Byte, payload: ByteArray, timeout: Int, callback: IResultCallback<Any?>?) {}
    override fun deviceOper(frameType: Byte, msgType: Byte, payload: ByteArray, timeout: Int): CallbackData<Any?>? = null

    abstract fun buildDataPacket(frameType: Byte, msgType: Short, basePack: DataPacket.BasePacket): DataPacket
    abstract fun buildDataPacket(frameType: Byte, frameNum: Short, msgType: Short, frameSeq: Short, basePack: DataPacket.BasePacket): DataPacket
    abstract override fun getDeviceVersion(timeout: Int)
}
