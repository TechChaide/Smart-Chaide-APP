package com.chaide.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaide.app.net.Api
import com.chaide.app.net.GatewayApiClient
import com.chaide.app.net.GatewayRealtimeDto
import com.chaide.app.net.UploadSleepSessionRequest
import com.chaide.app.platform.platformLog
import com.chaide.app.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * Datos en vivo (ritmo cardiaco / respiracion) mientras el usuario esta
 * acostado sobre el colchon. Replica el modelo y la logica de estados REAL
 * encontrada en el bundle H5 de produccion (com.mambtech.chaidesmart):
 *   - heartRate/breathRate == 255 (o sin dato) => "sin lectura todavia":
 *     muestra "--" y una animacion de pulso mientras detecta.
 *   - valor valido => se muestra el numero y se detiene el pulso.
 * La fuente es nuestro gateway TCP propio (m800-gateway), que ya confirma
 * login real del equipo y dispara POST_START_VIEW_REALDATA tras el login;
 * este endpoint HTTP solo expone la ultima lectura que el gateway parseo de
 * los frames msgType=1536 reales del equipo.
 *
 * Mientras esta pantalla esta abierta, junta una muestra cada 60s (mismo
 * paso que `history_detail_NN` real) y, al salir, la sube al backend
 * (`POST /api/sleep/report`, ver Api.kt) para que quede guardada — antes se
 * veia en vivo y se perdia por completo al cerrar la pantalla. [uploadScope]
 * viene de AppNav (no de un `rememberCoroutineScope()` de esta pantalla):
 * ese scope muere JUNTO con esta composable en `onDispose`, antes de que el
 * `launch` del upload pudiera siquiera arrancar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveMonitorScreen(onBack: () -> Unit, uploadScope: CoroutineScope) {
    var data by remember { mutableStateOf<GatewayRealtimeDto?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val sessionStart = remember { Clock.System.now().epochSeconds }
    val heartSamples = remember { mutableListOf<Int>() }
    val breathSamples = remember { mutableListOf<Int>() }
    val tempSamples = remember { mutableListOf<Int>() }
    val humiditySamples = remember { mutableListOf<Int>() }

    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            try {
                data = GatewayApiClient.service.realtime()
                errorMsg = null
            } catch (e: Exception) {
                errorMsg = "No se pudo conectar con el equipo"
            }
            // Una muestra cada 60 polls (60s), no cada poll de 1s — el poll
            // rapido es solo para que el numero en pantalla se vea fluido.
            if (tick % 60 == 0) {
                data?.let {
                    heartSamples += it.heartRate ?: 0
                    breathSamples += it.breathRate ?: 0
                    tempSamples += it.temp ?: 0
                    humiditySamples += it.humidity ?: 0
                }
            }
            tick++
            delay(1000)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Menos de 2 muestras (<2 min) no vale la pena guardarlo como
            // sesion — sería una "noche" de un solo punto o ninguno.
            if (heartSamples.size >= 2) {
                val startTime = sessionStart
                val hr = heartSamples.toList(); val br = breathSamples.toList()
                val temp = tempSamples.toList(); val hum = humiditySamples.toList()
                uploadScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            Api.service.uploadSleepSession(
                                UploadSleepSessionRequest(
                                    startTime = startTime, intervalSec = 60,
                                    heartRate = hr, breathRate = br, eTemp = temp, eHumidity = hum,
                                )
                            )
                        }
                    } catch (e: Exception) {
                        // Silencioso a proposito: el usuario ya salio de la
                        // pantalla, no hay donde mostrarle un error. Se deja
                        // log nativo para depurar si hace falta.
                        platformLog("LiveMonitor", "No se pudo subir la sesion: ${e.message}")
                    }
                }
            }
        }
    }

    val heartRate = data?.heartRate ?: -1
    val breathRate = data?.breathRate ?: -1
    val detecting = heartRate <= 0 || heartRate == 255 || breathRate <= 0 || breathRate == 255

    Scaffold(
        containerColor = Night,
        topBar = {
            TopAppBar(
                title = { Text("Live Data") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = TextHi) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Night, titleContentColor = TextHi)
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (detecting) "Detectando…" else "En vivo",
                color = Mist, style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(28.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                VitalCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Favorite,
                    label = "Ritmo cardiaco",
                    value = if (detecting || heartRate < 0) "--" else heartRate.toString(),
                    unit = "bpm",
                    pulsing = detecting,
                )
                VitalCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Air,
                    label = "Respiración",
                    value = if (detecting || breathRate < 0) "--" else breathRate.toString(),
                    unit = "rpm",
                    pulsing = detecting,
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(
                Modifier.fillMaxWidth().background(Night2, RoundedCornerShape(16.dp)).padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SmallReading(Icons.Filled.Thermostat, "Temperatura", data?.temp?.takeIf { it in 0..60 }?.let { "$it°C" } ?: "--")
                SmallReading(Icons.Filled.WaterDrop, "Humedad", data?.humidity?.takeIf { it in 0..100 }?.let { "$it%" } ?: "--")
            }

            Spacer(Modifier.height(20.dp))

            errorMsg?.let {
                Text(it, color = Low, style = MaterialTheme.typography.bodyMedium)
            }

            if (data == null && errorMsg == null) {
                Spacer(Modifier.height(20.dp))
                CircularProgressIndicator(color = Moon)
            }
        }
    }
}

@Composable
private fun VitalCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    unit: String,
    pulsing: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha"
    )
    val scale by transition.animateFloat(
        initialValue = 0.92f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "scale"
    )

    Column(
        modifier.background(Night2, RoundedCornerShape(20.dp)).padding(vertical = 22.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon, null, tint = Low,
            modifier = Modifier.size(28.dp)
                .graphicsLayer {
                    if (pulsing) { this.alpha = alpha; scaleX = scale; scaleY = scale }
                }
        )
        Spacer(Modifier.height(10.dp))
        Text(value, color = TextHi, style = MaterialTheme.typography.displayLarge.copy(fontSize = 40.sp))
        Spacer(Modifier.height(2.dp))
        Text(unit, color = Faint, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        Text(label, color = Mist, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SmallReading(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Mist, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(value, color = TextHi, style = MaterialTheme.typography.titleMedium)
            Text(label, color = Faint, style = MaterialTheme.typography.labelMedium)
        }
    }
}
