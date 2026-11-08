package app.orchard.tv.settings

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The interface language: the TV's own (Auto, the default) or one picked in Personalize, for a TV
 * whose system language is not the owner's (a Chinese owner, a TV set to English).
 *
 * The choice is read straight from SharedPreferences because [wrap] runs in attachBaseContext, before
 * [AerialSettings.init]. It also sets the default locale, which code that picks between zh and en
 * texts (Wallpapers, Weather) reads through Locale.getDefault().
 */
enum class AppLanguage(val key: String, val locale: Locale?) {
    Auto("auto", null), Chinese("zh", Locale.SIMPLIFIED_CHINESE), English("en", Locale.ENGLISH);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Auto

        fun saved(context: Context): AppLanguage =
            of(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("language", null))

        /** [base] with the chosen language applied; [base] itself for Auto. */
        fun wrap(base: Context): Context {
            val locale = saved(base).locale ?: return base
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            return base.createConfigurationContext(config)
        }
    }
}
