package com.chaide.m800sdk.m800

import com.chaide.m800sdk.callback.IBaseCallback
import com.chaide.m800sdk.callback.IDeviceManager
import com.chaide.m800sdk.callback.IResultCallback
import com.chaide.m800sdk.log.SdkLog
import com.chaide.m800sdk.m800.domain.CollectState
import com.chaide.m800sdk.m800.domain.EnvironmentData
import com.chaide.m800sdk.m800.domain.RealTimeData
import com.chaide.m800sdk.m800.domain.SleepState
import com.chaide.m800sdk.platform.PlatformLock
import com.chaide.m800sdk.protocol.CallbackData
import com.chaide.m800sdk.protocol.ConnectionState
import com.chaide.m800sdk.protocol.StatusCode

/**
 * Port of `com.sleepace.sdk.m800.M800Helper` — the public facade an app
 * (or the standalone `Gateway.java` process) talks to. One TCP connection,
 * one login, then query/stream calls; this is the entry point this module
 * exposes as `M800Sdk`.
 */
class M800Helper private constructor() {
    private val tag = "M800Helper"
    private val deviceMgr: M800Manager = M800Manager.getInstance()
    private val callbacks = HashMap<String, IResultCallback<Any?>>()
    private var sid: String? = null
    private var deviceId: String? = null
    private var autoLogin = false

    private val callback = object : IBaseCallback {
        override fun onStateChanged(manager: IDeviceManager?, state: ConnectionState) {
            if (state == ConnectionState.DISCONNECT) {
                if (isAutoLogin()) {
                    if (callbacks.containsKey(CallbackType.LOGIN_SERVER)) {
                        resultCallback(CallbackType.LOGIN_SERVER, StatusCode.FAIL, null)
                    }
                } else {
                    resultCallback(CallbackType.LOGIN_SERVER, StatusCode.FAIL, null)
                }
            } else if (state == ConnectionState.CONNECTED) {
                if (isAutoLogin()) {
                    val res = deviceMgr.loginBySid(M800DataPacket.MsgType.LOGIN, sid)
                    resultCallback(CallbackType.LOGIN_SERVER, if (res) 0 else StatusCode.FAIL, null)
                } else {
                    resultCallback(CallbackType.LOGIN_SERVER, 0, null)
                }
            }
        }

        override fun onResultCallback(data: CallbackData<Any?>) {
            SdkLog.log("$tag onDataCallback $data")
            if (data.callbackType == CallbackType.DEVICE_UPGRADE && data.isSuccess) {
                val progress = data.result as? Int ?: 0
                val cb = if (progress >= 100) callbacks.remove(CallbackType.DEVICE_UPGRADE) else callbacks[CallbackType.DEVICE_UPGRADE]
                cb?.onResultCallback(data)
                return
            }
            resultCallback(data)
        }
    }

    init {
        deviceMgr.registCallBack(callback)
    }

    fun isAutoLogin() = autoLogin
    fun setAutoLogin(value: Boolean) { autoLogin = value }

    fun registOnlineStateListener(l: OnlineStateListener) = deviceMgr.registOnlineStateListener(l)
    fun unregistOnlineStateListener(l: OnlineStateListener) = deviceMgr.unregistOnlineStateListener(l)
    fun registSleepReportUploadStateListener(l: SleepReportUploadStateListener) = deviceMgr.registSleepReportUploadStateListener(l)
    fun unregistSleepReportUploadStateListener(l: SleepReportUploadStateListener) = deviceMgr.unregistSleepReportUploadStateListener(l)
    fun registRealtimeDataListener(l: RealtimeDataListener) = deviceMgr.registRealtimeDataListener(l)
    fun unregistRealtimeDataListener(l: RealtimeDataListener) = deviceMgr.unregistRealtimeDataListener(l)
    fun registRawDataListener(l: RawDataListener) = deviceMgr.registRawDataListener(l)
    fun unregistRawDataListener(l: RawDataListener) = deviceMgr.unregistRawDataListener(l)
    fun registRealtimeSleepStateListener(l: RealtimeSleepStateListener) = deviceMgr.registRealtimeSleepStateListener(l)
    fun unregistRealtimeSleepStateListener(l: RealtimeSleepStateListener) = deviceMgr.unregistRealtimeSleepStateListener(l)
    fun registWiFiSignalListener(l: WiFiSignalListener) = deviceMgr.registWiFiSignalListener(l)
    fun unregistWiFiSignalListener(l: WiFiSignalListener) = deviceMgr.unregistWiFiSignalListener(l)

    /** Opens the TCP connection to the gateway server and logs in by session id. */
    fun login(ip: String, port: Int, sid: String?, deviceId: String?, cb: IResultCallback<Any?>) {
        if (!callbacks.containsKey(CallbackType.LOGIN_SERVER)) {
            callbacks[CallbackType.LOGIN_SERVER] = cb
            this.sid = sid
            this.deviceId = deviceId
            setAutoLogin(true)
            deviceMgr.connectDevice(ip, port)
        }
    }

    fun queryDeviceOnlineState(deviceType: Short, deviceId: String, cb: IResultCallback<Any?>) {
        if (!callbacks.containsKey(CallbackType.ONLINE_STATE_GET)) {
            callbacks[CallbackType.ONLINE_STATE_GET] = cb
            deviceMgr.queryDeviceOnlineState(deviceType, deviceId)
        }
    }

    fun queryCollectState(deviceType: Short, deviceId: String, num: Int, cb: IResultCallback<Any?>) {
        if (!callbacks.containsKey(CallbackType.COLLECT_STATUS_GET)) {
            callbacks[CallbackType.COLLECT_STATUS_GET] = cb
            deviceMgr.queryCollectState(deviceType, deviceId, num)
        }
    }

    fun querySleepState(deviceType: Short, deviceId: String, num: Int, cb: IResultCallback<Any?>) {
        if (!callbacks.containsKey(CallbackType.SLEEP_STATE_GET)) {
            callbacks[CallbackType.SLEEP_STATE_GET] = cb
            deviceMgr.querySleepState(deviceType, deviceId, num)
        }
    }

    fun queryEnvironmentData(deviceId: String, cb: IResultCallback<Any?>) {
        if (!callbacks.containsKey(CallbackType.ENVIRONMENT_DATA_GET)) {
            callbacks[CallbackType.ENVIRONMENT_DATA_GET] = cb
            deviceMgr.queryEnvironmentData(deviceId)
        }
    }

    fun startRealTimeData(deviceType: Short, deviceId: String, num: Int, cb: IResultCallback<Any?>) {
        if (!callbacks.containsKey(CallbackType.REALTIME_DATA_START)) {
            callbacks[CallbackType.REALTIME_DATA_START] = cb
            deviceMgr.startRealTimeData(deviceType, deviceId, num)
        }
    }

    fun stopRealTimeData(deviceType: Short, deviceId: String, num: Int, cb: IResultCallback<Any?>) {
        if (!callbacks.containsKey(CallbackType.REALTIME_DATA_STOP)) {
            callbacks[CallbackType.REALTIME_DATA_STOP] = cb
            deviceMgr.stopRealTimeData(deviceType, deviceId, num)
        }
    }

    fun startOriginalData(deviceType: Short, deviceId: String, num: Int, cb: IResultCallback<Any?>) {
        if (!callbacks.containsKey(CallbackType.RAW_DATA_START)) {
            callbacks[CallbackType.RAW_DATA_START] = cb
            deviceMgr.startSeeRawData(deviceType, deviceId, num)
        }
    }

    fun stopOriginalData(deviceType: Short, deviceId: String, num: Int, cb: IResultCallback<Any?>) {
        if (!callbacks.containsKey(CallbackType.RAW_DATA_STOP)) {
            callbacks[CallbackType.RAW_DATA_STOP] = cb
            deviceMgr.stopSeeRawData(deviceType, deviceId, num)
        }
    }

    fun stopCollection(userId: Int, deviceType: Short, deviceId: String, num: Int, timestamp: Int, cb: IResultCallback<Any?>) {
        if (!callbacks.containsKey(CallbackType.COLLECT_STOP)) {
            callbacks[CallbackType.COLLECT_STOP] = cb
            deviceMgr.stopCollection(userId, deviceType, deviceId, num, timestamp)
        }
    }

    fun upgradeDevice(deviceVersion: Float, deviceType: Short, deviceId: String, cb: IResultCallback<Any?>) {
        if (!callbacks.containsKey(CallbackType.DEVICE_UPGRADE)) {
            callbacks[CallbackType.DEVICE_UPGRADE] = cb
            deviceMgr.upgradeDevice(deviceVersion, deviceType, deviceId)
        }
    }

    private fun resultCallback(callbackType: String, status: Short, result: Any?) {
        val data = CallbackData<Any?>()
        data.callbackType = callbackType
        data.status = status
        data.result = result
        resultCallback(data)
    }

    private fun resultCallback(data: CallbackData<Any?>) {
        val cb = callbacks.remove(data.callbackType)
        cb?.onResultCallback(data)
    }

    companion object {
        private var instance: M800Helper? = null
        private val lock = PlatformLock()

        fun getInstance(): M800Helper = lock.withLock {
            instance ?: M800Helper().also { instance = it }
        }
    }
}
