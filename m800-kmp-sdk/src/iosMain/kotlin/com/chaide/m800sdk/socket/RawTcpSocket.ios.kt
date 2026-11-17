package com.chaide.m800sdk.socket

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.EINPROGRESS
import platform.posix.FD_ISSET
import platform.posix.FD_SET
import platform.posix.FD_ZERO
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fd_set
import platform.posix.htons
import platform.posix.inet_pton
import platform.posix.read
import platform.posix.recv
import platform.posix.select
import platform.posix.send
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval

/**
 * Real BSD-socket TCP client using `platform.posix` bindings that ship with
 * every Kotlin/Native Apple target by default — no Ktor or other networking
 * dependency needed for the M800's plain-TCP gateway protocol.
 *
 * NOT exercised on real hardware in this session (no Mac/Xcode toolchain
 * available to build the iOS targets here) — review and validate on-device
 * before shipping.
 */
@OptIn(ExperimentalForeignApi::class)
private class PosixTcpSocket : RawTcpSocket {
    private var fd: Int = -1

    override val isConnected: Boolean get() = fd >= 0

    override fun connect(ip: String, port: Int, timeoutMs: Int): Boolean {
        memScoped {
            val sockFd = socket(AF_INET, SOCK_STREAM, 0)
            if (sockFd < 0) return false

            // Non-blocking connect + select() with a timeout, since POSIX `connect`
            // has no millisecond-timeout parameter of its own.
            val flags = fcntl(sockFd, platform.posix.F_GETFL, 0)
            fcntl(sockFd, F_SETFL, flags or O_NONBLOCK)

            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = htons(port.toUShort())
            if (inet_pton(AF_INET, ip, addr.sin_addr.ptr) != 1) {
                close(sockFd)
                return false
            }

            val result = platform.posix.connect(sockFd, addr.ptr.reinterpret<platform.posix.sockaddr>(), sockaddr_in.size.convert())
            if (result != 0 && errno != EINPROGRESS) {
                close(sockFd)
                return false
            }

            if (result != 0) {
                val writeSet = alloc<fd_set>()
                FD_ZERO(writeSet.ptr)
                FD_SET(sockFd, writeSet.ptr)
                val tv = alloc<timeval>()
                tv.tv_sec = (timeoutMs / 1000).convert()
                tv.tv_usec = ((timeoutMs % 1000) * 1000).convert()
                val sel = select(sockFd + 1, null, writeSet.ptr, null, tv.ptr)
                if (sel <= 0 || !FD_ISSET(sockFd, writeSet.ptr)) {
                    close(sockFd)
                    return false
                }
            }

            // Back to blocking mode for the simple read()/write() loop SocketHelper runs.
            fcntl(sockFd, F_SETFL, flags)
            fd = sockFd
            return true
        }
    }

    override fun write(bytes: ByteArray) {
        val f = fd
        if (f < 0 || bytes.isEmpty()) return
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val n = send(f, pinned.addressOf(offset), (bytes.size - offset).convert(), 0)
                if (n <= 0) break
                offset += n.toInt()
            }
        }
    }

    override fun read(buffer: ByteArray): Int {
        val f = fd
        if (f < 0) return -1
        return buffer.usePinned { pinned ->
            recv(f, pinned.addressOf(0), buffer.size.convert(), 0).toInt()
        }
    }

    override fun close() {
        if (fd >= 0) close(fd)
        fd = -1
    }
}

actual fun createRawTcpSocket(): RawTcpSocket = PosixTcpSocket()
