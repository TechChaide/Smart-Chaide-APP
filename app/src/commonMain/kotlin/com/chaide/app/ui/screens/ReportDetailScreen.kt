package com.chaide.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaide.app.net.Api
import com.chaide.app.net.AdviceDto
import com.chaide.app.net.DayReportDto
import com.chaide.app.net.TrendDto
import com.chaide.app.net.QualityDistributionDto
import com.chaide.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import com.chaide.app.util.DateFmt
import kotlin.math.roundToInt

private enum class Period { DAY, WEEK, MONTH }

/**
 * Réplica fiel de la pantalla real de reporte (Day/Week/Month). Datos reales
 * (score, curva de sueño, frecuencia cardíaca/respiratoria, temperatura,
 * humedad, consejos/deducciones) desde history_analysis_NN / history_detail_NN
 * — verificados contra la app real (mismos avg/min/max BPM y RPM).
 */
@Composable
fun ReportDetailScreen(onBack: () -> Unit, initialPeriod: String = "day") {
    // Home tiene 3 renglones (Daily/Weekly/Monthly) que antes llamaban todos
    // al mismo `onOpenReport` sin indicar cuál — siempre abría Day sin
    // importar cuál tocaras. Ahora cada uno pasa su período real.
    val startPeriod = when (initialPeriod) {
        "week" -> Period.WEEK
        "month" -> Period.MONTH
        else -> Period.DAY
    }
    var period by remember { mutableStateOf(startPeriod) }
    // Week/Month: arranca en el último período YA COMPLETO (offset=1), no en
    // el actual — el actual casi siempre está vacío (recién empezó) o
    // bloqueado (mes en curso). Mismo criterio que ya usa Home (ahí offset=1
    // dio 66/68, coincidiendo con producción); con offset=0 el enlace
    // "Weekly Report" de Home llevaba a una pantalla vacía que parecía rota.
    var offset by remember { mutableIntStateOf(if (startPeriod == Period.DAY) 0 else 1) }

    Scaffold(containerColor = Night) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextHi) }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                    PeriodTab("Day", period == Period.DAY) { period = Period.DAY; offset = 0 }
                    PeriodTab("Week", period == Period.WEEK) { period = Period.WEEK; offset = 1 }
                    PeriodTab("Month", period == Period.MONTH) { period = Period.MONTH; offset = 1 }
                }
                IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.Help, null, tint = TextHi) }
            }

            when (period) {
                // Day navega por el registro real adyacente (como la app real,
                // que abre /report con un id de noche concreto), no por días
                // de calendario — por eso su propia fila de navegación vive
                // dentro de DayContent, con el cursor de startTime real.
                Period.DAY -> DayContent()
                else -> {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { offset++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous", tint = TextHi) }
                        Text(
                            dateLabel(period, offset), color = TextHi, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f), textAlign = TextAlign.Center
                        )
                        IconButton(onClick = { if (offset > 0) offset-- }, enabled = offset > 0) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next", tint = if (offset > 0) TextHi else Faint)
                        }
                        Icon(Icons.Filled.CalendarToday, null, tint = TextHi, modifier = Modifier.padding(start = 8.dp))
                    }
                    HorizontalDivider(color = Hairline)
                    TrendContent(period, offset)
                }
            }
        }
    }
}

private fun dateLabel(period: Period, offset: Int): String = when (period) {
    Period.WEEK -> when (offset) { 0 -> "This Week"; 1 -> "Last Week"; else -> "$offset Weeks Ago" }
    // Nombre de mes calendario real ("Sep.2026"), no "Last Month"/"N Months
    // Ago" — verificado contra la captura real de producción, que navega
    // Month por mes calendario y lo rotula así.
    else -> {
        DateFmt.monthYearLabel(offset)
    }
}

/** "Yesterday" si el registro cae en el día calendario anterior a hoy; si no,
 * su fecha real — comparación en zona local del dispositivo (validado contra
 * la app real en sesión anterior). NOTA: a diferencia de `clock()`/hora del
 * día, el día-calendario de "Ayer" NO siguió el mismo patrón UTC al
 * verificarlo hoy (mostraba "Nov 4" en vez de "Ayer") — pendiente de
 * investigar la causa exacta; se deja en zona local que es lo último
 * confirmado correcto. */
private fun dayLabel(startTimeSec: Long): String {
    return if (DateFmt.isYesterday(startTimeSec)) "Yesterday" else DateFmt.monthDayUs(startTimeSec)
}

@Composable
private fun PeriodTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp)) {
        Text(label, color = if (selected) TextHi else Faint, style = MaterialTheme.typography.titleLarge)
        if (selected) { Spacer(Modifier.height(4.dp)); Box(Modifier.width(56.dp).height(2.dp).background(TextHi)) }
    }
}

// ---------------- DAY ----------------

@Composable
private fun DayContent() {
    // Cursor por startTime real del registro (como la app real: /report
    // recibe un id de noche concreto). null/null = noche más reciente.
    var before by remember { mutableStateOf<Long?>(null) }
    var after by remember { mutableStateOf<Long?>(null) }
    var report by remember { mutableStateOf<DayReportDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(before, after) {
        loading = true
        try { report = withContext(Dispatchers.IO) { Api.service.sleepReportDay(before, after) }.data } catch (_: Exception) { report = null }
        loading = false
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { report?.startTime?.let { before = it; after = null } },
            enabled = report != null
        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous", tint = if (report != null) TextHi else Faint) }
        Text(
            report?.startTime?.let { dayLabel(it) } ?: "—", color = TextHi, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f), textAlign = TextAlign.Center
        )
        IconButton(
            onClick = { report?.startTime?.let { after = it; before = null } },
            enabled = report?.hasNewer == true
        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next", tint = if (report?.hasNewer == true) TextHi else Faint) }
        Icon(Icons.Filled.CalendarToday, null, tint = TextHi, modifier = Modifier.padding(start = 8.dp))
    }
    HorizontalDivider(color = Hairline)

    if (loading) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = TextHi) }; return }
    val r = report
    if (r == null) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No data for this night.", color = Mist) }; return }

    val n = r.series?.heartRate?.size ?: r.series?.sleepCurve?.size ?: 0
    val xLabels = timeAxisLabels(r.startTime, r.durationMin, n)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        ScoreRing(r.score, "Sleep Score")
        Spacer(Modifier.height(28.dp))
        // Etiquetas reales confirmadas en vue-reference-en-strings.json
        // (time_asleep="Actual Sleep Time", sleep_time="Sleep Time",
        // stop_monitor="Wake Up Time") y verificadas contra la app real
        // instalada: 6h12m / 03:45 (dormiste) / 09:57 (despertaste = dormiste + duración).
        val fellAsleep = (r.startTime ?: 0) + (r.asleepMin ?: 0) * 60L
        val wokeUp = fellAsleep + (r.durationMin ?: 0) * 60L
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatCol(hmUpper(r.durationMin), "Actual Sleep Time")
            StatCol(clock(fellAsleep), "Sleep Time")
            StatCol(clock(wokeUp), "Wake Up Time")
        }
        Spacer(Modifier.height(20.dp))

        AdviceCard(r.advice)

        val s = r.series
        if (s?.sleepCurve != null) {
            ChartCard("Sleep Cycle", Icons.AutoMirrored.Filled.ShowChart, null) {
                // `dailyLong` real: smooth:true, lineStyle.width 2.5, sin símbolos.
                //
                // La columna `sleepCurve` SÍ es la fuente correcta: es una
                // señal continua y gradual (verificado con el arreglo
                // completo: sube suave de -0.05 a 2.64 a lo largo de decenas
                // de muestras, no un salto discreto) — por eso produce la
                // onda orgánica real. Se había cambiado por error a
                // `sleepStates` (código de etapa 0-3), que al ser un valor
                // por minuto sin transición gradual dibuja escalones en vez
                // de curva.
                //
                // El código real aplica, antes de graficar:
                //   valor_negativo -> 0 (clamp)
                //   valor_graficado = 4 - valor
                // así Awake (valor crudo bajo, cerca de 0 al inicio/fin de la
                // noche) queda arriba, y a mayor valor crudo (picos de la
                // curva) baja hacia Deep — exactamente lo que vimos: el
                // arreglo empieza en ~0 (Awake, antes de dormirse) y sube en
                // cada ciclo hacia valores más altos (más profundo).
                val h = 190.dp
                val stageValues = s.sleepCurve.map { v -> 4f - (v ?: 0.0).toFloat().coerceAtLeast(0f) }
                ChartYAxis(listOf("Awake", "Light", "Mid", "Deep"), h) {
                    EChartLine(
                        values = stageValues,
                        yLabels = emptyList(), xLabels = xLabels,
                        height = h, smooth = true,
                        yMin = 0f, yMax = 4f,
                        zeroIsGap = false,
                    )
                }
                ChartXAxis(xLabels)
                Spacer(Modifier.height(14.dp))
                // Denominador correcto: la suma de los 4 componentes, no
                // `durationMin` (que es solo el tiempo DORMIDO, más corto que
                // el total despierto+dormido que cubre el Sleep Cycle) — con
                // `durationMin` los 4 porcentajes sumaban 109%, no 100%.
                val stageTotal = (r.deepMin ?: 0) + (r.midMin ?: 0) + (r.lightMin ?: 0) + (r.wakeMin ?: 0)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    PctItem(pct(r.deepMin, stageTotal), "Deep")
                    PctItem(pct(r.midMin, stageTotal), "Mid")
                    PctItem(pct(r.lightMin, stageTotal), "Light")
                    PctItem(pct(r.wakeMin, stageTotal), "Awake")
                }
            }
        }
        if (s?.heartRate != null) {
            ChartCard("Heart Rate", Icons.Filled.Favorite, "${r.avgHeartRate ?: "—"} BPM") {
                // `dailyShortHumidityBase`: width 1, sin suavizado, markPoint
                // de máximo y mínimo con su valor.
                val h = 170.dp
                // Escala fija 0..150, igual que sus rótulos: así cada punto
                // cae donde dice el eje.
                ChartRangeZones("High", "Low", h) {
                    ChartYAxis(listOf("150", "100", "50", "0"), h) {
                        EChartLine(
                            values = s.heartRate.map { (it ?: 0).toFloat() },
                            yLabels = emptyList(), xLabels = xLabels,
                            height = h, markMinMax = true,
                            yMin = 0f, yMax = 150f,
                        )
                    }
                }
                ChartXAxis(xLabels)
            }
        }
        if (s?.breathRate != null) {
            ChartCard("Respiratory Rate", Icons.Filled.MonitorHeart, "${r.avgBreathRate ?: "—"} RPM") {
                val h = 170.dp
                ChartRangeZones("High", "Low", h) {
                ChartYAxis(listOf("30", "20", "10", "0"), h) {
                    EChartLine(
                        values = s.breathRate.map { (it ?: 0).toFloat() },
                        yLabels = emptyList(), xLabels = xLabels,
                        height = h, markMinMax = true,
                        yMin = 0f, yMax = 30f,
                    )
                }
                }
                ChartXAxis(xLabels)
            }
        }
        if (s?.movement != null) {
            val moveVals = s.movement.map { (it ?: 0).toFloat() }
            // Techo dinámico real (antes fijo "3,2,1,0" sin relación con los
            // datos: un pico que valía más de 3 se salía del gráfico).
            val moveTop = (moveVals.maxOrNull() ?: 3f).let { kotlin.math.ceil(it).toInt().coerceAtLeast(3) }
            ChartCard("Movement", Icons.Filled.Hotel, null) {
                AxisChart(
                    values = moveVals, color = Brand, height = 130.dp,
                    yAxisLabels = listOf(moveTop, moveTop * 2 / 3, moveTop / 3, 0).map { "$it" },
                    xAxisLabels = xLabels, spikes = true, maxOverride = moveTop.toFloat(),
                )
            }
        }
        if (s?.eTemp != null) {
            val vals = s.eTemp.map { (it ?: 0.0).toFloat() }
            val scale = niceScale(vals)
            ChartCard("Temperature", Icons.Filled.DeviceThermostat, rangeLabel(vals, "°C")) {
                val h = 170.dp
                ChartYAxis(scale.labels, h) {
                    EChartLine(
                        values = vals, yLabels = emptyList(), xLabels = xLabels, height = h,
                        markMinMax = true, yMin = scale.lo, yMax = scale.hi,
                    )
                }
                ChartXAxis(xLabels)
            }
        }
        if (s?.eHumidity != null) {
            val vals = s.eHumidity.map { (it ?: 0.0).toFloat() }
            val scale = niceScale(vals)
            ChartCard("Humidity", Icons.Filled.Opacity, rangeLabel(vals, "%")) {
                val h = 170.dp
                ChartYAxis(scale.labels, h) {
                    EChartLine(
                        values = vals, yLabels = emptyList(), xLabels = xLabels, height = h,
                        markMinMax = true, yMin = scale.lo, yMax = scale.hi,
                    )
                }
                ChartXAxis(xLabels)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AdviceCard(advice: AdviceDto?) {
    val items = advice?.items.orEmpty()
    Column(Modifier.fillMaxWidth().background(Night2, RoundedCornerShape(18.dp))) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Chat, null, tint = Mist, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                advice?.comment?.replaceFirstChar { it.uppercase() }?.let { "Sleep quality: $it" } ?: "No advice available for this night.",
                color = TextHi, style = MaterialTheme.typography.bodyLarge
            )
        }
        items.forEachIndexed { i, item ->
            HorizontalDivider(color = Hairline)
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.label ?: item.key ?: "—", color = Mist, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("-${item.deduct ?: 0}Score", color = Mist, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.HelpOutline, null, tint = Faint, modifier = Modifier.size(16.dp))
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

private fun rangeLabel(vals: List<Float>, unit: String): String? {
    val real = vals.filter { it != 0f }
    if (real.isEmpty()) return null
    val lo = real.min().roundToInt(); val hi = real.max().roundToInt()
    return if (lo == hi) "$lo$unit" else "$lo~$hi$unit"
}

private class NiceScale(val lo: Float, val hi: Float, val labels: List<String>)

/**
 * Rango con padding + sus 5 etiquetas (una por cada una de las 5 líneas de
 * rejilla que dibuja `EChartLine`, `SPLIT_NUMBER+1`). Antes esto se calculaba
 * en dos sitios independientes — las etiquetas con padding aquí, la escala
 * real de la línea con el min/max crudo dentro de `EChartLine` — así que el
 * rótulo y el punto no correspondían al mismo valor. Ahora ambos usan el
 * mismo `lo`/`hi`.
 */
private fun niceScale(vals: List<Float>): NiceScale {
    val real = vals.filter { it != 0f }
    if (real.isEmpty()) return NiceScale(0f, 1f, List(5) { "—" })
    val rawLo = real.min(); val rawHi = real.max()
    val pad = ((rawHi - rawLo) * 0.25f).coerceAtLeast(1f)
    var hi = rawHi + pad; var lo = rawLo - pad
    // 5 etiquetas enteras necesitan al menos 4 de separación total, si no se
    // repiten (p.ej. un rango real de 13~14°C redondeaba a "15,14,14,13,12").
    if (hi - lo < 4f) { val mid = (hi + lo) / 2; hi = mid + 2f; lo = mid - 2f }
    val step = (hi - lo) / 4
    val labels = (0..4).map { (hi - it * step).roundToInt().toString() }
    return NiceScale(lo, hi, labels)
}

/**
 * `timexAxis` real: `l = t.length/6`, ticks en `[0, l, 2l, 3l, 4l, 5l, length]`,
 * siempre con `e` (startTime original, sin recortar) como origen.
 *
 * El `t.length` que usa para calcular `l` NO es el arreglo completo que
 * guarda la base (385 muestras: incluye los minutos de "en cama pero
 * despierto" antes de dormirse y después de despertar) — es el arreglo ya
 * recortado de ese colchón inicial/final, cuyo tamaño coincide por
 * construcción con `duration` (minutos realmente dormidos). Verificado
 * contra la app real, misma noche: con `l = floor(duration/6) = 57` los
 * primeros 6 ticks calzan exactos contra la captura (23:05, 00:02, 00:59,
 * 01:56, 02:53, 03:50); con el largo completo (385 → l=64) no coincidían.
 */
private fun timeAxisLabels(startTime: Long?, durationMin: Int?, sampleCount: Int, intervalSec: Int = 60): List<String> {
    if (startTime == null || sampleCount <= 1) return emptyList()
    val n = (durationMin?.takeIf { it > 0 } ?: (sampleCount - 1))
    val l = n / 6
    return (0..5).map { i -> DateFmt.hhmm(startTime + i.toLong() * l * intervalSec) } +
        DateFmt.hhmm(startTime + n.toLong() * intervalSec)
}

// ---------------- WEEK / MONTH ----------------

@Composable
private fun TrendContent(period: Period, offset: Int) {
    var trend by remember(period, offset) { mutableStateOf<TrendDto?>(null) }
    var loading by remember(period, offset) { mutableStateOf(true) }
    val periodParam = if (period == Period.WEEK) "week" else "month"
    LaunchedEffect(period, offset) {
        loading = true
        try { trend = withContext(Dispatchers.IO) { Api.service.sleepReportTrend(periodParam, offset) }.data } catch (_: Exception) { trend = null }
        loading = false
    }
    if (loading) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = TextHi) }; return }
    val t = trend
    // Mes en curso: producción no muestra un parcial, rehúsa con este mensaje
    // real exacto (verificado contra la app real, pantalla "Este mes").
    if (t?.notFinished == true) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(80.dp))
            Text(
                if (period == Period.WEEK)
                    "The current week hasn't ended yet.\nCheck your sleep report on the first day of next week."
                else
                    "The current month hasn't ended yet.\nCheck your sleep report on the first day of next month.",
                color = Faint, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            )
        }
        return
    }
    val nights = t?.nightsData.orEmpty()
    // Semana: etiqueta de día de semana (LUN/MAR/...) y cada punto rotulado,
    // como en la captura real. Mes: número de día del mes (1,3,5...) y solo
    // se rotulan mínimo/máximo — con 30 puntos, rotular todos se amontona
    // (verificado contra ambas capturas reales, son dos estilos distintos).
    fun axisLabel(startTime: Long?): String = if (period == Period.WEEK)
        DateFmt.weekdayShort(startTime ?: 0).uppercase()
    else
        DateFmt.dayOfMonth(startTime ?: 0)
    // Mes real: pocas etiquetas (1,3,5,7...), no las ~30 del mes — con esa
    // cantidad cada número de 2 dígitos no cabe en su slot y se parte en 2
    // líneas (bug visto en pantalla real). Con un paso de 3 caben ~11 incluso
    // a 2 dígitos. Semana: las 7 caben todas.
    val monthStep = (nights.size / 10).coerceAtLeast(2)
    val nightLabels = nights.mapIndexed { i, n -> if (period == Period.WEEK || i % monthStep == 0) axisLabel(n.startTime) else "" }
    val labelAll = period == Period.WEEK

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        if (period == Period.MONTH && t?.qualityDistribution != null) {
            QualityRing(t.score, t.qualityDistribution)
        } else {
            ScoreRing(t?.score, "Sleep Score")
        }
        Spacer(Modifier.height(24.dp))
        // Título real del week-view / month-view (claves i18n
        // your_lastweek_sleep_quality / your_lastmonth_sleep_quality) y los
        // estados vacíos reales (empty_weekly_report / empty_monthly_report).
        val titleKey = if (period == Period.WEEK) "Your Sleep Quality Last Week" else "Your Sleep Quality Last Month"
        val emptyKey = if (period == Period.WEEK) "No weekly report so far" else "No monthly report so far"
        Text(
            if (t?.score != null) titleKey else emptyKey,
            color = TextHi, style = MaterialTheme.typography.titleLarge,
        )
        t?.nights?.takeIf { it > 0 }?.let {
            Spacer(Modifier.height(4.dp))
            Text("$it nights recorded", color = Faint, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(18.dp))

        // 3 renglones navegables reales ("Irregularidad del sueño", "Promedio
        // de veces que te levantaste de la cama"/"Sueño insuficiente",
        // "Riesgos del sueño") — en producción abren un modal educativo con
        // contenido fijo que no tenemos transcrito; aquí abren un resumen con
        // el dato real que sí tenemos, marcado como tal, no el texto original.
        if (t?.score != null) {
            val bedtimes = nights.mapNotNull { it.fellAsleepTime }
            val irregularityMin = bedtimeSpreadMin(bedtimes)
            DrilldownRow("Irregularidad del sueño") {
                if (irregularityMin != null) "Tus horas de dormir variaron hasta ${hmUpper(irregularityMin)} entre noches."
                else "No hay suficientes noches para calcular esto."
            }
            if (period == Period.WEEK) {
                DrilldownRow("Promedio de veces que te levantaste de la cama") {
                    "Promedio: ${avgOfInts(nights.mapNotNull { it.outOfBedCount })?.let { "$it veces/noche" } ?: "—"}."
                }
            } else {
                DrilldownRow("Sueño insuficiente") {
                    val short = nights.count { (it.durationMin ?: 0) in 1..359 }
                    "Dormiste menos de 6 horas en $short de ${nights.size} noches."
                }
            }
            DrilldownRow("Riesgos del sueño") {
                val apneaAvg = t.avgApneaMin?.takeIf { it > 0 }
                if (apneaAvg != null) "Hubo apnea en algunas noches de este período (promedio ${apneaAvg}M)."
                else "No se detectaron riesgos relevantes en este período."
            }
        }

        Spacer(Modifier.height(4.dp))
        val points = t?.points.orEmpty().map { (it.score ?: 0).toFloat() }
        val scoreStep = (points.size / 10).coerceAtLeast(2)
        val xLabels = t?.points.orEmpty().mapIndexed { i, p -> if (period == Period.WEEK || i % scoreStep == 0) axisLabel(p.startTime) else "" }
        if (points.isNotEmpty()) {
            ChartCard("Sleep Score", Icons.AutoMirrored.Filled.ShowChart, if (t?.score != null) "${t.score} Score" else null) {
                AxisChart(points, Indigo, height = 150.dp, yAxisLabels = listOf("100", "50", "0"), xAxisLabels = xLabels, showDots = true, labelAll = labelAll, yMin = 0f, yMax = 100f)
            }
        }

        // Gráfico de barras por noche de tiempo dormido — presente en la app
        // real ("Tiempo de sueño") bajo el de tendencia de score. Eje Y en
        // HORAS como la captura real (icono "H" arriba del eje), no minutos.
        val durationsH = nights.map { (it.durationMin ?: 0) / 60f }
        if (durationsH.any { it > 0f }) {
            ChartCard("Time Asleep", Icons.Filled.Hotel, t?.avgTimeAsleepMin?.let { hmUpper(it) }) {
                BarChart(durationsH, Indigo, height = 140.dp, xAxisLabels = nightLabels)
            }
        }

        // Gráfico de barras agrupadas: eficiencia de sueño % y sueño profundo
        // % por noche — "Eficacia del sueño / Sueño profundo". `sleepEfficiencyPct`
        // es una aproximación propia, no verificada 1:1 — ver backend.
        val effPct = nights.map { (it.sleepEfficiencyPct ?: 0).toFloat() }
        val deepPct = nights.map { (it.deepSleepPct ?: 0).toFloat() }
        if (effPct.any { it > 0 } || deepPct.any { it > 0 }) {
            ChartCard("Sleep Efficiency", Icons.Filled.Hotel, null) {
                Row(Modifier.padding(bottom = 10.dp)) {
                    LegendDot(Indigo, "Sleep Efficiency")
                    Spacer(Modifier.width(16.dp))
                    LegendDot(IndigoSoft, "Deep Sleep")
                }
                GroupedBarChart(effPct, deepPct, Indigo, IndigoSoft, height = 140.dp, xAxisLabels = nightLabels)
            }
        }

        // "Patrones de sueño" — hora de dormirse / hora de levantarse por
        // noche, dos líneas. Ausente antes por completo.
        val fellAsleep = nights.map { it.fellAsleepTime }
        val wokeUp = nights.map { it.wokeUpTime }
        if (fellAsleep.any { it != null }) {
            ChartCard("Sleep Patterns", Icons.AutoMirrored.Filled.ShowChart, sleepPatternLabel(fellAsleep)) {
                Row(Modifier.padding(bottom = 10.dp)) {
                    LegendDot(IndigoSoft, "Fall Asleep")
                    Spacer(Modifier.width(16.dp))
                    LegendDot(Indigo, "Wake Up")
                }
                SleepPatternChart(fellAsleep, wokeUp, height = 150.dp, xAxisLabels = nightLabels, labelAll = labelAll)
            }
        }

        // Promedio de frecuencia cardíaca / respiratoria por noche.
        val hr = nights.map { (it.avgHeartRate ?: 0).toFloat() }
        if (hr.any { it > 0 }) {
            ChartCard("Heart Rate", Icons.Filled.Favorite, t?.let { avgOfInts(nights.mapNotNull { n -> n.avgHeartRate }) }?.let { "$it BPM" }) {
                AxisChart(hr, Indigo, height = 140.dp, yAxisLabels = listOf("150", "100", "50", "0"), xAxisLabels = nightLabels, showDots = true, labelAll = labelAll, yMin = 0f, yMax = 150f)
            }
        }
        val rr = nights.map { (it.avgBreathRate ?: 0).toFloat() }
        if (rr.any { it > 0 }) {
            ChartCard("Respiratory Rate", Icons.Filled.MonitorHeart, avgOfInts(nights.mapNotNull { it.avgBreathRate })?.let { "$it RPM" }) {
                AxisChart(rr, Indigo, height = 140.dp, yAxisLabels = listOf("30", "20", "10", "0"), xAxisLabels = nightLabels, showDots = true, labelAll = labelAll, yMin = 0f, yMax = 30f)
            }
        }

        // "Promedio de tiempo en la cama" — ver nota en el backend sobre qué
        // columna real se usa (aproximación, no verificada).
        val bedMin = nights.map { (it.timeInBedMin ?: 0).toFloat() }
        if (bedMin.any { it > 0 }) {
            ChartCard("Time in Bed", Icons.Filled.CalendarToday, avgOfInts(nights.mapNotNull { it.timeInBedMin })?.let { "${it}M" }) {
                BarChart(bedMin, Indigo, height = 120.dp, xAxisLabels = nightLabels)
            }
        }

        // "Despertarse" (veces) / "Levantarse de la cama" (veces).
        val wakeTimes = nights.map { (it.wakeTimes ?: 0).toFloat() }
        if (wakeTimes.any { it > 0 }) {
            ChartCard("Wake-ups", Icons.Filled.Hotel, null) {
                AxisChart(wakeTimes, Indigo, height = 130.dp, yAxisLabels = listOf("6", "4", "2", "0"), xAxisLabels = nightLabels, showDots = true, labelAll = labelAll, zeroIsGap = false, yMin = 0f, yMax = 6f)
            }
        }
        val outOfBed = nights.map { (it.outOfBedCount ?: 0).toFloat() }
        if (outOfBed.any { it > 0 }) {
            ChartCard("Times Out of Bed", Icons.Filled.Hotel, null) {
                AxisChart(outOfBed, Indigo, height = 130.dp, yAxisLabels = listOf("6", "4", "2", "0"), xAxisLabels = nightLabels, showDots = true, labelAll = labelAll, zeroIsGap = false, yMin = 0f, yMax = 6f)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Variación (máx-mín) de la hora de dormirse entre noches, en minutos —
 *  usada por el drill-down "Irregularidad del sueño". Resuelve el cruce de
 *  medianoche comparando contra el mediodía del mismo día, como `SleepPatternChart`. */
private fun bedtimeSpreadMin(epochs: List<Long>): Int? {
    if (epochs.size < 2) return null
    val mins = epochs.map { minutesFromNoon(it) }
    return ((mins.max() - mins.min())).toInt()
}

private fun avgOfInts(vals: List<Int>): Int? {
    val real = vals.filter { it > 0 }
    if (real.isEmpty()) return null
    return real.sum() / real.size
}

@Composable
private fun DrilldownRow(title: String, detail: () -> String) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = TextHi, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Faint)
        }
        HorizontalDivider(color = Hairline)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text("OK") } },
            title = { Text(title) },
            text = { Text(detail()) },
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Faint, style = MaterialTheme.typography.labelMedium)
    }
}

// ---------------- Shared building blocks ----------------

@Composable
private fun ScoreRing(score: Int?, label: String) {
    val valid = score != null && score > 0
    Box(Modifier.fillMaxWidth(), Alignment.Center) {
        Canvas(Modifier.size(210.dp)) {
            val sw = 3.dp.toPx()
            drawCircle(Hairline, radius = size.minDimension / 2, style = Stroke(sw, cap = StrokeCap.Round))
            if (valid) {
                val sweep = (score!!.coerceIn(0, 100) / 100f) * 300f
                drawArc(Indigo, -240f, sweep, false, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (valid) "$score" else "—", color = TextHi, fontSize = 56.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Light)
            Text(label, color = Mist, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Anillo multicolor de distribución de calidad — solo Month en la app real
 * (captura: 0% Excelente/40% Buena/30% Media/26% Mala/4% No registrado, con
 * arcos de colores distintos en vez de un solo progreso azul). Los cortes de
 * puntaje que generan estos % son una hipótesis propia — ver backend.
 */
@Composable
private fun QualityRing(score: Int?, dist: QualityDistributionDto) {
    val valid = score != null && score > 0
    val segments = listOf(
        (dist.excellent ?: 0) to Color(0xFF4FC3F7),
        (dist.good ?: 0) to Color(0xFF2ED6A1),
        (dist.fair ?: 0) to Color(0xFF9C8CF0),
        (dist.poor ?: 0) to Color(0xFFE24B6B),
        (dist.unrecorded ?: 0) to Color(0xFFFFFFFF),
    )
    Box(Modifier.fillMaxWidth(), Alignment.Center) {
        Canvas(Modifier.size(210.dp)) {
            val sw = 5.dp.toPx()
            var startAngle = -90f
            segments.forEach { (pct, color) ->
                val sweep = pct / 100f * 360f
                if (sweep > 0f) {
                    drawArc(color, startAngle, sweep, false, style = Stroke(sw, cap = StrokeCap.Round))
                    startAngle += sweep
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (valid) "$score" else "—", color = TextHi, fontSize = 56.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Light)
            Text("Sleep Score", color = Mist, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Spacer(Modifier.height(18.dp))
    val labels = listOf("Excellent" to dist.excellent, "Good" to dist.good, "Fair" to dist.fair, "Poor" to dist.poor, "Unrecorded" to dist.unrecorded)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        labels.take(3).forEachIndexed { i, (label, pct) -> QualityStat(pct ?: 0, label, segments[i].second) }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        labels.drop(3).forEachIndexed { i, (label, pct) -> QualityStat(pct ?: 0, label, segments[i + 3].second) }
    }
}

@Composable
private fun QualityStat(pct: Int, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$pct%", color = TextHi, style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Spacer(Modifier.width(4.dp))
            Text(label, color = Faint, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun StatCol(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = TextHi, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(label, color = Faint, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PctItem(pct: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$pct", color = TextHi, style = MaterialTheme.typography.headlineMedium)
            Text("%", color = TextHi, style = MaterialTheme.typography.bodyMedium)
        }
        Text(label, color = Faint, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ChartCard(title: String, icon: ImageVector, headerValue: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).background(Night2, RoundedCornerShape(18.dp)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Mist, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, color = TextHi, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            headerValue?.let { Text(it, color = TextHi, style = MaterialTheme.typography.titleMedium) }
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

/**
 * Gráfica de línea con eje Y (etiquetas fijas a la izquierda), eje X (tiempos
 * reales abajo), zonas de color opcionales a la derecha (High/Normal/Low), y
 * marcadores de mínimo/máximo con su valor — réplica de los gráficos reales
 * (Sleep Cycle / Heart Rate / Respiratory Rate / Movement / Temperature /
 * Humidity).
 */
@Composable
private fun AxisChart(
    values: List<Float>, color: Color, height: Dp,
    yAxisLabels: List<String>, xAxisLabels: List<String>,
    rightZoneLabels: List<Pair<String, Color>>? = null,
    showDots: Boolean = false, spikes: Boolean = false, unit: String = "",
    /** Techo fijo para que las barras/picos escalen contra el mismo valor que
     *  muestran las etiquetas del eje Y (si se omite, escala al máximo real
     *  de `values`, que puede no coincidir con un eje redondeado). */
    maxOverride: Float? = null,
    /** true: rotula CADA punto (captura real de Week, ≤7 puntos). false:
     *  solo mínimo/máximo (captura real de Month, 30 puntos — se amontonan). */
    labelAll: Boolean = false,
    /** false para series donde 0 es un valor real (p.ej. "0 despertares" en
     *  Wake-ups/Times Out of Bed) y no debe cortar la línea como si fuera un
     *  hueco sin lectura (caso de Heart/Breath Rate, donde 0 = sin señal). */
    zeroIsGap: Boolean = true,
    /** Rango fijo del eje Y, el mismo que describen `yAxisLabels` (p.ej. 0..150
     *  para "150/100/50/0"). Sin esto, `LineChartCanvas` escalaba la línea
     *  contra el min/max REAL de los datos de esa semana/mes — las etiquetas
     *  decían un rango y el punto se dibujaba en otro, así que un valor como
     *  125 podía terminar a la altura visual de la línea de "100" o "150"
     *  según cómo cayera el min/max de esos días, no en su posición real. */
    yMin: Float? = null,
    yMax: Float? = null,
) {
    // Las etiquetas del eje Y van SUPERPUESTAS sobre el canvas (igual que
    // `ChartYAxis`/`EChartLine` del Day view), no en una columna aparte que le
    // resta ancho al gráfico con un `Row` + `weight`. Antes el trazo real solo
    // ocupaba el ancho sobrante tras descontar esas columnas fijas; ahora el
    // canvas usa el 100% del ancho de la tarjeta y los márgenes para las
    // etiquetas se reservan DENTRO del dibujo (leftMargin/rightMargin).
    val leftMargin = 32.dp
    val rightMargin = if (rightZoneLabels != null) 52.dp else 0.dp
    Box(Modifier.fillMaxWidth().height(height)) {
        GridLines(yAxisLabels.size, height)
        if (spikes) SpikeChart(values, color, height, maxOverride, leftMargin, rightMargin)
        else LineChartCanvas(values, color, height, showDots, unit, labelAll, zeroIsGap, yMin, yMax, leftMargin, rightMargin)
        Column(Modifier.width(leftMargin).height(height), verticalArrangement = Arrangement.SpaceBetween) {
            yAxisLabels.forEach { Text(it, color = Faint, fontSize = 10.sp) }
        }
        if (rightZoneLabels != null) {
            Column(
                Modifier.align(Alignment.CenterEnd).width(rightMargin).height(height),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                rightZoneLabels.forEach { (label, c) -> Text(label, color = c, fontSize = 10.sp) }
            }
        }
    }
    if (xAxisLabels.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(start = leftMargin + 4.dp, end = rightMargin, top = 6.dp)) {
            xAxisLabels.forEach { Text(it, color = Faint, fontSize = 9.sp, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f), textAlign = TextAlign.Center) }
        }
    }
}

/** Líneas de referencia horizontales en cada valor del eje Y — presentes en
 * todas las gráficas reales (confirmado contra la app real instalada), ausentes
 * en la réplica anterior. */
@Composable
private fun GridLines(rows: Int, height: Dp) {
    if (rows < 2) return
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val step = size.height / (rows - 1)
        for (i in 0 until rows) {
            val y = i * step
            drawLine(Hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
    }
}

@Composable
private fun LineChartCanvas(
    values: List<Float>, color: Color, height: Dp, showDots: Boolean, unit: String,
    labelAll: Boolean = false, zeroIsGap: Boolean = true,
    yMin: Float? = null, yMax: Float? = null,
    // Margen reservado DENTRO del canvas (que ya ocupa el 100% del ancho de
    // la tarjeta) para que las etiquetas del eje Y/zonas de color, dibujadas
    // encima, no tapen el trazo — en vez de restarle ancho al Canvas desde
    // afuera con un Row.
    leftMargin: Dp = 0.dp, rightMargin: Dp = 0.dp,
) {
    if (values.isEmpty()) { Box(Modifier.fillMaxWidth().height(height)); return }
    val real = if (zeroIsGap) values.filter { it != 0f } else values
    // Si el llamador fija yMin/yMax (el mismo rango que describen las
    // etiquetas del eje), se usa ESE rango, no el min/max real de los datos
    // — si no, el eje dice un rango y la línea se dibuja en otro.
    val lo = yMin ?: (if (zeroIsGap) (real.minOrNull() ?: 0f) else 0f)
    val hi = yMax ?: (real.maxOrNull() ?: 1f)
    val range = (hi - lo).let { if (it == 0f) 1f else it }
    val minIdx = values.indices.minByOrNull { if (zeroIsGap && values[it] == 0f) Float.MAX_VALUE else values[it] } ?: 0
    val maxIdx = values.indices.maxByOrNull { values[it] } ?: 0

    val textMeasurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val l = leftMargin.toPx(); val r = size.width - rightMargin.toPx()
        val w = (r - l).coerceAtLeast(1f)
        val stepX = w / (values.size - 1).coerceAtLeast(1)
        val path = androidx.compose.ui.graphics.Path()
        // Margen vertical para que los marcadores min/max no choquen con el
        // título de la tarjeta (arriba) ni con la tarjeta siguiente (abajo).
        val inset = 16.dp.toPx()
        val drawHeight = size.height - inset * 2
        fun yOf(v: Float) = inset + drawHeight - ((v - lo) / range) * drawHeight
        // Los 0 son huecos sin lectura (fuera de cama/sensor sin señal) SOLO
        // cuando `zeroIsGap` — en series como "veces que despertaste" un 0 es
        // un dato real (esa noche no se despertó) y debe dibujarse, no cortar
        // la línea.
        var drawing = false
        values.forEachIndexed { i, v ->
            if (zeroIsGap && v == 0f) { drawing = false; return@forEachIndexed }
            val x = l + i * stepX; val y = yOf(v)
            if (!drawing) { path.moveTo(x, y); drawing = true } else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))

        if (showDots) {
            // Azul de marca, no blanco: las tarjetas son claras ahora (antes
            // esto se diseñó para el tema oscuro original), blanco sobre
            // fondo claro era invisible.
            fun dot(idx: Int, label: String, above: Boolean) {
                val x = l + idx * stepX; val y = yOf(values[idx])
                drawCircle(Surface, radius = 4.dp.toPx(), center = Offset(x, y))
                drawCircle(color, radius = 2.5.dp.toPx(), center = Offset(x, y))
                val ty = if (above) y - 10.dp.toPx() else y + 18.dp.toPx()
                drawTextCentered(textMeasurer, label, x.coerceIn(l + 14.dp.toPx(), r - 14.dp.toPx()), ty, Color(0xFF0055B8), 11.sp)
            }
            if (labelAll) {
                values.forEachIndexed { i, v -> if (!zeroIsGap || v != 0f) dot(i, "${v.roundToInt()}", i != minIdx) }
            } else {
                dot(maxIdx, "${values[maxIdx].roundToInt()}", true)
                dot(minIdx, "${values[minIdx].roundToInt()}", false)
            }
        }
    }
}

@Composable
private fun SpikeChart(
    values: List<Float>, color: Color, height: Dp, maxOverride: Float? = null,
    leftMargin: Dp = 0.dp, rightMargin: Dp = 0.dp,
) {
    if (values.isEmpty()) { Box(Modifier.fillMaxWidth().height(height)); return }
    val maxV = (maxOverride ?: values.maxOrNull() ?: 1f).coerceAtLeast(1f)
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val l = leftMargin.toPx(); val r = size.width - rightMargin.toPx()
        val w = (r - l).coerceAtLeast(1f)
        val stepX = w / values.size
        values.forEachIndexed { i, v ->
            if (v <= 0f) return@forEachIndexed
            val x = l + i * stepX + stepX / 2
            val h = (v / maxV) * size.height
            drawLine(color, Offset(x, size.height), Offset(x, size.height - h), strokeWidth = 2.dp.toPx())
        }
    }
}

/** Techo "lindo" para un eje Y de barras (0, top/3, top*2/3, top) — redondea
 *  a un paso razonable según la magnitud, en vez de dejar el eje sin
 *  etiquetas (gap señalado: BarChart/GroupedBarChart no tenían eje Y). */
private fun niceBarTop(maxV: Float): Int {
    val raw = maxV.coerceAtLeast(1f)
    val step = when { raw <= 10f -> 2; raw <= 30f -> 5; raw <= 100f -> 20; raw <= 300f -> 60; else -> 120 }
    return (kotlin.math.ceil(raw / step) * step).toInt().coerceAtLeast(step)
}

private val BarLeftMargin = 32.dp

@Composable
private fun BarYAxis(top: Int, height: Dp) {
    Column(Modifier.width(BarLeftMargin).height(height), verticalArrangement = Arrangement.SpaceBetween) {
        listOf(top, top * 2 / 3, top / 3, 0).forEach { Text("$it", color = Faint, fontSize = 10.sp) }
    }
}

/** Barras por noche (una serie) — "Tiempo de sueño" de la app real. */
@Composable
private fun BarChart(values: List<Float>, color: Color, height: Dp, xAxisLabels: List<String>) {
    val top = niceBarTop(values.maxOrNull() ?: 1f)
    Column {
        Box(Modifier.fillMaxWidth().height(height)) {
            GridLines(4, height)
            Canvas(Modifier.fillMaxWidth().height(height)) {
                val l = BarLeftMargin.toPx(); val w = size.width - l
                val stepX = w / values.size
                val barW = (stepX * 0.45f).coerceAtMost(18.dp.toPx())
                values.forEachIndexed { i, v ->
                    if (v <= 0f) return@forEachIndexed
                    val cx = l + i * stepX + stepX / 2
                    val h = (v / top) * size.height
                    drawLine(
                        color, Offset(cx, size.height), Offset(cx, size.height - h),
                        strokeWidth = barW, cap = StrokeCap.Round,
                    )
                }
            }
            BarYAxis(top, height)
        }
        if (xAxisLabels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = BarLeftMargin + 4.dp, top = 6.dp)) {
                xAxisLabels.forEach { Text(it, color = Faint, fontSize = 9.sp, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f), textAlign = TextAlign.Center) }
            }
        }
    }
}

/** Barras agrupadas (dos series) — "Eficacia del sueño / Sueño profundo". */
@Composable
private fun GroupedBarChart(
    a: List<Float>, b: List<Float>, colorA: Color, colorB: Color,
    height: Dp, xAxisLabels: List<String>,
) {
    val top = niceBarTop(listOf(a.maxOrNull() ?: 0f, b.maxOrNull() ?: 0f).max())
    Column {
        Box(Modifier.fillMaxWidth().height(height)) {
            GridLines(4, height)
            Canvas(Modifier.fillMaxWidth().height(height)) {
                val l = BarLeftMargin.toPx(); val w = size.width - l
                val n = a.size.coerceAtLeast(1)
                val stepX = w / n
                val barW = (stepX * 0.22f).coerceAtMost(10.dp.toPx())
                val gap = 4.dp.toPx()
                for (i in a.indices) {
                    val cx = l + i * stepX + stepX / 2
                    val va = a[i]; val vb = b.getOrElse(i) { 0f }
                    if (va > 0f) {
                        val h = (va / top) * size.height
                        drawLine(colorA, Offset(cx - gap, size.height), Offset(cx - gap, size.height - h), strokeWidth = barW, cap = StrokeCap.Round)
                    }
                    if (vb > 0f) {
                        val h = (vb / top) * size.height
                        drawLine(colorB, Offset(cx + gap, size.height), Offset(cx + gap, size.height - h), strokeWidth = barW, cap = StrokeCap.Round)
                    }
                }
            }
            BarYAxis(top, height)
        }
        if (xAxisLabels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = BarLeftMargin + 4.dp, top = 6.dp)) {
                xAxisLabels.forEach { Text(it, color = Faint, fontSize = 9.sp, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f), textAlign = TextAlign.Center) }
            }
        }
    }
}

/** Minutos de distancia respecto al mediodía ([-720, 720)) — evita que una
 *  hora de dormir que cruza medianoche (23:xx → 00:xx) se vea como un salto
 *  gigante en la gráfica; la mayoría de horas de dormir/despertar caen lejos
 *  del mediodía, así que partir la escala ahí es seguro. */
private fun minutesFromNoon(epochSec: Long): Int {
    val mins = DateFmt.minutesOfDay(epochSec)
    return ((mins - 720 + 1440) % 1440) - 720
}

/** "Irregular"/"Regular" — real la app muestra esta palabra junto al título
 *  de "Patrones de sueño"; aproximación propia por varianza de hora de
 *  dormir (>60min de spread = Irregular), no el algoritmo real verificado. */
private fun sleepPatternLabel(fellAsleep: List<Long?>): String? {
    val real = fellAsleep.filterNotNull()
    if (real.size < 2) return null
    val mins = real.map { minutesFromNoon(it) }
    return if (mins.max() - mins.min() > 60) "Irregular" else "Regular"
}

/** "Patrones de sueño": dos líneas (hora de dormir / hora de levantarse) por
 *  noche, con la hora real como etiqueta — ausente antes por completo. */
@Composable
private fun SleepPatternChart(
    fellAsleep: List<Long?>, wokeUp: List<Long?>, height: Dp,
    xAxisLabels: List<String>, labelAll: Boolean,
) {
    val n = fellAsleep.size
    if (n == 0) { Box(Modifier.fillMaxWidth().height(height)); return }
    val fa = fellAsleep.map { it?.let { t -> minutesFromNoon(t).toFloat() } }
    val wu = wokeUp.map { it?.let { t -> minutesFromNoon(t).toFloat() } }
    val real = (fa + wu).filterNotNull()
    val lo = (real.minOrNull() ?: -60f) - 20f; val hi = (real.maxOrNull() ?: 60f) + 20f
    val range = (hi - lo).let { if (it == 0f) 1f else it }

    val textMeasurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val stepX = size.width / (n - 1).coerceAtLeast(1)
        fun yOf(v: Float) = size.height - ((v - lo) / range) * size.height
        fun drawSeries(vals: List<Float?>, color: Color) {
            val path = androidx.compose.ui.graphics.Path()
            var drawing = false
            vals.forEachIndexed { i, v ->
                if (v == null) { drawing = false; return@forEachIndexed }
                val x = i * stepX; val y = yOf(v)
                if (!drawing) { path.moveTo(x, y); drawing = true } else path.lineTo(x, y)
                drawCircle(color, radius = 2.5.dp.toPx(), center = Offset(x, y))
            }
            drawPath(path, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        }
        drawSeries(fa, IndigoSoft)
        drawSeries(wu, Indigo)

        if (labelAll) {
            fun label(epoch: Long?, v: Float?, i: Int, above: Boolean, c: Color) {
                if (epoch == null || v == null) return
                val x = i * stepX; val y = yOf(v) + if (above) -8.dp.toPx() else 16.dp.toPx()
                drawTextCentered(textMeasurer, clock(epoch), x.coerceIn(20.dp.toPx(), size.width - 20.dp.toPx()), y, c, 10.sp)
            }
            fellAsleep.forEachIndexed { i, e -> label(e, fa[i], i, true, Color(0xFF00408C)) }
            wokeUp.forEachIndexed { i, e -> label(e, wu[i], i, false, Color(0xFF0055B8)) }
        }
    }
    if (xAxisLabels.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            xAxisLabels.forEach { Text(it, color = Faint, fontSize = 9.sp, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f), textAlign = TextAlign.Center) }
        }
    }
}

private fun hmUpper(min: Int?): String {
    if (min == null || min <= 0) return "—"
    return if (min >= 60) "${min / 60}H${min % 60}M" else "${min}M"
}

// CORRECCIÓN (2026-11-05): `startTime` SÍ es un epoch UTC real. La conclusión
// anterior ("es hora local guardada sin offset, hay que formatear en UTC")
// era falsa — coincidía por casualidad porque esa prueba se hizo en el
// EMULADOR, que corre en GMT (UTC+0), así que local==UTC ahí y el error no se
// notaba. Verificado hoy contra producción en el celular real (America/
// Guayaquil, UTC-5), misma noche: el gráfico real arranca a las 23:05, y
// epoch 1793851515 en UTC puro da 04:05 — exactamente 5h de diferencia, el
// propio offset de Ecuador. O sea: hay que usar la zona horaria del
// DISPOSITIVO (conversión real), no forzar UTC. Por eso aquí ya no se fija
// ningún TimeZone: `DateFmt` usa la zona local del dispositivo, que es
// la conversión correcta.
private fun clock(epochSec: Long?): String {
    if (epochSec == null || epochSec < 1_000_000_000L) return "—"
    return DateFmt.hhmm(epochSec)
}

private fun pct(part: Int?, total: Int?): Int {
    if (part == null || total == null || total <= 0) return 0
    return ((part.toFloat() / total) * 100).toInt()
}
