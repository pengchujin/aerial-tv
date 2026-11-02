package com.jamdeo.tv

import android.content.Context
import com.jamdeo.tv.common.ConfigOptionManager as Native
import com.jamdeo.tv.common.EnumConstants

class TvManager {
    companion object { @JvmStatic fun getInstance() = TvManager() }
    fun getAudioManager(context: Context) = AudioManager()
}

class AudioManager {
    fun isConnected() = true
    fun setVolume(level: Int) = Native.setConfigurationIntValue(EnumConstants.AudioOptions.TIL_VOLUME, level)
    fun setMuteFlag(muted: Boolean) = Native.setConfigurationBooleanValue(EnumConstants.AudioOptions.TIL_VOLUME_MUTE, muted)
}
