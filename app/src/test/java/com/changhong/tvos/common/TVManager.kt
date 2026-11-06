package com.changhong.tvos.common

import android.content.Context

class TVManager {
    val picture = PictureManager()
    var providesPicture = true
    fun getPictureManager() = picture.takeIf { providesPicture }

    companion object {
        var instance = TVManager()
        @JvmStatic fun getInstance(context: Context): TVManager = instance
    }
}

class PictureManager {
    var level = 50
    var acceptsWrites = true
    var appliesWrites = true
    var throws = false
    var throwsOnWrite = false

    fun getBackLight(): Int {
        if (throws) throw SecurityException("Unavailable")
        return level
    }

    fun setBackLight(percent: Int): Boolean {
        if (throws || throwsOnWrite) throw SecurityException("Unavailable")
        if (acceptsWrites && appliesWrites) level = percent
        return acceptsWrites
    }
}
