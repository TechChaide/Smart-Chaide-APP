package com.chaide.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaide.app.resources.Res
import com.chaide.app.resources.ic_chaide_moon
import com.chaide.app.resources.welcome_bg
import com.chaide.app.ui.theme.*

/** Réplica fiel de la pantalla real de bienvenida: foto real
 * (`login_pic_launch`, extraída del bundle H5 real) + overlay azul + logo
 * real de la luna (`iconos chaide/Chaide.svg`, provisto por el usuario). */
@Composable
fun WelcomeScreen(onLogin: () -> Unit, onSignUp: () -> Unit = {}) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(Res.drawable.welcome_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x99174B93), 0.5f to Color(0xCC123D7A), 1f to Night)
            )
        )
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painter = painterResource(Res.drawable.ic_chaide_moon), contentDescription = null, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(18.dp))
            Text("ChaideSmart", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Button(
                onClick = onSignUp,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14437E), contentColor = Color.White)
            ) { Text("Sign Up", style = MaterialTheme.typography.labelLarge) }

            Button(
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9AA7B8), contentColor = Color(0xFF13305C))
            ) { Text("Log in", style = MaterialTheme.typography.labelLarge) }
        }
    }
}
