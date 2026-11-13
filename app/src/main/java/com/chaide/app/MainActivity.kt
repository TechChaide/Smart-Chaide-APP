package com.chaide.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.chaide.app.net.GatewaySettings
import com.chaide.app.net.Session
import com.chaide.app.ui.AppNav
import com.chaide.app.ui.theme.ChaideTheme
import com.chaide.app.ui.theme.Night

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        GatewaySettings.init(this)
        setContent {
            ChaideTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Night) {
                    AppNav()
                }
            }
        }
    }
}
