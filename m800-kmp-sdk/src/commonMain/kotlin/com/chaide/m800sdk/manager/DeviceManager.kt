package com.chaide.m800sdk.manager

import com.chaide.m800sdk.callback.IBaseCallback
import com.chaide.m800sdk.callback.IDeviceManager
import com.chaide.m800sdk.platform.PlatformLock
import com.chaide.m800sdk.protocol.CallbackData
import com.chaide.m800sdk.protocol.ConnectionState
import com.chaide.m800sdk.protocol.DeviceType

/**
 * Port of `com.sleepace.sdk.manager.DeviceManager`. Dropped the
 * `SoftReference`-based callback list (JVM-only GC hint with no portable
 * equivalent) in favor of plain strong references cleared explicitly via
 * `unRegistCallBack` — callers already do this on `release()`.
 */
abstract class DeviceManager : IDeviceManager {

    enum class ConnectType { BLE, TCP }

    protected val tag: String = this::class.simpleName ?: "DeviceManager"
    private val callbackLock = PlatformLock()
    protected val callbacks = ArrayList<IBaseCallback>()
    var connectType: ConnectType = ConnectType.BLE

    // Named with a `Field` suffix (rather than `address`/`deviceType`/`connectionState`)
    // because on the JVM/Android target, a Kotlin property auto-generates a
    // `getAddress()`/`getDeviceType()`/`getConnectionState()` accessor that
    // collides byte-for-byte with the hand-written functions below, which
    // `IDeviceManager` requires by those exact names — "platform declaration
    // clash". Subclasses throughout this module read/write these fields
    // directly (they're `protected`), only external callers use the functions.
    protected var addressField: String? = null
    protected var deviceTypeField: DeviceType? = null
    protected var connectionStateField: ConnectionState = ConnectionState.DISCONNECT

    override fun getConnectionState(): ConnectionState = connectionStateField

    fun getAddress(): String? = addressField

    fun setDeviceType(type: DeviceType?) {
        deviceTypeField = type
    }

    fun getDeviceType(): DeviceType? = deviceTypeField

    abstract fun getHeartbeatData(): ByteArray?

    override fun isConnected(): Boolean = getConnectionState() == ConnectionState.CONNECTED

    fun checkCallbackDataStatus(cd: CallbackData<*>?): Boolean = cd != null && cd.isSuccess

    override fun registCallBack(callback: IBaseCallback) {
        callbackLock.withLock {
            callbacks.remove(callback)
            callbacks.add(callback)
        }
    }

    override fun unRegistCallBack(callback: IBaseCallback) {
        callbackLock.withLock { callbacks.remove(callback) }
    }

    fun dataCallback(data: CallbackData<Any?>) {
        val snapshot = callbackLock.withLock { ArrayList(callbacks) }
        for (cb in snapshot) cb.onResultCallback(data)
    }

    protected fun callbackState(state: ConnectionState) {
        connectionStateField = state
        val snapshot = callbackLock.withLock { ArrayList(callbacks) }
        for (cb in snapshot) cb.onStateChanged(this, state)
    }

    companion object {
        fun checkRestOnZ1(deviceId: String?) = deviceId != null && Regex("^(Z1-)[0-9a-zA-Z]{10}$").matches(deviceId)
        fun checkRestOnZ2(deviceId: String?) = deviceId != null && Regex("^(Z2-)[0-9a-zA-Z]{10}$").matches(deviceId)
        fun checkRestOnZ400(deviceId: String?) = deviceId != null && Regex("^(Z4-)[0-9a-zA-Z]{10}$").matches(deviceId)
        fun checkRestOnZ400T(deviceId: String?) = deviceId != null && Regex("^(Z4)[-0-9a-zA-Z]{11}$").matches(deviceId)
        fun checkRestOn(deviceId: String?) = deviceId != null && Regex("^(Z[1-9]-)[0-9a-zA-Z]{10}$").matches(deviceId)
        fun checkPillow(deviceId: String?) = deviceId != null && Regex("^(P)[-0-9a-zA-Z]{12}$").matches(deviceId)
        fun checkSleepDot(deviceId: String?) = deviceId != null && Regex("^(B[1-9]-)[0-9a-zA-Z]{10}$").matches(deviceId)
        fun checkSleepDot502(deviceId: String?) = deviceId != null && Regex("^(B5-02)[0-9a-zA-Z]{8}$").matches(deviceId)
        fun checkSleepDot502T(deviceId: String?) = deviceId != null && Regex("^(B502T)[0-9a-zA-Z]{8}$").matches(deviceId)
    }
}
