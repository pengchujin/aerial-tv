package com.jamdeo.tv.common

/** Firmware-shaped test double: the production bridge must resolve names, not assumed option IDs. */
object EnumConstants {
    object AudioOptions {
        const val TIL_VOLUME = 41
        const val TIL_VOLUME_MUTE = 42
    }
    object PictureOptions {
        const val TIL_BACK_LIGHT_CUSTOM = 43
        const val TIL_BACK_LIGHT_MODE = 44
    }
    object SystemOptions { const val TIL_SENSOR_ENABLE = 45 }
}

object ConfigOptionManager {
    val values = mutableMapOf<Int, Int>()
    var muted = false
    var acceptWrites = true
    @JvmStatic fun getConfigurationIntValue(option: Int) = values[option] ?: -1
    @JvmStatic fun setConfigurationIntValue(option: Int, value: Int): Boolean {
        if (acceptWrites) values[option] = value
        return acceptWrites
    }
    @JvmStatic fun getConfigurationBooleanValue(option: Int): Boolean {
        if (option == EnumConstants.SystemOptions.TIL_SENSOR_ENABLE) return false
        require(option == EnumConstants.AudioOptions.TIL_VOLUME_MUTE)
        return muted
    }
    @JvmStatic fun setConfigurationBooleanValue(option: Int, value: Boolean): Boolean {
        require(option == EnumConstants.AudioOptions.TIL_VOLUME_MUTE)
        if (acceptWrites) muted = value
        return acceptWrites
    }
}
