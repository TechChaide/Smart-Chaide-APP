package com.chaide.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaide.app.resources.Res
import com.chaide.app.resources.device_m800
import com.chaide.app.ble.M800BleProvisioner
import com.chaide.app.net.Api
import com.chaide.app.net.ApiException
import com.chaide.app.net.BindDeviceRequest
import com.chaide.app.platform.WifiNetwork
import com.chaide.app.platform.scanWifiNetworks
import com.chaide.app.platform.platformName
import com.chaide.app.platform.rememberBlePermission
import com.chaide.app.platform.rememberWifiSsidPermission
import com.chaide.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Permisos BLE / SSID: ver platform/Platform.kt (Android pide BLUETOOTH_SCAN/
// CONNECT o ubicacion segun version; iOS lo resuelve CoreBluetooth al primer uso).

/**
 * Réplica del componente Vue real `addDevice` (ruta /adddevice, módulo "O4Ex").
 *
 * Flujo real, leído del código del bundle — NO inventado:
 *   1. Pantalla con la foto del equipo, el título `install_device`
 *      ("Install {value}"), la instrucción `greenlight_slow_press_button` y el
 *      enlace `red_light_flash` ("Red Light Flashing?").
 *   2. Botón `next` ("Continue") -> `_startScan()`: escanea BLE durante
 *      10 segundos (`setTimeout(doNext, 1e4)`) mostrando `adding_device`
 *      ("Adding Device…"), filtrando por nombre sacado del ADVERTISEMENT
 *      (`getDeviceName(s.advertisData)`) y deduplicando por deviceName.
 *   3. `doNext()`: si encontró exactamente uno -> `bindDeviceIfNeeded(device)`
 *      directo; si encontró varios -> pantalla /searchDevice para elegir.
 *   4. `bindDeviceIfNeeded` hace login BLE y luego manda al servidor
 *      { deviceType, deviceName, deviceId, macAddr, deviceVersion }.
 *   5. CORREGIDO tras ver la app real instalada (Chaide_release 1.0.7) en el
 *      equipo fisico: SI pide WiFi — tras vincular, muestra "Ajustes de
 *      Wi-Fi" con el SSID autorellenado (misma red a la que ya esta
 *      conectado el telefono) + password, nota "solo 2.4 GHz", y "Continuar".
 *      La pantalla real NO pide IP/puerto de servidor (va hardcodeado o lo
 *      resuelve el backend); aqui se mantienen como ajuste avanzado oculto
 *      porque nuestro backend de pruebas si lo necesita.
 *
 * El serial real (p.ej. "M800424600184") viaja normalmente en los datos de
 * fabricante del advertisement (AD 0xFF). Cuando no viene ahi (confirmado:
 * intermitente), la app real lo resuelve conectandose por BLE y preguntandole
 * al equipo directamente (getSystemInfo) — eso es lo que hace [identifyDevice]
 * aqui abajo, en vez de pedirle el serial al usuario de una.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceScreen(onBack: () -> Unit, onBound: () -> Unit = {}) {
    val scope = rememberCoroutineScope()

    val blePermission = rememberBlePermission()
    val hasPermissions = blePermission.granted

    // Leer el SSID actual exige ubicacion en Android, AUNQUE el escaneo BLE ya
    // no la necesite — es un segundo permiso para un proposito distinto.
    val ssidPermission = rememberWifiSsidPermission()
    val hasLocationPerm = ssidPermission.granted

    // Wizard: INTRO -> PICK_DEVICE (cards BLE) -> PICK_NETWORK (cards WiFi) -> PASSWORD.
    var step by remember { mutableStateOf(WizardStep.INTRO) }
    var scanning by remember { mutableStateOf(false) }
    var nearby by remember { mutableStateOf<List<M800BleProvisioner.Nearby>>(emptyList()) }
    var hiddenUnnamed by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<M800BleProvisioner.Nearby?>(null) }
    var identifying by remember { mutableStateOf(false) }
    var binding by remember { mutableStateOf(false) }
    var needsManualSerial by remember { mutableStateOf(false) }
    var boundName by remember { mutableStateOf<String?>(null) }
    var provisioning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var scanner by remember { mutableStateOf<M800BleProvisioner?>(null) }
    var diagLines by remember { mutableStateOf<List<String>>(emptyList()) }
    // Herramientas que NO existen en la pantalla real (sonda cruda, override
    // manual de IP/puerto del servidor): ocultas por defecto.
    var showDebugTools by remember { mutableStateOf(false) }
    // Red elegida por el usuario para el M800 + su contrasena.
    var networks by remember { mutableStateOf<List<WifiNetwork>?>(null) }
    var networksSupported by remember { mutableStateOf(true) }
    var loadingNetworks by remember { mutableStateOf(false) }
    var wifiSsid by remember { mutableStateOf("") }
    var wifiPass by remember { mutableStateOf("") }
    // Persistido (GatewaySettings), no hardcodeado: Home/LiveMonitorScreen leen
    // este mismo valor para /realtime. La app real no lo pide (va fijo a su
    // backend); aqui queda como ajuste avanzado para el gateway de pruebas.
    var serverIp by remember { mutableStateOf(com.chaide.app.net.GatewaySettings.ip) }
    var serverPort by remember { mutableStateOf("29010") }

    DisposableEffect(Unit) { onDispose { scanner?.stopNearbyScan(); scanner?.stop() } }

    // Chequeo previo: si la cuenta ya tiene un M800, ni se pide permiso de
    // Bluetooth ni se escanea — se avisa de una vez (el backend real igual lo
    // rechazaría con ACCOUNT_HAD_BOUND, pero preguntarle primero a /api/device
    // evita hacerle perder 45s de escaneo al usuario para terminar en el mismo
    // error). `null` = aún verificando, `""` = no tiene ninguno (puede seguir).
    var existingDeviceName by remember { mutableStateOf<String?>(null) }
    var checkedExisting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val devices = withContext(Dispatchers.IO) { Api.service.devices() }.data ?: emptyList()
            existingDeviceName = devices.firstOrNull { it.deviceType == 38 }?.deviceName
        } catch (_: Exception) {
            // Sin conexión para chequear: se deja seguir, el bind real igual valida.
        } finally {
            checkedExisting = true
        }
    }

    fun parseBindError(e: Exception): String = when (e) {
        is ApiException -> e.errorMessage ?: "Bind failed"
        else -> e.message ?: "Bind failed"
    }

    fun loadNetworks() {
        scope.launch {
            loadingNetworks = true
            val list = scanWifiNetworks()
            networksSupported = list != null
            networks = list
            loadingNetworks = false
        }
    }

    // Al conceder la ubicacion estando en el paso de redes, se buscan de una vez.
    LaunchedEffect(step, hasLocationPerm) {
        if (step == WizardStep.PICK_NETWORK && hasLocationPerm && networks == null && !loadingNetworks) loadNetworks()
    }

    fun bind(serial: String) {
        failure = null; binding = true
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    Api.service.bindDevice(BindDeviceRequest(deviceName = serial.trim()))
                }
                boundName = r.data?.deviceName ?: serial
                needsManualSerial = false
                networks = null
                step = WizardStep.PICK_NETWORK
            } catch (e: Exception) {
                failure = parseBindError(e)
            } finally {
                binding = false
            }
        }
    }

    fun startScan() {
        failure = null; boundName = null; selected = null; needsManualSerial = false
        nearby = emptyList(); hiddenUnnamed = 0
        step = WizardStep.PICK_DEVICE; scanning = true
        scanner?.stopNearbyScan()
        val p = M800BleProvisioner()
        scanner = p
        // 45 s: el M800 anuncia en RAFAGAS intermitentes (verificado contra
        // hardware real), no de forma continua. La lista se llena en vivo.
        p.scanNearby(
            timeoutMs = 45_000,
            onUpdate = { list, hidden -> nearby = list; hiddenUnnamed = hidden },
            onDone = { list, hidden -> nearby = list; hiddenUnnamed = hidden; scanning = false },
        )
    }

    fun pick(device: M800BleProvisioner.Nearby) {
        val p = scanner ?: return
        p.stopNearbyScan(); scanning = false
        selected = device; failure = null
        p.select(device)
        // El serial real viaja en los datos de fabricante del anuncio (AD 0xFF).
        // Si no vino, se le pregunta al equipo por BLE (getSystemInfo), como la
        // app real; el nombre corto NUNCA se usa para vincular (lo comparten
        // varias unidades).
        val serial = device.serial
        if (serial != null) { bind(serial); return }
        identifying = true
        p.identifyDevice { info ->
            identifying = false
            val resolved = info?.deviceId?.takeIf { it.isNotBlank() }
            if (resolved != null) bind(resolved) else needsManualSerial = true
        }
    }

    fun provision() {
        failure = null; provisioning = true
        val p = scanner ?: M800BleProvisioner().also { scanner = it }
        p.provisionWifi(
            ssid = wifiSsid, password = wifiPass,
            serverIp = serverIp, serverPort = serverPort.toIntOrNull() ?: 29010,
            onDone = { success ->
                provisioning = false
                if (success) {
                    result = boundName
                    onBound()
                } else {
                    failure = "No se pudo configurar el WiFi del colchón. Revisa la contraseña y que siga en modo emparejamiento, e intenta de nuevo."
                }
            },
        )
    }

    Scaffold(
        containerColor = Night,
        topBar = {
            TopAppBar(
                title = { Text("Add Device") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextHi)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Night, titleContentColor = TextHi)
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                result != null -> BoundOk(result!!, onBack)
                !checkedExisting -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = TextHi)
                }
                existingDeviceName != null -> AlreadyHasDevice(existingDeviceName!!, onBack)
                needsManualSerial && selected != null -> ConfirmSerial(
                    bleName = selected!!.name ?: selected!!.address,
                    failure = failure,
                    onConfirm = { bind(it) },
                    onCancel = { needsManualSerial = false; failure = null; startScan() },
                )
                step == WizardStep.PICK_DEVICE -> DevicePickerStep(
                    devices = nearby, hiddenUnnamed = hiddenUnnamed, scanning = scanning,
                    busyAddress = if (identifying || binding) selected?.address else null,
                    failure = failure,
                    onPick = { pick(it) },
                    onRescan = { startScan() },
                )
                step == WizardStep.PICK_NETWORK -> NetworkPickerStep(
                    deviceName = boundName ?: "",
                    networks = networks, supported = networksSupported, loading = loadingNetworks,
                    hasLocationPerm = hasLocationPerm,
                    onRequestLocation = ssidPermission.request,
                    onRefresh = { loadNetworks() },
                    onPick = { ssid -> wifiSsid = ssid; wifiPass = ""; failure = null; step = WizardStep.PASSWORD },
                )
                step == WizardStep.PASSWORD -> PasswordStep(
                    ssid = wifiSsid, onSsidChange = { wifiSsid = it },
                    password = wifiPass, onPasswordChange = { wifiPass = it },
                    provisioning = provisioning, failure = failure,
                    onChangeNetwork = { if (!provisioning) step = WizardStep.PICK_NETWORK },
                    onContinue = { provision() },
                )
                else -> {
                    Spacer(Modifier.height(16.dp))
                    // `install_device` = "Install {value}"
                    Text(
                        "Install Smart Mattress",
                        color = TextHi, style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(28.dp))
                    Image(
                        painter = painterResource(Res.drawable.device_m800),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(0.75f),
                    )
                    Spacer(Modifier.height(28.dp))
                    // `greenlight_slow_press_button`, texto literal real
                    Text(
                        "After the green light flashes slowly, please press the power button twice in row.",
                        color = Mist, style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    // `red_light_flash`
                    Text(
                        "Red Light Flashing?",
                        color = Mist, style = MaterialTheme.typography.bodyMedium,
                        textDecoration = TextDecoration.Underline,
                    )

                    failure?.let {
                        Spacer(Modifier.height(20.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Error, null, tint = Low, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(it, color = Low, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // Pantalla real de produccion termina aqui (imagen + texto +
                    // "Red Light Flashing?" + Continue). Las herramientas de abajo
                    // son nuestras, para depurar el protocolo BLE — no existen en
                    // produccion, por eso quedan ocultas detras de este toggle en
                    // vez de mostrarse siempre (antes hacian que la pantalla se
                    // viera 100% distinta a la real).
                    TextButton(onClick = { showDebugTools = !showDebugTools }) {
                        Text(
                            if (showDebugTools) "Ocultar herramientas de depuracion" else "Herramientas de depuracion (dev)",
                            color = Faint, style = MaterialTheme.typography.labelSmall,
                        )
                    }

                    if (showDebugTools) {
                        // --- Diagnóstico: volcar en pantalla lo que responde el equipo ---
                        if (diagLines.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Column(
                                Modifier.fillMaxWidth().heightIn(max = 220.dp)
                                    .background(Night2, RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                diagLines.forEach {
                                    Text(it, color = Mist, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        } else {
                            Spacer(Modifier.height(16.dp))
                        }

                        OutlinedButton(
                            onClick = {
                                diagLines = listOf("— iniciando sonda —")
                                val p = M800BleProvisioner()
                                scanner = p
                                p.probeDeviceInfo { line ->
                                    diagLines = diagLines + line
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Ver respuesta del equipo (diagnóstico)", color = Mist) }
                        Spacer(Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = {
                                diagLines = listOf("— probando datos en vivo por BLE (experimental) —")
                                val p = M800BleProvisioner()
                                scanner = p
                                p.probeRealTimeData { line ->
                                    diagLines = diagLines + line
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Probar datos en vivo por BLE (experimental)", color = Mist) }
                        Spacer(Modifier.height(10.dp))

                        // Override del gateway de pruebas (la app real no lo expone:
                        // su backend ya sabe a donde debe hablar el equipo).
                        val fieldColors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = TextHi, unfocusedTextColor = TextHi,
                            focusedIndicatorColor = Mist, unfocusedIndicatorColor = Hairline,
                            cursorColor = TextHi, focusedLabelColor = Mist, unfocusedLabelColor = Mist,
                        )
                        Row {
                            TextField(serverIp, {
                                serverIp = it
                                // Se guarda en cuanto es una IPv4 valida: Home y Live
                                // Monitor leen esta misma IP para /realtime.
                                if (com.chaide.app.net.GatewaySettings.isValidIp(it)) com.chaide.app.net.GatewaySettings.ip = it.trim()
                            }, label = { Text("Server IP (gateway de pruebas)") },
                                singleLine = true, colors = fieldColors, modifier = Modifier.weight(2f))
                            Spacer(Modifier.width(6.dp))
                            TextField(serverPort, { serverPort = it }, label = { Text("Port") },
                                singleLine = true, colors = fieldColors, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    Spacer(Modifier.height(10.dp))

                    if (!hasPermissions) {
                        Button(
                            onClick = blePermission.request,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BtnFill, contentColor = BtnText),
                        ) { Text("Grant Bluetooth permission", style = MaterialTheme.typography.labelLarge) }
                    } else {
                        // `next` = "Continue"
                        Button(
                            onClick = { startScan() },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BtnFill, contentColor = BtnText),
                        ) { Text("Continue", style = MaterialTheme.typography.labelLarge) }
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
}

private enum class WizardStep { INTRO, PICK_DEVICE, PICK_NETWORK, PASSWORD }

/** Encabezado comun de los pasos del wizard: "Paso N de 3" + titulo + ayuda. */
@Composable
private fun WizardHeader(stepNumber: Int, title: String, help: String) {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))
        Text("Paso $stepNumber de 3", color = Mist, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text(title, color = TextHi, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(help, color = Mist, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(18.dp))
    }
}

/** Intensidad de senal legible a partir del RSSI (dBm). */
private fun signalLabel(rssi: Int): String = when {
    rssi >= -60 -> "Señal fuerte"
    rssi >= -75 -> "Señal media"
    else -> "Señal débil"
}

/** Card base del wizard: fondo de tarjeta, borde de marca si se puede elegir. */
@Composable
private fun WizardCard(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = Night2,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) Brand else Hairline),
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.55f),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), content = content)
    }
}

/**
 * Paso 1: equipos Bluetooth cercanos en cards (nombre, MAC, serial). Solo los
 * M800 se pueden elegir; el resto aparece atenuado como "No compatible".
 */
@Composable
private fun DevicePickerStep(
    devices: List<M800BleProvisioner.Nearby>,
    hiddenUnnamed: Int,
    scanning: Boolean,
    busyAddress: String?,
    failure: String?,
    onPick: (M800BleProvisioner.Nearby) -> Unit,
    onRescan: () -> Unit,
) {
    // iOS no expone la MAC: muestra el identificador Bluetooth del sistema.
    val addressLabel = if (platformName == "ios") "ID Bluetooth" else "MAC"
    Column(Modifier.fillMaxWidth()) {
        WizardHeader(
            1, "Selecciona tu colchón",
            "Equipos Bluetooth cerca. Solo puedes elegir un Smart Mattress (M800). Si no aparece, comprueba que la luz verde parpadee lento.",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (scanning) {
                CircularProgressIndicator(color = TextHi, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Buscando… (hasta 45 s)", color = Mist, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("${devices.count { it.isM800 }} colchón(es) encontrados", color = Mist, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRescan, enabled = busyAddress == null) {
                Icon(Icons.Filled.Refresh, null, tint = TextHi, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Buscar de nuevo", color = TextHi)
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            devices.forEach { d ->
                val busy = busyAddress == d.address
                WizardCard(enabled = d.isM800 && busyAddress == null, onClick = { onPick(d) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (d.isM800) Icons.Filled.Bed else Icons.Filled.Bluetooth, null,
                            tint = if (d.isM800) TextHi else Mist, modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name ?: "Sin nombre", color = TextHi, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text("$addressLabel: ${d.address}", color = Mist, style = MaterialTheme.typography.bodySmall)
                            if (d.isM800) {
                                Text(
                                    "Serial: ${d.serial ?: "se lee al conectar"}",
                                    color = Mist, style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text("${signalLabel(d.rssi)} · ${d.rssi} dBm", color = Faint, style = MaterialTheme.typography.bodySmall)
                        }
                        when {
                            busy -> CircularProgressIndicator(color = TextHi, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            d.isM800 -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Elegir", tint = TextHi)
                            else -> Text("No compatible", color = Faint, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (busy) {
                        Spacer(Modifier.height(8.dp))
                        Text("Conectando y vinculando a tu cuenta…", color = Mist, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (devices.isEmpty() && !scanning) {
            Spacer(Modifier.height(12.dp))
            Text("No se encontró ningún equipo. Acerca el teléfono al colchón y busca de nuevo.", color = Mist, style = MaterialTheme.typography.bodyMedium)
        }
        if (hiddenUnnamed > 0) {
            Spacer(Modifier.height(10.dp))
            Text("y $hiddenUnnamed equipo(s) Bluetooth sin nombre (no se muestran).", color = Faint, style = MaterialTheme.typography.bodySmall)
        }
        failure?.let {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Error, null, tint = Low, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(it, color = Low, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * Paso 2: redes WiFi en cards (Android). El M800 solo funciona en 2,4 GHz: las
 * redes solo-5 GHz aparecen atenuadas. En iOS el sistema no deja listar redes:
 * se ofrece escribir el nombre (card "Otra red").
 */
@Composable
private fun NetworkPickerStep(
    deviceName: String,
    networks: List<WifiNetwork>?,
    supported: Boolean,
    loading: Boolean,
    hasLocationPerm: Boolean,
    onRequestLocation: () -> Unit,
    onRefresh: () -> Unit,
    onPick: (String) -> Unit,
) {
    var manualSsid by remember { mutableStateOf("") }
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        focusedTextColor = TextHi, unfocusedTextColor = TextHi,
        focusedIndicatorColor = Mist, unfocusedIndicatorColor = Hairline,
        cursorColor = TextHi, focusedLabelColor = Mist, unfocusedLabelColor = Mist,
    )
    Column(Modifier.fillMaxWidth()) {
        WizardHeader(
            2, "Elige la red Wi-Fi",
            "$deviceName quedó vinculado a tu cuenta. Elige la red a la que se conectará el colchón (solo 2,4 GHz).",
        )
        when {
            !supported -> {
                Text(
                    "iPhone no permite que las apps vean las redes Wi-Fi cercanas. Escribe el nombre exacto de la red.",
                    color = Mist, style = MaterialTheme.typography.bodyMedium,
                )
            }
            !hasLocationPerm -> {
                Text(
                    "Android pide el permiso de ubicación para mostrar las redes Wi-Fi cercanas.",
                    color = Mist, style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onRequestLocation, modifier = Modifier.fillMaxWidth()) {
                    Text("Permitir y buscar redes", color = TextHi)
                }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (loading) {
                        CircularProgressIndicator(color = TextHi, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Buscando redes…", color = Mist, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("${networks?.size ?: 0} redes encontradas", color = Mist, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onRefresh, enabled = !loading) {
                        Icon(Icons.Filled.Refresh, null, tint = TextHi, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Actualizar", color = TextHi)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    networks.orEmpty().forEach { n ->
                        WizardCard(enabled = n.has24GHz, onClick = { onPick(n.ssid) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Wifi, null, tint = if (n.has24GHz) TextHi else Mist, modifier = Modifier.size(26.dp))
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(n.ssid, color = TextHi, style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(4.dp))
                                    val bands = listOfNotNull("2,4 GHz".takeIf { n.has24GHz }, "5 GHz".takeIf { n.has5GHz }).joinToString(" + ")
                                    Text("$bands · ${signalLabel(n.rssi)}", color = Mist, style = MaterialTheme.typography.bodySmall)
                                }
                                if (n.secured) Icon(Icons.Filled.Lock, "Con contraseña", tint = Mist, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                if (n.has24GHz) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Elegir", tint = TextHi)
                                else Text("Solo 5 GHz", color = Faint, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                if (!loading && networks.isNullOrEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No se encontraron redes. Activa la ubicación del teléfono y pulsa Actualizar, o escribe la red abajo.",
                        color = Mist, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        // "Otra red": siempre disponible (red oculta, o iOS).
        Spacer(Modifier.height(16.dp))
        WizardCard(enabled = true, onClick = {}) {
            Text(if (supported) "Otra red (escribir nombre)" else "Nombre de la red", color = TextHi, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = manualSsid, onValueChange = { manualSsid = it }, singleLine = true,
                label = { Text("Nombre de la red (SSID)") }, colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onPick(manualSsid.trim()) },
                enabled = manualSsid.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BtnFill, contentColor = BtnText,
                    disabledContainerColor = Night, disabledContentColor = Faint,
                ),
            ) { Text("Usar esta red", style = MaterialTheme.typography.labelLarge) }
        }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * Paso 3: contrasena de la red elegida y envio al colchon por Bluetooth
 * (WiFiConfigHelper.bleWiFiConfig real, ver M800BleProvisioner.provisionWifi).
 */
@Composable
private fun PasswordStep(
    ssid: String,
    onSsidChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    provisioning: Boolean,
    failure: String?,
    onChangeNetwork: () -> Unit,
    onContinue: () -> Unit,
) {
    var showPassword by remember { mutableStateOf(false) }
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        focusedTextColor = TextHi, unfocusedTextColor = TextHi,
        focusedIndicatorColor = Mist, unfocusedIndicatorColor = Hairline,
        cursorColor = TextHi, focusedLabelColor = Mist, unfocusedLabelColor = Mist,
    )
    val canContinue = ssid.isNotBlank() && password.isNotBlank() && !provisioning

    Column(Modifier.fillMaxWidth()) {
        WizardHeader(3, "Contraseña de la red", "Se enviará al colchón por Bluetooth junto con la dirección del servidor.")
        WizardCard(enabled = !provisioning, onClick = onChangeNetwork) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Wifi, null, tint = TextHi, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Red elegida", color = Mist, style = MaterialTheme.typography.bodySmall)
                    Text(ssid.ifBlank { "—" }, color = TextHi, style = MaterialTheme.typography.titleMedium)
                }
                Text("Cambiar", color = TextHi, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Contraseña") },
            singleLine = true,
            enabled = !provisioning,
            visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None
                else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        if (showPassword) "Ocultar contraseña" else "Mostrar contraseña", tint = Mist,
                    )
                }
            },
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Este dispositivo solo es compatible con una red Wi-Fi de 2.4 GHz.",
            color = Faint, style = MaterialTheme.typography.bodySmall,
        )
        if (provisioning) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = TextHi, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Enviando la red al colchón por Bluetooth… (hasta 90 s)", color = Mist, style = MaterialTheme.typography.bodyMedium)
            }
        }
        failure?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = Low, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onContinue,
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BtnFill, contentColor = BtnText,
                disabledContainerColor = Night2, disabledContentColor = Faint,
            ),
        ) {
            if (provisioning) CircularProgressIndicator(color = BtnText, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            else Text("Enviar al colchón", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * Confirmación del equipo encontrado. El demo oficial del fabricante hace lo
 * mismo: `bindDevice(args)` recibe el `deviceId` que el usuario escribe en un
 * campo de texto; el SDK no lo descubre por BLE.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmSerial(
    bleName: String,
    failure: String?,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var serial by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Icon(Icons.Filled.CheckCircle, null, tint = Good, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("Device found", color = TextHi, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(bleName, color = Mist, style = MaterialTheme.typography.bodyLarge)

        Spacer(Modifier.height(28.dp))
        Text(
            "Enter the serial number printed on the box",
            color = Mist, style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = serial,
            onValueChange = { serial = it },
            label = { Text("Serial (e.g. M800424600184)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Night2, unfocusedContainerColor = Night2,
                focusedTextColor = TextHi, unfocusedTextColor = TextHi,
                focusedIndicatorColor = Mist, unfocusedIndicatorColor = Hairline,
                cursorColor = TextHi, focusedLabelColor = Mist, unfocusedLabelColor = Faint,
            ),
        )

        failure?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = Low, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.weight(1f))
        Button(
            onClick = { onConfirm(serial) },
            enabled = serial.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BtnFill, contentColor = BtnText,
                disabledContainerColor = Night2, disabledContentColor = Faint,
            ),
        ) { Text("Add", style = MaterialTheme.typography.labelLarge) }
        TextButton(onClick = onCancel, modifier = Modifier.padding(vertical = 4.dp)) {
            Text("Scan again", color = Mist)
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** Se muestra en vez de escanear cuando la cuenta ya tiene un M800 — evita
 * pedir permiso de Bluetooth y hacer un escaneo de 45s para terminar en el
 * mismo rechazo que ya daría el backend (ACCOUNT_ALREADY_HAS_DEVICE_TYPE). */
@Composable
private fun AlreadyHasDevice(deviceName: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Error, null, tint = Low, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text("You already have a Smart Mattress", color = TextHi, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "Tu cuenta ya tiene vinculado $deviceName. Desvincúlalo desde el detalle del dispositivo antes de agregar uno nuevo.",
            color = Mist, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BtnFill, contentColor = BtnText),
        ) { Text("Volver", style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun BoundOk(name: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.CheckCircle, null, tint = Good, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(20.dp))
        Text("Added successfully", color = TextHi, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(name, color = Mist, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(0.8f).height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BtnFill, contentColor = BtnText),
        ) { Text("Start to use", style = MaterialTheme.typography.labelLarge) }
    }
}
