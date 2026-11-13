package com.chaide.app.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.sleepace.sdk.interfs.IBleScanCallback
import com.sleepace.sdk.interfs.IDeviceCallback
import com.sleepace.sdk.interfs.IDeviceManager
import com.sleepace.sdk.interfs.IResultCallback
import com.sleepace.sdk.manager.CONNECTION_STATE
import com.sleepace.sdk.manager.CallbackData
import com.sleepace.sdk.manager.DeviceManager
import com.sleepace.sdk.manager.DeviceType
import com.sleepace.sdk.manager.ble.BleHelper
import com.sleepace.sdk.wificonfig.WiFiConfigHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Réplica fiel (no aproximación) del flujo real de aprovisionamiento WiFi por
 * BLE del M800, portado del SDK oficial decompilado (WiFiConfigHelper del
 * fabricante: C:\Documentos_C\IT_Projects\AplicacionColchones\Android_wifi_config).
 * Usa BleHelper real (sdkcore.jar, mismo jar que trae la app de producción)
 * para todo el manejo GATT de bajo nivel — solo reimplementamos el framing de
 * mensajes (M800Protocol) y la máquina de estados de aprovisionamiento, que en
 * el SDK real vive dentro de WiFiConfigHelper.
 */
class M800BleProvisioner(context: Context) : DeviceManager() {

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

    /**
     * Escaneo puro, sin aprovisionar: acumula los M800 vistos durante
     * `timeoutMs` deduplicando por deviceName y entrega la lista al terminar.
     * Réplica de `_startScan()` + `doNext()` del componente real (10 s).
     */
    fun scanForDevices(timeoutMs: Long = 10_000, onDone: (List<Found>) -> Unit) {
        val seen = LinkedHashMap<String, Found>()
        if (!bleHelper.isBluetoothOpen()) { onDone(emptyList()); return }

        val cb = object : IBleScanCallback {
            override fun onStartScan() {}
            override fun onLeScan(device: BluetoothDevice, rssi: Int, scanRecord: ByteArray) {
                val name = (M800Protocol.parseLocalName(scanRecord) ?: device.name)?.trim() ?: return
                if (!M800Protocol.DEVICE_NAME_REGEX.matches(name)) return
                // El serial completo viaja en los datos de fabricante (AD 0xFF).
                val serial = M800Protocol.parseSerial(scanRecord)
                if (!seen.containsKey(name)) {
                    android.util.Log.i("M800Scan", "encontrado $name serial=$serial mac=${device.address} rssi=$rssi")
                    seen[name] = Found(name, serial, device.address, rssi)
                } else if (seen[name]?.serial == null && serial != null) {
                    // Algunos anuncios llegan sin el campo de fabricante; se
                    // completa en cuanto aparece uno que sí lo trae.
                    seen[name] = seen[name]!!.copy(serial = serial)
                }
            }
            override fun onStopScan() {}
        }
        bleHelper.scanBleDevice(cb)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            bleHelper.stopScan(false)
            onDone(seen.values.toList())
        }, timeoutMs)
    }

    /**
     * Config WiFi+servidor por BLE REAL, usando la clase oficial del fabricante
     * `WiFiConfigHelper.bleWiFiConfig` (wificonfigsdk.jar, confirmada en el
     * historial git de `AplicacionColchones/Android_wifi_config` — commit
     * 8ca91a9 "蓝牙配WiFi支持M800设备" = "BLE WiFi config soporta M800" — y en
     * el commit 2367354 que amplía esa misma función a más equipos incl. M800
     * explícitamente). La luz roja fija (vs. verde = emparejado+en WiFi) que
     * reportó el usuario es consistente con que el equipo NUNCA recibió estas
     * credenciales: nuestro flujo anterior solo hacía identificación
     * (GET_SYSTEM_INFO), nunca aprovisionaba WiFi. No reimplementamos el
     * framing — se llama directo a la clase real, cero riesgo de errores de
     * protocolo de nuestro lado.
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
            val helper = WiFiConfigHelper.getInstance(appContext)
            helper.bleWiFiConfig(
                M800Protocol.DEVICE_TYPE_M800,
                target.macAddr,
                serverIp,
                serverPort,
                ssid.toByteArray(Charsets.UTF_8),
                password,
                object : IResultCallback<Any> {
                    override fun onResultCallback(cd: CallbackData<Any>) {
                        onLine("resultado: status=${cd.status} success=${cd.isSuccess}")
                        onLine("result obj: ${cd.result}")
                        onDone(cd.isSuccess)
                    }
                },
            )
        }
    }

    private val appContext = context.applicationContext
    private val bleHelper: BleHelper = BleHelper.getInstance(appContext)
    private val reassembler = M800Protocol.FrameReassembler()
    private var pendingAck: CompletableDeferred<M800Protocol.ParsedFrame>? = null
    private var pendingSeq: Byte = 0

    private var onStep: ((Step) -> Unit)? = null
    private var targetSsidRaw: ByteArray = ByteArray(0)
    private var targetPassword: String = ""
    private var serverIp: String = ""
    private var serverPort: Int = 0
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

    /** Escanea, conecta y pide GET_DEVICE_INFO, reportando cada paso en crudo. */
    fun probeDeviceInfo(onLine: (String) -> Unit) {
        diagMode = true
        onDiag = onLine
        foundDeviceId = null
        if (!bleHelper.isBluetoothOpen()) { diag("Bluetooth apagado"); return }
        // OJO con el orden: el SDK define
        //   initUUID(notifyService, notifyChar, writeService, writeChar)
        // (verificado en el bytecode de BleHelper). Antes se llamaba al revés,
        // y como los UUIDs también estaban invertidos los dos errores se
        // cancelaban; al corregir uno había que corregir el otro.
        bleHelper.initUUID(
            M800Protocol.NOTIFY_SERVICE_UUID, M800Protocol.NOTIFY_CHAR_UUID,
            M800Protocol.WRITE_SERVICE_UUID, M800Protocol.WRITE_CHAR_UUID
        )
        bleHelper.registListener(callback)
        diag("escaneando…")
        bleHelper.scanBleDevice(scanCallback)
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
        if (!bleHelper.isBluetoothOpen()) { diag("Bluetooth apagado"); return }
        bleHelper.initUUID(
            M800Protocol.NOTIFY_SERVICE_UUID, M800Protocol.NOTIFY_CHAR_UUID,
            M800Protocol.WRITE_SERVICE_UUID, M800Protocol.WRITE_CHAR_UUID
        )
        bleHelper.registListener(callback)
        diag("escaneando…")
        bleHelper.scanBleDevice(scanCallback)
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
        if (!bleHelper.isBluetoothOpen()) { onResult(null); return }
        bleHelper.initUUID(
            M800Protocol.NOTIFY_SERVICE_UUID, M800Protocol.NOTIFY_CHAR_UUID,
            M800Protocol.WRITE_SERVICE_UUID, M800Protocol.WRITE_CHAR_UUID
        )
        bleHelper.registListener(callback)
        bleHelper.scanBleDevice(scanCallback)
    }

    private fun runIdentify() {
        com.sleepace.sdk.manager.DeviceManager.sTheadExecutor.execute {
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
        }
    }

    private fun runRealTimeDataProbe() {
        com.sleepace.sdk.manager.DeviceManager.sTheadExecutor.execute {
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
                    if (ack2 == null) { diag("sin respuesta tampoco con deviceId — el equipo no reconoce este comando por BLE"); return@execute }
                    diag("=== RESPUESTA AL INICIO (variante deviceId) ===")
                    diag("payload HEX  : ${ack2.payload.hex()}")
                    diag("payload ASCII: ${ack2.payload.ascii()}")
                    return@execute
                }
                diag("=== RESPUESTA AL INICIO ===")
                diag("payload HEX  : ${ack.payload.hex()}")
                diag("payload ASCII: ${ack.payload.ascii()}")
                diag("— esperando notificaciones en vivo (quedan registradas arriba conforme lleguen) —")
            } catch (e: Exception) {
                diag("error: ${e.message}")
            }
        }
    }

    private fun runDeviceInfoProbe() {
        com.sleepace.sdk.manager.DeviceManager.sTheadExecutor.execute {
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
                    return@execute
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
        }
    }

    private val callback = object : IDeviceCallback {
        override fun getMaster(): IDeviceManager = this@M800BleProvisioner
        override fun handleData(data: ByteArray) {
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
        override fun onStateChanged(manager: IDeviceManager, state: CONNECTION_STATE) {
            diag("estado BLE: $state")
            when (state) {
                CONNECTION_STATE.CONNECTED -> when {
                    realtimeProbeMode -> runRealTimeDataProbe()
                    identifyMode -> runIdentify()
                    diagMode -> runDeviceInfoProbe()
                    else -> runProvisioning()
                }
                CONNECTION_STATE.DISCONNECT -> {
                    if (diagMode) diag("DESCONECTADO por el equipo")
                    if (identifyMode) { identifyMode = false; onIdentifyResult?.invoke(null) }
                    if (onStep != null) onStep?.invoke(Step.Failed("Device disconnected"))
                }
                else -> {}
            }
        }
    }

    private val scanCallback = object : IBleScanCallback {
        override fun onStartScan() {}
        override fun onLeScan(device: BluetoothDevice, rssi: Int, scanRecord: ByteArray) {
            // `device.name` llega null en la gran mayoría de resultados de
            // escaneo (solo trae valor si el sistema ya cacheó el nombre), así
            // que el nombre real se saca del advertisement.
            val name = (M800Protocol.parseLocalName(scanRecord) ?: device.name)?.trim()
            // Log de diagnóstico: deja ver en logcat TODO lo que anuncia cerca
            // (nombre, MAC y RSSI) para distinguir "no hay sensor" de "el
            // sensor anuncia con un nombre que el filtro descarta".
            android.util.Log.i("M800Scan", "seen name=${name ?: "<null>"} mac=${device.address} rssi=$rssi")
            if (name == null) return
            if (!M800Protocol.DEVICE_NAME_REGEX.matches(name)) return
            android.util.Log.i("M800Scan", "MATCH -> $name mac=${device.address} rssi=$rssi")
            // Volcado completo del advertisement: la MAC WiFi del sensor suele
            // venir en los datos de fabricante (AD type 0xFF) y no coincide con
            // la MAC BLE. Sirve para dar de alta el equipo en redes con filtro MAC.
            android.util.Log.i(
                "M800Scan",
                "ADV hex=${scanRecord.joinToString("") { "%02x".format(it) }}"
            )
            foundDeviceId = name
            bleHelper.stopScan(false)
            setDeviceType(DeviceType.DEVICE_TYPE_M800)
            onStep?.invoke(Step.Connecting)
            bleHelper.connectDevice(device.address, this@M800BleProvisioner)
        }
        override fun onStopScan() {
            if (foundDeviceId == null) onStep?.invoke(Step.Failed("No M800 device found nearby"))
        }
    }

    /** Punto de entrada: escanea, conecta y aprovisiona IP:puerto del servidor + WiFi real. */
    fun start(ssidRaw: ByteArray, password: String, serverIp: String, serverPort: Int, onStep: (Step) -> Unit) {
        this.targetSsidRaw = ssidRaw
        this.targetPassword = password
        this.serverIp = serverIp
        this.serverPort = serverPort
        this.onStep = onStep

        if (!bleHelper.isBluetoothOpen()) {
            onStep(Step.Failed("Bluetooth is off"))
            return
        }
        // OJO con el orden: el SDK define
        //   initUUID(notifyService, notifyChar, writeService, writeChar)
        // (verificado en el bytecode de BleHelper). Antes se llamaba al revés,
        // y como los UUIDs también estaban invertidos los dos errores se
        // cancelaban; al corregir uno había que corregir el otro.
        bleHelper.initUUID(
            M800Protocol.NOTIFY_SERVICE_UUID, M800Protocol.NOTIFY_CHAR_UUID,
            M800Protocol.WRITE_SERVICE_UUID, M800Protocol.WRITE_CHAR_UUID
        )
        bleHelper.registListener(callback)
        onStep(Step.Scanning)
        bleHelper.scanBleDevice(scanCallback)
    }

    fun stop() {
        bleHelper.stopScan(false)
        bleHelper.unRegistListener(callback)
        bleHelper.disconnect(this, true)
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
            val r = runCatching { bleHelper.sendData(c, this) }
            diag("   chunk $i (${c.size}B) -> ${r.exceptionOrNull()?.message ?: "enviado"}")
        }
        val ack = kotlinx.coroutines.runBlocking {
            withTimeoutOrNull(5000) { deferred.await() }
        }
        return ack
    }

    // ---- DeviceManager / IDeviceManager: solo lo que esta clase realmente usa ----
    override fun getHeartbeatData(): ByteArray = ByteArray(0)
    override fun connectDevice(address: String, timeout: Int) { bleHelper.connectDevice(address, timeout, this) }
    override fun connectDevice(address: String, deviceType: DeviceType, timeout: Int) {
        setDeviceType(deviceType); connectDevice(address, timeout)
    }
    override fun deviceOper(frameType: Byte, msgType: Byte, data: ByteArray, timeout: Int): CallbackData<Any> = CallbackData()
    override fun deviceOper(frameType: Byte, msgType: Byte, data: ByteArray, timeout: Int, callback: IResultCallback<Any>) {}
    override fun getDeviceVersion(timeout: Int) {}
    override fun getDeviceInfo(timeout: Int) {}
    override fun disconnect() { bleHelper.disconnect(this) }
    override fun release() { bleHelper.unRegistListener(callback) }
}
