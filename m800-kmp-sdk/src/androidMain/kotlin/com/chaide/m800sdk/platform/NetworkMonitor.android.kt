package com.chaide.m800sdk.platform

import android.content.Context
import android.net.ConnectivityManager

actual object NetworkMonitor {
    actual fun isConnected(): Boolean {
        if (!appContextInitialized()) return true
        val cm = M800SdkAndroid.appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val info = cm.activeNetworkInfo
        return info?.isAvailable ?: true
    }

    private fun appContextInitialized(): Boolean = try {
        M800SdkAndroid.appContext
        true
    } catch (e: Exception) {
        false
    }
}
