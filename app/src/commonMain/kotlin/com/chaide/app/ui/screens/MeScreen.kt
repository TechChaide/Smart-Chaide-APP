package com.chaide.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.unit.dp
import com.chaide.app.resources.Res
import com.chaide.app.resources.device_m800
import com.chaide.app.resources.icon_gender_man
import com.chaide.app.resources.icon_gender_woman
import com.chaide.app.net.Api
import com.chaide.app.net.DeviceDto
import com.chaide.app.net.ProfileDto
import com.chaide.app.net.Session
import com.chaide.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Réplica del componente Vue real `MoreHomePage` (tab "Me"). Su `data`
 * define exactamente estas tres celdas, en este orden:
 *   tableList: [ friends -> "Friends", user_account -> "Account", set -> "Configure" ]
 * y su `didSelectCell` enruta a /FriendList, /UserAccount y /Setting.
 * Además monta `getBondDevice()` (fila DeviceInfoItem con nombre + firmware
 * del dispositivo vinculado, que abre /deviceInfo) y `getNewRequest()`
 * (badge de solicitudes de amistad pendientes).
 * El avatar por defecto depende del género (`placeholderIcon`: gender==2 →
 * icono mujer, si no icono hombre) usando los assets reales del bundle.
 */
@Composable
fun MeScreen(
    modifier: Modifier = Modifier,
    onOpenFriends: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenDeviceDetail: (String) -> Unit = {},
) {
    var profile by remember { mutableStateOf<ProfileDto?>(null) }
    var device by remember { mutableStateOf<DeviceDto?>(null) }
    var pendingRequests by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        try { profile = withContext(Dispatchers.IO) { Api.service.profile() }.data } catch (_: Exception) {}
        try { device = withContext(Dispatchers.IO) { Api.service.devices() }.data?.firstOrNull() } catch (_: Exception) {}
        try {
            pendingRequests = withContext(Dispatchers.IO) { Api.service.friends() }.data?.newRequestCount ?: 0
        } catch (_: Exception) {}
    }

    // Mismo orden que `getUserInfo` del componente real: nickname > email,
    // truncado a 16 caracteres.
    val name = (profile?.info?.nickname ?: Session.user?.nickname ?: Session.user?.email ?: "User")
        .substringBefore("@").take(16)
    val gender = profile?.info?.gender ?: Session.user?.gender

    Column(modifier.fillMaxSize().background(Night)) {
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xFF0D6FC4), Color(0xFF00529D))))
                .padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(
                    if (gender == 2) Res.drawable.icon_gender_woman else Res.drawable.icon_gender_man
                ),
                contentDescription = null,
                modifier = Modifier.size(88.dp).clip(CircleShape),
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = Color.White, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.Edit, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Night).padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // DeviceInfoItem real: nombre del dispositivo + "Firmware:V{ver}".
            device?.let { d ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { d.deviceId?.let(onOpenDeviceDetail) }
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        // El nombre que se muestra sale del deviceType, NO del
                        // serial: el componente real `DeviceInfoItem` hace
                        //   deviceName: function(){ return getDeviceName(this.Device.deviceType) }
                        // Antes aquí se pintaba `d.deviceName` (el serial
                        // M800425A00184), que no es lo que muestra la app oficial.
                        Text(deviceLabel(d.deviceType), color = TextHi, style = MaterialTheme.typography.titleMedium)
                        Text(
                            d.firmwareVersion?.let { "Firmware:V$it" } ?: "Firmware:—",
                            color = Faint, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Image(
                        painter = painterResource(Res.drawable.device_m800),
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                    )
                }
                HorizontalDivider(color = Hairline)
            }

            MenuRow(Icons.Filled.People, "Friends", badge = pendingRequests, onClick = onOpenFriends)
            HorizontalDivider(color = Hairline)
            MenuRow(Icons.Filled.Person, "Account", onClick = onOpenAccount)
            HorizontalDivider(color = Hairline)
            MenuRow(Icons.Filled.Settings, "Configure", onClick = onOpenSettings)
            HorizontalDivider(color = Hairline)
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, badge: Int = 0, onClick: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Mist, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = TextHi, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (badge > 0) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(Low), Alignment.Center) {
                Text("$badge", color = TextHi, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(6.dp))
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Faint)
    }
}
