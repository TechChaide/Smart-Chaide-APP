package com.chaide.app.platform

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.darwin.NSObjectProtocol

actual val platformName: String = "ios"

// NUNCA pasar String de Kotlin como argumento variadico a NSLog ("%@"/"%s"):
// en Kotlin/Native no llega como objeto ObjC y la app se cierra (crash
// EXC_BREAKPOINT en el primer log del escaneo BLE). Se arma el texto en Kotlin
// y se pasa como formato unico, escapando los '%'.
actual fun platformLog(tag: String, message: String) {
    NSLog("[$tag] $message".replace("%", "%%"))
}

actual fun <T> runBlockingPlatform(block: suspend CoroutineScope.() -> T): T = runBlocking(block = block)

// Leer el SSID en iOS exige el entitlement "Access WiFi Information" + permiso
// de ubicacion, no disponible con un equipo de desarrollo personal. En iOS el
// usuario escribe el nombre de la red en "Ajustes de Wi-Fi".
actual fun currentWifiSsid(): String? = null

// iOS no permite listar redes WiFi (requiere el entitlement NEHotspotHelper,
// que Apple solo concede a apps de gestion de hotspots): la UI pide escribirla.
actual suspend fun scanWifiNetworks(): List<WifiNetwork>? = null

// CoreBluetooth muestra el dialogo de permiso solo la primera vez que se usa
// el radio (requiere NSBluetoothAlwaysUsageDescription en Info.plist).
@Composable
actual fun rememberBlePermission(): PermissionHandle = PermissionHandle(true) {}

@Composable
actual fun rememberWifiSsidPermission(): PermissionHandle = PermissionHandle(true) {}

@OptIn(ExperimentalForeignApi::class)
actual class AudioPlayer actual constructor() {
    private val player = AVPlayer()
    private var endObserver: NSObjectProtocol? = null

    init {
        try {
            AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
            AVAudioSession.sharedInstance().setActive(true, error = null)
        } catch (_: Throwable) {}
    }

    actual fun play(url: String, onCompletion: () -> Unit) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        val item = AVPlayerItem(uRL = nsUrl)
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue
        ) { _ -> onCompletion() }
        player.replaceCurrentItemWithPlayerItem(item)
        player.play()
    }
    actual fun pause() = player.pause()
    actual fun resume() = player.play()
    actual fun seekTo(ms: Int) { player.seekToTime(CMTimeMake(value = ms.toLong(), timescale = 1000)) }
    actual val positionMs: Int get() = seconds(CMTimeGetSeconds(player.currentTime()))
    actual val durationMs: Int get() = player.currentItem?.let { seconds(CMTimeGetSeconds(it.duration)) } ?: 0
    actual fun release() {
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = null
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
    }

    private fun seconds(s: Double): Int = if (s.isNaN() || s.isInfinite() || s < 0) 0 else (s * 1000).toInt()
}
