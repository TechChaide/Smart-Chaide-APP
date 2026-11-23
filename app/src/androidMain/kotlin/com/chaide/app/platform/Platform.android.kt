package com.chaide.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Contexto de aplicacion, registrado en MainActivity.onCreate. */
@SuppressLint("StaticFieldLeak")
object AndroidApp {
    lateinit var context: Context
        private set
    fun init(context: Context) { this.context = context.applicationContext }
}

actual val platformName: String = "android"

actual fun platformLog(tag: String, message: String) { Log.i(tag, message) }

actual fun <T> runBlockingPlatform(block: suspend CoroutineScope.() -> T): T = runBlocking(block = block)

actual suspend fun currentWifiSsid(): String? = try {
    @Suppress("DEPRECATION")
    val wm = AndroidApp.context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
    @Suppress("DEPRECATION")
    wm?.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
} catch (_: Exception) { null }

actual suspend fun scanWifiNetworks(): List<WifiNetwork>? = withContext(Dispatchers.Main) {
    val ctx = AndroidApp.context
    val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return@withContext emptyList()
    // Pide un escaneo nuevo y espera su resultado (max 8 s). Android limita
    // los escaneos (4 cada 2 min); si se rechaza se usan los ultimos resultados.
    val ready = CompletableDeferred<Unit>()
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { ready.complete(Unit) }
    }
    ContextCompat.registerReceiver(
        ctx, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_EXPORTED,
    )
    try {
        @Suppress("DEPRECATION")
        val started = try { wm.startScan() } catch (_: Exception) { false }
        if (started) withTimeoutOrNull(8_000) { ready.await() }
    } finally {
        try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
    }
    val results = try { wm.scanResults } catch (_: SecurityException) { emptyList() }
    @Suppress("DEPRECATION")
    results.filter { !it.SSID.isNullOrBlank() }
        .groupBy { it.SSID }
        .map { (ssid, rs) ->
            WifiNetwork(
                ssid = ssid,
                rssi = rs.maxOf { it.level },
                has24GHz = rs.any { it.frequency in 2400..2500 },
                has5GHz = rs.any { it.frequency >= 4900 },
                secured = rs.any { r -> listOf("WPA", "WEP", "SAE", "PSK", "EAP").any { r.capabilities.contains(it) } },
            )
        }
        .sortedByDescending { it.rssi }
}

// En Android 12+ el manifest declara BLUETOOTH_SCAN con `neverForLocation`, asi
// que el escaneo BLE no requiere ubicacion. El componente Vue real
// (`addDevice.startScanDevice`) hace esta misma distincion por apiLevel.
private val BLE_PERMISSIONS: Array<String> = if (Build.VERSION.SDK_INT >= 31) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

@Composable
actual fun rememberBlePermission(): PermissionHandle {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(BLE_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        granted = results.values.all { it }
    }
    return PermissionHandle(granted) { launcher.launch(BLE_PERMISSIONS) }
}

// Leer el SSID (WifiManager.connectionInfo) exige ubicacion en Android, aunque
// el escaneo BLE ya no la necesite: es un segundo permiso para otro proposito.
@Composable
actual fun rememberWifiSsidPermission(): PermissionHandle {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    return PermissionHandle(granted) { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
}

actual class AudioPlayer actual constructor() {
    private val player = MediaPlayer()
    private var prepared = false

    actual fun play(url: String, onCompletion: () -> Unit) {
        prepared = false
        player.reset()
        player.setDataSource(url)
        player.setOnPreparedListener { prepared = true; it.start() }
        player.setOnCompletionListener { onCompletion() }
        player.prepareAsync()
    }
    actual fun pause() { if (prepared) player.pause() }
    actual fun resume() { if (prepared) player.start() }
    actual fun seekTo(ms: Int) { if (prepared) player.seekTo(ms) }
    actual val positionMs: Int get() = try { if (prepared) player.currentPosition else 0 } catch (_: Exception) { 0 }
    actual val durationMs: Int get() = try { if (prepared) player.duration.coerceAtLeast(0) else 0 } catch (_: Exception) { 0 }
    actual fun release() = player.release()
}
