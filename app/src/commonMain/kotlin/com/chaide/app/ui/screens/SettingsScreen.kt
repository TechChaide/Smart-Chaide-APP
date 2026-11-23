package com.chaide.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chaide.app.i18n.AppLanguage
import com.chaide.app.i18n.LocalStrings
import com.chaide.app.i18n.LocaleSettings
import com.chaide.app.ui.theme.*

/**
 * Réplica de la pantalla real `/Setting` (componente Vue `Setting`, módulo
 * "1Fvv" del bundle). Sus elementos y textos están tomados 1:1 del componente
 * real y del diccionario i18n real:
 *   - `unit`            -> "Unit"              (toggle ℃ / ℉, campo real
 *                                               `userExt.temperatureType`)
 *   - `notification`    -> "Notification"      (push a /notification)
 *   - `license`         -> "License Agreement" (push a /license)
 *   - `cur_app_version` -> "Current Version: V{value}"
 *   - `check_update` / `newest_version` -> "Version Updates" / "Latest Version"
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val strings = LocalStrings.current
    // `temperatureType` vive en user_ext, una tabla que este backend todavía
    // no expone; el toggle es local por ahora (no se inventa un valor remoto).
    var celsius by remember { mutableStateOf(true) }

    Scaffold(
        containerColor = Night,
        topBar = { ChaideTopBar(strings.configure, onBack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))

            // Fila "Unit": en la app real es un selector segmentado ℃ / ℉.
            Row(
                Modifier.fillMaxWidth().padding(vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(strings.unit, color = TextHi, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                UnitToggle(celsius) { celsius = it }
            }
            HorizontalDivider(color = Hairline)

            // Fila "Language": NO existe en la app real (ésta solo seguía el
            // idioma del sistema) — es nueva, pedida explícitamente: la app
            // arranca siempre en español y el usuario la cambia a mano aquí,
            // guardado via LocaleSettings (persistido, ver i18n/AppLanguage.kt).
            Row(
                Modifier.fillMaxWidth().padding(vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(strings.language, color = TextHi, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                LanguageToggle(LocaleSettings.language, strings) { LocaleSettings.language = it }
            }
            HorizontalDivider(color = Hairline)

            SettingRow(strings.notification) { }
            HorizontalDivider(color = Hairline)

            SettingRow(strings.licenseAgreement) { }
            HorizontalDivider(color = Hairline)

            // "Version Updates" + la versión actual, como en la app real.
            Row(
                Modifier.fillMaxWidth().clickable { }.padding(vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(strings.versionUpdates, color = TextHi, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(strings.lastestVersion, color = Faint, style = MaterialTheme.typography.bodyMedium)
                Icon(Icons.Filled.ChevronRight, null, tint = Faint)
            }
            HorizontalDivider(color = Hairline)

            Spacer(Modifier.weight(1f))
            Text(
                strings.currentVersion("1.0.7"),
                color = Faint,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 28.dp)
            )
        }
    }
}

@Composable
private fun LanguageToggle(current: AppLanguage, strings: com.chaide.app.i18n.AppStrings, onChange: (AppLanguage) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(Night3)
    ) {
        listOf(strings.languageSpanish to AppLanguage.ES, strings.languageEnglish to AppLanguage.EN).forEach { (label, lang) ->
            val on = current == lang
            Text(
                label,
                color = if (on) Color.White else Faint,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clickable { onChange(lang) }
                    .background(if (on) Indigo else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun UnitToggle(celsius: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(Night3)
    ) {
        listOf("℃" to true, "℉" to false).forEach { (label, isC) ->
            val on = celsius == isC
            Text(
                label,
                color = if (on) Color.White else Faint,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clickable { onChange(isC) }
                    .background(if (on) Indigo else Color.Transparent)
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextHi, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        value?.let { Text(it, color = Faint, style = MaterialTheme.typography.bodyMedium) }
        Icon(Icons.Filled.ChevronRight, null, tint = Faint)
    }
}

/** Barra superior común de las sub-pantallas (flecha atrás + título centrado),
 * igual que las pantallas reales de la app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaideTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, color = TextHi, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextHi) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Night, titleContentColor = TextHi),
    )
}
