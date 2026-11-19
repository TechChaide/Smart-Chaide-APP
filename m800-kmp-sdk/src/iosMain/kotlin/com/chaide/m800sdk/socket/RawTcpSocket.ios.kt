package com.chaide.m800sdk.socket

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.EINPROGRESS
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.POLLOUT
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_NOSIGPIPE
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getsockopt
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket

/**
 * IPv4 "a.b.c.d" -> in_addr.s_addr en orden de red. Se arma a mano porque
 * `inet_pton`/`htons` son macros/funciones que Kotlin/Native no expone en
 * los targets iOS. Todos los iPhone/simuladores son little-endian.
 */
private fun ipv4ToNetworkOrder(ip: String): UInt? {
    val parts = ip.trim().split('.')
    if (parts.size != 4) return null
    var out = 0u
    for ((i, p) in parts.withIndex()) {
        val b = p.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        out = out or (b.toUInt() shl (8 * i))
    }
    return out
}

private fun hostToNetworkShort(v: Int): UShort =
    (((v and 0xFF) shl 8) or ((v shr 8) and 0xFF)).toUShort()

/**
 * Cliente TCP con sockets BSD reales (`platform.posix`), sin dependencias
 * extra, para el protocolo TCP plano del gateway M800.
 */
@OptIn(ExperimentalForeignApi::class)
private class PosixTcpSocket : RawTcpSocket {
    private var fd: Int = -1

    override val isConnected: Boolean get() = fd >= 0

    override fun connect(ip: String, port: Int, timeoutMs: Int): Boolean {
        val sAddr = ipv4ToNetworkOrder(ip) ?: return false
        memScoped {
            val sockFd = socket(AF_INET, SOCK_STREAM, 0)
            if (sockFd < 0) return false

            // Sin esto, escribir en un socket que el gateway ya cerro mata la
            // app entera con SIGPIPE (comportamiento por defecto en iOS).
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(sockFd, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())

            // connect no bloqueante + poll() con timeout: POSIX `connect` no
            // tiene parametro de timeout en milisegundos.
            val flags = fcntl(sockFd, F_GETFL, 0)
            fcntl(sockFd, F_SETFL, flags or O_NONBLOCK)

            val addr = alloc<sockaddr_in>()
            addr.sin_len = sizeOf<sockaddr_in>().convert()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = hostToNetworkShort(port)
            addr.sin_addr.s_addr = sAddr

            val result = platform.posix.connect(sockFd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert())
            if (result != 0 && errno != EINPROGRESS) {
                close(sockFd)
                return false
            }

            if (result != 0) {
                val pfd = alloc<pollfd>()
                pfd.fd = sockFd
                pfd.events = POLLOUT.convert()
                val sel = poll(pfd.ptr, 1u, timeoutMs)
                if (sel <= 0) {
                    close(sockFd)
                    return false
                }
                val soErr = alloc<IntVar>()
                val len = alloc<platform.posix.socklen_tVar>().apply { value = sizeOf<IntVar>().convert() }
                getsockopt(sockFd, SOL_SOCKET, SO_ERROR, soErr.ptr, len.ptr)
                if (soErr.value != 0) {
                    close(sockFd)
                    return false
                }
            }

            // De vuelta a modo bloqueante para el bucle read()/write() de SocketHelper.
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
