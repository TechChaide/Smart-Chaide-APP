package com.chaide.m800sdk.wificonfig

import com.chaide.m800sdk.platform.BackgroundThread

import com.chaide.m800sdk.ble.BleConnState
import com.chaide.m800sdk.ble.BleScanResult
import com.chaide.m800sdk.ble.BleTransport
import com.chaide.m800sdk.ble.BleTransportListener
import com.chaide.m800sdk.ble.DataPacket
import com.chaide.m800sdk.callback.IDeviceManager
import com.chaide.m800sdk.callback.IResultCallback
import com.chaide.m800sdk.log.SdkLog
import com.chaide.m800sdk.manager.DeviceManager
import com.chaide.m800sdk.platform.PlatformLock
import com.chaide.m800sdk.platform.sleepMillis
import com.chaide.m800sdk.protocol.CallbackData
import com.chaide.m800sdk.protocol.ConnectionState
import com.chaide.m800sdk.protocol.DeviceType
import com.chaide.m800sdk.protocol.StatusCode
import com.chaide.m800sdk.socket.SocketFrame
import com.chaide.m800sdk.socket.SocketManager
import com.chaide.m800sdk.util.ByteUtil
import com.chaide.m800sdk.util.PortableBuffer
import com.chaide.m800sdk.util.PortableCRC32

data class APNBean(var apn: String = "", var username: String = "", var password: String = "")
data class WifiDeviceInfo(var deviceId: String = "", var version: String = "")

/**
 * Port of `com.sleepace.sdk.wificonfig.WiFiConfigHelper` — the class the
 * Android app's `M800BleProvisioner.provisionWifi()` ultimately calls
 * (`bleWiFiConfig`). For M800 the wire format is the *same* `SocketFrame`
 * framing used over TCP (`isBleProtocol()` is only true for EW201W/Nox2W/
 * NoxSAW) — BLE here is just the transport, not a different protocol — so
 * this class reuses `SocketManager.handleData`/`parseRespone` unchanged and
 * only overrides `sendData` to route bytes through the [BleTransport] when
 * `connectType == BLE`.
 */
class WiFiConfigHelper private constructor(
    private val bleTransport: BleTransport
) : SocketManager() {

    private var serverAddress: String? = null
    private var serverPort: Int = 0
    private var ssidRaw: ByteArray? = null
    private var pwd: String = ""
    private var apn: String = ""
    private val pendingCallbacks = HashMap<String, IResultCallback<Any?>>()
    private var blePacket = BlePacket()

    companion object {
        const val WIFI_CONFIG_PORT = 65532
        private const val MSG_TYPE_APN_SET: Short = 4115
        private const val MSG_TYPE_APN_GET: Short = 4116
        const val CALLBACK_TYPE_CONFIG_WIFI_AP = "config_wifi_ap"
        const val CALLBACK_TYPE_CONFIG_WIFI_BLE = "config_wifi_ble"
        const val CALLBACK_TYPE_APN_SET = "apn_set"
        const val CALLBACK_TYPE_APN_GET = "apn_get"

        private var instance: WiFiConfigHelper? = null
        private val lock = PlatformLock()

        fun getInstance(bleTransport: BleTransport): WiFiConfigHelper = lock.withLock {
            instance ?: WiFiConfigHelper(bleTransport).also { instance = it }
        }
    }

    // El transporte BLE es compartido (uno por proceso) y la app tambien le pone
    // su propio listener para escanear. Antes este listener se registraba SOLO
    // en init, asi que desde el segundo aprovisionamiento el helper ya no
    // recibia eventos y se quedaba colgado: ahora se re-registra en cada uso.
    private val transportListener = object : BleTransportListener {
            override fun onScanResult(result: BleScanResult) {
                if (pendingCallbacks.containsKey(CALLBACK_TYPE_CONFIG_WIFI_BLE) && result.address == getAddress()) {
                    SdkLog.log("$tag config scan-------ok")
                    bleTransport.stopScan()
                    bleTransport.connect(getAddress()!!)
                }
            }
            override fun onScanStopped() {
                SdkLog.log("$tag config scan-------fail")
                getAddress()?.let { bleTransport.connect(it) }
            }
            override fun onConnectionStateChanged(state: BleConnState) {
                val mapped = when (state) {
                    BleConnState.CONNECTING -> ConnectionState.CONNECTING
                    BleConnState.CONNECTED -> ConnectionState.CONNECTED
                    BleConnState.DISCONNECTED -> ConnectionState.DISCONNECT
                }
                onBleStateChanged(mapped)
            }
            override fun onDataReceived(data: ByteArray) {
                if (isBleProtocol()) handleBleData(data) else handleData(data)
            }
        }

    init {
        bleTransport.setListener(transportListener)
        registCallBack(object : com.chaide.m800sdk.callback.IBaseCallback {
            override fun onStateChanged(manager: IDeviceManager?, state: ConnectionState) {
                SdkLog.log("$tag onStateChanged-----state:$state")
                if (state == ConnectionState.CONNECTED) {
                    if (pendingCallbacks.containsKey(CALLBACK_TYPE_CONFIG_WIFI_AP)) apWiFiConfig()
                } else if (state == ConnectionState.DISCONNECT && pendingCallbacks.containsKey(CALLBACK_TYPE_CONFIG_WIFI_AP)) {
                    resultCallback(CALLBACK_TYPE_CONFIG_WIFI_AP, StatusCode.DISCONNECT, null)
                }
            }
            override fun onResultCallback(data: CallbackData<Any?>) {
                val cb = pendingCallbacks.remove(data.callbackType)
                if (cb != null) {
                    cb.onResultCallback(data)
                    if (data.callbackType == CALLBACK_TYPE_CONFIG_WIFI_BLE || data.callbackType == CALLBACK_TYPE_APN_SET || data.callbackType == CALLBACK_TYPE_APN_GET) {
                        bleTransport.disconnect()
                    } else {
                        disconnect()
                    }
                }
            }
        })
    }

    override fun isConnected(): Boolean =
        if (connectType == ConnectType.TCP) super.isConnected() else bleTransport.isConnected()

    private fun onBleStateChanged(state: ConnectionState) {
        callbackState(state)
        if (state == ConnectionState.CONNECTED) {
            // El envio de la config es BLOQUEANTE (sleep + espera de cada
            // respuesta hasta 3 s). Antes corria dentro de este callback: en
            // Android llega en un hilo de binder y funcionaba, pero en iOS
            // CoreBluetooth lo entrega en el hilo PRINCIPAL -> la UI se
            // congelaba, las respuestas del equipo (tambien en el hilo
            // principal) nunca se procesaban y iOS terminaba cerrando la app.
            val job: (() -> Unit)? = when {
                pendingCallbacks.containsKey(CALLBACK_TYPE_CONFIG_WIFI_BLE) -> ::bleWiFiConfigInternal
                pendingCallbacks.containsKey(CALLBACK_TYPE_APN_SET) -> ::apnSetInternal
                pendingCallbacks.containsKey(CALLBACK_TYPE_APN_GET) -> ::apnGetInternal
                else -> null
            }
            job?.let { BackgroundThread("wifi-config") { it() }.start() }
        } else if (state == ConnectionState.DISCONNECT) {
            when {
                pendingCallbacks.containsKey(CALLBACK_TYPE_CONFIG_WIFI_BLE) -> resultCallback(CALLBACK_TYPE_CONFIG_WIFI_BLE, StatusCode.DISCONNECT, null)
                pendingCallbacks.containsKey(CALLBACK_TYPE_APN_SET) -> resultCallback(CALLBACK_TYPE_APN_SET, StatusCode.DISCONNECT, null)
                pendingCallbacks.containsKey(CALLBACK_TYPE_APN_GET) -> resultCallback(CALLBACK_TYPE_APN_GET, StatusCode.DISCONNECT, null)
            }
        }
    }

    private fun isBleProtocol(): Boolean =
        DeviceType.isEW201W(getDeviceType()) || DeviceType.isNox2W(getDeviceType()) || DeviceType.isNoxSAW(getDeviceType())

    private fun handleBleData(data: ByteArray) {
        for (b in data) {
            blePacket.packetBuffer.put(b)
            val pos = blePacket.packetBuffer.position
            if (pos >= 4 && blePacket.packetBuffer.getInt(pos - 4) == 610222125) {
                blePacket.packetBuffer.limit(pos)
                if (blePacket.check() && blePacket.parse() && blePacket.packetHead.frameType == DataPacket.PacketType.FA_RESPONSE) {
                    val frame = blePacket.toSocketFrame()
                    receiveQueue?.offer(frame, frame.frameSeq)
                }
                blePacket = BlePacket()
            }
        }
    }

    override fun release() {
        super.release()
        bleTransport.setListener(null)
    }

    override fun deviceOper(frameType: Byte, msgType: Byte, payload: ByteArray, timeout: Int, callback: IResultCallback<Any?>?) {}
    override fun deviceOper(frameType: Byte, msgType: Byte, payload: ByteArray, timeout: Int): CallbackData<Any?>? = null
    override fun getDeviceVersion(timeout: Int) {}
    override fun getDeviceInfo(timeout: Int) {}
    override fun getHeartbeatData(): ByteArray? = null

    /** BLE-provisioning entry point — this is what `M800BleProvisioner.provisionWifi()` calls. */
    fun bleWiFiConfig(
        deviceType: Short,
        btAddress: String,
        serverIP: String,
        serverPort: Int,
        ssidRaw: ByteArray?,
        pwd: String?,
        callback: IResultCallback<Any?>
    ) {
        SdkLog.log("$tag bleWiFiConfig deviceType:$deviceType,ip:$serverIP,port:$serverPort")
        if (pendingCallbacks.containsKey(CALLBACK_TYPE_CONFIG_WIFI_BLE)) {
            // Un intento anterior quedo sin respuesta: antes se salia sin avisar
            // (el llamador esperaba para siempre). Se descarta y se reintenta.
            SdkLog.log("$tag config repeat------- descartando intento anterior")
            pendingCallbacks.remove(CALLBACK_TYPE_CONFIG_WIFI_BLE)
            bleTransport.stopScan()
            bleTransport.disconnect()
        }
        pendingCallbacks[CALLBACK_TYPE_CONFIG_WIFI_BLE] = callback
        if (!bleTransport.isBluetoothOpen()) {
            resultCallback(CALLBACK_TYPE_CONFIG_WIFI_BLE, StatusCode.NOT_ENABLE, null)
            return
        }
        val dType = DeviceType.getDeviceType(deviceType)
        if (serverIP.isEmpty() || dType == null) {
            resultCallback(CALLBACK_TYPE_CONFIG_WIFI_BLE, StatusCode.PARAMETER_ERROR, null)
            return
        }
        if (!(DeviceType.isM901L(dType) || (ssidRaw != null && ssidRaw.size <= 32))) {
            resultCallback(CALLBACK_TYPE_CONFIG_WIFI_BLE, StatusCode.PARAMETER_ERROR, null)
            return
        }
        connectType = ConnectType.BLE
        addressField = btAddress
        this.serverAddress = serverIP
        this.serverPort = serverPort
        this.ssidRaw = ssidRaw
        this.pwd = pwd ?: ""
        setDeviceType(dType)
        if (DeviceType.isEW201W(dType)) {
            bleTransport.initUuids(
                "0000AE00-0000-1000-8000-00805f9b34fb", "0000AE02-0000-1000-8000-00805f9b34fb",
                "0000AE00-0000-1000-8000-00805f9b34fb", "0000AE01-0000-1000-8000-00805f9b34fb"
            )
        } else {
            bleTransport.initUuids(
                "0000ffe0-0000-1000-8000-00805f9b34fb", "0000ffe4-0000-1000-8000-00805f9b34fb",
                "0000ffe5-0000-1000-8000-00805f9b34fb", "0000ffe9-0000-1000-8000-00805f9b34fb"
            )
        }
        bleTransport.setListener(transportListener)
        bleTransport.scan()
    }

    fun apnSet(deviceType: Short, btAddress: String, apn: String, callback: IResultCallback<Any?>) {
        if (pendingCallbacks.containsKey(CALLBACK_TYPE_APN_SET)) return
        pendingCallbacks[CALLBACK_TYPE_APN_SET] = callback
        if (!bleTransport.isBluetoothOpen()) { resultCallback(CALLBACK_TYPE_APN_SET, StatusCode.NOT_ENABLE, null); return }
        val dType = DeviceType.getDeviceType(deviceType)
        if (dType == null || !DeviceType.isM901L(dType)) { resultCallback(CALLBACK_TYPE_APN_SET, StatusCode.PARAMETER_ERROR, null); return }
        connectType = ConnectType.BLE
        addressField = btAddress
        this.apn = apn
        setDeviceType(dType)
        bleTransport.initUuids(
            "0000ffe0-0000-1000-8000-00805f9b34fb", "0000ffe4-0000-1000-8000-00805f9b34fb",
            "0000ffe5-0000-1000-8000-00805f9b34fb", "0000ffe9-0000-1000-8000-00805f9b34fb"
        )
        bleTransport.setListener(transportListener)
        bleTransport.connect(btAddress)
    }

    fun apnGet(deviceType: Short, btAddress: String, callback: IResultCallback<Any?>) {
        if (pendingCallbacks.containsKey(CALLBACK_TYPE_APN_GET)) return
        pendingCallbacks[CALLBACK_TYPE_APN_GET] = callback
        if (!bleTransport.isBluetoothOpen()) { resultCallback(CALLBACK_TYPE_APN_GET, StatusCode.NOT_ENABLE, null); return }
        val dType = DeviceType.getDeviceType(deviceType)
        if (dType == null || !DeviceType.isM901L(dType)) { resultCallback(CALLBACK_TYPE_APN_GET, StatusCode.PARAMETER_ERROR, null); return }
        connectType = ConnectType.BLE
        addressField = btAddress
        setDeviceType(dType)
        bleTransport.initUuids(
            "0000ffe0-0000-1000-8000-00805f9b34fb", "0000ffe4-0000-1000-8000-00805f9b34fb",
            "0000ffe5-0000-1000-8000-00805f9b34fb", "0000ffe9-0000-1000-8000-00805f9b34fb"
        )
        bleTransport.setListener(transportListener)
        bleTransport.connect(btAddress)
    }

    private fun getMsgType(deviceType: DeviceType?): ShortArray {
        val m = ShortArray(3)
        when (deviceType) {
            DeviceType.DEVICE_TYPE_Z3, DeviceType.DEVICE_TYPE_Z4_TWB, DeviceType.DEVICE_TYPE_Z4_TWP,
            DeviceType.DEVICE_TYPE_Z400TWP_2, DeviceType.DEVICE_TYPE_Z400TWP_3 -> { m[0] = 518; m[1] = 930; m[2] = 928 }
            DeviceType.DEVICE_TYPE_M600, DeviceType.DEVICE_TYPE_M800, DeviceType.DEVICE_TYPE_BM8701_2,
            DeviceType.DEVICE_TYPE_BG001A, DeviceType.DEVICE_TYPE_M8701W, DeviceType.DEVICE_TYPE_SDC100,
            DeviceType.DEVICE_TYPE_M901L, DeviceType.DEVICE_TYPE_BM8701, DeviceType.DEVICE_TYPE_FH601W,
            DeviceType.DEVICE_TYPE_SM100, DeviceType.DEVICE_TYPE_SM200, DeviceType.DEVICE_TYPE_SM300 -> { m[0] = 514; m[1] = 930; m[2] = 928 }
            DeviceType.DEVICE_TYPE_BG002 -> { m[0] = 518; m[1] = 5683; m[2] = 5680 }
            DeviceType.DEVICE_TYPE_SN913E -> { m[0] = 518; m[1] = 5122; m[2] = 5120 }
            DeviceType.DEVICE_TYPE_NOX_SAW -> { m[0] = 17; m[1] = 46; m[2] = 37 }
            DeviceType.DEVICE_TYPE_NOX_2W -> { m[0] = 17; m[1] = 0; m[2] = 37 }
            DeviceType.DEVICE_TYPE_EW201W -> { m[0] = 17; m[1] = 35; m[2] = 33 }
            DeviceType.DEVICE_TYPE_EW202W -> { m[0] = 518; m[1] = 5378; m[2] = 5376 }
            else -> {}
        }
        return m
    }

    private fun apWiFiConfig() {
        val msgTypes = getMsgType(getDeviceType())
        var cd = getDeviceInfo(msgTypes[0])
        if (!cd.isSuccess) { resultCallback(CALLBACK_TYPE_CONFIG_WIFI_AP, StatusCode.FAIL, null); return }
        val device = cd.result as? WifiDeviceInfo
        cd = setNet(msgTypes[1])
        if (!cd.isSuccess) { resultCallback(CALLBACK_TYPE_CONFIG_WIFI_AP, StatusCode.FAIL, null); return }
        cd = setWifi(msgTypes[2])
        if (cd.isSuccess) {
            cd.callbackType = CALLBACK_TYPE_CONFIG_WIFI_AP
            cd.result = device
            dataCallback(cd)
        } else {
            resultCallback(CALLBACK_TYPE_CONFIG_WIFI_AP, StatusCode.FAIL, null)
        }
    }

    private fun bleWiFiConfigInternal() {
        sleepMillis(500)
        val msgTypes = getMsgType(getDeviceType())
        var cd = getDeviceInfo(msgTypes[0])
        if (!cd.isSuccess) { resultCallback(CALLBACK_TYPE_CONFIG_WIFI_BLE, StatusCode.FAIL, null); return }
        val device = cd.result as? WifiDeviceInfo
        if (!DeviceType.isNox2W(getDeviceType())) {
            cd = setNet(msgTypes[1])
            if (!cd.isSuccess) { resultCallback(CALLBACK_TYPE_CONFIG_WIFI_BLE, StatusCode.FAIL, null); return }
        }
        if (ssidRaw == null) {
            cd.callbackType = CALLBACK_TYPE_CONFIG_WIFI_BLE
            cd.result = device
            dataCallback(cd)
        } else {
            cd = setWifi(msgTypes[2])
            if (cd.isSuccess) {
                cd.callbackType = CALLBACK_TYPE_CONFIG_WIFI_BLE
                cd.result = device
                dataCallback(cd)
            } else {
                resultCallback(CALLBACK_TYPE_CONFIG_WIFI_BLE, StatusCode.FAIL, null)
            }
        }
    }

    private fun apnSetInternal() {
        sleepMillis(200)
        val cd = setAPN(apn, null, null)
        resultCallback(CALLBACK_TYPE_APN_SET, cd.status, null)
    }

    private fun apnGetInternal() {
        sleepMillis(200)
        val cd = getAPN()
        resultCallback(CALLBACK_TYPE_APN_GET, cd.status, cd.result)
    }

    private fun setAPN(apn: String, username: String?, password: String?): CallbackData<Any?> {
        val data = ByteArray(320)
        var index = 0
        apn.encodeToByteArray().copyInto(data, index)
        index += 64
        (username ?: "").encodeToByteArray().let { it.copyInto(data, index) }
        index += 128
        (password ?: "").encodeToByteArray().let { it.copyInto(data, index) }
        return requestServer(data, MSG_TYPE_APN_SET)
    }

    private fun getAPN(): CallbackData<Any?> = requestServer(MSG_TYPE_APN_GET)

    private fun setWifi(msgType: Short): CallbackData<Any?> {
        var data = ByteArray(120)
        var index = 0
        ssidRaw?.copyInto(data, index)
        index += 33
        data[index++] = if (pwd.isEmpty()) 0 else 1
        pwd.encodeToByteArray().copyInto(data, index)
        index += 65
        data[index] = 0
        if (isBleProtocol()) {
            if (DeviceType.isNox2W(getDeviceType()) || DeviceType.isNoxSAW(getDeviceType())) {
                val temp = ByteArray(data.size + 1)
                temp[0] = 1
                data.copyInto(temp, 1)
                data = temp
            }
            val msgSeq = DataPacket.BasePacketHead.getSequence()
            val packet = DataPacket.CommonReq(data)
            val bytes = BlePacket.buildPacket(DataPacket.PacketType.FA_REQUEST, msgSeq, msgType, packet)
            return sendMsg2Server(bytes, msgSeq.toInt())
        }
        return requestServer(data, msgType)
    }

    private fun setNet(msgType: Short): CallbackData<Any?> {
        // M800 (and the shared M600/BM8701/.../EW202W branch) — the path this module targets.
        val d = getDeviceType()
        if (DeviceType.isZ4TW(d) || DeviceType.isM600(d) || DeviceType.isM800(d) || DeviceType.isBG001A(d) ||
            DeviceType.isSN913E(d) || DeviceType.isBM8701(d) || DeviceType.isBM8701_2(d) || DeviceType.isM8701W(d) ||
            DeviceType.isFH601W(d) || DeviceType.isEW202W(d)
        ) {
            val data = ByteArray(162)
            var index = 0
            val ip = if (DeviceType.isZ4TWB(d)) "" else (serverAddress ?: "")
            val http = if (DeviceType.isZ4TWB(d)) (serverAddress ?: "") else ""
            val port: Short = if (DeviceType.isZ4TWB(d)) 0 else serverPort.toShort()
            ip.encodeToByteArray().copyInto(data, index)
            index += 32
            ByteUtil.short2byte(port).copyInto(data, index)
            index += 2
            http.encodeToByteArray().copyInto(data, index)
            return requestServer(data, msgType)
        }
        // Other device families (NoxSAW / EW201W) kept for completeness.
        val data = ByteArray(34)
        var index = 0
        (serverAddress ?: "").encodeToByteArray().copyInto(data, index)
        index += 32
        ByteUtil.short2byte(serverPort.toShort()).copyInto(data, index)
        if (DeviceType.isEW201W(d)) {
            val msgSeq = DataPacket.BasePacketHead.getSequence()
            val packet = DataPacket.CommonReq(data)
            val bytes = BlePacket.buildPacket(DataPacket.PacketType.FA_REQUEST, msgSeq, msgType, packet)
            return sendMsg2Server(bytes, msgSeq.toInt())
        }
        return requestServer(data, msgType)
    }

    private fun getDeviceInfo(msgType: Short): CallbackData<Any?> {
        var data: CallbackData<Any?>? = null
        for (i in 0 until 3) {
            data = if (isBleProtocol()) {
                val msgSeq = DataPacket.BasePacketHead.getSequence()
                val bytes = BlePacket.buildPacket(DataPacket.PacketType.FA_REQUEST, msgSeq, msgType)
                sendMsg2Server(bytes, msgSeq.toInt())
            } else {
                requestServer(msgType)
            }
            if (data.isSuccess) break
            sleepMillis(100)
        }
        val result = data ?: CallbackData()
        if (result.isSuccess) {
            val device = WifiDeviceInfo()
            if (isBleProtocol()) {
                val rsp = result.result as? BlePacket.DeviceInfoRsp
                if (rsp != null) {
                    device.deviceId = rsp.deviceId
                    device.version = rsp.versionName
                    result.result = device
                }
            } else {
                val frame = result.result as? SocketFrame
                if (frame != null) {
                    var index = 2
                    val resultCode = ByteUtil.byte2short(frame.msgContent, 0)
                    if (resultCode.toInt() == 0) {
                        val d = getDeviceType()
                        if (DeviceType.isZ4TW(d) || DeviceType.isSN913E(d) || DeviceType.isEW202W(d) ||
                            DeviceType.isBG002(d) || DeviceType.isZ400TWP3(d)
                        ) index += 14
                        device.deviceId = frame.msgContent.decodeToString(index, index + 13).trim { it.code == 0 || it.isWhitespace() }
                        index += 14
                        index += 2 // deviceType (unused)
                        val versionRaw = ByteUtil.byte2short(frame.msgContent, index)
                        device.version = ((versionRaw.toInt() and 0xFFFF) * 0.01f).let { v -> "${(v * 100).toInt() / 100.0}" }
                        result.result = device
                    }
                }
            }
        }
        return result
    }

    override fun sendData(data: ByteArray) {
        if (connectType == ConnectType.BLE) {
            var offset = 0
            while (offset < data.size) {
                val count = minOf(20, data.size - offset)
                bleTransport.send(data.copyOfRange(offset, offset + count))
                offset += count
            }
        } else {
            super.sendData(data)
        }
    }

    override fun parseRespone(data: CallbackData<Any?>, frame: SocketFrame) {
        val buffer = PortableBuffer.wrap(frame.msgContent)
        data.status = if (frame.msgStatus.toInt() == 0) 0 else StatusCode.FAIL
        when (frame.msgType.toInt()) {
            1028 -> data.result = frame.msgContent
            514, 518 -> data.result = frame
            1184 -> { buffer.position(2); data.result = buffer.get() }
            4116 -> {
                var pos = 2
                buffer.position(pos)
                val apnBuf = ByteArray(64); buffer.get(apnBuf)
                val bean = APNBean(apn = apnBuf.decodeToString().trim())
                pos += 64; buffer.position(pos)
                val nameBuf = ByteArray(128); buffer.get(nameBuf)
                bean.username = nameBuf.decodeToString().trim()
                pos += 128; buffer.position(pos)
                val pwdBuf = ByteArray(128); buffer.get(pwdBuf)
                bean.password = pwdBuf.decodeToString().trim()
                data.result = bean
            }
            929 -> data.result = frame
            else -> data.result = data.status
        }
    }

    private fun resultCallback(callbackType: String, status: Short, result: Any?) {
        if (pendingCallbacks.containsKey(callbackType)) {
            val data = CallbackData<Any?>()
            data.callbackType = callbackType
            data.status = status
            data.result = result
            dataCallback(data)
        }
    }

    /** BLE sub-protocol used only by EW201W/Nox2W/NoxSAW (`isBleProtocol() == true`); M800 never takes this path. */
    class BlePacket : DataPacket() {
        var packetBuffer: PortableBuffer = PortableBuffer.allocate(1024)
        lateinit var packetHead: BlePacketHead
        var msgBody: BlePacketBody = BlePacketBody()

        override fun buildCallbackKey(): Any? = null
        override fun check(buffer: PortableBuffer): Boolean = check()
        fun check(): Boolean {
            val crc32 = PortableCRC32()
            crc32.update(packetBuffer.array, 0, packetBuffer.limit - 8)
            return (crc32.getValue() and 0xFFFFFFFFL).toInt() == packetBuffer.getInt(packetBuffer.limit - 8)
        }

        override fun parse(buffer: PortableBuffer): Boolean = parse()
        fun parse(): Boolean {
            packetHead = BlePacketHead()
            packetHead.parseBuffer(packetBuffer)
            msgBody = BlePacketBody()
            return msgBody.parseBuffer(packetHead, packetBuffer) != null
        }

        override fun parseBuffer(buffer: PortableBuffer): PortableBuffer? {
            parse()
            return buffer
        }

        override fun fillBuffer(buffer: PortableBuffer): PortableBuffer {
            buffer.position(0)
            packetHead.fillBuffer(buffer)
            msgBody.fillBuffer(packetHead, buffer)
            return buffer
        }

        fun toSocketFrame(): SocketFrame {
            val frame = SocketFrame()
            frame.deviceType = packetHead.deviceType
            frame.frameSeq = packetHead.msgSeq.toShort()
            frame.msgType = msgBody.type.toShort()
            frame.msgStatus = (msgBody.content as? RspPacket)?.rspCode?.toShort() ?: 0
            frame.resObj = msgBody.content?.obj
            return frame
        }

        open class RspPacket : BaseRspPacket() {
            override fun parseBuffer(buffer: PortableBuffer): PortableBuffer {
                rspCode = buffer.get().toInt()
                return buffer
            }
            override fun fillBuffer(buffer: PortableBuffer): PortableBuffer {
                buffer.put(rspCode.toByte())
                return buffer
            }
        }

        class DeviceInfoRsp : RspPacket() {
            var deviceName: String = ""
            var deviceId: String = ""
            var versionName: String = ""
            var btAddress: String = ""

            override fun parseBuffer(buffer: PortableBuffer): PortableBuffer {
                rspCode = buffer.get().toInt()
                if (rspCode == 0) {
                    val xDeviceName = ByteArray(14); buffer.get(xDeviceName)
                    val xDeviceId = ByteArray(14); buffer.get(xDeviceId)
                    buffer.getShort() // usModel
                    val usVerCode = buffer.getShort()
                    buffer.getShort() // usFactoryVerCode
                    buffer.getInt() // ulFactoryTime
                    buffer.getShort() // usFactoryNum
                    buffer.getShort() // usCoopNum
                    buffer.getShort() // pcbCode
                    val addr = ByteArray(6); buffer.get(addr)
                    deviceName = xDeviceName.decodeToString().trim()
                    deviceId = xDeviceId.decodeToString().trim()
                    val major = (usVerCode / 100)
                    val minor = (usVerCode % 100)
                    versionName = "$major.${if (minor < 10) "0$minor" else "$minor"}"
                    btAddress = addr.reversed().joinToString(":") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
                    obj = this
                }
                return buffer
            }
        }

        class BlePacketBody(type: Byte = 0, content: BasePacket? = null) : BasePacketBody(type, content) {
            override fun parseBuffer(head: BasePacketHead, buffer: PortableBuffer): PortableBuffer? {
                type = buffer.get()
                if (head.frameType != DataPacket.PacketType.FA_RESPONSE) return null
                content = when (type.toInt()) {
                    17 -> DeviceInfoRsp()
                    else -> RspPacket()
                }
                return try {
                    content?.parseBuffer(buffer)
                    buffer
                } catch (e: Exception) {
                    null
                }
            }
            override fun fillBuffer(head: BasePacketHead, buffer: PortableBuffer): PortableBuffer {
                buffer.put(type)
                content?.fillBuffer(buffer)
                return buffer
            }
        }

        class BlePacketHead : BasePacketHead() {
            fun init(frameType: Byte, msgSeq: Byte) {
                version = 0
                this.frameType = frameType
                frameCount = 1
                frameNum = 0
                this.msgSeq = msgSeq
            }
            override fun parseBuffer(buffer: PortableBuffer): PortableBuffer {
                buffer.position(0)
                version = buffer.get()
                frameType = buffer.get()
                frameCount = buffer.get()
                frameNum = buffer.get()
                msgSeq = buffer.get()
                deviceType = buffer.getShort()
                return buffer
            }
            override fun fillBuffer(buffer: PortableBuffer): PortableBuffer {
                buffer.position(0)
                buffer.put(0).put(frameType).put(frameCount).put(frameNum).put(msgSeq).putShort(deviceType)
                return buffer
            }
        }

        companion object {
            fun buildPacket(frameType: Byte, msgSeq: Byte, msgType: Short, basePacket: BasePacket = BasePacket()): ByteArray {
                val packet = BlePacket()
                packet.msgBody = BlePacketBody(msgType.toByte(), basePacket)
                packet.packetHead = BlePacketHead().also { it.init(frameType, msgSeq) }
                packet.packetBuffer.position(0)
                packet.packetHead.fillBuffer(packet.packetBuffer)
                packet.msgBody.fillBuffer(packet.packetHead, packet.packetBuffer)
                val crc32 = PortableCRC32()
                crc32.update(packet.packetBuffer.array, 0, packet.packetBuffer.position)
                packet.packetBuffer.putInt((crc32.getValue() and 0xFFFFFFFFL).toInt())
                packet.packetBuffer.put(byteArrayOf(36, 95, 64, 45))
                return packet.packetBuffer.array.copyOfRange(0, packet.packetBuffer.position)
            }
        }
    }
}
