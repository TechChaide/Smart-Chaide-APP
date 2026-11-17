package com.chaide.m800sdk.socket

/**
 * Minimal blocking TCP client contract. `SocketHelper` used raw
 * `java.net.Socket` directly — JVM-only — so this boundary is what makes the
 * rest of the protocol (framing, login, heartbeat, request/response) common.
 * Android's `actual` wraps `java.net.Socket` (unchanged behavior); iOS's
 * `actual` wraps Kotlin/Native POSIX sockets.
 */
interface RawTcpSocket {
    val isConnected: Boolean
    /** Blocking connect with timeout; returns true once the TCP handshake completes. */
    fun connect(ip: String, port: Int, timeoutMs: Int): Boolean
    /** Blocking write of the full buffer. */
    fun write(bytes: ByteArray)
    /** Blocking read into [buffer]; returns bytes read, or -1 on orderly close. */
    fun read(buffer: ByteArray): Int
    fun close()
}

expect fun createRawTcpSocket(): RawTcpSocket
