package com.chaide.app.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chaide.app.ui.MusicPlayerState
import com.chaide.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private fun fmt(ms: Int): String {
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** "Now Playing" de pantalla completa — real: barra de progreso, shuffle,
 *  repeat, favorito (persistido), temporizador de sueno (15/30/60 min) y cola
 *  (abre la biblioteca) funcionan de verdad (ver MusicPlayerState). No hay
 *  carátula ni "artista" reales en los datos (la tabla solo tiene nombre/
 *  duración/url), así que en vez de una carátula o nombre de artista
 *  inventado se usa un anillo de onda — animado de verdad con la
 *  reproducción, no decorativo. */
@Composable
fun NowPlayingScreen(music: MusicPlayerState, onBack: () -> Unit, onOpenLibrary: () -> Unit = onBack) {
    LaunchedEffect(music.isPlaying) {
        while (music.isPlaying) {
            music.refreshPosition()
            delay(400)
        }
    }
    val duration = music.durationMs.coerceAtLeast(1)
    val position = music.positionMs.coerceIn(0, duration)

    Scaffold(containerColor = Surface) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink) }
                Text("Now Playing", color = Ink, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(4.dp))

            // Solo acciones con funcionalidad real (antes habia Ecualizador/
            // Compartir/Mas sin nada detras: se quitaron).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                val fav = music.isFavorite(music.currentTrack)
                IconButton(onClick = { music.toggleFavorite(music.currentTrack) }) {
                    Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        if (fav) "Remove from favorites" else "Add to favorites", tint = if (fav) Brand else Mist)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { music.cycleSleepTimer() }) {
                        Icon(Icons.Filled.Timer, "Sleep timer", tint = if (music.sleepTimerMin > 0) Brand else Mist)
                    }
                    if (music.sleepTimerMin > 0) Text("${music.sleepTimerMin} min", color = Brand, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.weight(1f))

            CircularWaveform(playing = music.isPlaying, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            Spacer(Modifier.height(32.dp))

            Text(
                music.currentTrack?.name ?: "—", color = Ink, style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Sleep-Aid Music", color = Mist, style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(28.dp))

            Slider(
                value = position.toFloat(), onValueChange = { music.seekTo(it.toInt()) },
                valueRange = 0f..duration.toFloat(),
                colors = SliderDefaults.colors(thumbColor = Brand, activeTrackColor = Brand, inactiveTrackColor = Hairline),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(position), color = Mist, style = MaterialTheme.typography.labelMedium)
                Text(fmt(duration), color = Mist, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { music.toggleShuffle() }) {
                    Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (music.shuffle) Brand else Mist)
                }
                IconButton(onClick = { music.toggleRepeat() }) {
                    Icon(Icons.Filled.Repeat, "Repeat", tint = if (music.repeat) Brand else Mist)
                }
                IconButton(onClick = { music.previous() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.SkipPrevious, "Previous", tint = Ink, modifier = Modifier.size(32.dp))
                }
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Brand),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = { music.togglePlayPause() }) {
                        Icon(
                            if (music.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (music.isPlaying) "Pause" else "Play",
                            tint = Color.White, modifier = Modifier.size(32.dp),
                        )
                    }
                }
                IconButton(onClick = { music.next() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.SkipNext, "Next", tint = Ink, modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = onOpenLibrary) {
                    Icon(Icons.Filled.QueueMusic, "Queue", tint = Mist)
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** Anillo de onda: barras dispuestas en círculo, cada una "respirando" en
 *  bucle con una fase distinta — animación real ligada a [playing], no un
 *  dibujo estático; sustituye a la carátula inexistente en los datos. */
@Composable
private fun CircularWaveform(playing: Boolean, modifier: Modifier = Modifier) {
    val barCount = 48
    val transition = rememberInfiniteTransition(label = "ringWave")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ringPhase",
    )
    androidx.compose.foundation.Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = min(size.width, size.height) / 2f
        val ringRadius = outerRadius * 0.62f
        val barLenMin = outerRadius * 0.08f
        val barLenMax = outerRadius * 0.22f

        // Disco central sólido.
        drawCircle(color = BrandDeep, radius = ringRadius * 0.86f, center = center)
        drawCircle(
            color = Color.White.copy(alpha = 0.12f), radius = ringRadius * 0.86f, center = center,
            style = Stroke(width = 2f),
        )

        for (i in 0 until barCount) {
            val angle = (2 * kotlin.math.PI * i / barCount)
            val wobble = if (playing) {
                val t = (phase + i.toFloat() / barCount) % 1f
                0.3f + 0.7f * kotlin.math.abs(sin(t * kotlin.math.PI).toFloat())
            } else 0.25f
            val len = barLenMin + (barLenMax - barLenMin) * wobble
            val x1 = center.x + cos(angle).toFloat() * ringRadius
            val y1 = center.y + sin(angle).toFloat() * ringRadius
            val x2 = center.x + cos(angle).toFloat() * (ringRadius + len)
            val y2 = center.y + sin(angle).toFloat() * (ringRadius + len)
            drawLine(
                color = if (playing) Brand else Hairline,
                start = Offset(x1, y1), end = Offset(x2, y2),
                strokeWidth = 5f, cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}
