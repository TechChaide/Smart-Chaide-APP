package com.chaide.m800sdk.log

import platform.Foundation.NSLog

actual fun platformLog(message: String) {
    // Sin argumentos variadicos: un String de Kotlin en "%s"/"%@" rompe NSLog en Kotlin/Native.
    NSLog("M800Sdk: $message".replace("%", "%%"))
}
