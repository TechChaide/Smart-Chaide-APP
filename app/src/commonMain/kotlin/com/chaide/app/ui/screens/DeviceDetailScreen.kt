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
import androidx.compose.ui.unit.dp
import com.chaide.app.net.Api
import com.chaide.app.net.DeviceDto
import com.chaide.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Réplica fiel de la pantalla real M800Detail del H5 (com.sleepace.hrbrid, ruta
 * /m800detail), leída del código Vue decompilado: lista de menú con ID de
 * dispositivo, versión de firmware, WiFi, guía de uso, problema común, y
 * "eliminar dispositivo" al pie. Datos reales del backend (deviceCurrentVersion).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(deviceId: String, onBack: () -> Unit, onOpenLiveData: () -> Unit = {}) {
    var device by remember { mutableStateOf<DeviceDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showUnbindConfirm by remember { mutableStateOf(false) }
    var unbindError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(deviceId) {
        try { device = withContext(Dispatchers.IO) { Api.service.deviceDetail(deviceId) }.data } catch (_: Exception) {}
        loading = false
    }

    Scaffold(
        containerColor = Night,
        topBar = {
            TopAppBar(
                title = { Text(deviceLabel(device?.deviceType)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", tint = TextHi) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Night, titleContentColor = TextHi)
            )
        }
    ) { pad ->
        if (loading || device == null) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) { CircularProgressIndicator(color = Moon) }
            return@Scaffold
        }
        val d = device!!
        Column(Modifier.padding(pad).padding(horizontal = 20.dp).fillMaxSize()) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth().background(Night2, RoundedCornerShape(18.dp))
                    .clickable(onClick = onOpenLiveData).padding(0.dp)
            ) {
                MenuRow("View Live Data", null, arrow = true)
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().background(Night2, RoundedCornerShape(18.dp))) {
                MenuRow("Device ID", d.deviceName ?: "—", arrow = false)
                Divider()
                MenuRow("Firmware", d.firmwareVersion ?: "—", arrow = true)
                Divider()
                MenuRow("Sharing Setting", null, arrow = true)
                Divider()
                MenuRow("Change Wi-Fi", null, arrow = true)
                Divider()
                MenuRow("User Manual", null, arrow = true)
                Divider()
                MenuRow("FAQ", null, arrow = true)
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "Delete Device", color = Low, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally)
                    .clickable { showUnbindConfirm = true }.padding(12.dp)
            )
            unbindError?.let {
                Text(
                    it, color = Low, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 20.dp),
                )
            }
        }
    }

    if (showUnbindConfirm) {
        AlertDialog(
            onDismissRequest = { showUnbindConfirm = false },
            containerColor = Night2,
            title = { Text("Delete this device?", color = TextHi) },
            text = { Text("It will be unlinked from your ChaideSmart account.", color = Mist) },
            confirmButton = {
                TextButton(onClick = {
                    showUnbindConfirm = false
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { Api.service.unbindDevice(deviceId) }
                            onBack()   // vuelve a la lista, ya sin el equipo
                        } catch (e: Exception) {
                            unbindError = e.message ?: "No se pudo eliminar"
                        }
                    }
                }) { Text("Delete", color = Low) }
            },
            dismissButton = {
                TextButton(onClick = { showUnbindConfirm = false }) { Text("Cancel", color = Mist) }
            }
        )
    }
}

@Composable
private fun MenuRow(label: String, value: String?, arrow: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextHi, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        value?.let { Text(it, color = Mist, style = MaterialTheme.typography.bodyMedium) }
        if (arrow) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.ChevronRight, null, tint = Faint)
        }
    }
}

@Composable
private fun Divider() { HorizontalDivider(color = Hairline) }
