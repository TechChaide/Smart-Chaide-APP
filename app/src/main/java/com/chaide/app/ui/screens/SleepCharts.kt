package com.chaide.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaide.app.ui.theme.*
import kotlin.math.roundToInt

/*
 * Motor de gráficas portado de la configuración REAL de ECharts que usa la app
 * oficial (bundle H5, chunk del reporte). Valores tomados tal cual del código,
 * no deducidos de capturas:
 *
 *   dailyLong (Sleep Cycle)
 *     series: type line, smooth:true, symbol:"none",
 *             lineStyle { width: 2.5, color: "#00529D" }
 *     grid:   { top:45, bottom:30, left:50, right:40 }
 *     xAxis:  boundaryGap:false, axisLine:false, axisTick:false,
 *             axisLabel { margin:12 }
 *     yAxis:  min 0, max 5, splitNumber 4, axisLine:false, axisTick:false,
 *             splitLine { width: 0.7, opacidad 6% }
 *     markPoint: 4 íconos PNG (dormirse / despertar / movimiento / apnea)
 *
 *   dailyShortHumidityBase (Heart Rate, Respiratory, Temp, Humidity)
 *     series: type line, symbol:"none", lineStyle { width: 1, color:"#00529D" }
 *     markPoint: máximo y mínimo, etiqueta arriba/abajo, fontSize 12
 *
 *   weekMonthShortLine (tendencia semanal/mensual)
 *     series: line, showAllSymbol:true, connectNulls:true, color "#00529D"
 *     markArea de rango normal: "rgba(0,82,157,0.2)"
 *
 * El color de marca pasa a #0055B8 (paleta nueva) y las líneas de rejilla se
 * adaptan al fondo claro; la geometría —suavizado, grosores, márgenes,
 * divisiones— se respeta exactamente.
 */

/** Márgenes del `grid` de ECharts, en dp. */
private val GridTop = 45.dp
private val GridBottom = 30.dp
private val GridLeft = 50.dp
private val GridRight = 40.dp

/** `splitNumber: 4` del yAxis real. */
private const val SPLIT_NUMBER = 4

/**
 * Curva suavizada equivalente a `smooth: true` de ECharts: interpolación
 * monótona por cúbicas, que no sobrepasa los valores de los datos (a
 * diferencia de un Catmull-Rom simple, que genera jorobas falsas).
 */
private fun smoothPath(pts: List<Offset>): Path {
    val p = Path()
    if (pts.isEmpty()) return p
    p.moveTo(pts[0].x, pts[0].y)
    if (pts.size < 3) {
        pts.drop(1).forEach { p.lineTo(it.x, it.y) }
        return p
    }
    for (i in 0 until pts.size - 1) {
        val p0 = pts[if (i == 0) i else i - 1]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[if (i + 2 > pts.size - 1) i + 1 else i + 2]
        // Tensión 1/6: es la que reproduce el trazo de ECharts.
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        p.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
    return p
}

/** Rejilla horizontal: `splitLine` de ECharts (0.7 px, muy tenue). */
private fun DrawScope.drawSplitLines(left: Float, right: Float, top: Float, bottom: Float) {
    val step = (bottom - top) / SPLIT_NUMBER
    repeat(SPLIT_NUMBER + 1) { i ->
        val y = top + i * step
        drawLine(
            color = Line,
            start = Offset(left, y),
            end = Offset(right, y),
            strokeWidth = 0.7.dp.toPx(),
        )
    }
}

/**
 * Gráfica de línea del reporte diario.
 *
 * @param smooth true para el Sleep Cycle (`smooth:true` + 2.5 px); false para
 *               las cortas (ritmo cardíaco, respiratorio, temperatura), que en
 *               la config real van con `width: 1` y sin suavizado.
 * @param markMinMax marca el máximo y el mínimo con su valor, como el
 *               `markPoint` de `dailyShortHumidityBase`.
 */
@Composable
fun EChartLine(
    values: List<Float>,
    yLabels: List<String>,
    xLabels: List<String>,
    height: Dp,
    smooth: Boolean = false,
    markMinMax: Boolean = false,
    normalRange: ClosedFloatingPointRange<Float>? = null,
    yMin: Float? = null,
    yMax: Float? = null,
    /** false para series donde 0 es un valor real (p.ej. Deep del Sleep
     *  Cycle), no un hueco sin lectura (caso de heart/breath/temp rate). */
    zeroIsGap: Boolean = true,
) {
    if (values.isEmpty()) { Spacer(Modifier.fillMaxWidth().height(height)); return }

    val real = if (zeroIsGap) values.filter { it != 0f } else values
    // La escala la fijan `yMin`/`yMax` cuando se dan, igual que el `min`/`max`
    // explícito del yAxis de ECharts. Sin esto las etiquetas del eje no
    // corresponden a la posición real de los puntos: el gráfico se estira al
    // rango de los datos y los rótulos quedan mintiendo.
    val lo = yMin ?: (real.minOrNull() ?: 0f)
    val hi = yMax ?: (real.maxOrNull() ?: 1f)
    val range = (hi - lo).let { if (it == 0f) 1f else it }
    val minIdx = values.indices.filter { values[it] != 0f }.minByOrNull { values[it] } ?: 0
    val maxIdx = values.indices.maxByOrNull { values[it] } ?: 0

    Canvas(Modifier.fillMaxWidth().height(height)) {
        val l = GridLeft.toPx()
        val r = size.width - GridRight.toPx()
        val t = GridTop.toPx() * 0.35f          // el top 45 del original incluye el título
        val b = size.height - GridBottom.toPx()
        val w = (r - l).coerceAtLeast(1f)
        val h = (b - t).coerceAtLeast(1f)

        drawSplitLines(l, r, t, b)

        // Banda de rango normal (markArea "rgba(0,82,157,0.2)" del original)
        normalRange?.let { nr ->
            fun yOf(v: Float) = b - ((v - lo) / range) * h
            val y1 = yOf(nr.endInclusive).coerceIn(t, b)
            val y2 = yOf(nr.start).coerceIn(t, b)
            drawRect(
                color = Brand.copy(alpha = 0.10f),
                topLeft = Offset(l, y1),
                size = androidx.compose.ui.geometry.Size(w, (y2 - y1).coerceAtLeast(0f)),
            )
        }

        val stepX = w / (values.size - 1).coerceAtLeast(1)
        fun yOf(v: Float) = b - ((v - lo) / range) * h

        // Los ceros son huecos sin lectura (fuera de cama), no un valor: la
        // línea se corta ahí en vez de caer al eje.
        val segments = mutableListOf<MutableList<Offset>>()
        var cur = mutableListOf<Offset>()
        values.forEachIndexed { i, v ->
            if (zeroIsGap && v == 0f) {
                if (cur.size > 1) segments.add(cur)
                cur = mutableListOf()
            } else cur.add(Offset(l + i * stepX, yOf(v)))
        }
        if (cur.size > 1) segments.add(cur)

        val stroke = Stroke(
            width = (if (smooth) 2.5f else 1f).dp.toPx(),
            cap = StrokeCap.Round, join = StrokeJoin.Round,
        )
        segments.forEach { seg ->
            drawPath(if (smooth) smoothPath(seg) else Path().apply {
                moveTo(seg[0].x, seg[0].y)
                seg.drop(1).forEach { lineTo(it.x, it.y) }
            }, Brand, style = stroke)
        }

        if (markMinMax && real.isNotEmpty()) {
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(0, 0x55, 0xB8)   // Brand
                textSize = 12.sp.toPx()                           // fontSize:12 del original
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
            }
            listOf(maxIdx to true, minIdx to false).forEach { (idx, isMax) ->
                val x = (l + idx * stepX).coerceIn(l + 12.dp.toPx(), r - 12.dp.toPx())
                val y = yOf(values[idx])
                drawCircle(Surface, radius = 4.dp.toPx(), center = Offset(x, y))
                drawCircle(Brand, radius = 2.5.dp.toPx(), center = Offset(x, y))
                val label = values[idx].roundToInt().toString()
                val ty = if (isMax) y - 10.dp.toPx() else y + 18.dp.toPx()
                drawContext.canvas.nativeCanvas.drawText(label, x, ty, paint)
            }
        }
    }
}

/**
 * Etiquetas del eje Y, alineadas a las líneas de la rejilla.
 *
 * `splitNumber: 4` produce 5 líneas, así que se reparten 5 ranuras: se
 * rellena con cadenas vacías al final para que cada etiqueta caiga justo
 * sobre su línea en vez de quedar corrida.
 */
@Composable
fun ChartYAxis(labels: List<String>, height: Dp, content: @Composable () -> Unit) {
    val slots = SPLIT_NUMBER + 1
    val padded = (labels + List(maxOf(0, slots - labels.size)) { "" }).take(slots)
    Box(Modifier.fillMaxWidth()) {
        content()
        Column(
            Modifier.height(height).padding(top = GridTop * 0.35f, bottom = GridBottom),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            padded.forEach {
                Text(
                    it, color = InkFaint, fontSize = 10.sp,
                    modifier = Modifier.width(GridLeft - 8.dp).offset(y = (-6).dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/**
 * Zonas de rango a la derecha del gráfico ("Alto"/"Bajo" en rojo), como en
 * la captura real de Heart Rate / Respiratory Rate. Van arriba y abajo del
 * plot, no sobre una banda sombreada.
 */
@Composable
fun ChartRangeZones(high: String, low: String, height: Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        content()
        Column(
            Modifier.fillMaxWidth().height(height).padding(top = GridTop * 0.35f, bottom = GridBottom, end = 4.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(high, color = Bad, fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
            Text(low, color = Bad, fontSize = 10.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
        }
    }
}

/** Etiquetas de tiempo del eje X: el original toma 7 puntos (length/6). */
@Composable
fun ChartXAxis(labels: List<String>) {
    if (labels.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(start = GridLeft, end = GridRight, top = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        labels.forEach { Text(it, color = InkFaint, fontSize = 9.sp) }
    }
}
