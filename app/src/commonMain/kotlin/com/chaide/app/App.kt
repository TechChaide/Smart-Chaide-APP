package com.chaide.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.chaide.app.i18n.LocalStrings
import com.chaide.app.i18n.LocaleSettings
import com.chaide.app.i18n.stringsFor
import com.chaide.app.net.GatewaySettings
import com.chaide.app.net.Session
import com.chaide.app.ui.AppNav
import com.chaide.app.ui.theme.ChaideTheme
import com.chaide.app.ui.theme.Night

/** Carga la sesion, la IP del gateway y el idioma persistidos. Llamar una vez al arrancar. */
fun initApp() {
    Session.init()
    GatewaySettings.init()
    LocaleSettings.init()
}

/** Raiz de la UI — la misma en Android (MainActivity) e iOS (MainViewController). */
@Composable
fun App() {
    // Leer LocaleSettings.language AQUI (dentro de un @Composable) es lo que
    // hace que cambiar el idioma desde SettingsScreen recomponga TODA la app
    // de inmediato: es un mutableStateOf, asi que esta lectura se suscribe.
    CompositionLocalProvider(LocalStrings provides stringsFor(LocaleSettings.language)) {
        ChaideTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = Night) {
                AppNav()
            }
        }
    }
}
