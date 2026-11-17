package com.chaide.m800sdk.m800.domain

import com.chaide.m800sdk.util.PortableBuffer

/**
 * Live-streaming domain models actually consumed by `M800Manager`'s
 * `parsePost`/`parseRespone`. The original's historical sleep-report
 * classes (`Analysis`/`Detail`/`Summary`/`HistoryData...`) are post-hoc
 * scoring data not touched by the live BLE/TCP gateway path this module
 * targets, and `Analysis` in particular was never actually read during
 * this port (19KB class, by far the largest in the SDK) — rather than
 * fabricate its fields, it was deliberately left out. Add it from the
 * decompiled source in `kmp-sdk-research/decompiled/` if a caller needs it.
 */
data class RealTimeData(
    var heartRate: Short = 0,
    var breathRate: Short = 0,
    var status: Byte = 0,
    var statusValue: Short = 0,
    var temp: Byte = 0,
    var humidity: Byte = 0,
    var sleepFlag: Int = 0,
    var wakeFlag: Int = 0
) {
    companion object {
        fun fromBuffer(buffer: PortableBuffer): RealTimeData {
            buffer.getInt()
            val heartRate = (buffer.get().toInt() and 0xFF).toShort()
            val breathRate = (buffer.get().toInt() and 0xFF).toShort()
            val status = buffer.get()
            val statusValue = buffer.getShort()
            val sleepFlag = buffer.get().toInt()
            val wakeFlag = buffer.get().toInt()
            val temp = buffer.get()
            val humidity = buffer.get()
            return RealTimeData(heartRate, breathRate, status, statusValue, temp, humidity, sleepFlag, wakeFlag)
        }
    }
}

data class EnvironmentData(
    var temperature: Byte = 0,
    var humidity: Byte = 0
)

data class CollectState(
    var state: Byte = 0,
    var startTime: Int = 0
)

data class SleepState(
    var asleepFlag: Byte = 0,
    var wakeupFlag: Byte = 0,
    var outOfBedFlag: Byte = 0,
    var situpFlag: Byte = 0
)

data class OriginalData(
    var heartRate: FloatArray? = null,
    var breathRate: FloatArray? = null,
    var rawData: IntArray? = null
)
