package com.chaide.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chaide.app.net.Api
import com.chaide.app.net.ProfileDto
import com.chaide.app.net.Session
import com.chaide.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Réplica de la pantalla real `/UserAccount` (componente Vue `UserAccount`,
 * módulo "lIzU"). Elementos y textos tomados 1:1 del componente real y del
 * diccionario i18n real:
 *   - `user_account`   -> "Account" (título)
 *   - `register_email` -> "Email", `phone_number` -> "Phone Number",
 *     `unbound` -> "Unbound" (cuando el campo está vacío → push /BindAccount)
 *   - `edit_psw`       -> "Change Password" (push /ChangePassword)
 *   - `logout`         -> "Log Out", con el diálogo real
 *     `mine_dialog_exit_title` y `give_up` -> "Cancel"
 *   - `account_cancel` -> "Delete account"
 * Email y teléfono salen de `user_register` real (no inventados).
 */
@Composable
fun UserAccountScreen(onBack: () -> Unit, onLoggedOut: () -> Unit) {
    var profile by remember { mutableStateOf<ProfileDto?>(null) }
    LaunchedEffect(Unit) {
        try { profile = withContext(Dispatchers.IO) { Api.service.profile() }.data } catch (_: Exception) {}
    }
    val email = profile?.email ?: Session.user?.email
    val phone = profile?.mobile ?: Session.user?.mobile

    var showExitDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Night,
        topBar = { ChaideTopBar("Account", onBack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))

            AccountRow("Email", email ?: "Unbound", bound = email != null)
            HorizontalDivider(color = Hairline)
            AccountRow("Phone Number", phone ?: "Unbound", bound = phone != null)
            HorizontalDivider(color = Hairline)
            AccountRow("Change Password", null, bound = true)
            HorizontalDivider(color = Hairline)

            Spacer(Modifier.height(32.dp))

            // "Log Out" es un botón ancho, no una fila de lista (igual que la real).
            Button(
                onClick = { showExitDialog = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BtnFill, contentColor = BtnText),
            ) { Text("Log Out", style = MaterialTheme.typography.labelLarge) }

            Spacer(Modifier.height(20.dp))

            Text(
                "Delete account",
                color = Faint,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().clickable { }.padding(vertical = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            containerColor = Night2,
            // Texto literal de la clave real `mine_dialog_exit_title`.
            text = {
                Text(
                    "No history data deleted after exit, this account still works for log-in next time.",
                    color = TextHi, style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(onClick = { showExitDialog = false; Session.clear(); onLoggedOut() }) {
                    Text("Log Out", color = Low, style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("Cancel", color = Mist, style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
}

@Composable
private fun AccountRow(label: String, value: String?, bound: Boolean) {
    Row(
        Modifier.fillMaxWidth().clickable { }.padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextHi, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        value?.let {
            Text(it, color = if (bound) Mist else Faint, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(6.dp))
        }
        Icon(Icons.Filled.ChevronRight, null, tint = Faint)
    }
}
