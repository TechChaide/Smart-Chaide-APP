package com.chaide.app.ui

import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chaide.app.net.Api
import com.chaide.app.net.MusicTrackDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Estado del reproductor "Sleep-Aid Music", hoisted por encima de MainScreen
 * (vive en AppNav) para que sobreviva al cambiar de pestaña (Home/Device/Me)
 * y al navegar a la biblioteca de pistas — un MediaPlayer atado a una sola
 * pantalla se habría destruido y cortado la música al salir de Home.
 */
class MusicPlayerState(private val scope: CoroutineScope) {
    var tracks by mutableStateOf<List<MusicTrackDto>>(emptyList())
        private set
    var currentIndex by mutableIntStateOf(0)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    // Reales, no decorativos: alimentan la barra de progreso de "Now Playing"
    // y cambian de verdad cómo se elige la siguiente pista / qué pasa al
    // terminar una — no son toggles que no hacen nada.
    var shuffle by mutableStateOf(false)
        private set
    var repeat by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set

    fun toggleShuffle() { shuffle = !shuffle }
    fun toggleRepeat() { repeat = !repeat }
    fun seekTo(ms: Int) { player.seekTo(ms); positionMs = ms }
    val durationMs get() = player.takeIf { isPlaying || it.currentPosition > 0 }?.let {
        try { it.duration.coerceAtLeast(0) } catch (_: Exception) { 0 }
    } ?: 0
    fun refreshPosition() {
        positionMs = try { player.currentPosition } catch (_: Exception) { 0 }
    }

    private val player = MediaPlayer()

    fun loadTracks() {
        if (tracks.isNotEmpty()) return
        scope.launch {
            try { tracks = withContext(Dispatchers.IO) { Api.service.music() }.data.orEmpty() } catch (_: Exception) {}
        }
    }

    val currentTrack get() = tracks.getOrNull(currentIndex)

    fun playAt(index: Int) {
        val track = tracks.getOrNull(index) ?: return
        currentIndex = index
        isPlaying = true
        positionMs = 0
        try {
            player.reset()
            player.setDataSource(track.url ?: return)
            player.setOnPreparedListener { it.start() }
            // repeat: repite la misma pista al terminar, de verdad (no un
            // adorno) — si no, pasa a la siguiente como siempre.
            player.setOnCompletionListener {
                if (repeat) playAt(currentIndex) else { isPlaying = false; next() }
            }
            player.prepareAsync()
        } catch (_: Exception) { isPlaying = false }
    }

    fun togglePlayPause() {
        if (tracks.isEmpty()) return
        if (isPlaying) { player.pause(); isPlaying = false }
        else if (player.currentPosition > 0) { player.start(); isPlaying = true }
        else playAt(currentIndex)
    }

    // shuffle real: al pasar de pista elige un índice al azar distinto del
    // actual (si hay más de una), no solo cambia el ícono.
    fun next() {
        if (tracks.isEmpty()) return
        val nextIndex = if (shuffle && tracks.size > 1) {
            var r: Int; do { r = tracks.indices.random() } while (r == currentIndex); r
        } else (currentIndex + 1) % tracks.size
        playAt(nextIndex)
    }
    fun previous() { if (tracks.isNotEmpty()) playAt((currentIndex - 1 + tracks.size) % tracks.size) }

    fun release() = player.release()
}
