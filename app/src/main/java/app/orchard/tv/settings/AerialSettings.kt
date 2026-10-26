package app.orchard.tv.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import android.os.SystemClock
import android.provider.Settings
import app.orchard.tv.apps.IconLibrary
import app.orchard.tv.guard.GuardSetup
import app.orchard.tv.system.DeviceClass
import app.orchard.tv.wallpaper.AerialQuality
import java.security.MessageDigest

/**
 * How the focused app shows itself, named after tvOS's Accessibility > Increase Contrast > Focus Style.
 *
 * tvOS's standard focus relies on the Siri Remote's touch surface: the icon tilts with the thumb, and
 * that motion is what the eye follows. With the D-pad remotes these TVs ship with, the icon only
 * grows, and owners lost track of it. High Contrast adds a white frame and is the default here.
 */
enum class FocusStyle(val key: String) {
    Standard("standard"), HighContrast("high_contrast");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: HighContrast
    }
}

/**
 * Where interface sounds come from. The TV's own are silent wherever its key sound setting is off,
 * which many TVs ship with (Sony, issue #160; #169), so that is not the default there.
 */
enum class UiSounds(val key: String) {
    Tv("tv"), Aerial("aerial"), Off("off");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/**
 * How much of the interface's light and motion to draw. Full is the glass panels, a focused tile's
 * sheen, parallax and shadow, and the panel's highest refresh rate. Lite draws the panels in plain
 * dark glass (no offscreen layer per frame), a focused tile without sheen, parallax or shadow, and
 * stays at 60 Hz: for TVs whose GPU cannot keep up (issues #232, #354). Auto is Lite on a low-end
 * TV (DeviceClass) and Full elsewhere.
 */
enum class Effects(val key: String) {
    Auto("auto"), Full("full"), Lite("lite");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Auto
    }
}

/** Aerial's own preferences. Compose state, written on the main thread only. */
object AerialSettings {
    private const val PREFS = "settings"
    private var started = false
    private lateinit var app: Context

    var focusStyle by mutableStateOf(FocusStyle.HighContrast); private set


    /** TV inputs pinned to Home as tiles, by TvInputInfo id. */
    var pinnedSources by mutableStateOf<Set<String>>(emptySet()); private set

    /**
     * The input to open when the TV is switched on (a set-top box, an Apple TV), by TvSource id;
     * null for none. Home still comes back to Aerial. Issue #25.
     */
    var bootSource by mutableStateOf<String?>(null); private set

    /**
     * An app to open when the TV is switched on instead of an input, by package name: a live TV
     * app that plays the last channel. Only one of this and [bootSource] is set.
     */
    var bootApp by mutableStateOf<String?>(null); private set

    /**
     * Only the top row on Home, low on the screen, so the wallpaper (a family photo) shows above it,
     * as tvOS does under a full-screen top shelf. The rest of the grid is one press down.
     */
    var oneRowHome by mutableStateOf(true); private set

    /** In one-row mode, the top of the second row peeking under the first; off hides it until you go down. */
    var peekNextRow by mutableStateOf(true); private set

    /** Interface sounds (system/Sounds), as tvOS plays them on every press. */
    var uiSounds by mutableStateOf(UiSounds.Tv); private set

    /** The Liquid Glass plate under the top row; some prefer the apps straight on the wallpaper (#151). */
    var homePlate by mutableStateOf(true); private set

    /** The clock, with the date and weather, in the top right instead of the top left. */
    var clockRight by mutableStateOf(false); private set

    /** The date and weather over a big clock above the top row, instead of one small line in the corner. */
    var largeClock by mutableStateOf(false); private set

    /**
     * The moving wallpaper (aerial films, the owner's own videos) plays on the top row whatever the
     * keys do, under a little shade, rather than only once Home has sat still (issues #360, #456).
     * The grid below and panels still stop it: they cover it.
     */
    var wallpaperAlwaysMoves by mutableStateOf(false); private set

    /** The weather beside the clock; off for those who want the time alone (issue #472). */
    var showWeather by mutableStateOf(true); private set

    /** Ready-made tile pictures for popular apps from the website ([IconLibrary]). */
    var iconLibrary by mutableStateOf(true); private set

    /**
     * The time over Aerial's aerial and photo screensavers (issue #240: TCL's firmware puts its own
     * clock over the system screensaver). The clock screensaver is the clock and keeps it.
     */
    var screensaverClock by mutableStateOf(true); private set

    /** Every app's name under its tile, not only the focused one's (issue #28), the top row's on the glass plate too. */
    var showAllNames by mutableStateOf(false); private set

    /** Back on Home's top row starts the screensaver, as on tvOS; off for homes where it gets pressed by accident (issue #94). */
    var backStartsScreensaver by mutableStateOf(true); private set

    /**
     * Parental PIN (issue #10): asked before TV settings open and before hidden apps come back.
     * Kept as a SHA-256 hash. One right entry holds for a while, until Home is pressed.
     */
    var pinSet by mutableStateOf(false); private set

    /**
     * The PIN is four presses of the arrow keys rather than four digits: quicker on a remote with
     * no number keys, and a child watching sees nothing on screen to copy (issue #368).
     */
    var pinArrows by mutableStateOf(false); private set
    private var pinHash: String? = null
    private var unlockedUntil = 0L
    private const val UNLOCK_MS = 10 * 60 * 1000L

    /**
     * Minutes of no input on Home before Aerial's screensaver starts (issue #66). The firmware's own
     * screensaver still caps it: Aerial starts just ahead of that one, see MainActivity.
     * [NEVER] starts none, and switches off the system's while that is Aerial's (issue #333).
     */
    var screensaverMinutes by mutableStateOf(DEFAULT_SCREENSAVER_MINUTES); private set

    const val NEVER = 0

    val screensaverChoices = listOf(2, 5, 10, 20, NEVER)

    /** Which of Apple's files the aerial screensaver plays; Auto is the best this TV decodes (issues #198, #217). */
    var aerialQuality by mutableStateOf(AerialQuality.Auto); private set

    var effects by mutableStateOf(Effects.Auto); private set

    /** This TV counts as low-end for [Effects.Auto]; see DeviceClass. */
    var lowEnd = false; private set

    /** Draw the lighter interface (see [Effects]). */
    val lite: Boolean get() = effects == Effects.Lite || (effects == Effects.Auto && lowEnd)
    private const val DEFAULT_SCREENSAVER_MINUTES = 5

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        focusStyle = FocusStyle.of(prefs.getString("focus_style", null))
        pinnedSources = prefs.getStringSet("pinned_sources", null)?.toSet() ?: emptySet()
        bootSource = prefs.getString("boot_source", null)
        bootApp = prefs.getString("boot_app", null)
        pinHash = prefs.getString("pin_hash", null)
        pinSet = pinHash != null
        pinArrows = prefs.getBoolean("pin_arrows", false)
        oneRowHome = prefs.getBoolean("one_row_home", true)
        peekNextRow = prefs.getBoolean("peek_next_row", true)
        clockRight = prefs.getBoolean("clock_right", false)
        largeClock = prefs.getBoolean("large_clock", false)
        homePlate = prefs.getBoolean("home_plate", true)
        showAllNames = prefs.getBoolean("show_all_names", false)
        wallpaperAlwaysMoves = prefs.getBoolean("wallpaper_always_moves", false)
        showWeather = prefs.getBoolean("show_weather", true)
        iconLibrary = prefs.getBoolean("icon_library", true)
        screensaverClock = prefs.getBoolean("screensaver_clock", true)
        // Before 1.28 this was on or off; on meant whichever sounds Aerial played.
        uiSounds = UiSounds.of(prefs.getString("ui_sounds_kind", null)) ?: when {
            !prefs.getBoolean("ui_sounds", true) -> UiSounds.Off
            Settings.System.getInt(app.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1 -> UiSounds.Tv
            else -> UiSounds.Aerial
        }
        backStartsScreensaver = prefs.getBoolean("back_starts_screensaver", true)
        screensaverMinutes = prefs.getInt("screensaver_minutes", DEFAULT_SCREENSAVER_MINUTES)
        aerialQuality = AerialQuality.of(prefs.getString("aerial_quality", null))
        effects = Effects.of(prefs.getString("effects", null))
        lowEnd = DeviceClass.lowEnd(app)
    }

    fun chooseBootSource(id: String?) {
        bootSource = id
        bootApp = null
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("boot_source", id).remove("boot_app") }
    }

    fun chooseBootApp(pkg: String?) {
        bootApp = pkg
        bootSource = null
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("boot_app", pkg).remove("boot_source") }
    }

    /** Something opens when the TV is switched on. */
    val hasBootTarget: Boolean get() = bootSource != null || bootApp != null

    val locked: Boolean get() = pinSet && SystemClock.elapsedRealtime() > unlockedUntil

    fun unlock(pin: String): Boolean {
        if (hash(pin) != pinHash) return false
        unlockedUntil = SystemClock.elapsedRealtime() + UNLOCK_MS
        return true
    }

    fun relock() { unlockedUntil = 0L }

    /** Sets the PIN, digits or [arrows] (U, D, L, R), or turns it off with null. */
    fun choosePin(pin: String?, arrows: Boolean = false) {
        pinHash = pin?.let(::hash)
        pinSet = pinHash != null
        pinArrows = pin != null && arrows
        unlockedUntil = if (pin != null) SystemClock.elapsedRealtime() + UNLOCK_MS else 0L
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("pin_hash", pinHash); putBoolean("pin_arrows", pinArrows) }
    }

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("aerial:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun chooseOneRowHome(on: Boolean) {
        oneRowHome = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("one_row_home", on) }
    }

    fun choosePeekNextRow(on: Boolean) {
        peekNextRow = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("peek_next_row", on) }
    }

    fun chooseUiSounds(kind: UiSounds) {
        uiSounds = kind
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("ui_sounds_kind", kind.key) }
    }

    fun chooseHomePlate(on: Boolean) {
        homePlate = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("home_plate", on) }
    }

    fun chooseClockRight(on: Boolean) {
        clockRight = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("clock_right", on) }
    }

    fun chooseLargeClock(on: Boolean) {
        largeClock = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("large_clock", on) }
    }

    fun chooseShowAllNames(on: Boolean) {
        showAllNames = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("show_all_names", on) }
    }

    fun chooseWallpaperAlwaysMoves(on: Boolean) {
        wallpaperAlwaysMoves = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("wallpaper_always_moves", on) }
    }

    fun chooseShowWeather(on: Boolean) {
        showWeather = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("show_weather", on) }
    }

    fun chooseIconLibrary(on: Boolean) {
        iconLibrary = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("icon_library", on) }
        IconLibrary.changed()
    }

    fun chooseScreensaverClock(on: Boolean) {
        screensaverClock = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("screensaver_clock", on) }
    }

    fun chooseBackStartsScreensaver(on: Boolean) {
        backStartsScreensaver = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("back_starts_screensaver", on) }
    }

    fun chooseAerialQuality(quality: AerialQuality) {
        aerialQuality = quality
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("aerial_quality", quality.key) }
    }

    fun chooseScreensaverMinutes(minutes: Int) {
        screensaverMinutes = minutes
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt("screensaver_minutes", minutes) }
        GuardSetup.setSystemScreensaverOff(app, minutes == NEVER)
    }

    fun setPinned(inputId: String, pinned: Boolean) {
        pinnedSources = if (pinned) pinnedSources + inputId else pinnedSources - inputId
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet("pinned_sources", pinnedSources) }
    }


    fun chooseEffects(choice: Effects) {
        effects = choice
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("effects", choice.key) }
    }

    fun chooseFocusStyle(style: FocusStyle) {
        focusStyle = style
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("focus_style", style.key) }
    }

}
