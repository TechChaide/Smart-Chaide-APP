package com.chaide.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
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
import com.chaide.app.platform.currentWifiSsid
import com.chaide.app.platform.platformName
import com.chaide.app.platform.rememberBlePermission
import com.chaide.app.platform.rememberWifiPicker
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

    var scanning by remember { mutableStateOf(false) }
    var identifying by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<M800BleProvisioner.Found>>(emptyList()) }
    var boundName by remember { mutableStateOf<String?>(null) }
    var wifiStep by remember { mutableStateOf(false) }
    var provisioning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var scanner by remember { mutableStateOf<M800BleProvisioner?>(null) }
    var diagLines by remember { mutableStateOf<List<String>>(emptyList()) }
    // Herramientas que NO existen en la pantalla real (sonda cruda, override
    // manual de IP/puerto del servidor): ocultas por defecto.
    var showDebugTools by remember { mutableStateOf(false) }
    // Persistido (GatewaySettings), no hardcodeado: esta IP cambia cada vez
    // que se reaprovisiona el equipo en otra red, y Home/LiveMonitorScreen
    // leen este mismo valor para el endpoint /realtime del gateway. La app
    // real no se lo pide al usuario (va fijo a su propio backend); aqui
    // queda como ajuste avanzado para poder apuntar a nuestro gateway de pruebas.
    var serverIp by remember { mutableStateOf(com.chaide.app.net.GatewaySettings.ip) }
    var serverPort by remember { mutableStateOf("29010") }

    DisposableEffect(Unit) { onDispose { scanner?.stop() } }

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

    fun bind(serial: String) {
        failure = null
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    Api.service.bindDevice(BindDeviceRequest(deviceName = serial.trim()))
                }
                boundName = r.data?.deviceName ?: serial
                found = emptyList()
                wifiStep = true
            } catch (e: Exception) {
                failure = parseBindError(e)
            }
        }
    }

    fun startScan() {
        failure = null; boundName = null; wifiStep = false; found = emptyList(); scanning = true
        val p = M800BleProvisioner()
        scanner = p
        // El componente Vue real usa 10s (`setTimeout(doNext, 1e4)`), pero eso
        // asume que el equipo anuncia por BLE de forma continua. Verificado
        // contra hardware real: el M800 ya aprovisionado (luz verde fija)
        // anuncia en RÁFAGAS intermitentes, no continuas — varios intentos de
        // 10s seguidos no lo encontraron, y uno de 45s sí. Se amplía la
        // ventana para no depender de la suerte del timing de la ráfaga.
        p.scanForDevices(timeoutMs = 45_000) { devices ->
            scanning = false
            if (devices.isEmpty()) {
                failure = "No device found. Make sure the green light is flashing slowly."
                return@scanForDevices
            }
            // El serial real viaja en los datos de fabricante del advertisement
            // (AD 0xFF) — p.ej. "M800424600184". Si está, se vincula directo
            // (equivalente a `doNext()` del Vue real: un solo equipo -> bind).
            // El nombre corto (0x09) NO identifica la unidad, lo comparten
            // varias de lotes distintos; por eso nunca se usa para vincular.
            val d = devices.first()
            if (d.serial != null) {
                bind(d.serial)
            } else {
                // Sin serial en el advertisement: se le pregunta al equipo
                // directo por BLE (igual que la app real, que tampoco muestra
                // el nombre corto sino el serial completo ya resuelto).
                identifying = true
                p.identifyDevice { info ->
                    identifying = false
                    val resolved = info?.deviceId?.takeIf { it.isNotBlank() }
                    if (resolved != null) bind(resolved) else found = devices
                }
            }
        }
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
                wifiStep -> WifiNetworkStep(
                    provisioning = provisioning, failure = failure,
                    hasLocationPerm = hasLocationPerm,
                    onRequestLocation = ssidPermission.request,
                    onContinue = { ssid, password ->
                        failure = null; provisioning = true
                        val p = scanner ?: M800BleProvisioner().also { scanner = it }
                        p.provisionWifi(
                            ssid = ssid, password = password,
                            serverIp = serverIp, serverPort = serverPort.toIntOrNull() ?: 29010,
                            onDone = { success ->
                                provisioning = false
                                if (success) {
                                    result = boundName
                                    onBound()
                                } else {
                                    failure = "No se pudo configurar el WiFi del equipo. Intenta de nuevo."
                                }
                            },
                        )
                    },
                )
                scanning || identifying -> Scanning()
                found.isNotEmpty() -> ConfirmSerial(
                    bleName = found.first().deviceName,
                    failure = failure,
                    onConfirm = { bind(it) },
                    onCancel = { found = emptyList(); failure = null },
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
                            TextField(serverIp, { serverIp = it }, label = { Text("Server IP (gateway de pruebas)") },
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
                            colors = ButtonDefaults.buttonColors(containerColor = BtnFill, contentColor = TextHi),
                        ) { Text("Grant Bluetooth permission", style = MaterialTheme.typography.labelLarge) }
                    } else {
                        // `next` = "Continue"
                        Button(
                            onClick = { startScan() },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BtnFill, contentColor = TextHi),
                        ) { Text("Continue", style = MaterialTheme.typography.labelLarge) }
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
}

/** Fases del mini-wizard de "Ajustes de Wi-Fi" (ver [WifiNetworkStep]). */
private enum class WifiPhase { DETECTING, ASK_USE_CURRENT, PICK_NETWORK, MANUAL_ENTRY, PASSWORD }

/**
 * "Ajustes de Wi-Fi" — aparece DESPUES de vincular, antes de mandar la config
 * por BLE. La app real autorellena el SSID con la red a la que el telefono ya
 * esta conectado; aqui se hace explicito con un primer paso de confirmacion
 * (pedido de negocio: no asumir la red actual sin preguntar), igual en Android
 * e iOS:
 *   1. Si se detecta una red actual -> se pregunta "¿usar esta red?".
 *      - Sí -> solo falta la contraseña (el password nunca se puede leer del
 *        sistema en ninguna plataforma).
 *      - No -> Android abre el selector nativo de redes (panel de Ajustes);
 *        iOS pide el nombre a mano (no tiene selector para apps de terceros).
 *   2. Si no se detecta ninguna red (sin permiso, sin Wi-Fi, o iOS sin el
 *      entitlement) se salta la pregunta y se va directo a esa misma rama.
 * Avisa que el equipo solo acepta 2.4 GHz. "Continuar" manda la config por
 * BLE (WiFiConfigHelper.bleWiFiConfig real, ver M800BleProvisioner.provisionWifi).
 */
@Composable
private fun WifiNetworkStep(
    provisioning: Boolean,
    failure: String?,
    hasLocationPerm: Boolean,
    onRequestLocation: () -> Unit,
    onContinue: (ssid: String, password: String) -> Unit,
) {
    var phase by remember { mutableStateOf(WifiPhase.DETECTING) }
    var detectedSsid by remember { mutableStateOf<String?>(null) }
    var chosenSsid by remember { mutableStateOf("") }
    var manualSsid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    // true cuando volvemos del selector nativo de Android: si ahora hay una
    // red conectada, el usuario ya la elegio a proposito -> no se le vuelve a
    // preguntar "¿usar esta red?", se pasa directo a pedir la contraseña.
    var cameFromPicker by remember { mutableStateOf(false) }

    val launchPicker = rememberWifiPicker(onResult = { cameFromPicker = true; phase = WifiPhase.DETECTING })

    // Se re-ejecuta cada vez que `phase` vuelve a DETECTING: carga inicial y
    // cada regreso del selector nativo de Android.
    LaunchedEffect(phase, hasLocationPerm) {
        if (phase != WifiPhase.DETECTING) return@LaunchedEffect
        val ssid = if (platformName == "android" && !hasLocationPerm) null else currentWifiSsid()
        detectedSsid = ssid
        phase = when {
            !ssid.isNullOrBlank() && cameFromPicker -> { chosenSsid = ssid; WifiPhase.PASSWORD }
            !ssid.isNullOrBlank() -> WifiPhase.ASK_USE_CURRENT
            platformName == "android" -> WifiPhase.PICK_NETWORK
            else -> WifiPhase.MANUAL_ENTRY
        }
    }

    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        focusedTextColor = TextHi, unfocusedTextColor = TextHi,
        focusedIndicatorColor = Mist, unfocusedIndicatorColor = Hairline,
        cursorColor = TextHi, focusedLabelColor = Mist, unfocusedLabelColor = Mist,
    )

    @Composable
    fun PasswordField() {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Contraseña") },
            singleLine = true,
            visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None
                else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        null, tint = Mist,
                    )
                }
            },
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    fun Header(subtitle: String) {
        Spacer(Modifier.height(24.dp))
        Text("Ajustes de Wi-Fi", color = TextHi, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = Mist, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }

    @Composable
    fun Footer(canContinue: Boolean, onClick: () -> Unit) {
        if (provisioning) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = TextHi, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Enviando la red al equipo por Bluetooth…", color = Mist, style = MaterialTheme.typography.bodyMedium)
            }
        }
        failure?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = Low, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onClick,
            enabled = canContinue && !provisioning,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BtnFill, contentColor = TextHi,
                disabledContainerColor = Night2, disabledContentColor = Faint,
            ),
        ) {
            if (provisioning) CircularProgressIndicator(color = TextHi, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            else Text("Continuar", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(20.dp))
    }

    when (phase) {
        WifiPhase.DETECTING -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = TextHi)
        }

        WifiPhase.ASK_USE_CURRENT -> Column(Modifier.fillMaxSize()) {
            Header("Detectamos que tu teléfono está conectado a esta red Wi-Fi.")
            Text("Red detectada", color = Mist, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text(detectedSsid.orEmpty(), color = TextHi, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(28.dp))
            Text(
                "¿Quieres compartir esta red con el equipo?",
                color = Mist, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { chosenSsid = detectedSsid.orEmpty(); phase = WifiPhase.PASSWORD },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BtnFill, contentColor = TextHi),
            ) { Text("Sí, usar esta red", style = MaterialTheme.typography.labelLarge) }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    cameFromPicker = false
                    phase = if (platformName == "android") WifiPhase.PICK_NETWORK else WifiPhase.MANUAL_ENTRY
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
            ) { Text("No, elegir otra red", color = TextHi, style = MaterialTheme.typography.labelLarge) }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(20.dp))
        }

        WifiPhase.PICK_NETWORK -> Column(Modifier.fillMaxSize()) {
            Header("Elige la red Wi-Fi que quieres compartir con el equipo.")
            if (!hasLocationPerm) {
                Text(
                    "Android exige el permiso de ubicación para detectar automáticamente la red conectada.",
                    color = Faint, style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRequestLocation, modifier = Modifier.fillMaxWidth()) {
                    Text("Conceder permiso de ubicación", color = TextHi)
                }
                Spacer(Modifier.height(16.dp))
            }
            Button(
                onClick = launchPicker,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BtnFill, contentColor = TextHi),
            ) { Text("Elegir red Wi-Fi", style = MaterialTheme.typography.labelLarge) }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { phase = WifiPhase.MANUAL_ENTRY }, modifier = Modifier.fillMaxWidth()) {
                Text("Prefiero escribir el nombre de la red", color = Mist, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(20.dp))
        }

        WifiPhase.MANUAL_ENTRY -> Column(Modifier.fillMaxSize()) {
            Header("Escribe el nombre y la contraseña de la red Wi-Fi del equipo.")
            OutlinedTextField(
                value = manualSsid, onValueChange = { manualSsid = it },
                label = { Text("Nombre de la red (SSID)") }, singleLine = true,
                colors = fieldColors, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            PasswordField()
            Spacer(Modifier.height(16.dp))
            Text(
                "Este dispositivo solo es compatible con una red Wi-Fi de 2.4 GHz.",
                color = Faint, style = MaterialTheme.typography.bodySmall,
            )
            Footer(canContinue = manualSsid.isNotBlank() && password.isNotBlank()) {
                onContinue(manualSsid.trim(), password)
            }
        }

        WifiPhase.PASSWORD -> Column(Modifier.fillMaxSize()) {
            Header("Confirma la contraseña para compartirla con el equipo por Bluetooth.")
            Text("Wi-Fi conectado", color = Mist, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text(chosenSsid, color = TextHi, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            PasswordField()
            Spacer(Modifier.height(16.dp))
            Text(
                "Este dispositivo solo es compatible con una red Wi-Fi de 2.4 GHz.",
                color = Faint, style = MaterialTheme.typography.bodySmall,
            )
            Footer(canContinue = password.isNotBlank()) { onContinue(chosenSsid, password) }
        }
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
                containerColor = BtnFill, contentColor = TextHi,
                disabledContainerColor = Night2, disabledContentColor = Faint,
            ),
        ) { Text("Add", style = MaterialTheme.typography.labelLarge) }
        TextButton(onClick = onCancel, modifier = Modifier.padding(vertical = 4.dp)) {
            Text("Scan again", color = Mist)
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** `adding_device` = "Adding Device…" */
@Composable
private fun Scanning() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = TextHi)
        Spacer(Modifier.height(20.dp))
        Text("Adding Device…", color = TextHi, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        // El equipo anuncia por BLE en ráfagas intermitentes, no continuas —
        // puede tardar hasta ~45s en vez de unos pocos segundos. Sin este
        // aviso, un usuario ve el spinner "colgado" y cierra la pantalla
        // justo antes de que la ráfaga llegue.
        Text(
            "Esto puede tardar hasta 45 segundos — el equipo no anuncia de forma continua.",
            color = Mist, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
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
            colors = ButtonDefaults.buttonColors(containerColor = BtnFill, contentColor = TextHi),
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
            colors = ButtonDefaults.buttonColors(containerColor = BtnFill, contentColor = TextHi),
        ) { Text("Start to use", style = MaterialTheme.typography.labelLarge) }
    }
}
