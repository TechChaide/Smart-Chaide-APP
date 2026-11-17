package com.chaide.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaide.app.R
import com.chaide.app.net.Api
import com.chaide.app.net.GatewayApiClient
import com.chaide.app.net.GatewayRealtimeDto
import com.chaide.app.net.SleepDto
import com.chaide.app.net.TrendDto
import com.chaide.app.ui.MusicPlayerState
import com.chaide.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

private fun hm(min: Int?): String {
    if (min == null || min <= 0) return "—"
    return if (min >= 60) "${min / 60}H${min % 60}M" else "${min}M"
}

/** HH:mm-HH:mm a partir del inicio de sueño real (epoch s) + duración.
 * `startTime` es un epoch UTC real; se formatea en la zona horaria del
 * dispositivo (sin forzar UTC) — ver nota en ReportDetailScreen.kt/clock(). */
private fun period(startEpochSec: Long?, durationMin: Int?): String {
    if (startEpochSec == null || durationMin == null || startEpochSec < 1_500_000_000L) return "—"
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    val start = Date(startEpochSec * 1000)
    val end = Date(startEpochSec * 1000 + durationMin * 60_000L)
    return "${fmt.format(start)}-${fmt.format(end)}"
}

/** Réplica fiel de la Home real (app-reference/03-home.png): banner superior,
 * selector Left/Right(Me), tarjetas Daily/Weekly/Monthly Report. */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    music: MusicPlayerState,
    onOpenReport: (String) -> Unit = {},
    onOpenMusic: () -> Unit = {},
) {
    var sleep by remember { mutableStateOf<SleepDto?>(null) }
    var week by remember { mutableStateOf<TrendDto?>(null) }
    var month by remember { mutableStateOf<TrendDto?>(null) }
    var syncing by remember { mutableStateOf(false) }
    // Tips reales (scene_banner_sleeptips, channelId 57018 — el mismo texto
    // exacto visto en la captura real: "Beber leche antes de dormir...",
    // "Las nueces pueden aliviar..."), no inventados. Idioma del dispositivo.
    var tips by remember { mutableStateOf<List<String>>(emptyList()) }
    var tipIndex by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Ritmo cardiaco/respiracion en vivo (gateway TCP propio, m800-gateway).
    // Va justo debajo del selector Left/Right(Me), igual que en la app real
    // (donde reemplaza/acompaña el estado "Levantarse de la cama").
    var liveData by remember { mutableStateOf<GatewayRealtimeDto?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            try { liveData = GatewayApiClient.service.realtime() } catch (_: Exception) {}
            delay(1000)
        }
    }

    // El reproductor real (pistas "Sleep-Aid Music", sleepace_discovery.music)
    // vive en `music`, hoisted por encima de MainScreen, así sigue sonando y
    // el mini-player sobre el nav bar lo refleja aunque cambies de pestaña.
    LaunchedEffect(Unit) { music.loadTracks() }

    suspend fun reload() {
        try { sleep = withContext(Dispatchers.IO) { Api.service.sleepLatest() }.data } catch (_: Exception) {}
        // offset=1 = la semana/mes calendario YA COMPLETO (el anterior al
        // actual), no el que está en curso — con offset=0 la semana/mes
        // actual casi siempre tiene 0-pocas noches (recién empezó) o, en
        // Month, está bloqueado (`notFinished`) porque no terminó. Verificado
        // contra producción: offset=1 da Semana=66, el mismo número exacto
        // que ya había capturado de la app real.
        try { week = withContext(Dispatchers.IO) { Api.service.sleepReportTrend("week", 1) }.data } catch (_: Exception) {}
        try { month = withContext(Dispatchers.IO) { Api.service.sleepReportTrend("month", 1) }.data } catch (_: Exception) {}
    }
    LaunchedEffect(Unit) {
        reload()
        val lang = if (Locale.getDefault().language == "es") "es" else "en-us"
        try { tips = withContext(Dispatchers.IO) { Api.service.sleepTips(lang) }.data.orEmpty() } catch (_: Exception) {}
    }

    Column(modifier.fillMaxSize().background(Night)) {
        // Banner superior: foto real (`index_pic_sleep_tips`, extraída del
        // bundle H5 real — la misma que usa la app real para este banner,
        // confirmada contra la captura real "GOOD NIGHT") + overlay azul.
        Box(Modifier.fillMaxWidth().height(210.dp)) {
            Image(
                painter = painterResource(R.drawable.home_banner_bg),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0x990D6FC4), Color(0xCC00529D))))
                    .padding(horizontal = 20.dp)
            ) {
            Column(Modifier.align(Alignment.BottomStart).padding(bottom = 18.dp)) {
                Surface(
                    shape = RoundedCornerShape(20.dp), color = Color(0x33FFFFFF),
                    modifier = Modifier.clickable(enabled = tips.isNotEmpty()) { tipIndex = (tipIndex + 1) % tips.size },
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Sleep Tips", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        Icon(Icons.Filled.ChevronRight, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    tips.getOrNull(tipIndex) ?: "Less caffeine, better sleep.",
                    color = Color.White, style = MaterialTheme.typography.bodyLarge,
                )
            }
            }
        }

        Column(
            Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Night).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(18.dp))
            // Selector Left / Right(Me)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Left", color = Faint, style = MaterialTheme.typography.titleMedium)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Right(Me)", color = TextHi, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.width(90.dp).height(2.dp).background(TextHi))
                }
            }
            Spacer(Modifier.height(18.dp))
            // Bajo el tab "Right(Me)" la app real muestra el estado del OTRO
            // lado (Left) — aquí no hay dispositivo vinculado a la derecha,
            // así que literalmente dice "Left Bed" (verificado contra la app
            // real con esta misma cuenta: el único device está en leftRight=0).
            // Cuando hay datos en vivo del sensor (gateway propio), la app
            // real reemplaza este renglon por ritmo cardiaco/respiracion en
            // vivo (bundle H5: realtimeData.heartRate/breathRate, sentinela
            // 255/0 = "sin lectura todavia").
            //
            // CORREGIDO: la app real NO muestra las tarjetas de bpm/rpm con
            // "--" cuando no hay captura — directamente NO aparecen; solo se
            // muestran cuando hay una lectura real y fresca. Antes esto
            // siempre renderizaba los íconos (con guiones si no había dato),
            // dando la impresión de estar "monitoreando" incluso sin equipo
            // conectado o sin nadie en la cama. `ageMs` descarta lecturas
            // viejas (equipo desconectado del gateway hace rato) y status=5
            // (SLEEP_LEAVE, "fuera de cama") descarta a alguien que se
            // levantó — igual que el SDK oficial documenta ese valor.
            val hr = liveData?.heartRate ?: -1
            val br = liveData?.breathRate ?: -1
            val fresh = (liveData?.ageMs ?: Long.MAX_VALUE) < 15_000L
            val outOfBed = liveData?.status == 5
            val isCapturing = fresh && !outOfBed && hr > 0 && hr != 255 && br > 0 && br != 255
            if (isCapturing) {
                // Réplica del renglón real de app-reference/1Home.png: DOS
                // tarjetas con ícono circular + número grande + unidad +
                // etiqueta debajo — NO texto chico pegado a "Left Bed" (así
                // estaba antes, que solo arreglaba CUÁNDO se mostraba pero
                // no CÓMO se ve). Reemplaza el renglón "Left Bed" entero
                // mientras hay captura real, igual que la referencia (que no
                // muestra ningún texto de "lado" cuando sí hay datos en vivo).
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    VitalStatCard(Modifier.weight(1f), Icons.Filled.Favorite, hr, "BPM", "Heart Rate")
                    VitalStatCard(Modifier.weight(1f), Icons.Filled.Air, br, "RPM", "Respiratory Rate")
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Hotel, null, tint = Mist, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Left Bed", color = TextHi, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
            }
            HorizontalDivider(color = Hairline)
            Spacer(Modifier.height(14.dp))

            ReportCard(
                title = "Daily Report", subtitle = "Yesterday",
                onClick = { onOpenReport("day") },
                trailing = {
                    // No hay BLE real desde esta pantalla para forzar una
                    // sincronización con el dispositivo — lo honesto que se
                    // puede hacer es recargar los datos reales del servidor,
                    // no simular una sync falsa que no haga nada.
                    Surface(
                        shape = RoundedCornerShape(16.dp), color = Color.White,
                        modifier = Modifier.clickable(enabled = !syncing) { scope.launch { syncing = true; reload(); syncing = false } },
                    ) {
                        Text(if (syncing) "..." else "Sync", color = Night, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                    }
                }
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ScoreStat("${sleep?.score ?: "—"}", "Sleep Score")
                    ScoreStat(hm(sleep?.durationMin), "Actual Sleep Time")
                    ScoreStat(period(sleep?.startTime, sleep?.durationMin), "Sleep Period")
                }
            }
            Spacer(Modifier.height(4.dp))
            SimpleReportRow("Weekly Report", "Last Week", week?.score?.toString() ?: "—") { onOpenReport("week") }
            SimpleReportRow("Monthly Report", "Last Month", month?.score?.toString() ?: "—") { onOpenReport("month") }
            if (music.tracks.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenMusic).padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.MusicNote, null, tint = Mist, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Sleep-Aid Music", color = TextHi, style = MaterialTheme.typography.titleLarge)
                        Text("${music.tracks.size} tracks", color = Faint, style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = Faint)
                }
                HorizontalDivider(color = Hairline)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReportCard(title: String, subtitle: String, onClick: () -> Unit = {}, trailing: @Composable () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CalendarToday, null, tint = Mist, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = TextHi, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, color = Faint, style = MaterialTheme.typography.bodyMedium)
            }
            trailing()
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
    HorizontalDivider(color = Hairline)
}

@Composable
private fun SimpleReportRow(title: String, subtitle: String, score: String, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CalendarToday, null, tint = Mist, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextHi, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, color = Faint, style = MaterialTheme.typography.bodyMedium)
        }
        Text("$score Score", color = TextHi, style = MaterialTheme.typography.titleMedium)
    }
    HorizontalDivider(color = Hairline)
}

/**
 * Réplica de la tarjeta real de app-reference/1Home.png: círculo con el
 * ícono, número grande + unidad en la misma línea a su derecha, etiqueta
 * ("Heart Rate"/"Respiratory Rate") debajo. Antes esto se mostraba como
 * texto chico inline junto a "Left Bed" — nada que ver con el tamaño/
 * disposición real.
 */
@Composable
private fun VitalStatCard(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, value: Int, unit: String, label: String) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).background(IndigoSoft.copy(alpha = 0.25f), androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = TextHi, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$value", color = TextHi, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(4.dp))
                Text(unit, color = Mist, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Faint, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ScoreStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(label, color = Faint, style = MaterialTheme.typography.labelMedium)
    }
}
