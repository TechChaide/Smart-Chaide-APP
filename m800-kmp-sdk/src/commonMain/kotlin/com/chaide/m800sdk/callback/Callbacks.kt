package com.chaide.m800sdk.callback

import com.chaide.m800sdk.protocol.CallbackData
import com.chaide.m800sdk.protocol.ConnectionState
import com.chaide.m800sdk.protocol.DeviceType

/** Port of `com.sleepace.sdk.interfs.IDeviceManager`. */
interface IDeviceManager {
    fun registCallBack(callback: IBaseCallback)
    fun unRegistCallBack(callback: IBaseCallback)
    fun connectDevice(address: String, timeout: Int)
    fun connectDevice(address: String, deviceType: DeviceType?, timeout: Int)
    fun deviceOper(frameType: Byte, msgType: Byte, payload: ByteArray, timeout: Int, callback: IResultCallback<Any?>?)
    fun deviceOper(frameType: Byte, msgType: Byte, payload: ByteArray, timeout: Int): CallbackData<Any?>?
    fun getDeviceVersion(timeout: Int)
    fun getDeviceInfo(timeout: Int)
    fun getConnectionState(): ConnectionState
    fun isConnected(): Boolean
    fun disconnect()
    fun release()
}

/** Port of `com.sleepace.sdk.interfs.IConnectionStateCallback`. */
fun interface IConnectionStateCallback {
    fun onStateChanged(manager: IDeviceManager?, state: ConnectionState)
}

/** Port of `com.sleepace.sdk.interfs.IDataCallback`. */
fun interface IDataCallback {
    fun handleData(data: ByteArray)
}

/** Port of `com.sleepace.sdk.interfs.IResultCallback`. */
fun interface IResultCallback<T> {
    fun onResultCallback(data: CallbackData<T>)
}

/** Port of `com.sleepace.sdk.interfs.IBaseCallback`. */
interface IBaseCallback : IConnectionStateCallback, IResultCallback<Any?>

/** Port of `com.sleepace.sdk.interfs.IDeviceCallback`. */
interface IDeviceCallback : IConnectionStateCallback, IDataCallback {
    fun getMaster(): IDeviceManager?
}
