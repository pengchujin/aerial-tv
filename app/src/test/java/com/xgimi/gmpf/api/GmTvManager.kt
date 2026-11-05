package com.xgimi.gmpf.api

class GmTvManager {
    var imax = false
    fun isImaxVideoPlaying() = imax
    companion object {
        private val instance = GmTvManager()
        @JvmStatic fun getInstance() = instance
    }
}
