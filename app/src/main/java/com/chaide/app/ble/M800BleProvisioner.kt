package com.chaide.app.ble

import android.content.Context
import com.chaide.m800sdk.ble.BleConnState
import com.chaide.m800sdk.ble.BleScanResult
import com.chaide.m800sdk.ble.BleTransport
import com.chaide.m800sdk.ble.BleTransportListener
import com.chaide.m800sdk.ble.BleTransportProvider
import com.chaide.m800sdk.callback.IResultCallback
import com.chaide.m800sdk.platform.M800SdkAndroid
import com.chaide.m800sdk.wificonfig.WiFiConfigHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Réplica fiel (no aproximación) del flujo real de aprovisionamiento WiFi por
 * BLE del M800, portado del SDK oficial decompilado (WiFiConfigHelper del
 * fabricante). Antes llamaba directo a `sdkcore.jar`/`wificonfigsdk.jar`; ahora
 * usa `m800-kmp-sdk` (`../m800-kmp-sdk`), la reimplementación 100% Kotlin de
 * ese mismo SDK — mismo protocolo, mismo manejo GATT (`AndroidBleTransport` es
 * un puerto directo de `BleHelper`), cero binarios de fabricante en el build.
 */
class M800BleProvisioner(context: Context) {

    init {
        // Único paso de setup que pide el módulo — ver m800-kmp-sdk/README.md.
        if (!M800SdkAndroid.isInitialized()) M800SdkAndroid.init(context)
    }

    sealed class Step {
        object Scanning : Step()
        object Connecting : Step()
        object SendingServerAddress : Step()
        object SendingWifiConfig : Step()
        data class Success(val deviceId: String) : Step()
        data class Failed(val reason: String) : Step()
    }

    /**
     * Un equipo visto en el escaneo.
     * - [deviceName]: nombre corto anunciado (AD 0x09), p.ej. "M80040184".
     * - [serial]: SERIAL real (AD 0xFF, datos de fabricante), p.ej.
     *   "M800424600184". Es el que identifica la unidad y el que se manda a
     *   vincular; el nombre corto lo comparten varias unidades.
     */
    data class Found(
        val deviceName: String,
        val serial: String?,
        val macAddr: String,
        val rssi: Int,
    )

    // Un solo radio BLE por proceso — `BleTransportProvider.create()` entrega
    // siempre la misma instancia compartida (ver AndroidBleTransport.fromAppContext),
    // igual que el `BleHelper` singleton original.
    private val bleTransport: BleTransport = BleTransportProvider.create()
    private val reassembler = M800Protocol.FrameReassembler()
    private var pendingAck: CompletableDeferred<M800Protocol.ParsedFrame>? = null
    private var pendingSeq: Byte = 0

    private var onStep: ((Step) -> Unit)? = null
    private var foundDeviceId: String? = null

    // ---- Modo diagnóstico: conectar y volcar EXACTAMENTE lo que responde el
    // equipo a GET_DEVICE_INFO, sin interpretar nada. ----
    private var diagMode = false
    private var onDiag: ((String) -> Unit)? = null

    private fun diag(line: String) {
        android.util.Log.i("M800Prov", line)
        onDiag?.invoke(line)
    }

    private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }
    private fun ByteArray.ascii() = map { b ->
        val c = b.toInt() and 0xFF
        if (c in 32..126) c.toChar() else '.'
    }.joinToString("")

    /**
     * Escaneo puro, sin aprovisionar: acumula los M800 vistos durante
     * `timeoutMs` deduplicando por deviceName y entrega la lista al terminar.
     */
    fun scanForDevices(timeoutMs: Long = 10_000, onDone: (List<Found>) -> Unit) {
        val seen = LinkedHashMap<String, Found>()
        if (!bleTransport.isBluetoothOpen()) { onDone(emptyList()); return }

        bleTransport.setListener(object : BleTransportListener {
            override fun onScanResult(result: BleScanResult) {
                val name = (M800Protocol.parseLocalName(result.rawRecord) ?: result.name)?.trim()
                // DIAGNÓSTICO temporal: todo candidato de nombre, no solo los que matchean.
                android.util.Log.i("M800Scan", "candidate name=$name mac=${result.address} adv=${result.rawRecord.joinToString(""){"%02x".format(it)}}")
                if (name == null) return
                if (!M800Protocol.DEVICE_NAME_REGEX.matches(name)) return
                // El serial completo viaja en los datos de fabricante (AD 0xFF).
                val serial = M800Protocol.parseSerial(result.rawRecord)
                if (!seen.containsKey(name)) {
                    android.util.Log.i("M800Scan", "encontrado $name serial=$serial mac=${result.address} rssi=${result.rssi}")
                    seen[name] = Found(name, serial, result.address, result.rssi)
                } else if (seen[name]?.serial == null && serial != null) {
                    // Algunos anuncios llegan sin el campo de fabricante; se
                    // completa en cuanto aparece uno que sí lo trae.
                    seen[name] = seen[name]!!.copy(serial = serial)
                }
            }
        })
        bleTransport.scan(timeoutMs.toInt())
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            bleTransport.stopScan()
            onDone(seen.values.toList())
        }, timeoutMs)
    }

    /**
     * Config WiFi+servidor por BLE REAL, usando la clase oficial del fabricante
     * `WiFiConfigHelper.bleWiFiConfig` — hoy portada en `m800-kmp-sdk`, mismo
     * protocolo, sin reimplementar el framing de nuestro lado.
     */
    fun provisionWifi(
        ssid: String, password: String, serverIp: String, serverPort: Int,
        onLine: (String) -> Unit = {},
        onDone: (Boolean) -> Unit = {},
    ) {
        onLine("— aprovisionando WiFi+servidor por BLE (oficial) —")
        onLine("escaneando…")
        scanForDevices(timeoutMs = 10_000) { found ->
            val target = found.firstOrNull()
            if (target == null) {
                onLine("no se encontró ningún M800 cerca")
                onDone(false)
                return@scanForDevices
            }
            onLine("encontrado ${target.deviceName} (${target.macAddr}); mandando config…")
            val helper = WiFiConfigHelper.getInstance(bleTransport)
            helper.bleWiFiConfig(
                M800Protocol.DEVICE_TYPE_M800,
                target.macAddr,
                serverIp,
                serverPort,
                ssid.toByteArray(Charsets.UTF_8),
                password,
                IResultCallback { cd ->
                    onLine("resultado: status=${cd.status} success=${cd.isSuccess}")
                    onLine("result obj: ${cd.result}")
                    onDone(cd.isSuccess)
                },
            )
        }
    }

    /** Escanea, conecta y pide GET_DEVICE_INFO, reportando cada paso en crudo. */
    fun probeDeviceInfo(onLine: (String) -> Unit) {
        diagMode = true
        onDiag = onLine
        foundDeviceId = null
        if (!bleTransport.isBluetoothOpen()) { diag("Bluetooth apagado"); return }
        bleTransport.initUuids(
            M800Protocol.NOTIFY_SERVICE_UUID, M800Protocol.NOTIFY_CHAR_UUID,
            M800Protocol.WRITE_SERVICE_UUID, M800Protocol.WRITE_CHAR_UUID
        )
        bleTransport.setListener(callback)
        diag("escaneando…")
        bleTransport.scan()
    }

    /**
     * EXPERIMENTAL: escanea, conecta y manda "iniciar datos en vivo" por BLE
     * (msgType=7, ver M800Protocol.MSG_START_REALTIME) en vez de pedir
     * identidad. Reporta TODO lo que llegue en crudo (igual que
     * [probeDeviceInfo]) — si el equipo empieza a empujar notificaciones
     * periodicas tras el ack inicial, se veran aqui una a una, lo que
     * confirmaria que el firmware SI acepta streaming por BLE pese a que la
     * app oficial solo lo use por TCP. No se desconecta solo: queda
     * escuchando hasta que se llame [stop].
     */
    fun probeRealTimeData(onLine: (String) -> Unit) {
        diagMode = true
        onDiag = onLine
        foundDeviceId = null
        realtimeProbeMode = true
        if (!bleTransport.isBluetoothOpen()) { diag("Bluetooth apagado"); return }
        bleTransport.initUuids(
            M800Protocol.NOTIFY_SERVICE_UUID, M800Protocol.NOTIFY_CHAR_UUID,
            M800Protocol.WRITE_SERVICE_UUID, M800Protocol.WRITE_CHAR_UUID
        )
        bleTransport.setListener(callback)
        diag("escaneando…")
        bleTransport.scan()
    }

    private var realtimeProbeMode = false

    // ---- Identificación real (no diagnóstico): conecta, pide getSystemInfo
    // y devuelve el deviceId/serial real del equipo — lo mismo que hace la
    // app oficial antes de vincular (la pantalla real muestra "ID:M800...",
    // el serial completo, nunca el nombre corto ambiguo del advertisement). ----
    private var identifyMode = false
    private var onIdentifyResult: ((M800Protocol.SystemInfo?) -> Unit)? = null

    /** Escanea, conecta y resuelve la identidad real (deviceId) del equipo por BLE. */
    fun identifyDevice(onResult: (M800Protocol.SystemInfo?) -> Unit) {
        identifyMode = true
        onIdentifyResult = onResult
        foundDeviceId = null
        if (!bleTransport.isBluetoothOpen()) { onResult(null); return }
        bleTransport.initUuids(
            M800Protocol.NOTIFY_SERVICE_UUID, M800Protocol.NOTIFY_CHAR_UUID,
            M800Protocol.WRITE_SERVICE_UUID, M800Protocol.WRITE_CHAR_UUID
        )
        bleTransport.setListener(callback)
        bleTransport.scan()
    }

    private fun runIdentify() {
        Thread {
            try {
                val frame = M800Protocol.buildFrame(
                    M800Protocol.MSG_GET_SYSTEM_INFO,
                    M800Protocol.buildIdentityPayload(),
                )
                val ack = sendAndWait(frame)
                val info = ack?.let { M800Protocol.parseSystemInfo(it.payload) }
                identifyMode = false
                onIdentifyResult?.invoke(info)
            } catch (e: Exception) {
                identifyMode = false
                onIdentifyResult?.invoke(null)
            }
        }.start()
    }

    private fun runRealTimeDataProbe() {
        Thread {
            try {
                diag("conectado; pidiendo iniciar datos en vivo (msgType=${M800Protocol.MSG_START_REALTIME})")
                val frame = M800Protocol.buildFrame(
                    M800Protocol.MSG_START_REALTIME,
                    M800Protocol.buildIdentityPayload(),
                )
                val ack = sendAndWait(frame)
                if (ack == null) {
                    diag("sin respuesta en 5 s al comando de inicio — probando variante con deviceId del equipo")
                    val frame2 = M800Protocol.buildFrame(
                        M800Protocol.MSG_START_REALTIME,
                        M800Protocol.buildIdentityPayload(deviceId = foundDeviceId ?: ""),
                    )
                    val ack2 = sendAndWait(frame2)
                    if (ack2 == null) { diag("sin respuesta tampoco con deviceId — el equipo no reconoce este comando por BLE"); return@Thread }
                    diag("=== RESPUESTA AL INICIO (variante deviceId) ===")
                    diag("payload HEX  : ${ack2.payload.hex()}")
                    diag("payload ASCII: ${ack2.payload.ascii()}")
                    return@Thread
                }
                diag("=== RESPUESTA AL INICIO ===")
                diag("payload HEX  : ${ack.payload.hex()}")
                diag("payload ASCII: ${ack.payload.ascii()}")
                diag("— esperando notificaciones en vivo (quedan registradas arriba conforme lleguen) —")
            } catch (e: Exception) {
                diag("error: ${e.message}")
            }
        }.start()
    }

    private fun runDeviceInfoProbe() {
        Thread {
            try {
                // getSystemInfo (514) devuelve el deviceId SIN necesitar
                // conocerlo; getDeviceInfo (518) lo exige en la petición.
                diag("conectado; pidiendo getSystemInfo (msgType=${M800Protocol.MSG_GET_SYSTEM_INFO})")
                val frame = M800Protocol.buildFrame(
                    M800Protocol.MSG_GET_SYSTEM_INFO,
                    M800Protocol.buildIdentityPayload(),
                )
                val ack = sendAndWait(frame)
                if (ack == null) {
                    diag("sin respuesta en 5 s")
                    return@Thread
                }
                diag("=== RESPUESTA DEL EQUIPO ===")
                diag("payload HEX  : ${ack.payload.hex()}")
                diag("payload ASCII: ${ack.payload.ascii()}")
                val info = M800Protocol.parseSystemInfo(ack.payload)
                if (info == null) {
                    diag("payload demasiado corto para parsear")
                } else {
                    diag("status      : ${info.status}")
                    diag("deviceId    : ${info.deviceId}")
                    diag("deviceType  : ${info.deviceType}")
                    diag("currVersion : ${info.currVersion}")
                    diag("facVersion  : ${info.facVersion}")
                    diag("facTime     : ${info.facTime}")
                    diag("manufacCode : ${info.manufacCode}")
                }
            } catch (e: Exception) {
                diag("error: ${e.message}")
            }
        }.start()
    }

    // Un único listener cubre escaneo+conexión+datos (antes eran dos objetos
    // separados — IBleScanCallback e IDeviceCallback — porque BleHelper
    // también los separaba; BleTransport los unifica).
    private val callback = object : BleTransportListener {
        override fun onDataReceived(data: ByteArray) {
            diag("RX ${data.size}B  ${data.hex()}")
            val frame = reassembler.feed(data) ?: run {
                diag("   (fragmento, esperando el resto del frame)")
                return
            }
            diag("FRAME COMPLETO ${frame.size}B  ${frame.hex()}")
            val parsed = M800Protocol.parseFrame(frame) ?: run {
                diag("   !! no se pudo parsear el frame")
                return
            }
            diag("   msgSeq=${parsed.msgSeq}  msgType=${parsed.msgType}  crcOk=${parsed.crcOk}")
            diag("   payload (${parsed.payload.size}B): ${parsed.payload.hex()}")
            diag("   payload ASCII: ${parsed.payload.ascii()}")
            if (parsed.msgSeq == pendingSeq) pendingAck?.complete(parsed)
        }

        override fun onConnectionStateChanged(state: BleConnState) {
            diag("estado BLE: $state")
            when (state) {
                BleConnState.CONNECTED -> when {
                    realtimeProbeMode -> runRealTimeDataProbe()
                    identifyMode -> runIdentify()
                    diagMode -> runDeviceInfoProbe()
                    else -> runProvisioning()
                }
                BleConnState.DISCONNECTED -> {
                    if (diagMode) diag("DESCONECTADO por el equipo")
                    if (identifyMode) { identifyMode = false; onIdentifyResult?.invoke(null) }
                    if (onStep != null) onStep?.invoke(Step.Failed("Device disconnected"))
                }
                BleConnState.CONNECTING -> {}
            }
        }

        override fun onScanResult(result: BleScanResult) {
            // `result.name` llega null en la gran mayoría de resultados de
            // escaneo (solo trae valor si el sistema ya cacheó el nombre), así
            // que el nombre real se saca del advertisement.
            val name = (M800Protocol.parseLocalName(result.rawRecord) ?: result.name)?.trim()
            // Log de diagnóstico: deja ver en logcat TODO lo que anuncia cerca
            // (nombre, MAC y RSSI) para distinguir "no hay sensor" de "el
            // sensor anuncia con un nombre que el filtro descarta".
            android.util.Log.i("M800Scan", "seen name=${name ?: "<null>"} mac=${result.address} rssi=${result.rssi}")
            if (name == null) return
            if (!M800Protocol.DEVICE_NAME_REGEX.matches(name)) return
            android.util.Log.i("M800Scan", "MATCH -> $name mac=${result.address} rssi=${result.rssi}")
            // Volcado completo del advertisement: la MAC WiFi del sensor suele
            // venir en los datos de fabricante (AD type 0xFF) y no coincide con
            // la MAC BLE. Sirve para dar de alta el equipo en redes con filtro MAC.
            android.util.Log.i(
                "M800Scan",
                "ADV hex=${result.rawRecord.joinToString("") { "%02x".format(it) }}"
            )
            foundDeviceId = name
            bleTransport.stopScan()
            onStep?.invoke(Step.Connecting)
            bleTransport.connect(result.address)
        }

        override fun onScanStopped() {
            if (foundDeviceId == null) onStep?.invoke(Step.Failed("No M800 device found nearby"))
        }
    }

    /** Punto de entrada: escanea, conecta y aprovisiona IP:puerto del servidor + WiFi real. */
    fun start(ssidRaw: ByteArray, password: String, serverIp: String, serverPort: Int, onStep: (Step) -> Unit) {
        this.onStep = onStep

        if (!bleTransport.isBluetoothOpen()) {
            onStep(Step.Failed("Bluetooth is off"))
            return
        }
        bleTransport.initUuids(
            M800Protocol.NOTIFY_SERVICE_UUID, M800Protocol.NOTIFY_CHAR_UUID,
            M800Protocol.WRITE_SERVICE_UUID, M800Protocol.WRITE_CHAR_UUID
        )
        bleTransport.setListener(callback)
        onStep(Step.Scanning)
        bleTransport.scan()
    }

    fun stop() {
        bleTransport.stopScan()
        bleTransport.setListener(null)
        bleTransport.disconnect()
    }

    /**
     * Antes aquí vivía un flujo de "aprovisionar WiFi + dirección de servidor"
     * que yo había inventado: el M800 real NO configura WiFi por BLE en el
     * flujo de alta (eso es de otro equipo, el EW202W, vía /confignetwork).
     * El alta real es: escanear -> leer identidad del equipo -> vincular.
     */
    private fun runProvisioning() {
        onStep?.invoke(Step.Failed("Flujo no aplicable al M800"))
    }

    private fun sendAndWait(built: M800Protocol.BuiltFrame): M800Protocol.ParsedFrame? {
        pendingSeq = built.msgSeq
        val deferred = CompletableDeferred<M800Protocol.ParsedFrame>()
        pendingAck = deferred
        val chunks = M800Protocol.chunk(built.bytes)
        diag("TX seq=${built.msgSeq} ${built.bytes.size}B en ${chunks.size} chunk(s)")
        diag("TX HEX: ${built.bytes.hex()}")
        chunks.forEachIndexed { i, c ->
            val r = runCatching { bleTransport.send(c) }
            diag("   chunk $i (${c.size}B) -> ${r.exceptionOrNull()?.message ?: "enviado"}")
        }
        val ack = kotlinx.coroutines.runBlocking {
            withTimeoutOrNull(5000) { deferred.await() }
        }
        return ack
    }
}
