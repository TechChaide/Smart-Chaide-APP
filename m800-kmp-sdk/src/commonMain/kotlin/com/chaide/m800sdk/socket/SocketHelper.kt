package com.chaide.m800sdk.socket

import com.chaide.m800sdk.callback.IDeviceCallback
import com.chaide.m800sdk.log.SdkLog
import com.chaide.m800sdk.manager.DeviceManager
import com.chaide.m800sdk.platform.BackgroundThread
import com.chaide.m800sdk.platform.PlatformLock
import com.chaide.m800sdk.platform.currentTimeMillis
import com.chaide.m800sdk.platform.sleepMillis
import com.chaide.m800sdk.protocol.ConnectionState
import com.chaide.m800sdk.util.StringUtilCommon

/**
 * Port of `com.sleepace.sdk.manager.socket.SocketHelper`. The original used
 * `java.net.Socket` + raw JVM `Thread`s directly; both are now behind the
 * [RawTcpSocket] / [BackgroundThread] platform boundaries so this class
 * itself is 100% common code, same as upstream (it never touched
 * `android.*` beyond `SystemClock.sleep` and `TextUtils.isEmpty`).
 */
class SocketHelper private constructor() {
    private val tag = "SocketHelper"

    private var serverIP: String? = null
    private var serverPort: Int = 0
    private var socket: RawTcpSocket? = null
    private var isReading = false
    private var isWriting = false
    private var readThread: BackgroundThread? = null
    private var writeThread: BackgroundThread? = null
    private var connectionState: ConnectionState = ConnectionState.DISCONNECT
    private val listeners = ArrayList<IDeviceCallback>()
    private val listenersLock = PlatformLock()
    private var lastHeartJumpTime = 0L
    var master: DeviceManager? = null

    private val sendQueue = ArrayDeque<ByteArray>()
    private val sendQueueLock = PlatformLock()
    private var readRunnableErr = false

    fun getConnectRetryCount(): Byte = 6
    fun getServerIP(): String? = serverIP
    fun getServerPort(): Int = serverPort

    fun registCallback(listener: IDeviceCallback): Boolean = listenersLock.withLock {
        if (!listeners.contains(listener)) { listeners.add(listener); true } else false
    }

    fun unregistCallback(listener: IDeviceCallback): Boolean = listenersLock.withLock {
        listeners.remove(listener)
    }

    fun sendByteBuffer2Server(bytes: ByteArray) {
        sendQueueLock.withLock { sendQueue.addLast(bytes) }
    }

    fun connect(manager: DeviceManager) {
        if (StringUtilCommon.checkIP(serverIP)) connect(manager, serverIP!!, serverPort)
    }

    fun connect(manager: DeviceManager, ip: String, port: Int, timeout: Int = 6000) {
        master = manager
        if (!StringUtilCommon.checkIP(ip)) {
            disConnect(true)
            return
        }
        setConnectionState(ConnectionState.CONNECTING)
        serverIP = ip
        serverPort = port
        val s = createRawTcpSocket()
        var connected = false
        var count = 0
        while (count < 6) {
            try {
                if (s.connect(ip, port, timeout)) { connected = true; break }
            } catch (e: Exception) {
                // matches original: swallow and retry
            }
            sleepMillis(1000)
            count++
        }
        SdkLog.log("$tag start connect ip:$ip,port:$port,count:$count,timeout:$timeout,state:$connected")
        if (connected) {
            socket = s
            isReading = true
            isWriting = true
            readRunnableErr = false
            writeThread = BackgroundThread("socket-write") { writeLoop() }.also { it.start() }
            readThread = BackgroundThread("socket-read") { readLoop() }.also { it.start() }
            lastHeartJumpTime = currentTimeMillis()
            setConnectionState(ConnectionState.CONNECTED)
            onStateChange(ConnectionState.CONNECTED)
        } else {
            disConnect(true)
        }
    }

    fun isSocketConnected(): Boolean = socket?.isConnected ?: false

    fun getConnectState(): ConnectionState = connectionState

    private fun setConnectionState(state: ConnectionState) {
        connectionState = state
    }

    private fun onStateChange(state: ConnectionState) {
        val snapshot = listenersLock.withLock { ArrayList(listeners) }
        for (l in snapshot) l.onStateChanged(master, state)
    }

    fun disConnect() = disConnect(true)

    fun disConnect(needCallback: Boolean) {
        SdkLog.log("$tag disConnect needCallback:$needCallback")
        isReading = false
        isWriting = false
        sendQueueLock.withLock { sendQueue.clear() }
        readThread?.interrupt()
        writeThread?.interrupt()
        socket?.close()
        socket = null
        setConnectionState(ConnectionState.DISCONNECT)
        if (needCallback) onStateChange(ConnectionState.DISCONNECT)
    }

    private fun handleData(bytes: ByteArray) {
        val snapshot = listenersLock.withLock { ArrayList(listeners) }
        for (l in snapshot) l.handleData(bytes)
    }

    fun writeBytes(bb: ByteArray) {
        val s = socket
        if (s != null) {
            s.write(bb)
        } else {
            SdkLog.log("$tag writeBytes socket null-------")
            disConnect()
        }
    }

    private fun writeLoop() {
        while (isWriting && isSocketConnected()) {
            val buffer = sendQueueLock.withLock { if (sendQueue.isEmpty()) null else sendQueue.removeFirst() }
            try {
                if (buffer == null) {
                    val deviceType = master?.getDeviceType()
                    val delay = currentTimeMillis() - lastHeartJumpTime
                    if (!isSocketConnected()) continue
                    if (delay > 60000L) {
                        disConnect(true)
                        break
                    }
                    if (com.chaide.m800sdk.protocol.DeviceType.isNox1(deviceType) || com.chaide.m800sdk.protocol.DeviceType.isEW202W(deviceType)) {
                        SocketFrame.sendHeartFrames(this)
                        sleepMillis(20) // small idle backoff; original relied on the 20s poll blocking
                        continue
                    }
                    val heartbeat = master?.getHeartbeatData()
                    if (heartbeat != null && heartbeat.isNotEmpty()) writeBytes(heartbeat)
                    sleepMillis(1000)
                } else {
                    writeBytes(buffer)
                }
            } catch (e: Exception) {
                // matches original: log and keep looping
            }
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(1024)
        while (isReading && isSocketConnected()) {
            val s = socket
            if (s == null) {
                disConnect()
                break
            }
            try {
                val length = s.read(buffer)
                lastHeartJumpTime = currentTimeMillis()
                if (length > 0) {
                    handleData(buffer.copyOfRange(0, length))
                } else {
                    disConnect()
                    break
                }
            } catch (e: Exception) {
                if (!readRunnableErr) {
                    readRunnableErr = true
                    disConnect()
                }
            }
        }
    }

    companion object {
        private var instance: SocketHelper? = null
        // `@Volatile`/`synchronized` are JVM-only; this module's own PlatformLock
        // covers the one static-singleton spot that needs locking on every target.
        private val initLock = PlatformLock()

        fun getInstance(): SocketHelper = initLock.withLock {
            instance ?: SocketHelper().also { instance = it }
        }
    }
}
