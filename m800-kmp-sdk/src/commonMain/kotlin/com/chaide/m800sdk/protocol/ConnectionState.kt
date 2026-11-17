package com.chaide.m800sdk.protocol

/** Port of `com.sleepace.sdk.manager.CONNECTION_STATE`. */
enum class ConnectionState(val value: Byte) {
    CONNECTED(0),
    CONNECTING(1),
    DISCONNECT(2)
}
