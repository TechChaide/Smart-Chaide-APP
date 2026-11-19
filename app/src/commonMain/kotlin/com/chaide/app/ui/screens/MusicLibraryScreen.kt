package com.chaide.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chaide.app.net.MusicTrackDto
import com.chaide.app.ui.MusicPlayerState
import com.chaide.app.ui.theme.*

/** Biblioteca completa de pistas reales "Sleep-Aid Music" — se abre al tocar
 *  el mini-reproductor anclado sobre el nav bar; elegir una la reproduce. */
@Composable
fun MusicLibraryScreen(music: MusicPlayerState, onBack: () -> Unit) {
    Scaffold(containerColor = Night) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextHi) }
                Text("Sleep-Aid Music", color = TextHi, style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider(color = Hairline)
            if (music.tracks.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = TextHi)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(music.tracks, key = { it.id ?: 0 }) { track ->
                        val index = music.tracks.indexOf(track)
                        TrackRow(
                            track = track,
                            playing = music.isPlaying && music.currentIndex == index,
                            onClick = {
                                if (music.currentIndex == index) music.togglePlayPause()
                                else music.playAt(index)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: MusicTrackDto, playing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (playing) Brand else Night3),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null,
                tint = Color.White, modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.name ?: "—", color = if (playing) Brand else TextHi,
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        track.durationSec?.takeIf { it > 0 }?.let {
            Text("${it / 60}:${(it % 60).toString().padStart(2, '0')}", color = Faint, style = MaterialTheme.typography.labelMedium)
        }
    }
    HorizontalDivider(color = Hairline)
}
