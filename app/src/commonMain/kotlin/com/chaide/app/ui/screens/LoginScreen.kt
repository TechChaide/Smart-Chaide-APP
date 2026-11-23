package com.chaide.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.chaide.app.i18n.LocalStrings
import com.chaide.app.net.Api
import com.chaide.app.net.LoginRequest
import com.chaide.app.net.Session
import com.chaide.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Réplica fiel de la pantalla real de login (app-reference/02-login.png): fondo
 * azul plano, campos subrayados (sin caja), checkbox de términos obligatorio. */
@Composable
fun LoginScreen(onBack: () -> Unit, onSuccess: () -> Unit) {
    val strings = LocalStrings.current
    var email by remember { mutableStateOf("crisandrade94.ca@gmail.com") }
    var password by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var acceptedTerms by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val canLogin = email.isNotBlank() && password.length >= 6 && acceptedTerms && !loading

    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        focusedTextColor = TextHi, unfocusedTextColor = TextHi,
        focusedIndicatorColor = Mist, unfocusedIndicatorColor = Hairline,
        cursorColor = TextHi, focusedLabelColor = Mist, unfocusedLabelColor = Mist,
    )

    Column(Modifier.fillMaxSize().background(Night).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextHi) }
            Spacer(Modifier.weight(1f))
            Text(strings.forgotPassword, color = Mist, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(end = 8.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(strings.logIn, style = MaterialTheme.typography.headlineLarge, color = TextHi)
        Spacer(Modifier.height(36.dp))

        TextField(email, { email = it; error = null }, label = { Text(strings.email) },
            singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(28.dp))
        TextField(password, { password = it; error = null }, label = { Text(strings.password) },
            singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showPass = !showPass }) {
                    Icon(if (showPass) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, "Show", tint = Mist)
                }
            })
        Spacer(Modifier.height(10.dp))
        Text(strings.passwordHint, color = TextHi, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { acceptedTerms = !acceptedTerms }) {
            Checkbox(checked = acceptedTerms, onCheckedChange = { acceptedTerms = it },
                colors = CheckboxDefaults.colors(checkedColor = Mist, uncheckedColor = Mist, checkmarkColor = Night))
            Text(strings.acceptPrefix, color = Mist, style = MaterialTheme.typography.bodyMedium)
            Text(strings.licenseAgreement, color = TextHi, style = MaterialTheme.typography.bodyMedium,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
        }

        error?.let {
            Text(it, color = Low, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        }

        Spacer(Modifier.height(26.dp))
        Button(
            onClick = {
                loading = true; error = null
                scope.launch {
                    try {
                        val r = withContext(Dispatchers.IO) { Api.service.login(LoginRequest(email.trim(), password)) }
                        if (r.token != null) { Session.token = r.token; Session.user = r.user; onSuccess() }
                        else error = r.message ?: strings.loginError
                    } catch (e: Exception) { error = strings.noConnection }
                    finally { loading = false }
                }
            },
            enabled = canLogin,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BtnFill, contentColor = TextHi,
                disabledContainerColor = Night2, disabledContentColor = Faint)
        ) {
            if (loading) CircularProgressIndicator(color = TextHi, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            else Text(strings.logIn, style = MaterialTheme.typography.labelLarge)
        }
    }
}
