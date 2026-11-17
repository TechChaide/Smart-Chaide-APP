package com.chaide.m800sdk.log

import platform.Foundation.NSLog

actual fun platformLog(message: String) {
    NSLog("M800Sdk: %s", message)
}
