package com.chaide.m800sdk.platform

import android.content.Context

/**
 * `commonMain` cannot reference `android.content.Context` directly. Call
 * [M800SdkAndroid.init] once (e.g. from `Application.onCreate`) before using
 * anything in this module on Android — this is the only Android-specific
 * setup step the module needs.
 */
object M800SdkAndroid {
    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isInitialized(): Boolean = this::appContext.isInitialized
}
