package com.chaide.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chaide.app.net.Api
import com.chaide.app.net.MusicTrackDto
import com.chaide.app.platform.AudioPlayer
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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

    // Favoritos: ids de pista, persistidos en el telefono.
    private val prefs: Settings by lazy { Settings() }
    var favorites by mutableStateOf(
        prefs.getStringOrNull(KEY_FAVORITES)?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
    )
        private set
    fun isFavorite(track: MusicTrackDto?) = track?.id != null && track.id in favorites
    fun toggleFavorite(track: MusicTrackDto?) {
        val id = track?.id ?: return
        favorites = if (id in favorites) favorites - id else favorites + id
        prefs.putString(KEY_FAVORITES, favorites.joinToString(","))
    }

    // Temporizador de sueno: pausa la musica al vencer. Ciclo apagado -> 15 -> 30 -> 60 min.
    var sleepTimerMin by mutableIntStateOf(0)
        private set
    private var sleepTimerJob: Job? = null
    fun cycleSleepTimer() {
        val next = when (sleepTimerMin) { 0 -> 15; 15 -> 30; 30 -> 60; else -> 0 }
        sleepTimerMin = next
        sleepTimerJob?.cancel()
        if (next > 0) sleepTimerJob = scope.launch {
            delay(next * 60_000L)
            if (isPlaying) { player.pause(); isPlaying = false }
            sleepTimerMin = 0
        }
    }

    fun toggleShuffle() { shuffle = !shuffle }
    fun toggleRepeat() { repeat = !repeat }
    fun seekTo(ms: Int) { player.seekTo(ms); positionMs = ms }
    val durationMs get() = player.durationMs
    fun refreshPosition() { positionMs = player.positionMs }

    // MediaPlayer en Android, AVPlayer en iOS (ver platform/Platform.kt).
    private val player = AudioPlayer()

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
        val url = track.url ?: run { isPlaying = false; return }
        try {
            // repeat: repite la misma pista al terminar, de verdad (no un
            // adorno) — si no, pasa a la siguiente como siempre.
            player.play(url) {
                if (repeat) playAt(currentIndex) else { isPlaying = false; next() }
            }
        } catch (_: Exception) { isPlaying = false }
    }

    fun togglePlayPause() {
        if (tracks.isEmpty()) return
        if (isPlaying) { player.pause(); isPlaying = false }
        else if (player.positionMs > 0) { player.resume(); isPlaying = true }
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

    fun release() { sleepTimerJob?.cancel(); player.release() }

    private companion object { const val KEY_FAVORITES = "music.favorites" }
}
