package com.chaide.m800sdk.protocol

/** Port of `com.sleepace.sdk.manager.CallbackData`. */
class CallbackData<T> {
    var status: Short = StatusCode.FAIL
    var frameType: Byte = -1
    var msgType: Short = 0
    var callbackType: String? = null
    var result: T? = null
    var data: ShortArray? = null

    val isSuccess: Boolean get() = status.toInt() == 0

    override fun toString(): String =
        "CallbackData{ status=$status, type=$callbackType, result=$result}"
}
