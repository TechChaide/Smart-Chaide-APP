package com.chaide.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chaide.app.i18n.LocalStrings
import com.chaide.app.ui.MusicPlayerState
import com.chaide.app.ui.theme.*

private data class Tab(val label: String, val icon: ImageVector)

@Composable
fun MainScreen(
    music: MusicPlayerState,
    onOpenDeviceDetail: (String) -> Unit = {},
    onOpenReport: (String) -> Unit = {},
    onOpenMusic: () -> Unit = {},
    onOpenNowPlaying: () -> Unit = {},
    onAddDevice: () -> Unit = {},
    onOpenFriends: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    // rememberSaveable (no `remember`): al volver de una sub-pantalla
    // (Friends/Account/Configure) MainScreen se recompone desde cero y con
    // `remember` el tab saltaba a Home. La app real conserva el tab.
    val strings = LocalStrings.current
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        Tab(strings.tabHome, Icons.Filled.Home),
        Tab(strings.tabDevice, Icons.Filled.GridView),
        Tab(strings.tabMe, Icons.Filled.Person),
    )

    val dockVisible = music.currentTrack != null
    // Por defecto (y al tocar la onda del estado fusionado): "apilado" — la
    // barra completa del reproductor (ícono play, nombre de pista, onda,
    // anterior/play-pausa/siguiente) flota SOBRE el nav bar, separada por un
    // espacio. Al presionar play ahí, se fusiona en una sola píldora con el
    // nav bar (onda+controles, sin nombre de pista). Doble toque en la onda,
    // en cualquiera de los dos estados, abre "Reproduciendo ahora". Igual,
    // paso a paso, a la referencia exacta que dio el usuario.
    var merged by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Surface,
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp, top = 6.dp)) {
                // Efecto "jelly" al minimizarse (estilo Dock de macOS): la
                // píldora se encoge HACIA el nav bar (origen abajo-centro) con
                // un spring rebotón, en vez de desaparecer en seco o con un
                // tween lineal — la sensación de "ser absorbida" es lo que
                // vende el minimizado, no solo que se achique.
                AnimatedVisibility(
                    visible = dockVisible && !merged,
                    enter = fadeIn(tween(120)) + scaleIn(
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                        transformOrigin = TransformOrigin(0.5f, 1f),
                    ),
                    exit = fadeOut(tween(180)) + scaleOut(
                        animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium),
                        transformOrigin = TransformOrigin(0.5f, 1f),
                    ),
                ) {
                    Column {
                        StackedPlayerDock(
                            music = music,
                            onPlayStarted = { merged = true },
                            onWaveformDoubleTap = onOpenNowPlaying,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                GlassNavBarPill(
                    tabs, selected, { selected = it }, Modifier.fillMaxWidth(),
                    trailingContent = if (dockVisible && merged) {
                        {
                            MergedFullControls(
                                music = music,
                                onWaveformTap = { merged = false },
                                onWaveformDoubleTap = onOpenNowPlaying,
                            )
                        }
                    } else null,
                )
            }
        }
    ) { pad ->
        val mod = Modifier.padding(pad)
        when (selected) {
            0 -> HomeScreen(mod, music, onOpenReport, onOpenMusic)
            1 -> DeviceScreen(mod, onOpenDeviceDetail, onAddDevice)
            else -> MeScreen(mod, onOpenFriends, onOpenAccount, onOpenSettings, onOpenDeviceDetail)
        }
    }
}

/** La píldora "glass" real (misma superficie que GlassNavBar), pero con un
 *  slot opcional al final para los controles del reproductor fusionados —
 *  así el nav bar y el reproductor se leen como UNA sola pieza, con un "|"
 *  de por medio, en vez de un bloque aparte pegado encima. */
@Composable
private fun GlassNavBarPill(
    tabs: List<Tab>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.verticalGradient(listOf(SurfaceAlt.copy(alpha = 0.97f), SurfaceAlt.copy(alpha = 0.85f))))
            .background(BrandTint.copy(alpha = 0.35f))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f, fill = trailingContent == null),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { i, tab ->
                GlassNavTab(GlassNavItem(tab.label, tab.icon), i == selectedIndex, { onSelect(i) }, Modifier.weight(1f))
            }
        }
        if (trailingContent != null) {
            // Mismo lenguaje "jelly" que StackedPlayerDock: el bulto entra
            // con un leve rebote (overshoot) en vez de deslizarse liso, como
            // si lo que se encogió afuera aterrizara aquí con elasticidad.
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + expandHorizontally(
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                    expandFrom = Alignment.Start,
                ) + scaleIn(
                    animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium),
                    transformOrigin = TransformOrigin(0f, 0.5f),
                ),
                exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start) + scaleOut(
                    transformOrigin = TransformOrigin(0f, 0.5f),
                ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(1.dp).height(28.dp).background(Color.White.copy(alpha = 0.25f)))
                    Spacer(Modifier.width(4.dp))
                    trailingContent()
                }
            }
        }
    }
}

/** Dock por defecto: píldora propia y completa (nombre de pista + onda +
 *  anterior/play-pausa/siguiente), flotando SEPARADA del nav bar por un
 *  espacio — más alta, con una marquita decorativa debajo. Presionar play
 *  acá es lo que dispara la fusión (vía [onPlayStarted], callback directo
 *  del propio botón — no un `LaunchedEffect` reactivo, que resultó poco
 *  confiable). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StackedPlayerDock(music: MusicPlayerState, onPlayStarted: () -> Unit, onWaveformDoubleTap: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(Brush.horizontalGradient(listOf(BrandDeep, Brand)))
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                music.currentTrack?.name ?: "—", color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1, softWrap = false,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            PlayerButtons(music, onWaveformTap = {}, onWaveformDoubleTap = onWaveformDoubleTap, onPlayStarted = onPlayStarted)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f)))
    }
}

/** Fusionado dentro del nav bar: controles completos (onda + anterior/
 *  play-pausa/siguiente, sin nombre de pista). Tocar la onda desacopla de
 *  vuelta al dock apilado; doble toque abre "Reproduciendo ahora". */
@Composable
private fun MergedFullControls(music: MusicPlayerState, onWaveformTap: () -> Unit, onWaveformDoubleTap: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlayerButtons(music, onWaveformTap, onWaveformDoubleTap)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerButtons(
    music: MusicPlayerState,
    onWaveformTap: () -> Unit,
    onWaveformDoubleTap: () -> Unit,
    onPlayStarted: () -> Unit = {},
) {
    Box(
        Modifier.combinedClickable(onClick = onWaveformTap, onDoubleClick = onWaveformDoubleTap)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        MiniWaveform(playing = music.isPlaying, modifier = Modifier.width(22.dp).height(18.dp))
    }
    IconButton(onClick = { music.previous() }, modifier = Modifier.size(28.dp)) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous", tint = Color.White, modifier = Modifier.size(20.dp))
    }
    IconButton(
        onClick = {
            val wasPlaying = music.isPlaying
            music.togglePlayPause()
            if (!wasPlaying) onPlayStarted()
        },
        modifier = Modifier.size(28.dp),
    ) {
        Icon(
            if (music.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play",
            tint = Color.White, modifier = Modifier.size(20.dp),
        )
    }
    IconButton(onClick = { music.next() }, modifier = Modifier.size(28.dp)) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next", tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun GlassNavTab(item: GlassNavItem, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint by animateColorAsState(
        if (selected) Color.White else InkFaint,
        tween(180), label = "navTint",
    )
    val pill by animateFloatAsState(
        if (selected) 1f else 0f,
        tween(180), label = "navPill",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(BrandTint.copy(alpha = 0.9f * pill))
            .selectable(
                selected = selected, onClick = onClick,
                role = Role.Tab,
                interactionSource = interaction, indication = null,
            )
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(3.dp))
            Text(item.label, color = tint, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Onda de audio animada — cada barra "respira" entre un mínimo y un máximo
 *  en bucle, desfasada, para leerse como un ecualizador real en vez de
 *  subir/bajar al unísono. Quieta cuando no se está reproduciendo. */
@Composable
private fun MiniWaveform(playing: Boolean, modifier: Modifier = Modifier) {
    val bars = listOf(
        Triple(0.3f, 0.65f, 480), Triple(0.35f, 1f, 620), Triple(0.3f, 0.85f, 380),
        Triple(0.3f, 0.7f, 540), Triple(0.3f, 0.55f, 460),
    )
    val transition = rememberInfiniteTransition(label = "waveform")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.5.dp), verticalAlignment = Alignment.CenterVertically) {
        bars.forEachIndexed { i, (lo, hi, durationMs) ->
            val animated by transition.animateFloat(
                initialValue = lo, targetValue = if (playing) hi else lo,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMs, delayMillis = i * 70, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar$i",
            )
            Box(
                Modifier.width(2.dp).fillMaxHeight(if (playing) animated else lo)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Color.White.copy(alpha = if (playing) 1f else 0.5f)),
            )
        }
    }
}
