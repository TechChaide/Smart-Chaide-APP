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
import com.chaide.app.net.Api
import com.chaide.app.net.ApiException
import com.chaide.app.net.RegisterRequest
import com.chaide.app.net.Session
import com.chaide.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mismo layout real que [LoginScreen] (fondo azul plano, campos subrayados,
 *  checkbox de términos) con el campo extra de confirmar contraseña que pide
 *  el registro real (`password2`, igual que `/pro/account/register/`, pero
 *  creado directo en `auth.controller.js` del backend de pruebas). */
@Composable
fun SignUpScreen(onBack: () -> Unit, onSuccess: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var acceptedTerms by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val canSubmit = email.isNotBlank() && password.length in 6..16 &&
        password == confirmPassword && acceptedTerms && !loading

    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        focusedTextColor = TextHi, unfocusedTextColor = TextHi,
        focusedIndicatorColor = Mist, unfocusedIndicatorColor = Hairline,
        cursorColor = TextHi, focusedLabelColor = Mist, unfocusedLabelColor = Mist,
    )

    Column(Modifier.fillMaxSize().background(Night).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextHi) }
        }
        Spacer(Modifier.height(18.dp))
        Text("Sign Up", style = MaterialTheme.typography.headlineLarge, color = TextHi)
        Spacer(Modifier.height(36.dp))

        TextField(email, { email = it; error = null }, label = { Text("Email") },
            singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(28.dp))
        TextField(password, { password = it; error = null }, label = { Text("Password") },
            singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showPass = !showPass }) {
                    Icon(if (showPass) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, "Show", tint = Mist)
                }
            })
        Spacer(Modifier.height(10.dp))
        Text("Use 6-16 characters (letters numbers symbols)", color = TextHi, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        TextField(confirmPassword, { confirmPassword = it; error = null }, label = { Text("Confirm Password") },
            singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { acceptedTerms = !acceptedTerms }) {
            Checkbox(checked = acceptedTerms, onCheckedChange = { acceptedTerms = it },
                colors = CheckboxDefaults.colors(checkedColor = Mist, uncheckedColor = Mist, checkmarkColor = Night))
            Text("Accept ChaideSmart ", color = Mist, style = MaterialTheme.typography.bodyMedium)
            Text("License Agreement", color = TextHi, style = MaterialTheme.typography.bodyMedium,
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
                        val r = withContext(Dispatchers.IO) {
                            Api.service.register(RegisterRequest(email.trim(), password, confirmPassword))
                        }
                        if (r.token != null) { Session.token = r.token; Session.user = r.user; onSuccess() }
                        else error = r.message ?: "Could not create the account"
                    } catch (e: ApiException) {
                        error = e.errorMessage ?: "Could not create the account"
                    } catch (e: Exception) {
                        error = "No connection to server"
                    } finally { loading = false }
                }
            },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BtnFill, contentColor = BtnText,
                disabledContainerColor = Night2, disabledContentColor = Faint)
        ) {
            if (loading) CircularProgressIndicator(color = BtnText, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            else Text("Sign Up", style = MaterialTheme.typography.labelLarge)
        }
    }
}
