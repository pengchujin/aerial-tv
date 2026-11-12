package app.orchard.tv.lite

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import java.security.MessageDigest

/** As the full app's FocusStyle: High Contrast adds the white frame (the default on D-pad remotes). */
enum class FocusStyle(val key: String) {
    Standard("standard"), HighContrast("high_contrast");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: HighContrast
    }
}

/** As the full app's UiSounds: the TV's own key sounds, Aerial's, or none. */
enum class UiSounds(val key: String) {
    Tv("tv"), Aerial("aerial"), Off("off");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** As the full app's Effects; in Lite, Full adds the focused tile's shadow and Lite drops every animation. */
/** 24- or 12-hour time, or as the TV is set; the full app's ClockHours (issues #1324 #1044). */
enum class ClockHours(val key: String) {
    System("system"), H24("24"), H12("12");

    companion object {
        fun of(key: String?) = values().firstOrNull { it.key == key } ?: System
    }
}

enum class Effects(val key: String) {
    Auto("auto"), Full("full"), Lite("lite");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Auto
    }
}

/**
 * Lite's preferences, under the full app's names and defaults (settings/AerialSettings.kt), so the
 * two read alike and the phone page's settings mean the same. Main thread only.
 */
object LiteSettings {
    private const val PREFS = "settings"
    private lateinit var prefs: SharedPreferences

    private var chosenFocusStyle = FocusStyle.HighContrast
    /** As chosen; always the white frame in Elders mode (Kids), as in the full app. */
    val focusStyle: FocusStyle get() = if (Kids.active && Kids.kind == KidsKind.Elders) FocusStyle.HighContrast else chosenFocusStyle
    var oneRowHome = true; private set
    var peekNextRow = true; private set
    /** Apps a row on Home and in folders, as the full app's: four, five or six. */
    private var chosenColumns = 5
    /** As chosen; [Kids.COLUMNS] in Kids and Elders mode. */
    val homeColumns: Int get() = if (Kids.active) Kids.COLUMNS else chosenColumns
    var homePlate = true; private set
    private var chosenAllNames = false
    /** As chosen; every name in Elders mode. */
    val showAllNames: Boolean get() = chosenAllNames || (Kids.active && Kids.kind == KidsKind.Elders)
    var clockRight = false; private set
    var largeClock = false; private set
    var clockHours = ClockHours.System; private set
    /** No app menus on Home or in folders, as the full app's homeLocked (issues #717 #1013 #1106). */
    var homeLocked = false; private set
    /** How often "My Photos, in turn" moves on, in minutes; the full app's photoMinutes (#1314). */
    var photoMinutes = DAY_MINUTES; private set
    const val DAY_MINUTES = 1440
    val PHOTO_MINUTES = listOf(DAY_MINUTES, 60, 15, 5, 1)
    var showWeather = true; private set
    var screensaverClock = true; private set
    var backStartsScreensaver = true; private set
    var uiSounds = UiSounds.Tv; private set
    var effects = Effects.Auto; private set
    var screensaverMinutes = 5; private set
    var bootApp: String? = null; private set

    const val NEVER = 0
    val HOME_COLUMNS = 4..6
    /** The full app's choices. */
    val screensaverChoices = listOf(2, 5, 10, 20, NEVER)

    var pinSet = false; private set
    var pinArrows = false; private set
    private var pinHash: String? = null
    private var unlockedUntil = 0L
    private const val UNLOCK_MS = 10 * 60 * 1000L

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        chosenFocusStyle = FocusStyle.of(prefs.getString("focus_style", null))
        oneRowHome = prefs.getBoolean("one_row_home", true)
        peekNextRow = prefs.getBoolean("peek_next_row", true)
        chosenColumns = prefs.getInt("home_columns", 5).takeIf { it in HOME_COLUMNS } ?: 5
        homePlate = prefs.getBoolean("home_plate", true)
        chosenAllNames = prefs.getBoolean("show_all_names", false)
        clockRight = prefs.getBoolean("clock_right", false)
        largeClock = prefs.getBoolean("large_clock", false)
        clockHours = ClockHours.of(prefs.getString("clock_hours", null))
        homeLocked = prefs.getBoolean("home_locked", false)
        photoMinutes = prefs.getInt("photo_minutes", DAY_MINUTES).takeIf { it in PHOTO_MINUTES } ?: DAY_MINUTES
        showWeather = prefs.getBoolean("show_weather", true)
        screensaverClock = prefs.getBoolean("screensaver_clock", true)
        backStartsScreensaver = prefs.getBoolean("back_starts_screensaver", true)
        // As the full app: the TV's own sounds where its key sound switch is on, else Aerial's.
        uiSounds = UiSounds.of(prefs.getString("ui_sounds_kind", null)) ?: if (
            runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) }.getOrDefault(1) == 1
        ) UiSounds.Tv else UiSounds.Aerial
        effects = Effects.of(prefs.getString("effects", null))
        screensaverMinutes = prefs.getInt("screensaver_minutes", 5)
        bootApp = prefs.getString("boot_app", null)
        pinHash = prefs.getString("pin_hash", null)
        pinSet = pinHash != null
        pinArrows = prefs.getBoolean("pin_arrows", false)
    }

    private fun put(block: SharedPreferences.Editor.() -> Unit) = prefs.edit(action = block)

    fun chooseFocusStyle(v: FocusStyle) { chosenFocusStyle = v; put { putString("focus_style", v.key) } }
    fun chooseOneRowHome(v: Boolean) { oneRowHome = v; put { putBoolean("one_row_home", v) } }
    fun chooseHomeColumns(v: Int) { if (v in HOME_COLUMNS) { chosenColumns = v; put { putInt("home_columns", v) } } }
    fun choosePeekNextRow(v: Boolean) { peekNextRow = v; put { putBoolean("peek_next_row", v) } }
    fun chooseHomePlate(v: Boolean) { homePlate = v; put { putBoolean("home_plate", v) } }
    fun chooseShowAllNames(v: Boolean) { chosenAllNames = v; put { putBoolean("show_all_names", v) } }
    fun chooseClockRight(v: Boolean) { clockRight = v; put { putBoolean("clock_right", v) } }
    fun chooseLargeClock(v: Boolean) { largeClock = v; put { putBoolean("large_clock", v) } }
    fun choosePhotoMinutes(v: Int) { if (v in PHOTO_MINUTES) { photoMinutes = v; put { putInt("photo_minutes", v) } } }
    fun chooseHomeLocked(v: Boolean) { homeLocked = v; put { putBoolean("home_locked", v) } }
    fun chooseClockHours(v: ClockHours) { clockHours = v; put { putString("clock_hours", v.key) } }
    fun chooseShowWeather(v: Boolean) { showWeather = v; put { putBoolean("show_weather", v) } }
    fun chooseScreensaverClock(v: Boolean) { screensaverClock = v; put { putBoolean("screensaver_clock", v) } }
    fun chooseBackStartsScreensaver(v: Boolean) { backStartsScreensaver = v; put { putBoolean("back_starts_screensaver", v) } }
    fun chooseUiSounds(v: UiSounds) { uiSounds = v; put { putString("ui_sounds_kind", v.key) } }
    fun chooseEffects(v: Effects) { effects = v; put { putString("effects", v.key) } }
    fun chooseScreensaverMinutes(v: Int) { screensaverMinutes = v; put { putInt("screensaver_minutes", v) } }
    fun chooseBootApp(pkg: String?) { bootApp = pkg; put { putString("boot_app", pkg) } }

    /** Animations and the focused tile's shadow: with hardware acceleration, unless Effects says Lite. */
    fun full(hardware: Boolean): Boolean = hardware && effects != Effects.Lite

    // ---- parental PIN (the full app's, issue #10) ----

    val locked: Boolean get() = pinSet && SystemClock.elapsedRealtime() > unlockedUntil

    fun unlock(pin: String): Boolean {
        if (hash(pin) != pinHash) return false
        unlockedUntil = SystemClock.elapsedRealtime() + UNLOCK_MS
        return true
    }

    fun relock() { unlockedUntil = 0L }

    /** Whether [pin] is the PIN, without unlocking anything: for Kids mode, as in the full app. */
    fun pinMatches(pin: String): Boolean = pinHash != null && hash(pin) == pinHash

    /** Sets the PIN, digits or [arrows] (U, D, L, R), or turns it off with null. */
    fun choosePin(pin: String?, arrows: Boolean = false) {
        pinHash = pin?.let(::hash)
        pinSet = pinHash != null
        pinArrows = pin != null && arrows
        // Locked from the start: a parent who sets the PIN and hands the remote over expects it
        // to hold at once, not after the next Home press (issue #718).
        unlockedUntil = 0L
        put { putString("pin_hash", pinHash); putBoolean("pin_arrows", pinArrows) }
    }

    /** The full app's hash, so a PIN reads the same in both. */
    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("aerial:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
}
