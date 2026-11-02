package com.xgimi.gmpf.api

/** Firmware boundary double: ordinary writes are ignored while Boost is active. */
class DisplayManager {
    var lens = 1
    var level = 10
    var rejectModeChange = false
    var automatic = false
    var format: Byte = EN_XGIMI_3D_NONE
    val calls = mutableListOf<String>()
    fun getLensDispMode() = lens
    fun setLensDispMode(mode: Int): Boolean {
        calls += "mode:$mode"
        if (rejectModeChange) return false
        lens = mode
        return true
    }
    fun getDlpLumensLevel(): Byte = level.toByte()
    fun setDlpLumensLevel(value: Byte) {
        calls += "level:$value"
        if (lens == 0) level = value.toInt()
    }
    fun getALOnOff() = automatic
    fun getCurrent3DFormat() = format
    companion object {
        @JvmField val EN_XGIMI_3D_NONE: Byte = 1
        private val instance = DisplayManager()
        @JvmStatic fun getInstance() = instance
    }
}
