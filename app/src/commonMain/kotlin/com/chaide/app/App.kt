package com.chaide.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.chaide.app.net.GatewaySettings
import com.chaide.app.net.Session
import com.chaide.app.ui.AppNav
import com.chaide.app.ui.theme.ChaideTheme
import com.chaide.app.ui.theme.Night

/** Carga la sesion y la IP del gateway persistidas. Llamar una vez al arrancar. */
fun initApp() {
    Session.init()
    GatewaySettings.init()
}

/** Raiz de la UI — la misma en Android (MainActivity) e iOS (MainViewController). */
@Composable
fun App() {
    ChaideTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Night) {
            AppNav()
        }
    }
}
