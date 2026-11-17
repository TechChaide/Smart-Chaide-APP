package com.chaide.m800sdk.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.chaide.m800sdk.log.SdkLog
import com.chaide.m800sdk.platform.M800SdkAndroid
import java.util.UUID

/**
 * Port of `com.sleepace.sdk.manager.ble.BleHelper` behind the common
 * [BleTransport] contract. Same UUIDs, same MTU negotiation, same
 * `connectGatt` flow, same retry-on-write behavior — scanning is the one
 * deliberate deviation from the original (modern `BluetoothLeScanner`
 * instead of the deprecated `startLeScan`, see [scanCallback]).
 */
class AndroidBleTransport(private val context: Context) : BleTransport {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothDevice: BluetoothDevice? = null
    private val handler = Handler(Looper.getMainLooper())

    private var notifyServiceUUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private var writeServiceUUID: UUID = UUID.fromString("0000ffe5-0000-1000-8000-00805f9b34fb")
    private var notifyCharacterUUID: UUID = UUID.fromString("0000ffe4-0000-1000-8000-00805f9b34fb")
    private var writeCharacterUUID: UUID = UUID.fromString("0000ffe9-0000-1000-8000-00805f9b34fb")
    private val notifyDescriptorUUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var gcWrite: BluetoothGattCharacteristic? = null
    private var gcNotify: BluetoothGattCharacteristic? = null

    private var listener: BleTransportListener? = null
    private var scanning = false
    private var btAddress: String? = null
    private var connectionState = 0 // 0 disconnected, 1 connecting, 2 connected
    private var timeout = 10000
    private var mtu = 20
    private var sendOk = false

    private val stopScanTask = Runnable { stopScan() }
    private val discoverTask = Runnable { bluetoothGatt?.discoverServices() }
    private val connectTimeoutTask = Runnable {
        SdkLog.log("BleTransport connect timeout----------------")
        disconnect()
    }

    // `BluetoothLeScanner.startScan` (modern API), not the deprecated
    // `BluetoothAdapter.startLeScan`: on Android 12+ the manifest's
    // `BLUETOOTH_SCAN` with `neverForLocation` only exempts apps from needing
    // `ACCESS_FINE_LOCATION` when they use *this* API. The legacy one still
    // silently returns zero results without location permission, even with
    // `BLUETOOTH_SCAN` granted — confirmed on a physical Android 14 device:
    // the official app (modern API) found the M800, this module (legacy API,
    // ported as-is from the original `BleHelper.java`) did not, same conditions.
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val rawName = try { device.name } catch (e: SecurityException) { null }
            SdkLog.log("BleTransport onScanResult addr=${device.address} name=$rawName rssi=${result.rssi} advLen=${result.scanRecord?.bytes?.size}")
            listener?.onScanResult(object : BleScanResult {
                override val address: String = device.address
                override val name: String? = rawName
                override val rssi: Int = result.rssi
                override val rawRecord: ByteArray = result.scanRecord?.bytes ?: ByteArray(0)
            })
        }

        override fun onScanFailed(errorCode: Int) {
            SdkLog.log("BleTransport onScanFailed errorCode=$errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onMtuChanged(gatt: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == 0) mtu = newMtu - 3
            handler.removeCallbacks(discoverTask)
            handler.postDelayed(discoverTask, 200L)
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== bluetoothGatt) return
            SdkLog.log("BleTransport onConnectionStateChange status:$status,newState:$newState")
            if (newState == 2 && status == 0) {
                if (mtu > 20 && Build.VERSION.SDK_INT >= 21) {
                    bluetoothGatt?.requestMtu(mtu + 3)
                } else {
                    handler.removeCallbacks(discoverTask)
                    handler.postDelayed(discoverTask, 200L)
                }
            } else if (newState == 0 && connectionState != newState) {
                connectionState = newState
                disconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== bluetoothGatt || status != 0) return
            val writeService = gatt.getService(writeServiceUUID)
            val notifyService = gatt.getService(notifyServiceUUID)
            if (writeService == null || notifyService == null) {
                disconnect()
                return
            }
            gcWrite = writeService.getCharacteristic(writeCharacterUUID)
            gcNotify = notifyService.getCharacteristic(notifyCharacterUUID)
            val write = gcWrite
            val notify = gcNotify
            if (write == null || notify == null) {
                disconnect()
                return
            }
            handler.removeCallbacks(connectTimeoutTask)
            handler.postDelayed({
                val g = bluetoothGatt ?: return@postDelayed
                connectionState = 2
                bluetoothDevice = g.device
                if ((notify.properties or 0x10) > 0) setCharacteristicNotification(notify, true)
                listener?.onConnectionStateChanged(BleConnState.CONNECTED)
            }, 200L)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (gatt !== bluetoothGatt || notifyCharacterUUID != characteristic.uuid) return
            val data = characteristic.value
            if (data != null && data.isNotEmpty()) listener?.onDataReceived(data)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (gatt === bluetoothGatt && status == 0) sendOk = true
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            if (state == BluetoothAdapter.STATE_OFF) disconnect()
        }
    }

    init {
        context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun setListener(listener: BleTransportListener?) { this.listener = listener }

    override fun initUuids(notifyServiceUuid: String, notifyCharacteristicUuid: String, writeServiceUuid: String, writeCharacteristicUuid: String) {
        notifyServiceUUID = UUID.fromString(notifyServiceUuid)
        notifyCharacterUUID = UUID.fromString(notifyCharacteristicUuid)
        writeServiceUUID = UUID.fromString(writeServiceUuid)
        writeCharacterUUID = UUID.fromString(writeCharacteristicUuid)
    }

    override fun isBluetoothOpen(): Boolean = bluetoothAdapter?.isEnabled == true
    override fun isSupportBle(): Boolean = context.packageManager.hasSystemFeature("android.hardware.bluetooth_le")

    override fun scan(scanTimeMs: Int) {
        if (scanning || !isBluetoothOpen()) return
        scanning = true
        listener?.onScanStarted()
        handler.postDelayed(stopScanTask, scanTimeMs.toLong())
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        SdkLog.log("BleTransport scan() starting, scanner=${scanner != null}")
        try {
            scanner?.startScan(scanCallback)
        } catch (e: SecurityException) {
            SdkLog.log("BleTransport scan() SecurityException: ${e.message}")
        }
    }

    override fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacks(stopScanTask)
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        listener?.onScanStopped()
    }

    override fun connect(address: String, timeoutMs: Int) {
        if (bluetoothAdapter == null || !BluetoothAdapter.checkBluetoothAddress(address)) return
        if (address != btAddress || connectionState == 0) {
            closeGatt()
            btAddress = address
            val device = bluetoothAdapter.getRemoteDevice(address) ?: return
            connectionState = 1
            listener?.onConnectionStateChanged(BleConnState.CONNECTING)
            if (timeoutMs > 0) timeout = timeoutMs
            handler.postDelayed(connectTimeoutTask, timeout.toLong())
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        } else if (isConnected()) {
            listener?.onConnectionStateChanged(BleConnState.CONNECTED)
        }
    }

    override fun disconnect() {
        handler.removeCallbacks(connectTimeoutTask)
        connectionState = 0
        closeGatt()
        listener?.onConnectionStateChanged(BleConnState.DISCONNECTED)
    }

    private fun closeGatt() {
        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {
            // matches original
        }
        bluetoothGatt = null
    }

    private fun setCharacteristicNotification(characteristic: BluetoothGattCharacteristic, enabled: Boolean) {
        val gatt = bluetoothGatt ?: return
        gatt.setCharacteristicNotification(characteristic, enabled)
        val descriptor = characteristic.getDescriptor(notifyDescriptorUUID) ?: return
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }

    override fun isConnected(): Boolean = connectionState == 2
    override fun getBondedAddress(): String? = btAddress
    override fun getMtu(): Int = mtu
    override fun setMtu(mtu: Int) { if (mtu in 20..509) this.mtu = mtu }

    override fun send(bytes: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val write = gcWrite ?: return
        if (!isConnected()) return
        write.value = bytes
        sendOk = false
        var retry = 10
        while (retry > 0 && bluetoothGatt != null && gcWrite != null && !sendOk && isConnected()) {
            gatt.writeCharacteristic(write)
            try { Thread.sleep(30) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
            retry--
        }
    }

    companion object {
        // One Bluetooth radio per device: the original `BleHelper` was a hard
        // singleton for exactly this reason (two live `BluetoothGatt`/scan
        // sessions fighting over the same adapter is a real conflict, not a
        // style choice), so every caller here — the TCP-gateway WiFi-config
        // flow and any app-level BLE provisioner — must share one instance.
        @Volatile private var instance: AndroidBleTransport? = null

        /** Convenience factory using the context registered via [M800SdkAndroid.init]. */
        fun fromAppContext(): AndroidBleTransport {
            return instance ?: synchronized(this) {
                instance ?: AndroidBleTransport(M800SdkAndroid.appContext).also { instance = it }
            }
        }
    }
}

actual object BleTransportProvider {
    actual fun create(): BleTransport = AndroidBleTransport.fromAppContext()
}
