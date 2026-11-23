package com.chaide.app.platform

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import platform.NetworkExtension.NEHotspotNetwork
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

actual fun platformLog(tag: String, message: String) {
    NSLog("[%@] %@", tag, message)
}

actual fun <T> runBlockingPlatform(block: suspend CoroutineScope.() -> T): T = runBlocking(block = block)

// Con el entitlement "Access WiFi Information" (com.apple.developer.networking.wifi-info,
// ver iosApp/iosApp.entitlements) NEHotspotNetwork.fetchCurrent devuelve el
// SSID actual sin pedir permiso de ubicacion (asi es desde iOS 14). Sin el
// entitlement (p.ej. un build firmado sin arrastrar ese archivo) siempre
// devuelve null y el usuario escribe el nombre de la red a mano.
actual suspend fun currentWifiSsid(): String? = suspendCancellableCoroutine { cont ->
    try {
        NEHotspotNetwork.fetchCurrentWithCompletionHandler { network ->
            cont.resume(network?.SSID)
        }
    } catch (_: Throwable) {
        cont.resume(null)
    }
}

// iOS no tiene un selector nativo de redes cercanas para apps de terceros
// (a diferencia del panel de Ajustes de Android) — por eso aqui no hace
// nada util; la pantalla usa esto solo para saber que debe pedir el nombre
// de la red a mano en vez de abrir un selector.
@Composable
actual fun rememberWifiPicker(onResult: () -> Unit): () -> Unit = { onResult() }

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
