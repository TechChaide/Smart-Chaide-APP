package com.chaide.m800sdk.ble

import com.chaide.m800sdk.log.SdkLog
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue

/**
 * CoreBluetooth implementation of [BleTransport] — this is the genuinely
 * new part of the port: there is no Sleepace Swift SDK being wrapped here,
 * this talks to the M800's GATT service (same UUIDs as Android's
 * `0000ffe0/e4/e5/e9` Nordic-UART-style layout) directly via Apple's own
 * framework.
 *
 * NOT exercised on real hardware in this session — this machine has no Mac/
 * Xcode toolchain to even compile the `iosArm64`/`iosSimulatorArm64`
 * targets, so treat this as a structurally-complete first draft that needs
 * a real build + on-device validation pass before shipping, not as
 * proven-working code the way the Android port is.
 */
@OptIn(ExperimentalForeignApi::class)
class IosBleTransport : NSObject(), BleTransport, CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {

    private var listener: BleTransportListener? = null
    private var centralManager: CBCentralManager? = null
    private var targetPeripheral: CBPeripheral? = null
    private var writeChar: CBCharacteristic? = null
    private var notifyChar: CBCharacteristic? = null

    private var notifyServiceUUID = CBUUID.UUIDWithString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private var notifyCharUUID = CBUUID.UUIDWithString("0000ffe4-0000-1000-8000-00805f9b34fb")
    private var writeServiceUUID = CBUUID.UUIDWithString("0000ffe5-0000-1000-8000-00805f9b34fb")
    private var writeCharUUID = CBUUID.UUIDWithString("0000ffe9-0000-1000-8000-00805f9b34fb")

    private var connected = false
    private var pendingConnectAddress: String? = null
    private var mtu = 20

    private fun manager(): CBCentralManager {
        var m = centralManager
        if (m == null) {
            m = CBCentralManager(delegate = this, queue = dispatch_get_main_queue())
            centralManager = m
        }
        return m
    }

    override fun setListener(listener: BleTransportListener?) { this.listener = listener }

    override fun initUuids(notifyServiceUuid: String, notifyCharacteristicUuid: String, writeServiceUuid: String, writeCharacteristicUuid: String) {
        notifyServiceUUID = CBUUID.UUIDWithString(notifyServiceUuid)
        notifyCharUUID = CBUUID.UUIDWithString(notifyCharacteristicUuid)
        writeServiceUUID = CBUUID.UUIDWithString(writeServiceUuid)
        writeCharUUID = CBUUID.UUIDWithString(writeCharacteristicUuid)
    }

    override fun isBluetoothOpen(): Boolean = manager().state == CBManagerStatePoweredOn
    override fun isSupportBle(): Boolean = true // every iOS device Kotlin/Native currently targets has BLE

    override fun scan(scanTimeMs: Int) {
        if (!isBluetoothOpen()) return
        listener?.onScanStarted()
        manager().scanForPeripheralsWithServices(null, options = null)
    }

    override fun stopScan() {
        centralManager?.stopScan()
        listener?.onScanStopped()
    }

    override fun connect(address: String, timeoutMs: Int) {
        // CoreBluetooth has no "connect by MAC address" API (iOS hides the
        // MAC and only exposes a per-app CBPeripheral.identifier UUID), so a
        // scan-then-match-by-identifier-string is required here. Callers on
        // iOS must pass the `CBPeripheral.identifier.UUIDString` as `address`,
        // obtained from a prior `scan()`'s `BleScanResult.address` — this
        // mirrors how Android's MAC address is used, just with iOS's own
        // stable identifier instead.
        pendingConnectAddress = address
        targetPeripheral?.let { if (it.identifier.UUIDString == address) { manager().connectPeripheral(it, options = null); return } }
        listener?.onConnectionStateChanged(BleConnState.CONNECTING)
        manager().scanForPeripheralsWithServices(null, options = null)
    }

    override fun disconnect() {
        targetPeripheral?.let { centralManager?.cancelPeripheralConnection(it) }
        connected = false
        listener?.onConnectionStateChanged(BleConnState.DISCONNECTED)
    }

    override fun isConnected(): Boolean = connected
    override fun getBondedAddress(): String? = targetPeripheral?.identifier?.UUIDString
    override fun getMtu(): Int = mtu
    override fun setMtu(mtu: Int) { this.mtu = mtu }

    override fun send(bytes: ByteArray) {
        val peripheral = targetPeripheral ?: return
        val char = writeChar ?: return
        val data = bytes.toNSData()
        val type = if ((char.properties and CBCharacteristicWriteWithResponse) != 0uL) CBCharacteristicWriteWithResponse else CBCharacteristicWriteWithoutResponse
        peripheral.writeValue(data, char, type)
    }

    // -- CBCentralManagerDelegateProtocol --

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        SdkLog.log("IosBleTransport state:${central.state}")
    }

    override fun centralManager(central: CBCentralManager, didDiscoverPeripheral: CBPeripheral, advertisementData: Map<Any?, *>, RSSI: NSNumber) {
        val address = didDiscoverPeripheral.identifier.UUIDString
        if (pendingConnectAddress != null) {
            if (address == pendingConnectAddress) {
                targetPeripheral = didDiscoverPeripheral
                central.stopScan()
                central.connectPeripheral(didDiscoverPeripheral, options = null)
            }
            return
        }
        listener?.onScanResult(object : BleScanResult {
            override val address: String = address
            override val name: String? = didDiscoverPeripheral.name
            override val rssi: Int = RSSI.intValue
            override val rawRecord: ByteArray = ByteArray(0)
        })
    }

    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
        targetPeripheral = didConnectPeripheral
        didConnectPeripheral.delegate = this
        didConnectPeripheral.discoverServices(null)
    }

    override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
        connected = false
        listener?.onConnectionStateChanged(BleConnState.DISCONNECTED)
    }

    override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
        connected = false
        writeChar = null
        notifyChar = null
        listener?.onConnectionStateChanged(BleConnState.DISCONNECTED)
    }

    // -- CBPeripheralDelegateProtocol --

    override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
        val services = peripheral.services?.filterIsInstance<CBService>() ?: return
        for (service in services) {
            if (service.UUID == writeServiceUUID || service.UUID == notifyServiceUUID) {
                peripheral.discoverCharacteristics(null, forService = service)
            }
        }
    }

    override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) {
        val chars = didDiscoverCharacteristicsForService.characteristics?.filterIsInstance<CBCharacteristic>() ?: return
        for (c in chars) {
            if (c.UUID == writeCharUUID) writeChar = c
            if (c.UUID == notifyCharUUID) {
                notifyChar = c
                peripheral.setNotifyValue(true, forCharacteristic = c)
            }
        }
        if (writeChar != null && notifyChar != null) {
            connected = true
            listener?.onConnectionStateChanged(BleConnState.CONNECTED)
        }
    }

    override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
        if (didUpdateValueForCharacteristic.UUID != notifyCharUUID) return
        val data = didUpdateValueForCharacteristic.value ?: return
        listener?.onDataReceived(data.toByteArray())
    }

    override fun peripheral(peripheral: CBPeripheral, didWriteValueForCharacteristic: CBCharacteristic, error: NSError?) {
        // Matches Android's `onCharacteristicWrite`: nothing to do, `send()` is fire-and-forget.
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = this.size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = this.length.toInt()
    val out = ByteArray(len)
    if (len > 0) {
        out.usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), this.bytes, len.convert())
        }
    }
    return out
}

actual object BleTransportProvider {
    actual fun create(): BleTransport = IosBleTransport()
}
