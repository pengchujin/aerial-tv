package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** ChangHong mt5520_ll (Android 5.1): EasySetting is a left-hand window hosted by a service. */
internal object ChanghongSettings {
    const val PACKAGE = "com.changhong.easysetting"
    const val NETWORK = "StatusBar_to_netset"
    const val PICTURE = "StatusBar_to_videoset"
    val component = ComponentName(PACKAGE, "$PACKAGE.MainService")

    fun intent(context: Context, page: String? = null): Intent? {
        if (!DeviceProfile.current(context).changhongTvos) return null
        val intent = Intent("Changhong.EasySetting").setComponent(component)
        if (page != null) intent.putExtra(page, true)
        return intent
    }

    fun open(context: Context, intent: Intent): Boolean =
        DeviceProfile.current(context).changhongTvos && runCatching { context.startService(intent) }.getOrNull() != null
}

/** The mt5520_ll firmware exposes panel backlight through TVOS, not screen_brightness. */
internal object ChanghongPicture {
    private var failed = false

    private fun manager(context: Context): Any? {
        if (failed || !DeviceProfile.current(context).changhongTvos) return null
        return runCatching {
            val type = Class.forName("com.changhong.tvos.common.TVManager", true, context.classLoader)
            val tv = type.getMethod("getInstance", Context::class.java).invoke(null, context.applicationContext)
                ?: return null
            type.getMethod("getPictureManager").invoke(tv)
        }.getOrNull()
    }

    fun backlight(context: Context): Int? {
        val picture = manager(context) ?: return null
        return runCatching { picture.javaClass.getMethod("getBackLight").invoke(picture) as Int }
            .getOrNull()?.takeIf { it in 1..100 }
    }

    fun setBacklight(context: Context, percent: Int) {
        val picture = manager(context) ?: return
        val level = percent.coerceIn(1, 100)
        val accepted = runCatching {
            picture.javaClass.getMethod("setBackLight", Int::class.javaPrimitiveType).invoke(picture, level) == true &&
                picture.javaClass.getMethod("getBackLight").invoke(picture) as Int == level
        }.getOrDefault(false)
        if (!accepted) failed = true
    }
}
