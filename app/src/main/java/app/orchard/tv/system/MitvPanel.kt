package app.orchard.tv.system

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.util.Log

/**
 * MiTV panel backlight (China firmware on Android 9, e.g. MiTV-ASTP0 `mulan`).
 *
 * There is no `picture_backlight` global on these TVs. The firmware's own picture settings keep the
 * backlight level in `Settings.System` `backLightForNormal` (0–100) and push it to the panel
 * through the mitv middleware, so writing the setting alone only changes what the menu shows — and
 * third-party apps are refused even that write. Control Center talks to the same service the TV
 * settings app uses:
 *
 *  1. `ServiceManager.getService("TvService")` returns the `mitv.internal.ITvService` binder.
 *  2. `getAccessoryService("TvSettingService")` (transaction 80) returns the
 *     `mitv.impl.tv.ITvSettingManager` binder.
 *  3. `getBacklightStatus()` (transaction 43) reads the panel, `setBacklightValue(int)` (transaction
 *     42) moves it, 1–100.
 *
 * The transaction numbers are AIDL method slots, which is why nothing here is used until [usable]
 * has moved the panel one level and seen it follow: a firmware that reorders `ITvSettingManager`
 * would make transaction 43 read some other method and transaction 42 write one. Comparing the read
 * value with [SETTING] is not enough, because this firmware refuses third-party writes to that
 * setting, so the two drift apart as soon as the panel is moved at all (ASTP0: panel 75, setting
 * still 100).
 *
 * Verified on MiTV-ASTP0: the TV's own backlight page and the actual panel follow these calls. Every
 * step is best-effort; when the binder is unavailable the slider still tracks the level, so the
 * control is usable and the next read picks the panel up again.
 */
object MitvPanel {
    private const val TAG = "AerialMitvPanel"

    /** `Settings.System` key the TV's own backlight UI reads and writes. */
    const val SETTING = "backLightForNormal"

    private const val ITV_SERVICE = "mitv.internal.ITvService"
    private const val ITV_SETTING_MANAGER = "mitv.impl.tv.ITvSettingManager"
    private const val ACCESSORY_NAME = "TvSettingService"
    private const val TX_GET_ACCESSORY_SERVICE = 80
    private const val TX_GET_BACKLIGHT_VALUE = 43
    private const val TX_SET_BACKLIGHT_VALUE = 42

    /** Levels moved by the check in [usable]. One step is not visible on screen. */
    private const val PROBE_STEP = 1

    /** How far a reading may sit from the level the panel was moved to (the getter animates). */
    private const val PROBE_TOLERANCE = 2


    private var verified: Boolean? = null

    /** True when this firmware keeps its panel level in [SETTING] instead of the AOSP brightness. */
    fun isAvailable(context: Context): Boolean =
        Settings.System.getString(context.contentResolver, SETTING) != null

    /**
     * True when the transactions below really are the ones that move this panel.
     *
     * These numbers are AIDL method slots, not a stable contract: a firmware that reorders
     * `ITvSettingManager` would make transaction 43 read some other method (and transaction 42 write
     * one), and `enforceInterface` only checks the interface name. Step the panel one level, read it
     * back and step it back once per process; only a panel that follows both calls gets the slider.
     * On a mismatch Control Center leaves the panel to the TV's own backlight slider instead of
     * moving some other picture parameter.
     */
    fun usable(context: Context): Boolean {
        verified?.let { return it }
        // A box has no panel. Mi Box MiTV-AZFP0 keeps [SETTING] all the same, and after standby
        // its TvService stopped answering: the lookup below never returned, and every refresh
        // that read the brightness waited behind it (issue #892).
        if (isBox()) return false.also { verified = it }
        // One probe at a time: a binder call that hangs then holds one thread, not all of them.
        if (!probing.compareAndSet(false, true)) return false
        try {
            return probe(context)
        } finally {
            probing.set(false)
        }
    }

    private val probing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** A set-top box (ro.build.characteristics stb or mbx). Here, not in SystemProbe: Lite shares this file. */
    private fun isBox(): Boolean = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "ro.build.characteristics") as String
    }.getOrNull().orEmpty().let { "stb" in it || "mbx" in it }

    private fun probe(context: Context): Boolean {
        val ok = run {
            // Models that keep [SETTING] yet stay on the TV's own slider: MiTV-MFTP0 has a
            // picture_backlight it does not honour (issues #284 and #335), and this path has not
            // been tried on it. A model whose panel transaction 42 turns out not to move goes into
            // devices.json the same way.
            if (DeviceProfile.current(context).ignoresPictureBacklight) return@run false
            if (!isAvailable(context)) return@run false
            val before = readSettled(context) ?: return@run false
            if (before !in 1..100) return@run false
            // Step up from the low end and down from the high end: the panel must never be asked
            // to go to 0, where Xiaomi turns the backlight off (issue #174).
            val step = if (before >= 50) -PROBE_STEP else PROBE_STEP
            setDelta(context, step)
            val moved = readSettled(context)
            setDelta(context, -step)
            val restored = readSettled(context)
            val ok = moved != null && restored != null &&
                kotlin.math.abs(moved - (before + step)) <= PROBE_TOLERANCE &&
                kotlin.math.abs(restored - before) <= PROBE_TOLERANCE
            if (!ok) Log.w(TAG, "backlight transactions unverified ($before -> $moved -> $restored)")
            ok
        }
        verified = ok
        return ok
    }

    /**
     * Current panel level 1-100, as the firmware's `getBacklightStatus` reports it.
     *
     * [SETTING] is only the fallback seed: this firmware refuses third-party writes to it, so after
     * Aerial moves the panel the setting still holds whatever the TV's own menu left there.
     */
    fun level(context: Context): Int? = readBacklight(context) ?: settingLevel(context)

    /** The level the TV's own picture menu last stored, 1-100. */
    private fun settingLevel(context: Context): Int? =
        runCatching { Settings.System.getInt(context.contentResolver, SETTING, 50) }.getOrNull()

    /**
     * Moves the panel from [from] to [to] (1–100).
     *
     * The middleware's `setBacklightValue` is a **relative step**, not an absolute level: the
     * firmware's impl does `SetBacklight(getBacklightStatus() + value)`. Sending an absolute value
     * therefore slams the panel around (25 -> 119, 30 -> 196 …). Callers pass the level Aerial set
     * last, not what the getter reports, because that one is animated and mid-step.
     */
    fun setBacklight(context: Context, from: Int, to: Int) {
        val value = to.coerceIn(1, 100)
        val current = from.coerceIn(1, 100)
        val delta = value - current
        if (delta != 0) setDelta(context, delta)
        // Best-effort: on this firmware the write is refused ("You cannot keep your settings in the
        // secure settings."), which is expected and not worth a warning on every step.
        runCatching { Settings.System.putInt(context.contentResolver, SETTING, value) }
            .onFailure { Log.d(TAG, "setting write refused: $it") }
    }

    /** The panel level the firmware currently reports (its own `getBacklightStatus`), 1-100. */
    fun readBacklight(context: Context): Int? {
        val settingService = settingService() ?: return null
        return runCatching {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ITV_SETTING_MANAGER)
                settingService.transact(TX_GET_BACKLIGHT_VALUE, data, reply, 0)
                reply.readException()
                reply.readInt()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }.onFailure { Log.w(TAG, "getBacklightStatus failed: $it") }.getOrNull()
    }

    /**
     * [readBacklight] once the panel has stopped animating: the middleware reports the level it is
     * moving through, so a check taken right after a step can see the previous level.
     */
    private fun readSettled(context: Context): Int? {
        var last: Int? = null
        repeat(3) { attempt ->
            val value = readBacklight(context) ?: return last
            if (value == last) return value
            last = value
            if (attempt < 2) runCatching { Thread.sleep(30) }
        }
        return last
    }

    /** Steps the panel by [delta] levels through the middleware, logging failures only. */
    private fun setDelta(context: Context, delta: Int) {
        val settingService = settingService()
        if (settingService == null) {
            Log.w(TAG, "TvService binder missing")
            return
        }
        runCatching {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ITV_SETTING_MANAGER)
                data.writeInt(delta)
                settingService.transact(TX_SET_BACKLIGHT_VALUE, data, reply, 0)
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
            Log.d(TAG, "setBacklightValue $delta")
        }.onFailure { Log.w(TAG, "setBacklightValue failed: $it") }
    }

    /** The `ITvSettingManager` binder behind `TvService`, or null when the binder is missing. */
    private fun settingService(): IBinder? {
        val tvService = runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            getService.invoke(null, "TvService") as? IBinder
        }.onFailure { Log.w(TAG, "TvService lookup failed: $it") }.getOrNull()
        if (tvService == null) {
            Log.w(TAG, "TvService binder missing")
            return null
        }
        return runCatching {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ITV_SERVICE)
                data.writeString(ACCESSORY_NAME)
                tvService.transact(TX_GET_ACCESSORY_SERVICE, data, reply, 0)
                reply.readException()
                reply.readStrongBinder()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }.onFailure { Log.w(TAG, "accessory lookup failed: $it") }.getOrNull()
    }
}
