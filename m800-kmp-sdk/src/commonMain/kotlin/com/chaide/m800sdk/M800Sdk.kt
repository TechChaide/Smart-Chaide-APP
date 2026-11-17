package com.chaide.m800sdk

import com.chaide.m800sdk.ble.BleTransportProvider
import com.chaide.m800sdk.m800.M800Helper
import com.chaide.m800sdk.wificonfig.WiFiConfigHelper

/**
 * Single entry point for the module. On Android, call
 * `com.chaide.m800sdk.platform.M800SdkAndroid.init(context)` once before
 * using either of these (e.g. from `Application.onCreate`); iOS needs no
 * init step.
 */
object M800Sdk {
    /** TCP gateway session (login, live heart/breath-rate streaming) — talks to this repo's `Gateway.java`. */
    fun m800Helper(): M800Helper = M800Helper.getInstance()

    /** BLE WiFi-provisioning session — same flow as `M800BleProvisioner.provisionWifi()` in the Android app. */
    fun wifiConfigHelper(): WiFiConfigHelper = WiFiConfigHelper.getInstance(BleTransportProvider.create())
}
