package com.chaide.m800sdk.util

/**
 * Portable subset of `com.sleepace.sdk.util.StringUtil` (dropped `java.util.regex`
 * in favor of hand-rolled checks, and `java.net.InetAddress` entirely — unused
 * by the M800 TCP/BLE path this module ports).
 */
object StringUtilCommon {

    fun checkDeviceCode(deviceCode: String?): Boolean {
        if (deviceCode == null) return false
        val parts = deviceCode.split("-")
        if (parts.size != 2) return false
        return parts[0].isNotEmpty() && parts[0].all { it.isDigit() } &&
            parts[1].isNotEmpty() && parts[1].all { it.isDigit() }
    }

    fun checkIP(ip: String?): Boolean {
        if (ip == null) return false
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val n = part.toIntOrNull() ?: return@all false
            n in 0..255 && (part == n.toString())
        }
    }

    fun int2IP(ip: Int): String {
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    fun bytes2HexString(src: ByteArray?): String {
        if (src == null || src.isEmpty()) return ""
        val sb = StringBuilder()
        for (b in src) {
            val v = b.toInt() and 0xFF
            val hv = v.toString(16)
            if (hv.length < 2) sb.append('0')
            sb.append(hv).append(':')
        }
        return sb.toString().dropLast(1).lowercase()
    }
}
