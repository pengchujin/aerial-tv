package app.orchard.tv.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.tv.TvInputInfo
import android.os.Bundle
import androidx.core.content.ContextCompat
import app.orchard.tv.apps.SOURCE_KEY_PREFIX
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.Restricted

/**
 * A device switched on over HDMI-CEC (One Touch Play: a Switch put in its dock, an Apple TV woken)
 * gets the TV's picture, as the stock launcher does it (issue #1020).
 *
 * The firmware notices the device; who switches is up to the launcher. Amlogic's (droidlogic)
 * input service says so with a broadcast, which PatchWall answers and nobody did with Aerial as
 * Home (Xiaomi A65, MiTV-ASTP0, Android 9: the reporter's logcat). Other firmwares have not been
 * seen to send anything an app can hear. Only while the owner turned it on
 * ([AerialSettings.cecSwitch]): not everyone wants a console waking to take the TV.
 *
 * Registered once per process with the application context, from Home and from Home Guard, so it
 * hears the broadcast whichever is up.
 */
object CecSwitch {
    /** droidlogic's DroidLogicTvUtils.ACTION_OTP_INPUT_SOURCE_CHANGED. */
    const val OTP_ACTION = "droidlogic.tv.action.OTP_INPUT_SOURCE_CHANGED"

    @Volatile private var registered = false

    private val receiver = object : BroadcastReceiver() {
        // Listing the inputs asks the TV's services: off the main thread.
        override fun onReceive(context: Context, intent: Intent) {
            // The settings are in credential storage, and Compose state: read in here, on the main thread.
            if (!app.orchard.tv.guard.GuardSetup.isUserUnlocked(context)) return
            AerialSettings.init(context)
            val pending = goAsync()
            Thread {
                try { onOneTouchPlay(context, intent) } finally { pending.finish() }
            }.start()
        }
    }

    fun register(context: Context) {
        if (registered) return
        registered = true
        runCatching {
            ContextCompat.registerReceiver(context.applicationContext, receiver, IntentFilter(OTP_ACTION), ContextCompat.RECEIVER_EXPORTED)
        }.onFailure { registered = false }
    }

    private fun onOneTouchPlay(context: Context, intent: Intent) {
        val extras = intent.extras
        // What it carries, for the diagnostics: only one firmware's has been seen, without its extras.
        DiagLog.i("cec: one touch play ${describe(extras)}")
        if (!AerialSettings.cecSwitch) return
        val sources = SystemProbe.sources(context)
        val texts = runCatching { texts(extras) }.getOrDefault(emptyList())
        val source = pick(sources.map { it.id }, texts) { parentOf(context, it) }?.let { id -> sources.firstOrNull { it.id == id } }
        if (source == null) {
            DiagLog.i("cec: no input matches")
            return
        }
        // Kids and Elders mode switches only to the inputs it shows.
        if (Restricted.active && SOURCE_KEY_PREFIX + source.id !in Restricted.allowed) {
            DiagLog.i("cec: ${source.label} not opened (kids)")
            return
        }
        DiagLog.i("cec: opening ${source.label}")
        runCatching {
            if (!HisenseSources.open(context, source.intent)) context.startActivity(source.intent)
        }.onFailure { DiagLog.i("cec: open failed ${it.javaClass.simpleName}") }
    }

    /**
     * Which of [ids] the broadcast names: an input's id among its text extras (Android's
     * TvInputInfo.EXTRA_INPUT_ID first), or a CEC device's input under one ([parentOf]).
     */
    fun pick(ids: List<String>, texts: List<String>, parentOf: (String) -> String? = { null }): String? {
        for (t in texts) {
            if (t in ids) return t
            parentOf(t)?.takeIf { it in ids }?.let { return it }
        }
        return null
    }

    /** The broadcast's text extras, Android's input id first. */
    private fun texts(extras: Bundle?): List<String> {
        if (extras == null) return emptyList()
        return buildList {
            extras.getString(TvInputInfo.EXTRA_INPUT_ID)?.let(::add)
            for (key in extras.keySet()) (runCatching { @Suppress("DEPRECATION") extras.get(key) }.getOrNull() as? String)?.let(::add)
        }
    }

    /** The port a CEC device's input sits under. */
    private fun parentOf(context: Context, id: String): String? = runCatching {
        (context.getSystemService(Context.TV_INPUT_SERVICE) as? android.media.tv.TvInputManager)?.getTvInputInfo(id)?.parentId
    }.getOrNull()

    private fun describe(extras: Bundle?): String = runCatching {
        @Suppress("DEPRECATION")
        extras?.keySet()?.joinToString(" ") { k -> "$k=${runCatching { extras.get(k) }.getOrNull()}" } ?: "(no extras)"
    }.getOrDefault("(unreadable extras)")
}
