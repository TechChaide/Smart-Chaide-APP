package com.chaide.m800sdk.protocol

/** Port of `com.sleepace.sdk.manager.DeviceMaterial`. */
object DeviceMaterial {
    fun checkDeviceMaterial(deviceType: Short, material: Int): Boolean {
        if (deviceType == DeviceType.DEVICE_TYPE_PILLOW.type) return material in 0..6
        if (deviceType == DeviceType.DEVICE_TYPE_Z4.type) return material in 3..4
        return true
    }
}

/**
 * Port of `com.sleepace.sdk.manager.DeviceType`. Kotlin enums can't carry
 * mutable custom/material state as cleanly as the Java original (which
 * mutated a shared enum constant for `DEVICE_TYPE_CUSTOM`), so `material`
 * is a plain `var` here too, kept for fidelity with the wire protocol checks
 * that read it.
 */
enum class DeviceType(typeInt: Int, var material: Int) {
    DEVICE_TYPE_PHONE(-1, 0),
    DEVICE_TYPE_NOX1(2, 0),
    DEVICE_TYPE_PILLOW(3, 0),
    DEVICE_TYPE_Z3(4, 0),
    DEVICE_TYPE_M600(8, 0),
    DEVICE_TYPE_Z2(9, 0),
    DEVICE_TYPE_SLEEPDOT(10, 0),
    DEVICE_TYPE_NOX_2B(11, 0),
    DEVICE_TYPE_NOX_2W(12, 0),
    DEVICE_TYPE_SLEEPDOT_502(16, 0),
    DEVICE_TYPE_SLEEPDOT_502T(17, 0),
    DEVICE_TYPE_Z4(22, 4),
    DEVICE_TYPE_NOX_SAW(23, 0),
    DEVICE_TYPE_NOX_SAB(24, 0),
    DEVICE_TYPE_Z4_TWB(26, 0),
    DEVICE_TYPE_Z4_TWP(27, 0),
    DEVICE_TYPE_EW201B(29, 0),
    DEVICE_TYPE_EW201W(30, 0),
    DEVICE_TYPE_BINATONE(33, 0),
    DEVICE_TYPE_P3(34, 3),
    DEVICE_TYPE_Z400TWP_2(37, 0),
    DEVICE_TYPE_Z400TWP_3(20489, 0),
    DEVICE_TYPE_M800(38, 0),
    DEVICE_TYPE_BINATONE_MTU(42, 0),
    DEVICE_TYPE_SN913E(44, 0),
    DEVICE_TYPE_P200A(46, 0),
    DEVICE_TYPE_Z400T_2(47, 0),
    DEVICE_TYPE_BM8701_2(49, 0),
    DEVICE_TYPE_BG001A(50, 0),
    DEVICE_TYPE_M8701W(52, 0),
    DEVICE_TYPE_EW202W(53, 0),
    DEVICE_TYPE_BG002(54, 0),
    DEVICE_TYPE_SDC100(58, 0),
    DEVICE_TYPE_M901L(59, 0),
    DEVICE_TYPE_P103T(60, 0),
    DEVICE_TYPE_BM8701(32777, 0),
    DEVICE_TYPE_FH601W(32787, 0),
    DEVICE_TYPE_ZP100(20482, 0),
    DEVICE_TYPE_SM100(20494, 0),
    DEVICE_TYPE_SM200(20495, 0),
    DEVICE_TYPE_SM300(20737, 0),
    DEVICE_TYPE_P102T(20491, 0),
    DEVICE_TYPE_P401M(20490, 0),
    DEVICE_TYPE_CUSTOM(0, 0);

    // Matches the original Java constructor's implicit `(short) deviceType` narrowing
    // cast — several device codes (e.g. 32777) exceed Short.MAX_VALUE and wrap.
    val type: Short = typeInt.toShort()

    companion object {
        fun isM800(d: DeviceType?) = d == DEVICE_TYPE_M800
        fun isM600(d: DeviceType?) = d == DEVICE_TYPE_M600
        fun isM901L(d: DeviceType?) = d == DEVICE_TYPE_M901L
        fun isZ2(d: DeviceType?) = d == DEVICE_TYPE_Z2
        fun isZ3(d: DeviceType?) = d == DEVICE_TYPE_Z3
        fun isZ4(d: DeviceType?) = d == DEVICE_TYPE_Z4
        fun isZ4TWB(d: DeviceType?) = d == DEVICE_TYPE_Z4_TWB
        fun isZ4TWP(d: DeviceType?) = d == DEVICE_TYPE_Z4_TWP
        fun isZ400TWP2(d: DeviceType?) = d == DEVICE_TYPE_Z400TWP_2
        fun isZ400TWP3(d: DeviceType?) = d == DEVICE_TYPE_Z400TWP_3
        fun isZ4TW(d: DeviceType?) = isZ4TWB(d) || isZ4TWP(d) || isZ400TWP2(d)
        fun isReston(d: DeviceType?) = isZ2(d) || isZ3(d) || isZ4(d) || isZ4TW(d)
        fun isSleepDot502(d: DeviceType?) = d == DEVICE_TYPE_SLEEPDOT_502
        fun isSleepDot502T(d: DeviceType?) = d == DEVICE_TYPE_SLEEPDOT_502T
        fun isSleepDot(d: DeviceType?) = d == DEVICE_TYPE_SLEEPDOT || isSleepDot502(d) || isSleepDot502T(d)
        fun isPillow(d: DeviceType?) = d == DEVICE_TYPE_PILLOW
        fun isMonitorDevice(d: DeviceType?) = isReston(d) || isPillow(d) || isSleepDot(d)
        fun isHeartBreathDevice(d: DeviceType?) = isReston(d) || isPillow(d)
        fun isBleDevice(d: DeviceType?) = isReston(d) || isPillow(d) || isSleepDot(d)
        fun isNox1(d: DeviceType?) = d == DEVICE_TYPE_NOX1
        fun isNox2B(d: DeviceType?) = d == DEVICE_TYPE_NOX_2B
        fun isNox2W(d: DeviceType?) = d == DEVICE_TYPE_NOX_2W
        fun isNox2(d: DeviceType?) = isNox2B(d) || isNox2W(d)
        fun isNoxSAB(d: DeviceType?) = d == DEVICE_TYPE_NOX_SAB
        fun isNoxSAW(d: DeviceType?) = d == DEVICE_TYPE_NOX_SAW
        fun isNoxSA(d: DeviceType?) = isNoxSAB(d) || isNoxSAW(d)
        fun isP3(d: DeviceType?) = d == DEVICE_TYPE_P3
        fun isEW201B(d: DeviceType?) = d == DEVICE_TYPE_EW201B
        fun isEW201W(d: DeviceType?) = d == DEVICE_TYPE_EW201W
        fun isEW202W(d: DeviceType?) = d == DEVICE_TYPE_EW202W
        fun isSN913E(d: DeviceType?) = d == DEVICE_TYPE_SN913E
        fun isP200A(d: DeviceType?) = d == DEVICE_TYPE_P200A
        fun isBM8701(d: DeviceType?) = d == DEVICE_TYPE_BM8701
        fun isBM8701_2(d: DeviceType?) = d == DEVICE_TYPE_BM8701_2
        fun isBG001A(d: DeviceType?) = d == DEVICE_TYPE_BG001A
        fun isBG002(d: DeviceType?) = d == DEVICE_TYPE_BG002
        fun isSDC100(d: DeviceType?) = d == DEVICE_TYPE_SDC100
        fun isM8701W(d: DeviceType?) = d == DEVICE_TYPE_M8701W
        fun isFH601W(d: DeviceType?) = d == DEVICE_TYPE_FH601W
        fun isBinatoneDevice(d: DeviceType?) = d == DEVICE_TYPE_BINATONE
        fun isBinatoneMTUDevice(d: DeviceType?) = d == DEVICE_TYPE_BINATONE_MTU
        fun isZ400T_2(d: DeviceType?) = d == DEVICE_TYPE_Z400T_2

        fun getDeviceType(deviceCode: String): DeviceType? {
            if (!com.chaide.m800sdk.util.StringUtilCommon.checkDeviceCode(deviceCode)) return null
            val ss = deviceCode.split("-")
            val dType = ss[0].toShort()
            val material = ss[1].toInt()
            return getDeviceType(dType, material)
        }

        fun getDeviceType(deviceType: Short): DeviceType? = getDeviceType(deviceType, 0)

        fun getDeviceType(deviceType: Short, material: Int): DeviceType? {
            for (t in entries) {
                if (t.type == deviceType && DeviceMaterial.checkDeviceMaterial(deviceType, material)) {
                    t.material = material
                    return t
                }
            }
            return null
        }
    }
}

/** Port of `com.sleepace.sdk.constant.DeviceCode`. */
object DeviceCode {
    const val Z2_9_0 = "9-0"
    const val Z4_22_3 = "22-3"
    const val Z4_22_4 = "22-4"
    const val PILLOW_3_0 = "3-0"
    const val PILLOW_3_1 = "3-1"
    const val PILLOW_3_2 = "3-2"
    const val PILLOW_3_3 = "3-3"
    const val PILLOW_3_4 = "3-4"
    const val PILLOW_3_5 = "3-5"
    const val PILLOW_3_6 = "3-6"
    const val PILLOW_34_3 = "34-3"
    const val DOT_10_0 = "10-0"
    const val DOT_16_0 = "16-0"
    const val DOT_17_0 = "17-0"
    val P200A = "${DeviceType.DEVICE_TYPE_P200A.type}-${DeviceType.DEVICE_TYPE_P200A.material}"
    val P102T = "${DeviceType.DEVICE_TYPE_P102T.type}-${DeviceType.DEVICE_TYPE_P102T.material}"
    val P401M = "${DeviceType.DEVICE_TYPE_P401M.type}-${DeviceType.DEVICE_TYPE_P401M.material}"
}
