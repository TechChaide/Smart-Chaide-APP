package com.chaide.m800sdk.ble

import com.chaide.m800sdk.log.SdkLog
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataManufacturerDataKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
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
import platform.Foundation.NSString
import platform.Foundation.create
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/**
 * Implementacion CoreBluetooth de [BleTransport], con la misma semantica que
 * `AndroidBleTransport` (que es la validada contra hardware real):
 *
 *  - UNA sola instancia por proceso ([BleTransportProvider.create] devuelve
 *    siempre la misma), igual que el `BleHelper` singleton original.
 *  - `scan(scanTimeMs)` se detiene solo al vencer el tiempo y avisa
 *    `onScanStopped()` (WiFiConfigHelper depende de eso).
 *  - `connect(address)`: iOS no expone la MAC; `address` es el
 *    `CBPeripheral.identifier.UUIDString` que entrega el propio escaneo en
 *    `BleScanResult.address`, asi que todo el flujo comun sigue funcionando.
 *  - `rawRecord`: CoreBluetooth no entrega los bytes crudos del advertisement,
 *    asi que se RECONSTRUYEN como estructuras AD `[len][type][data]` a partir
 *    del diccionario (0x09 nombre local, 0xFF datos de fabricante). Con eso
 *    `M800Protocol.parseLocalName/parseSerial` funcionan sin cambios.
 */
@OptIn(ExperimentalForeignApi::class)
class IosBleCentral internal constructor() : NSObject(),
    CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {

    private var listener: BleTransportListener? = null
    private val centralManager: CBCentralManager =
        CBCentralManager(delegate = this, queue = dispatch_get_main_queue())

    // CoreBluetooth exige retener los CBPeripheral descubiertos para poder conectarlos.
    private val discovered = mutableMapOf<String, CBPeripheral>()
    private var targetPeripheral: CBPeripheral? = null
    private var writeChar: CBCharacteristic? = null
    private var notifyChar: CBCharacteristic? = null

    private var notifyServiceUUID = CBUUID.UUIDWithString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private var notifyCharUUID = CBUUID.UUIDWithString("0000ffe4-0000-1000-8000-00805f9b34fb")
    private var writeServiceUUID = CBUUID.UUIDWithString("0000ffe5-0000-1000-8000-00805f9b34fb")
    private var writeCharUUID = CBUUID.UUIDWithString("0000ffe9-0000-1000-8000-00805f9b34fb")

    private var scanning = false
    private var scanGeneration = 0
    private var pendingScanMs: Int? = null      // scan pedido antes de que el radio este listo
    private var pendingConnectAddress: String? = null
    private var connectGeneration = 0
    private var connected = false
    private var mtu = 20

    fun setListener(listener: BleTransportListener?) { this.listener = listener }

    fun initUuids(notifyServiceUuid: String, notifyCharacteristicUuid: String, writeServiceUuid: String, writeCharacteristicUuid: String) {
        notifyServiceUUID = CBUUID.UUIDWithString(notifyServiceUuid)
        notifyCharUUID = CBUUID.UUIDWithString(notifyCharacteristicUuid)
        writeServiceUUID = CBUUID.UUIDWithString(writeServiceUuid)
        writeCharUUID = CBUUID.UUIDWithString(writeCharacteristicUuid)
    }

    fun isBluetoothOpen(): Boolean = centralManager.state == CBManagerStatePoweredOn
    fun isSupportBle(): Boolean = true

    fun scan(scanTimeMs: Int) {
        if (scanning) return
        if (!isBluetoothOpen()) {
            // Recien creado el CBCentralManager el estado es "unknown" unos ms;
            // se arranca en cuanto pase a poweredOn (centralManagerDidUpdateState).
            pendingScanMs = scanTimeMs
            return
        }
        scanning = true
        val gen = ++scanGeneration
        listener?.onScanStarted()
        SdkLog.log("IosBleTransport scan() ${scanTimeMs}ms")
        // AllowDuplicates: el M800 anuncia en rafagas y a veces el primer
        // anuncio llega sin datos de fabricante; hay que ver los siguientes.
        centralManager.scanForPeripheralsWithServices(
            null, options = mapOf<Any?, Any?>(CBCentralManagerScanOptionAllowDuplicatesKey to true)
        )
        runAfter(scanTimeMs.toLong()) { if (gen == scanGeneration && scanning) stopScan() }
    }

    fun stopScan() {
        pendingScanMs = null
        if (!scanning) return
        scanning = false
        scanGeneration++
        centralManager.stopScan()
        listener?.onScanStopped()
    }

    fun connect(address: String, timeoutMs: Int) {
        if (connected && targetPeripheral?.identifier?.UUIDString == address) {
            listener?.onConnectionStateChanged(BleConnState.CONNECTED)
            return
        }
        val peripheral = discovered[address] ?: run {
            SdkLog.log("IosBleTransport connect(): $address no fue visto en el escaneo")
            listener?.onConnectionStateChanged(BleConnState.DISCONNECTED)
            return
        }
        pendingConnectAddress = address
        targetPeripheral = peripheral
        listener?.onConnectionStateChanged(BleConnState.CONNECTING)
        val gen = ++connectGeneration
        centralManager.connectPeripheral(peripheral, options = null)
        runAfter(timeoutMs.toLong()) {
            if (gen == connectGeneration && !connected && pendingConnectAddress == address) {
                SdkLog.log("IosBleTransport connect timeout")
                centralManager.cancelPeripheralConnection(peripheral)
                pendingConnectAddress = null
                listener?.onConnectionStateChanged(BleConnState.DISCONNECTED)
            }
        }
    }

    fun disconnect() {
        connectGeneration++
        pendingConnectAddress = null
        targetPeripheral?.let { centralManager.cancelPeripheralConnection(it) }
        connected = false
        writeChar = null
        notifyChar = null
        listener?.onConnectionStateChanged(BleConnState.DISCONNECTED)
    }

    fun isConnected(): Boolean = connected
    fun getBondedAddress(): String? = targetPeripheral?.identifier?.UUIDString
    fun getMtu(): Int = mtu
    fun setMtu(mtu: Int) { if (mtu in 20..509) this.mtu = mtu }

    fun send(bytes: ByteArray) {
        val peripheral = targetPeripheral ?: return
        val char = writeChar ?: return
        if (!connected) return
        val type = if ((char.properties and CBCharacteristicPropertyWriteWithoutResponse) != 0uL)
            CBCharacteristicWriteWithoutResponse else CBCharacteristicWriteWithResponse
        peripheral.writeValue(bytes.toNSData(), char, type)
    }

    // -- CBCentralManagerDelegateProtocol --

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        SdkLog.log("IosBleTransport state:${central.state}")
        if (central.state == CBManagerStatePoweredOn) {
            pendingScanMs?.let { pendingScanMs = null; scan(it) }
        }
    }

    override fun centralManager(central: CBCentralManager, didDiscoverPeripheral: CBPeripheral, advertisementData: Map<Any?, *>, RSSI: NSNumber) {
        val address = didDiscoverPeripheral.identifier.UUIDString
        discovered[address] = didDiscoverPeripheral
        if (!scanning) return
        val localName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        val manufacturer = advertisementData[CBAdvertisementDataManufacturerDataKey] as? NSData
        val raw = buildRawRecord(localName, manufacturer?.toByteArray())
        listener?.onScanResult(object : BleScanResult {
            override val address: String = address
            override val name: String? = localName ?: didDiscoverPeripheral.name
            override val rssi: Int = RSSI.intValue
            override val rawRecord: ByteArray = raw
        })
    }

    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
        targetPeripheral = didConnectPeripheral
        didConnectPeripheral.delegate = this
        didConnectPeripheral.discoverServices(null)
    }

    @ObjCSignatureOverride
    override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
        SdkLog.log("IosBleTransport didFailToConnect: ${error?.localizedDescription}")
        connectGeneration++
        pendingConnectAddress = null
        connected = false
        listener?.onConnectionStateChanged(BleConnState.DISCONNECTED)
    }

    @ObjCSignatureOverride
    override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
        pendingConnectAddress = null
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
        if (!connected && writeChar != null && notifyChar != null) {
            connected = true
            connectGeneration++
            pendingConnectAddress = null
            listener?.onConnectionStateChanged(BleConnState.CONNECTED)
        }
    }

    @ObjCSignatureOverride
    override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
        if (didUpdateValueForCharacteristic.UUID != notifyCharUUID) return
        val data = didUpdateValueForCharacteristic.value ?: return
        listener?.onDataReceived(data.toByteArray())
    }

    @ObjCSignatureOverride
    override fun peripheral(peripheral: CBPeripheral, didWriteValueForCharacteristic: CBCharacteristic, error: NSError?) {
        if (error != null) SdkLog.log("IosBleTransport write error: ${error.localizedDescription}")
    }
}

/**
 * Fachada Kotlin pura: Kotlin/Native no permite que una subclase de NSObject
 * implemente tambien una interfaz Kotlin (BleTransport), asi que el delegado
 * CoreBluetooth vive en [IosBleCentral] y esta clase le reenvia todo.
 */
class IosBleTransport internal constructor(private val c: IosBleCentral) : BleTransport {
    override fun setListener(listener: BleTransportListener?) = c.setListener(listener)
    override fun initUuids(notifyServiceUuid: String, notifyCharacteristicUuid: String, writeServiceUuid: String, writeCharacteristicUuid: String) =
        c.initUuids(notifyServiceUuid, notifyCharacteristicUuid, writeServiceUuid, writeCharacteristicUuid)
    override fun isBluetoothOpen(): Boolean = c.isBluetoothOpen()
    override fun isSupportBle(): Boolean = c.isSupportBle()
    override fun scan(scanTimeMs: Int) = c.scan(scanTimeMs)
    override fun stopScan() = c.stopScan()
    override fun connect(address: String, timeoutMs: Int) = c.connect(address, timeoutMs)
    override fun disconnect() = c.disconnect()
    override fun isConnected(): Boolean = c.isConnected()
    override fun getBondedAddress(): String? = c.getBondedAddress()
    override fun getMtu(): Int = c.getMtu()
    override fun setMtu(mtu: Int) = c.setMtu(mtu)
    override fun send(bytes: ByteArray) = c.send(bytes)
}

private val sharedIosBleTransport: IosBleTransport by lazy { IosBleTransport(IosBleCentral()) }

/** Reconstruye estructuras AD `[len][type][data]` (0x09 nombre, 0xFF fabricante). */
internal fun buildRawRecord(localName: String?, manufacturerData: ByteArray?): ByteArray {
    val out = ArrayList<Byte>()
    fun ad(type: Int, data: ByteArray) {
        if (data.isEmpty() || data.size > 254) return
        out.add((data.size + 1).toByte()); out.add(type.toByte()); data.forEach { out.add(it) }
    }
    localName?.let { ad(0x09, it.encodeToByteArray()) }
    manufacturerData?.let { ad(0xFF, it) }
    return out.toByteArray()
}

private fun runAfter(ms: Long, block: () -> Unit) {
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, ms * 1_000_000L), dispatch_get_main_queue(), block)
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
    actual fun create(): BleTransport = sharedIosBleTransport
}
