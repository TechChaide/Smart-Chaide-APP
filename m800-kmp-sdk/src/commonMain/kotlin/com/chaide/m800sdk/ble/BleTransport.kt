package com.chaide.m800sdk.ble

/**
 * Common BLE transport contract — the ONE piece of the original SDK that
 * genuinely has no portable implementation, because it is not a protocol
 * (that part is 100% common code, see `socket/` and `m800/`) but a radio
 * API: Android's GATT client (`BleHelper.java`, ported faithfully in
 * `androidMain`) and iOS's CoreBluetooth are different system frameworks
 * with no shared Kotlin surface. Every BLE-using class in this module
 * (`BleManager`, `WiFiConfigHelper`) is written against this interface only.
 */
interface BleScanResult {
    val address: String
    val name: String?
    val rssi: Int
    val rawRecord: ByteArray
}

enum class BleConnState { CONNECTING, CONNECTED, DISCONNECTED }

interface BleTransportListener {
    fun onScanStarted() {}
    fun onScanResult(result: BleScanResult) {}
    fun onScanStopped() {}
    fun onConnectionStateChanged(state: BleConnState) {}
    fun onDataReceived(data: ByteArray) {}
}

/**
 * One transport instance == one logical connection, mirroring the original
 * `BleHelper` singleton (one active GATT link at a time per process).
 */
interface BleTransport {
    fun setListener(listener: BleTransportListener?)
    fun initUuids(notifyServiceUuid: String, notifyCharacteristicUuid: String, writeServiceUuid: String, writeCharacteristicUuid: String)
    fun isBluetoothOpen(): Boolean
    fun isSupportBle(): Boolean
    fun scan(scanTimeMs: Int = 6000)
    fun stopScan()
    fun connect(address: String, timeoutMs: Int = 10000)
    fun disconnect()
    fun isConnected(): Boolean
    fun getBondedAddress(): String?
    fun getMtu(): Int
    fun setMtu(mtu: Int)
    /** Fire-and-forget write, chunked to the negotiated MTU — matches the original's retrying `sendData`. */
    fun send(bytes: ByteArray)
}

expect object BleTransportProvider {
    fun create(): BleTransport
}
