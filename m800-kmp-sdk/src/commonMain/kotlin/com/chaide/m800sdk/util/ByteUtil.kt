package com.chaide.m800sdk.util

/** Faithful port of `com.sleepace.sdk.util.ByteUtil` — pure byte-array math, no JDK types. */
object ByteUtil {

    fun byte2Int(source: ByteArray, begin: Int): Int {
        var i = begin
        var out = 0
        out = out or ((source[i++].toInt() and 0xFF) shl 24)
        out = out or ((source[i++].toInt() and 0xFF) shl 16)
        out = out or ((source[i++].toInt() and 0xFF) shl 8)
        out = out or (source[i].toInt() and 0xFF)
        return out
    }

    fun byte2long(source: ByteArray, begin: Int): Long {
        var i = begin
        var out = 0L
        out = out or ((source[i++].toLong() and 0xFF) shl 56)
        out = out or ((source[i++].toLong() and 0xFF) shl 48)
        out = out or ((source[i++].toLong() and 0xFF) shl 40)
        out = out or ((source[i++].toLong() and 0xFF) shl 32)
        out = out or ((source[i++].toLong() and 0xFF) shl 24)
        out = out or ((source[i++].toLong() and 0xFF) shl 16)
        out = out or ((source[i++].toLong() and 0xFF) shl 8)
        out = out or (source[i].toLong() and 0xFF)
        return out
    }

    fun int2byte(source: Int): ByteArray {
        val l = source.toLong() and 0xFFFFFFFFL
        return byteArrayOf(
            ((l ushr 24) and 0xFFL).toByte(),
            ((l ushr 16) and 0xFFL).toByte(),
            ((l ushr 8) and 0xFFL).toByte(),
            (l and 0xFFL).toByte()
        )
    }

    fun long2byte(source: Long): ByteArray {
        val l = source and 0xFFFFFFFFL
        return byteArrayOf(
            ((l ushr 56) and 0xFFL).toByte(),
            ((l ushr 48) and 0xFFL).toByte(),
            ((l ushr 40) and 0xFFL).toByte(),
            ((l ushr 32) and 0xFFL).toByte(),
            ((l ushr 24) and 0xFFL).toByte(),
            ((l ushr 16) and 0xFFL).toByte(),
            ((l ushr 8) and 0xFFL).toByte(),
            (l and 0xFFL).toByte()
        )
    }

    fun short2byte(source: Short): ByteArray {
        val i = source.toInt() and 0xFFFF
        return byteArrayOf(((i ushr 8) and 0xFF).toByte(), (i and 0xFF).toByte())
    }

    fun byte2short(bb: ByteArray, index: Int): Short {
        var i = index
        var out = 0
        out = out or ((bb[i++].toInt() and 0xFF) shl 8)
        out = out or (bb[i].toInt() and 0xFF)
        return out.toShort()
    }

    fun str2Byte(str: String): ByteArray {
        val bb = str.encodeToByteArray()
        val ids = ByteArray(bb.size + 1)
        bb.copyInto(ids)
        return ids
    }

    fun to64Bytes(bytes: ByteArray): ByteArray = toNBytes(64, bytes)
    fun to32Bytes(bytes: ByteArray): ByteArray = toNBytes(32, bytes)

    fun toNBytes(n: Int, bytes: ByteArray): ByteArray {
        val out = ByteArray(n)
        for (i in 0 until n) {
            out[i] = if (i < bytes.size) bytes[i] else 0
        }
        return out
    }
}
