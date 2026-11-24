package app.orchard.tv.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.wallpaper.Wallpapers
import org.json.JSONObject

data class HomeTheme(val title: String, val accent: String, val wallpaper: String, val columns: Int) {
    fun json() = JSONObject().put("title", title).put("accent", accent)
        .put("wallpaper", wallpaper).put("columns", columns)

    companion object {
        fun parse(raw: String): HomeTheme {
            require(raw.length <= 4096)
            val data = JSONObject(raw)
            return HomeTheme(data.getString("title").trim(), data.getString("accent"),
                data.getString("wallpaper"), data.getInt("columns")).also {
                require(it.title.length in 1..64 && it.accent.matches(Regex("#[0-9a-fA-F]{6}")))
                require(it.columns in AerialSettings.HOME_COLUMNS && it.wallpaper in Wallpapers.FEATURED_BUILTINS)
            }
        }
    }
}

object HomeExtensions {
    private const val CARD_ACTION = "app.orchard.tv.action.HOME_CARD"
    private fun prefs(context: Context) = context.getSharedPreferences("home_extensions", 0)
    var accent by mutableStateOf(Color(0xFF0A84FF))
        private set
    var preview by mutableStateOf<HomeTheme?>(null)
        private set

    fun init(context: Context) {
        val saved = prefs(context)
        accent = Color(saved.getInt("accent", android.graphics.Color.rgb(10, 132, 255)))
        preview = saved.getString("preview", null)?.let { runCatching { HomeTheme.parse(it) }.getOrNull() }
    }

    fun stage(context: Context, theme: HomeTheme) {
        prefs(context).edit().putString("preview", theme.json().toString()).apply()
        preview = theme
    }

    fun apply(context: Context, theme: HomeTheme) {
        val color = android.graphics.Color.parseColor(theme.accent)
        prefs(context).edit().putInt("accent", color).apply()
        accent = Color(color)
        AerialSettings.chooseHomeColumns(theme.columns)
        Wallpapers.select(theme.wallpaper)
    }

    fun reset(context: Context) {
        prefs(context).edit().remove("accent").remove("preview").apply()
        accent = Color(0xFF0A84FF)
        preview = null
        AerialSettings.chooseHomeColumns(5)
        Wallpapers.select(Wallpapers.LATEST_DAILY)
    }

    data class Card(val component: ComponentName, val grantKey: String, val title: String, val summary: String)

    fun cards(context: Context): List<Card> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(CARD_ACTION), PackageManager.GET_META_DATA).mapNotNull { match ->
            runCatching {
                val info = match.activityInfo
                require(info.exported && info.permission.isNullOrEmpty())
                val component = ComponentName(info.packageName, info.name)
                val installed = pm.getPackageInfo(info.packageName, 0).firstInstallTime
                Card(component, "${component.flattenToString()}@$installed",
                    info.loadLabel(pm).toString().take(64),
                    info.descriptionRes.takeIf { it != 0 }?.let { pm.getText(info.packageName, it, info.applicationInfo) }
                        ?.toString().orEmpty().take(120))
            }.getOrNull()
        }
    }

    fun allowed(context: Context, card: Card) = prefs(context).getBoolean(card.grantKey, false)

    fun allow(context: Context, card: Card, yes: Boolean) {
        prefs(context).edit().apply { if (yes) putBoolean(card.grantKey, true) else remove(card.grantKey) }.apply()
    }

    fun entries(context: Context): List<AppEntry> = cards(context).filter { allowed(context, it) }.mapNotNull { card ->
        runCatching {
            val icon = context.packageManager.getActivityIcon(card.component)
            AppEntry("card:" + card.component.flattenToString(), card.title, card.component, false, false,
                TileArtFactory.fromIcon(icon), shortcut = Intent(CARD_ACTION).setComponent(card.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.getOrNull()
    }
}
