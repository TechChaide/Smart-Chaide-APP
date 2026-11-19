package com.chaide.app.ble

import com.chaide.m800sdk.platform.PlatformLock
import com.chaide.m800sdk.util.PortableCRC32


/**
 * Protocolo BLE real del M800.
 *
 * FUENTE: el bundle H5 de la app oficial (`assets/H5/.../vendor.*.js`), que es
 * donde vive de verdad este protocolo. La capa nativa del APK
 * (`BleHelper4WebView`) solo expone una API BLE genérica estilo WeChat
 * (startBluetoothDevicesDiscovery / createBLEConnection /
 * writeBLECharacteristicValue / onBLECharacteristicValueChange) sobre
 * BluetoothGatt crudo; no arma frames. Las clases Java del paquete
 * `com.sleepace.sdk` del APK son la ruta socket/TCP heredada y NO se usan aqui.
 *
 * Formato (de `buildHeader` / `buildEnder` del JS oficial):
 *
 *   Header (9 B):
 *     [0] = 0              version
 *     [1] = 2              frameType
 *     [2] = 1              frameCount
 *     [3] = 0              frameNum
 *     [4] = msgSeq
 *     [5..6] = deviceType  int16 big-endian (38 = M800)
 *     [7..8] = msgType     int16 big-endian
 *   Payload
 *   CRC32 (4 B) sobre bytes 0 .. 9+payloadLen
 *   Separador (4 B) = "$_@-"  [36,95,64,45]
 *
 * El tamano total es 17 + payloadLen.
 * En la respuesta el equipo devuelve: frameType@1, frameSeq@3, msgSeq@4,
 * deviceType@5..6, msgType@7..8 (ver `handleResp` del JS).
 *
 * CORREGIDO: msgType es de 2 bytes, NO 1 — con 1 byte, valores reales del SDK
 * como 514 (getSystemInfo), 930, 928 no caben (514 = 0x0202, truncaba a 0x02).
 * Confirmado contra el SDK nativo decompilado (com.sleepace.sdk.manager.socket.
 * SocketFrame / WiFiConfigHelper.getMsgType): todo msgType de este protocolo es
 * de 16 bits. Con el header de 8 bytes el equipo recibia msgType=2 en vez de
 * 514 y por eso nunca contestaba.
 */
object M800Protocol {

    // UUIDs reales (DEFAULT_BLE_CONFIG.uuids del JS oficial).
    // OJO: write = ffe5/ffe9 y notify = ffe0/ffe4. Antes estaban INVERTIDOS
    // aqui, asi que se escribia sobre la caracteristica de notificacion.
    const val WRITE_SERVICE_UUID = "0000ffe5-0000-1000-8000-00805f9b34fb"
    const val WRITE_CHAR_UUID = "0000ffe9-0000-1000-8000-00805f9b34fb"
    const val NOTIFY_SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val NOTIFY_CHAR_UUID = "0000ffe4-0000-1000-8000-00805f9b34fb"

    /** El sensor anuncia un nombre corto (p.ej. "M80040184"), mas corto que su
     *  serial de 13 caracteres — verificado contra el hardware real. */
    val DEVICE_NAME_REGEX = Regex("^M8[0-9a-zA-Z-]{3,}$")

    const val DEVICE_TYPE_M800: Short = 38

    // frameType (FRAME_TYPE del JS)
    const val FRAME_REQUEST: Short = 1
    const val FRAME_RESPONSE: Short = 2
    const val FRAME_ACK: Short = 3
    const val FRAME_POST: Short = 4

    /** Devuelve el deviceId SIN necesitar conocerlo: es la que sirve para
     *  descubrir la identidad del equipo. */
    const val MSG_GET_SYSTEM_INFO: Short = 514
    /** Requiere el deviceId en la peticion, asi que no sirve para descubrirlo. */
    const val MSG_GET_DEVICE_INFO: Short = 518

    /**
     * EXPERIMENTAL — sin confirmar contra el equipo real todavia. En el canal
     * TCP real (SocketFrame, ver gateway/M800Manager) "iniciar vista de datos
     * en vivo" es el valor 7 (POST_START_VIEW_REALDATA / REALTIME_DATA_OPEN,
     * ambos definidos asi en el SDK oficial). El campo msgType del framing
     * BLE es 1 byte, y 7 cabe sin truncarse — se prueba tal cual por si el
     * mismo firmware acepta el comando tambien por BLE (la app oficial solo
     * lo usa por TCP, pero eso no prueba que el firmware lo rechace por BLE).
     */
    const val MSG_START_REALTIME: Short = 7
    const val MSG_STOP_REALTIME: Short = 8

    private val SEPARATOR = byteArrayOf(36, 95, 64, 45) // "$_@-"
    private const val HEADER_SIZE = 9

    /** Maximo por escritura GATT. */
    const val BLE_CHUNK_SIZE = 20

    private var seq: Byte = 0

    private val seqLock = PlatformLock()

    private fun nextSeq(): Byte = seqLock.withLock {
        seq = ((seq + 1) and 0x7F).toByte()
        seq
    }

    // ---- Helpers big-endian (reemplazan java.nio.ByteBuffer, que no existe en iOS) ----
    private fun putShortBE(buf: ByteArray, at: Int, v: Short) {
        buf[at] = ((v.toInt() shr 8) and 0xFF).toByte(); buf[at + 1] = (v.toInt() and 0xFF).toByte()
    }
    private fun putIntBE(buf: ByteArray, at: Int, v: Int) {
        for (k in 0..3) buf[at + k] = ((v shr (24 - 8 * k)) and 0xFF).toByte()
    }
    private fun getShortBE(buf: ByteArray, at: Int): Short =
        (((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)).toShort()
    private fun getIntBE(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 24) or ((buf[at + 1].toInt() and 0xFF) shl 16) or
            ((buf[at + 2].toInt() and 0xFF) shl 8) or (buf[at + 3].toInt() and 0xFF)
    private fun crc32(data: ByteArray, length: Int): Int =
        PortableCRC32().apply { update(data, 0, length) }.getValue().toInt()
    /** ASCII "puro" (como Charsets.US_ASCII): bytes >127 pasan a '?'. */
    private fun ascii(data: ByteArray, offset: Int, length: Int): String =
        CharArray(length) { val c = data[offset + it].toInt() and 0xFF; if (c < 128) c.toChar() else '?' }.concatToString()

    data class BuiltFrame(val bytes: ByteArray, val msgSeq: Byte)

    /**
     * Payload de getSystemInfo / getDeviceInfo: deviceId (14 B ASCII, relleno
     * con ceros) + deviceType (2 B). Para descubrir la identidad se manda el
     * deviceId vacio.
     */
    fun buildIdentityPayload(deviceId: String = "", deviceType: Short = DEVICE_TYPE_M800): ByteArray {
        val buf = ByteArray(16)
        val id = deviceId.map { if (it.code < 128) it.code.toByte() else '?'.code.toByte() }
        val n = minOf(id.size, 14)
        for (k in 0 until n) buf[k] = id[k]   // resto ya en cero
        putShortBE(buf, 14, deviceType)
        return buf
    }

    fun buildFrame(
        msgType: Short,
        payload: ByteArray = ByteArray(0),
        deviceType: Short = DEVICE_TYPE_M800,
    ): BuiltFrame {
        val s = nextSeq()
        val total = HEADER_SIZE + payload.size + 4 + SEPARATOR.size
        val buf = ByteArray(total)
        buf[0] = 0                         // version
        buf[1] = 2                         // frameType
        buf[2] = 1                         // frameCount
        buf[3] = 0                         // frameNum
        buf[4] = s                         // msgSeq
        putShortBE(buf, 5, deviceType)     // deviceType int16 (38 para el M800)
        putShortBE(buf, 7, msgType)        // msgType int16 (NO cabe en 1 byte: 514, 930, 928...)
        payload.copyInto(buf, HEADER_SIZE)

        // CRC32 sobre header + payload, igual que `crc32buf(slice(0, 8+len))`
        putIntBE(buf, HEADER_SIZE + payload.size, crc32(buf, HEADER_SIZE + payload.size))
        SEPARATOR.copyInto(buf, HEADER_SIZE + payload.size + 4)
        return BuiltFrame(buf, s)
    }

    fun chunk(frame: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var off = 0
        while (off < frame.size) {
            val len = (frame.size - off).coerceAtMost(BLE_CHUNK_SIZE)
            out.add(frame.copyOfRange(off, off + len))
            off += len
        }
        return out
    }

    /** Acumula notificaciones hasta ver el separador "$_@-". */
    class FrameReassembler {
        private var buf = ByteArray(0)

        fun feed(data: ByteArray): ByteArray? {
            buf += data
            val all = buf
            if (all.size < HEADER_SIZE + 4 + SEPARATOR.size) return null
            val end = all.size
            val okSep = SEPARATOR.indices.all { all[end - SEPARATOR.size + it] == SEPARATOR[it] }
            if (!okSep) return null
            buf = ByteArray(0)
            return all
        }

        fun clear() { buf = ByteArray(0) }
    }

    data class ParsedFrame(
        val msgSeq: Byte,
        val msgType: Short,
        val frameType: Short,
        val payload: ByteArray,
        val crcOk: Boolean,
    )

    fun parseFrame(frame: ByteArray): ParsedFrame? {
        if (frame.size < HEADER_SIZE + 4 + SEPARATOR.size) return null
        val payloadLen = frame.size - HEADER_SIZE - 4 - SEPARATOR.size
        val payload = frame.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLen)
        val crcGot = getIntBE(frame, HEADER_SIZE + payloadLen)
        val crcCalc = crc32(frame, HEADER_SIZE + payloadLen)
        // Layout de respuesta segun `handleResp` del JS:
        //   frameType@1, frameSeq@3, msgSeq@4, deviceType@5..6, msgType@7..8
        return ParsedFrame(frame[4], getShortBE(frame, 7), frame[1].toShort(), payload, crcGot == crcCalc)
    }

    /**
     * Respuesta de getSystemInfo (514), segun el parseo del JS oficial:
     *   status(2B) deviceId(14B) deviceType(2B) currVersion(2B)
     *   facVersion(2B) facTime(4B) manufacCode(2B)
     */
    data class SystemInfo(
        val status: Int,
        val deviceId: String,
        val deviceType: Int,
        val currVersion: Int,
        val facVersion: Int,
        val facTime: Int,
        val manufacCode: Int,
    )

    fun parseSystemInfo(payload: ByteArray): SystemInfo? {
        if (payload.size < 16) return null
        val id = ascii(payload, 2, 14).trim { it <= ' ' }
        fun s(at: Int) = if (payload.size >= at + 2) getShortBE(payload, at).toInt() else 0
        fun i(at: Int) = if (payload.size >= at + 4) getIntBE(payload, at) else 0
        return SystemInfo(getShortBE(payload, 0).toInt(), id, s(16), s(18), s(20), i(22), s(26))
    }

    /**
     * Devuelve el campo del advertisement con el AD type pedido, como ASCII.
     * Equivale a `BleDeviceNameUtil.getBleDeviceName(type, record)` del demo
     * oficial del fabricante, que recibe justamente el `type` como parámetro.
     */
    fun parseAdField(scanRecord: ByteArray?, adType: Int): String? {
        if (scanRecord == null) return null
        var i = 0
        while (i < scanRecord.size - 1) {
            val len = scanRecord[i].toInt() and 0xFF
            if (len == 0 || i + len >= scanRecord.size) return null
            if ((scanRecord[i + 1].toInt() and 0xFF) == adType) {
                return ascii(scanRecord, i + 2, len - 1)
                    .trim { it <= ' ' || it == ' ' }
                    .takeIf { it.isNotEmpty() }
            }
            i += len + 1
        }
        return null
    }

    /** Nombre local anunciado (AD 0x09 completo / 0x08 corto), p.ej. "M80040184". */
    fun parseLocalName(scanRecord: ByteArray?): String? =
        parseAdField(scanRecord, 0x09) ?: parseAdField(scanRecord, 0x08)

    /**
     * SERIAL del equipo (el impreso en la caja, p.ej. "M800424600184").
     * Viaja en los datos de fabricante (AD type 0xFF) del advertisement —
     * verificado contra el sensor real:
     *   0e ff 4d383030343234363030313834  ->  "M800424600184"
     * Es el dato que identifica la unidad; el nombre corto (0x09) NO sirve,
     * porque varias unidades de lotes distintos lo comparten.
     */
    fun parseSerial(scanRecord: ByteArray?): String? =
        parseAdField(scanRecord, 0xFF)?.takeIf { DEVICE_NAME_REGEX.matches(it) }
}
