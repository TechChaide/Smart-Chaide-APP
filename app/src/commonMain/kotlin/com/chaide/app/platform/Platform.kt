package com.chaide.app.platform

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope

/*
 * Todo lo que cambia entre Android e iOS vive detras de estas declaraciones
 * `expect`; el resto de la app (pantallas, red, protocolo M800) es comun.
 */

/** "android" / "ios" — solo para textos de ayuda y logs. */
expect val platformName: String

/** Log nativo: logcat en Android, consola de Xcode en iOS. */
expect fun platformLog(tag: String, message: String)

/**
 * Bloquea el hilo actual hasta terminar [block]. Solo para hilos de fondo
 * (el flujo BLE del M800 espera acks de forma sincrona, como el SDK original).
 */
expect fun <T> runBlockingPlatform(block: suspend CoroutineScope.() -> T): T

/** SSID de la red WiFi actual, o null si no se puede leer (iOS: se escribe a mano). */
expect fun currentWifiSsid(): String?

/** Estado de un permiso del sistema + forma de pedirlo. */
class PermissionHandle(val granted: Boolean, val request: () -> Unit)

/**
 * Permisos para escanear/conectar BLE. Android: BLUETOOTH_SCAN/CONNECT (12+)
 * o ubicacion (<12). iOS: lo pide CoreBluetooth solo al primer uso, siempre "granted".
 */
@Composable
expect fun rememberBlePermission(): PermissionHandle

/** Permiso para leer el SSID actual (Android: ubicacion). iOS: no aplica. */
@Composable
expect fun rememberWifiSsidPermission(): PermissionHandle

/** Reproductor de audio por URL: MediaPlayer en Android, AVPlayer en iOS. */
expect class AudioPlayer() {
    /** Empieza a reproducir [url]; [onCompletion] al terminar la pista. */
    fun play(url: String, onCompletion: () -> Unit)
    fun pause()
    fun resume()
    fun seekTo(ms: Int)
    val positionMs: Int
    val durationMs: Int
    fun release()
}
