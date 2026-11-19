package com.chaide.app

import androidx.compose.ui.window.ComposeUIViewController
import com.chaide.m800sdk.ble.BleTransportProvider
import platform.UIKit.UIViewController

/**
 * Punto de entrada iOS: lo llama `iosApp/iosApp/ContentView.swift`.
 * Misma UI Compose que Android (`App()`).
 */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController {
    initApp()
    // Crea el CBCentralManager ya: tarda unos ms en pasar a "poweredOn" y el
    // primer escaneo lo necesita listo (tambien dispara el permiso Bluetooth).
    BleTransportProvider.create()
    return ComposeUIViewController { App() }
}
