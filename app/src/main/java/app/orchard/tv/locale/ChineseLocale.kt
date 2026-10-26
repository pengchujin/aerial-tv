package app.orchard.tv.locale

import java.util.Locale

/** Android infers Hant for zh-HK/TW/MO even when Locale.script is empty. */
object ChineseLocale {
    fun isTraditional(locale: Locale): Boolean {
        if (locale.language != "zh") return false
        if (locale.script.isNotEmpty()) return locale.script.equals("Hant", ignoreCase = true)
        return locale.country in setOf("HK", "MO", "TW")
    }
}
