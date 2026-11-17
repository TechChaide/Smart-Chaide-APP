package com.chaide.m800sdk.platform

/**
 * TODO: wire to `Network.framework`'s `NWPathMonitor` for a real check.
 * The original Java SDK treated a null `Context` (i.e. "can't tell") as
 * "assume connected" and only ever used this as an optional pre-flight
 * gate before a request it would time out on anyway — so this is a safe,
 * behavior-preserving default while that integration is pending, not a
 * functional gap in the ported protocol itself.
 */
actual object NetworkMonitor {
    actual fun isConnected(): Boolean = true
}
