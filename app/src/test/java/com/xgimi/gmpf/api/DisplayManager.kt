package com.xgimi.gmpf.api

/** Firmware boundary double: ordinary writes are ignored while Boost is active. */
class DisplayManager {
    var lens = 1
    var level = 10
    var rejectModeChange = false
    var unavailable = false
    val calls = mutableListOf<String>()
    fun getLensDispMode() = lens
    fun setLensDispMode(mode: Int): Boolean {
        calls += "mode:$mode"
        if (rejectModeChange) return false
        lens = mode
        return true
    }
    fun getDlpLumensLevel(): Byte {
        check(!unavailable)
        return level.toByte()
    }
    fun setDlpLumensLevel(value: Byte) {
        calls += "level:$value"
        if (lens == 0) level = value.toInt()
    }
    companion object {
        private val instance = DisplayManager()
        @JvmStatic fun getInstance() = instance
    }
}
