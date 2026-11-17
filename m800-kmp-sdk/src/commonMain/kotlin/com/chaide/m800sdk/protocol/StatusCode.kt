package com.chaide.m800sdk.protocol

object StatusCode {
    const val SUCCESS: Short = 0
    const val DISCONNECT: Short = -1
    const val TIMEOUT: Short = -2
    const val FAIL: Short = -3
    const val NOT_ENABLE: Short = -4
    const val PARAMETER_ERROR: Short = -5
    const val CERTIFICATION_EXPIRED: Short = -6
}
