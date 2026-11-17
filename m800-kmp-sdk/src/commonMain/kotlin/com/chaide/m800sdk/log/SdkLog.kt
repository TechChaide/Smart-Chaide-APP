package com.chaide.m800sdk.log

expect fun platformLog(message: String)

object SdkLog {
    fun log(message: Any?) {
        platformLog(message?.toString() ?: "null")
    }
}
