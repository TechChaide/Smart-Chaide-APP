package com.chaide.m800sdk.log

actual fun platformLog(message: String) {
    android.util.Log.d("M800Sdk", message)
}
