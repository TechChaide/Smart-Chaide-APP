package com.chaide.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chaide.app.resources.Res
import com.chaide.app.resources.icon_empty_add_friend
import com.chaide.app.resources.icon_empty_friend_notice
import com.chaide.app.resources.icon_gender_man
import com.chaide.app.resources.icon_gender_woman
import com.chaide.app.net.Api
import com.chaide.app.net.FriendDto
import com.chaide.app.net.FriendsDto
import com.chaide.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Réplica de la pantalla real `/FriendList` (componente Vue `FriendList`,
 * módulo "XzpL"). Elementos y textos tomados 1:1 del componente real y del
 * diccionario i18n real:
 *   - `my_friends`     -> "Mine Friends" (título)
 *   - `friend_request` -> "New Friends" (fila superior, push /FriendRequest;
 *                         lleva badge con las solicitudes pendientes)
 *   - `friends_empty`  -> "Follow Friends, Understand Your Score Ranking"
 *   - `addFriend`      -> push /SearchFriend (botón "+" del header)
 *   - `goReport`       -> abrir el reporte del amigo (/report con su userId)
 * Los datos salen de la tabla real `sleepace_pro_user.user_friend`.
 */
@Composable
fun FriendListScreen(onBack: () -> Unit) {
    var data by remember { mutableStateOf<FriendsDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        try { data = withContext(Dispatchers.IO) { Api.service.friends() }.data } catch (_: Exception) { data = null }
        loading = false
    }

    Scaffold(
        containerColor = Night,
        topBar = {
            Box(Modifier.fillMaxWidth()) {
                ChaideTopBar("Mine Friends", onBack)
                IconButton(
                    onClick = { },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
                ) { Icon(Icons.Filled.Add, "Add New Friend", tint = TextHi) }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 20.dp)) {
            // Fila "New Friends" con badge de solicitudes pendientes reales.
            Row(
                Modifier.fillMaxWidth().clickable { }.padding(vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(Res.drawable.icon_empty_friend_notice),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text("New Friends", color = TextHi, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                val pending = data?.newRequestCount ?: 0
                if (pending > 0) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(Low), Alignment.Center) {
                        Text("$pending", color = TextHi, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Icon(Icons.Filled.ChevronRight, null, tint = Faint)
            }
            HorizontalDivider(color = Hairline)

            val friends = data?.friends.orEmpty()
            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = TextHi) }
                friends.isEmpty() -> EmptyFriends()
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(friends) { f -> FriendRow(f) }
                }
            }
        }
    }
}

/** Estado vacío real: ilustración `more_icon_empty_addnew_friend` del bundle
 * real + el texto literal de la clave `friends_empty`. */
@Composable
private fun EmptyFriends() {
    Column(
        Modifier.fillMaxSize().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(Res.drawable.icon_empty_add_friend),
            contentDescription = null,
            modifier = Modifier.size(140.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Follow Friends, Understand Your Score Ranking",
            color = Mist,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun FriendRow(f: FriendDto) {
    Row(
        Modifier.fillMaxWidth().clickable { }.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar por defecto según género, igual que la app real
        // (login_icon_man_sel / login_icon_woman_sel).
        Image(
            painter = painterResource(
                if (f.gender == 2) Res.drawable.icon_gender_woman else Res.drawable.icon_gender_man
            ),
            contentDescription = null,
            modifier = Modifier.size(44.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(14.dp))
        Text(f.name ?: "—", color = TextHi, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, null, tint = Faint)
    }
    HorizontalDivider(color = Hairline)
}
