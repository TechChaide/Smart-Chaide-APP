package com.chaide.m800sdk.m800

object M800DataPacket {
    object MsgType {
        const val LOGIN: Short = 517
        const val REALTIME_DATA_OPEN: Short = 7
        const val REALTIME_DATA_CLOSE: Short = 8
        const val RAW_DATA_OPEN: Short = 15
        const val RAW_DATA_CLOSE: Short = 16
        const val COLLECT_STOP: Short = 10
        const val DEVICE_UPGRADE: Short = 32
        const val QUERY_ONLINE_STATE: Short = 1025
        const val QUERY_COLLECT_STATE: Short = 1026
        const val QUERY_SLEEP_STATE: Short = 1030
        const val QUERY_ENVIRONMENT_DATA: Short = 1031
        const val REALTIME_DATA: Short = 1536
        const val RAW_DATA: Short = 1286
        const val GET_WIFI_SIGNAL: Short = 1188
    }
}

/** Port of `com.sleepace.sdk.m800.CallbackType`. */
object CallbackType {
    const val LOGIN_SERVER = "login_server"
    const val DEVICE_LIST = "device_list"
    const val DEVICE_BIND = "device_bind"
    const val DEVICE_UNBIND = "device_unbind"
    const val SLEEP_REPORT = "sleep_report"
    const val ONLINE_STATE_GET = "online_state_get"
    const val ONLINE_STATE_POST = "online_state_post"
    const val COLLECT_STATUS_GET = "collect_status_get"
    const val COLLECT_STOP = "collect_stop"
    const val ENVIRONMENT_DATA_GET = "environment_data_get"
    const val REALTIME_DATA = "realtime_data"
    const val REALTIME_DATA_START = "realtime_data_start"
    const val REALTIME_DATA_STOP = "realtime_data_stop"
    const val RAW_DATA = "raw_data"
    const val RAW_DATA_START = "raw_data_start"
    const val RAW_DATA_STOP = "raw_data_stop"
    const val DEVICE_UPGRADE = "device_upgrade"
    const val SLEEP_STATE_GET = "sleep_state_get"
    const val SLEEP_STATE_POST = "sleep_state_post"
    const val DEVICE_STATE_POST = "device_state_post"
    const val HISTORY_DATA_DOWNLOAD = "history_data_download"
}
