package com.chaide.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Barra de navegación "glass": una cápsula translúcida que flota sobre el
 * contenido en vez de un bloque sólido pegado al borde.
 *
 * El efecto se construye con capas de color, no con blur de hardware: Compose
 * solo ofrece `Modifier.blur` real a partir de API 31 y el mínimo de la app es
 * 26, así que un degradé blanco de alta opacidad sobre un borde claro da la
 * misma lectura de vidrio y se ve igual en todos los dispositivos.
 *
 * El indicador de selección es una píldora teñida que se desplaza; solo el
 * ítem activo lleva color de marca, para que la pestaña actual se lea de un
 * vistazo sin competir con el contenido.
 */
@Composable
fun GlassNavBar(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // true cuando el mini-reproductor está anclado justo encima: aplana las
    // esquinas superiores para que ambas píldoras se lean como una sola
    // pieza continua (el reproductor "minimizándose" hacia el nav bar), no
    // dos cápsulas flotando por separado con un corte entre ellas.
    flatTop: Boolean = false,
) {
    val shape = if (flatTop) RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp) else RoundedCornerShape(26.dp)
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 14.dp, top = if (flatTop) 0.dp else 6.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    // Vidrio navy, no blanco: el fondo real de la app es
                    // oscuro (#092754), un vidrio blanco flotaba desentonado.
                    Brush.verticalGradient(
                        listOf(SurfaceAlt.copy(alpha = 0.97f), SurfaceAlt.copy(alpha = 0.85f))
                    )
                )
                .background(BrandTint.copy(alpha = 0.35f))
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, item ->
                GlassNavTab(
                    item = item,
                    selected = i == selectedIndex,
                    onClick = { onSelect(i) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

data class GlassNavItem(val label: String, val icon: ImageVector)

@Composable
private fun GlassNavTab(
    item: GlassNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Blanco para el activo, gris azulado para el resto — igual que la barra
    // real ("Inicio" en blanco, "Dispositivo"/"Yo" en gris). `Brand` (navy)
    // se perdía contra el fondo navy de la propia píldora.
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
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interaction,
                indication = null,
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
