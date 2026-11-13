package com.chaide.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.chaide.app.R
import com.chaide.app.net.Api
import com.chaide.app.net.DeviceDto
import com.chaide.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Réplica fiel de "My Device" (app-reference/04-device.png): título centrado,
 * botón "+", foto real del producto, firmware visible directo en la tarjeta. */
@Composable
fun DeviceScreen(modifier: Modifier = Modifier, onOpenDetail: (String) -> Unit = {}, onAddDevice: () -> Unit = {}) {
    var devices by remember { mutableStateOf<List<DeviceDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        try { devices = withContext(Dispatchers.IO) { Api.service.devices() }.data ?: emptyList() } catch (_: Exception) {}
        loading = false
    }

    Column(modifier.fillMaxSize().background(Night)) {
        Box(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
            Text("My Device", style = MaterialTheme.typography.titleLarge, color = TextHi,
                modifier = Modifier.align(Alignment.Center))
            IconButton(onClick = onAddDevice, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(Icons.Filled.Add, "Add device", tint = TextHi)
            }
        }
        HorizontalDivider(color = Hairline)
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(18.dp))
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(30.dp), Alignment.Center) { CircularProgressIndicator(color = TextHi) }
                devices.isEmpty() -> Text("No devices linked yet.", color = Mist, style = MaterialTheme.typography.bodyLarge)
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(devices) { d -> DeviceRow(d) { onOpenDetail(d.deviceId ?: "") } }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(d: DeviceDto, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(Night2, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.device_m800),
            contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(78.dp).clip(RoundedCornerShape(10.dp))
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(deviceLabel(d.deviceType), color = TextHi, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text("Firmware:V${d.firmwareVersion ?: "—"}", color = Mist, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

fun deviceLabel(type: Int?): String = when (type) {
    38 -> "Smart Mattress"
    else -> "Sleepace Device"
}
