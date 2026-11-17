package com.chaide.m800sdk.platform

/**
 * Original Java SDK only used this as an optional gate (null Context
 * meant "skip the check"), so a platform that cannot answer reliably
 * is free to always report connected.
 */
expect object NetworkMonitor {
    fun isConnected(): Boolean
}
