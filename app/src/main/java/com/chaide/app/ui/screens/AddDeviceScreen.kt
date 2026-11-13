package com.chaide.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.chaide.app.R
import com.chaide.app.ble.M800BleProvisioner
import com.chaide.app.net.Api
import com.chaide.app.net.BindDeviceRequest
import com.chaide.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// En Android 12+ el manifest declara BLUETOOTH_SCAN con `neverForLocation`, así
// que el escaneo BLE no requiere permiso de ubicación. El componente Vue real
// (`addDevice.startScanDevice`) hace exactamente esta misma distinción:
// apiLevel >= 31 -> requestBlePermission(); si no -> checkLocation().
private val REQUIRED_PERMISSIONS: Array<String> = if (Build.VERSION.SDK_INT >= 31) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasPermissions by remember {
        mutableStateOf(REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
    }

    // Leer el SSID actual (WifiManager.connectionInfo) exige ubicacion en
    // Android, AUNQUE el escaneo BLE ya no la necesite (eso es aparte, ver
    // REQUIRED_PERMISSIONS arriba) — por eso el "Ajustes de Wi-Fi" pedia el
    // permiso de nuevo: es un segundo permiso para un proposito distinto.
    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var hasLocationPerm by remember { mutableStateOf(hasLocationPermission()) }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasLocationPerm = granted }

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
    // SSID/password SI son parte del flujo real ("Ajustes de Wi-Fi" tras
    // vincular) — confirmado contra la app oficial instalada en el equipo.
    var wifiSsid by remember { mutableStateOf("") }
    var wifiPass by remember { mutableStateOf("") }
    // Persistido (GatewaySettings), no hardcodeado: esta IP cambia cada vez
    // que se reaprovisiona el equipo en otra red, y Home/LiveMonitorScreen
    // leen este mismo valor para el endpoint /realtime del gateway. La app
    // real no se lo pide al usuario (va fijo a su propio backend); aqui
    // queda como ajuste avanzado para poder apuntar a nuestro gateway de pruebas.
    var serverIp by remember { mutableStateOf(com.chaide.app.net.GatewaySettings.ip) }
    var serverPort by remember { mutableStateOf("29010") }

    // Autorelleno del SSID con la red a la que el teléfono YA está conectado
    // (mismo patrón que la demo oficial del fabricante, `getSSID()` en
    // BleConfigSdkDemo_M901L/MainActivity.java) — el usuario no debería tener
    // que escribirlo a mano; el flujo real es "conecta tu teléfono a la WiFi
    // del colchón" y la app lo detecta solo. Reintenta cuando se llega al
    // paso de WiFi o cuando se concede el permiso de ubicacion (necesario
    // para leer el SSID, aparte del permiso de escaneo BLE).
    LaunchedEffect(wifiStep, hasLocationPerm) {
        if (!wifiStep || !hasLocationPerm) return@LaunchedEffect
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val ssid = wm?.connectionInfo?.ssid?.trim('"')
            if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>") wifiSsid = ssid
        } catch (_: Exception) { /* sin WiFi activa: se deja vacío para editar a mano */ }
    }

    DisposableEffect(Unit) { onDispose { scanner?.stop() } }

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
                failure = e.message ?: "Bind failed"
            }
        }
    }

    fun startScan() {
        failure = null; boundName = null; wifiStep = false; found = emptyList(); scanning = true
        val p = M800BleProvisioner(context)
        scanner = p
        // 10 s, igual que el `setTimeout(doNext, 1e4)` del componente real.
        p.scanForDevices(timeoutMs = 10_000) { devices ->
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
                wifiStep -> WifiSettingsStep(
                    ssid = wifiSsid, onSsidChange = { wifiSsid = it },
                    password = wifiPass, onPasswordChange = { wifiPass = it },
                    provisioning = provisioning, failure = failure,
                    hasLocationPerm = hasLocationPerm,
                    onRequestLocation = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                    onContinue = {
                        failure = null; provisioning = true
                        val p = scanner ?: M800BleProvisioner(context).also { scanner = it }
                        p.provisionWifi(
                            ssid = wifiSsid, password = wifiPass,
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
                        painter = painterResource(R.drawable.device_m800),
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
                                val p = M800BleProvisioner(context)
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
                                val p = M800BleProvisioner(context)
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
                            onClick = { permissionLauncher.launch(REQUIRED_PERMISSIONS) },
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

/**
 * "Ajustes de Wi-Fi" — pantalla real confirmada contra la app oficial
 * (Chaide_release 1.0.7) instalada en el equipo: aparece DESPUES de vincular,
 * con el SSID de la red actual del telefono ya puesto, pide password, y avisa
 * que el equipo solo acepta 2.4 GHz. "Continuar" manda la config por BLE
 * (WiFiConfigHelper.bleWiFiConfig real, ver M800BleProvisioner.provisionWifi).
 */
@Composable
private fun WifiSettingsStep(
    ssid: String,
    onSsidChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    provisioning: Boolean,
    failure: String?,
    hasLocationPerm: Boolean,
    onRequestLocation: () -> Unit,
    onContinue: () -> Unit,
) {
    var showPassword by remember { mutableStateOf(false) }
    var editingSsid by remember { mutableStateOf(false) }
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        focusedTextColor = TextHi, unfocusedTextColor = TextHi,
        focusedIndicatorColor = Mist, unfocusedIndicatorColor = Hairline,
        cursorColor = TextHi, focusedLabelColor = Mist, unfocusedLabelColor = Mist,
    )
    // Mini-wizard: paso 1 detectar/confirmar red, paso 2 password+enviar.
    // No deja avanzar sin red ni password, para no mandar config a medias al M800.
    val canDetect = !hasLocationPerm && ssid.isBlank() && !editingSsid
    val canContinue = ssid.isNotBlank() && password.isNotBlank() && !provisioning

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(24.dp))
        Text("Ajustes de Wi-Fi", color = TextHi, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "Paso 1 de 1: confirma la red y la contraseña para compartirlas con el equipo por Bluetooth.",
            color = Mist, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Wi-Fi conectado", color = Mist, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                if (editingSsid) {
                    OutlinedTextField(
                        value = ssid, onValueChange = onSsidChange, singleLine = true,
                        colors = fieldColors,
                    )
                } else {
                    Text(ssid.ifBlank { "—" }, color = TextHi, style = MaterialTheme.typography.titleMedium)
                }
            }
            TextButton(onClick = { editingSsid = !editingSsid }) {
                Text(if (editingSsid) "Listo" else "Cambiar a otra red Wi-Fi", color = TextHi, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (canDetect) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRequestLocation, modifier = Modifier.fillMaxWidth()) {
                Text("Detectar mi red Wi-Fi actual", color = TextHi)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Android exige el permiso de ubicacion para leer el nombre de la red conectada.",
                color = Faint, style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
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
        Spacer(Modifier.height(16.dp))
        Text(
            "Este dispositivo solo es compatible con una red Wi-Fi de 2.4 GHz.",
            color = Faint, style = MaterialTheme.typography.bodySmall,
        )
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
            onClick = onContinue,
            enabled = canContinue,
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
