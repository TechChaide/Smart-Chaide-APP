package com.chaide.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import org.jetbrains.compose.resources.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.chaide.app.resources.Res
import com.chaide.app.resources.gotham_book
import com.chaide.app.resources.gotham_light

/* ───────────────────────── Paleta ─────────────────────────
 * Colores REALES de la app de producción — muestreados por pixel de
 * capturas reales (carpeta app-reference y las capturas prod_ de esta
 * sesión) en vez de inventados:
 *   fondo principal #092754, tarjetas/divisores #053871 (confirmado en 4+
 *   puntos distintos de 3 pantallas reales), rojo de alerta #ED5458 (etiqueta
 *   real "Alto"). El azul de acento de líneas/gráficas #00529D viene del
 *   propio config de ECharts del bundle H5 decompilado (no una muestra de
 *   pixel, el valor literal del código: `lineStyle: { color: '#00529D' }`
 *   en `dailyLong`/`dailyShortHumidityBase`). Reemplaza el rediseño
 *   #0055B8+blanco anterior — vuelta explícita a los colores originales.
 */
val Brand      = Color(0xFF00529D)   // azul real de líneas/acentos (ECharts, bundle H5)
val BrandDeep  = Color(0xFF07336B)   // variante oscura (barras "Sueño profundo", presionado)
val BrandTint  = Color(0xFF0C3A68)   // superficies teñidas/seleccionadas sobre fondo oscuro
val BrandEdge  = Color(0xFF14518F)   // bordes sobre superficie teñida

val Surface    = Color(0xFF092754)   // fondo real de página/tarjeta
val SurfaceAlt = Color(0xFF053871)   // tarjetas anidadas / divisores reales
val Ink        = Color(0xFFFFFFFF)   // texto principal (blanco sobre navy)
val InkMute    = Color(0xFF8CA3C2)   // texto secundario
val InkFaint   = Color(0xFF5E7CA3)   // terciario, deshabilitado
val Line       = Color(0xFF1C4A80)   // separadores (sutiles sobre el navy)

// Bandas de puntaje y señales
val Good = Color(0xFF2ED6A1)
val Warn = Color(0xFFE8A33D)
val Bad  = Color(0xFFED5458)         // rojo real, etiqueta "Alto"/"Alert" muestreada

/* ── Alias de compatibilidad ── (nombres históricos, remapeados) */
val Night    = Surface
val Night2   = SurfaceAlt
val Night3   = BrandTint
val NavBar   = Surface
val Moon     = Color.White
val BtnFill  = Color.White
/** Texto/indicadores SOBRE BtnFill: antes se usaba TextHi (blanco) -> botones blancos sin texto visible. */
val BtnText  = Surface
val TextHi   = Ink
val Mist     = InkMute
val Faint    = InkFaint
val Hairline = Line
val Indigo   = Brand
val IndigoSoft = BrandDeep
val Okk  = InkMute
val Low  = Bad

private val Scheme = lightColorScheme(
    primary = Brand, onPrimary = Color.White,
    secondary = BrandDeep, onSecondary = Color.White,
    background = Surface, onBackground = Ink,
    surface = Surface, onSurface = Ink,
    surfaceVariant = SurfaceAlt, onSurfaceVariant = InkMute,
    outline = Line,
    error = Bad,
)

/* ───────────────────────── Tipografía ─────────────────────────
 * Gotham, la familia real de la marca, extraída del propio bundle de la app.
 * Solo existen dos pesos reales (Light y Book); la jerarquía se construye con
 * tamaño y color, no inventando pesos que la fuente no tiene.
 */
// En Compose Multiplatform las fuentes de recursos se cargan en un @Composable.
@Composable
private fun gothamFamily() = FontFamily(
    Font(Res.font.gotham_light, FontWeight.Light),
    Font(Res.font.gotham_book, FontWeight.Normal),
    Font(Res.font.gotham_book, FontWeight.Medium),
    Font(Res.font.gotham_book, FontWeight.SemiBold),
    Font(Res.font.gotham_book, FontWeight.Bold),
)

private fun chaideType(Gotham: FontFamily) = Typography(
    // El puntaje de sueño es el héroe: enorme y ligero.
    displayLarge   = TextStyle(fontFamily = Gotham, fontWeight = FontWeight.Light,    fontSize = 72.sp, letterSpacing = (-2).sp),
    headlineLarge  = TextStyle(fontFamily = Gotham, fontWeight = FontWeight.Bold,     fontSize = 28.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Gotham, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.3).sp),
    titleLarge     = TextStyle(fontFamily = Gotham, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium    = TextStyle(fontFamily = Gotham, fontWeight = FontWeight.Medium,   fontSize = 15.sp),
    bodyLarge      = TextStyle(fontFamily = Gotham, fontWeight = FontWeight.Normal,   fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium     = TextStyle(fontFamily = Gotham, fontWeight = FontWeight.Normal,   fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge     = TextStyle(fontFamily = Gotham, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelMedium    = TextStyle(fontFamily = Gotham, fontWeight = FontWeight.Medium,   fontSize = 12.sp),
)

@Composable
fun ChaideTheme(content: @Composable () -> Unit) {
    val gotham = gothamFamily()
    val type = remember(gotham) { chaideType(gotham) }
    MaterialTheme(colorScheme = Scheme, typography = type, content = content)
}
