package com.chaide.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.chaide.app.platform.AndroidApp
import com.chaide.m800sdk.platform.M800SdkAndroid

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidApp.init(this)
        // Unico paso de setup que pide el SDK M800 en Android (ver m800-kmp-sdk/README.md).
        if (!M800SdkAndroid.isInitialized()) M800SdkAndroid.init(this)
        initApp()
        setContent { App() }
    }
}
