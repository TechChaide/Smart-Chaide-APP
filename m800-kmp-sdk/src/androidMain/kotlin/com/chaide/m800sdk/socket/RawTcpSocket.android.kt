package com.chaide.m800sdk.socket

import java.net.InetSocketAddress
import java.net.Socket

/** Android/JVM `actual`: thin wrapper over `java.net.Socket` — same type the original SDK used directly. */
private class AndroidTcpSocket : RawTcpSocket {
    private var socket: Socket? = null

    override val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    override fun connect(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(ip, port), timeoutMs)
            socket = s
            s.isConnected
        } catch (e: Exception) {
            false
        }
    }

    override fun write(bytes: ByteArray) {
        val out = socket?.getOutputStream() ?: return
        out.write(bytes)
        out.flush()
    }

    override fun read(buffer: ByteArray): Int {
        val input = socket?.getInputStream() ?: return -1
        return input.read(buffer)
    }

    override fun close() {
        try { socket?.close() } catch (e: Exception) { /* ignore */ }
        socket = null
    }
}

actual fun createRawTcpSocket(): RawTcpSocket = AndroidTcpSocket()
