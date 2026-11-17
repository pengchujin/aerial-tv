package app.orchard.tv.lite

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.Modifier

/**
 * TCL MS901 / Android 4.2.1 (MStar edison), verified on 192.168.0.151 on 2026-11-15.
 *
 * This firmware has no android.media.tv input service. It exposes the TV's real input ports
 * through its own framework jar (/system/framework/com.tcl.tvos.addon.jar):
 * com.tcl.tvmanager.TTvCommonManager with getInstance(Context), getCurrentInputSource(),
 * setInputSource(EnTCLInputSource), and the enum com.tcl.tvmanager.vo.EnTCLInputSource.
 * Nothing is compiled against the vendor jar: all of it is reflection, so Lite still builds
 * with the public SDK and installs on any 4.x TV (the feature simply detects as absent there).
 *
 * The port list is NOT hard-coded. Every MStar board with this jar answers the same feature
 * probe, but a board with an HDMI3 socket would be wrong if we always listed MS901's eight
 * ports. The board's compiled-in source table comes from com.mstar.android.tvapi.common.
 * TvManager.getSourceList(): an int[] indexed by TvOsType.EnumInputSource.ordinal() where a
 * non-zero entry means the firmware built that source in. The TV's own InSrcActivity reads
 * this very array (CommonDeskImpl.InitSourceList -> psl) to mask its grid. TTvCommonManager's
 * own private InputSource_MstarToTcl() maps each MStar constant onto EnTCLInputSource, so the
 * mapping stays the firmware's, not ours. On the verified set the table is
 * [2,1,1,1,0..,1,0..,1,1,0,0,0,1,0..,1,1,1,1]: VGA/ATV/AV1/AV2/YPbPr/HDMI1/HDMI2/DTV
 * are on, HDMI3/HDMI4 are explicitly off, and STORAGE/KTV/JPEG/DTV2 are internal sources.
 *
 * Two things come from JustTvLauncher's TCL support, both verified on this TV:
 *  - its source panel is the same entry the remote's source key uses,
 *    action com.tcl.intent.action.CHOOSE_SOURCE -> com.tcl.tv/com.tcl.insrc.InSrcActivity;
 *  - after a Lite-initiated switch, coming back to Home must put the input back to STORAGE,
 *    or the picture stays on the external input and the launcher is not visible. Do that off
 *    the main thread and compare getCurrentInputSource first, so repeated resumes are cheap.
 *
 * The restore is NOT unconditional (PR #1556 review): on another TCL MStar board CEC may have
 * selected HDMI at boot, or the viewer may have used the remote's input key, and Lite must not
 * undo a choice it never made. backToStorage() therefore only restores a port Lite itself set,
 * and only while that port is still selected.
 *
 * Enum constants are looked up by NAME, never by ordinal: the order differs between firmwares.
 */
internal object TclMs901Sources {
    private const val TAG = "AerialTclSources"
    private const val MANAGER = "com.tcl.tvmanager.TTvCommonManager"
    private const val ENUM = "com.tcl.tvmanager.vo.EnTCLInputSource"
    // MStar's framework (BOOTCLASSPATH: /system/framework/com.mstar.android.jar). The second
    // name is the same native table behind a different wrapper on some 4.x builds.
    private const val MSTARS_MANAGER = "com.mstar.android.tvapi.common.TvManager"
    private const val MSTARS_FALLBACK = "com.mstar.android.tv.TvCommonManager"
    private const val MSTARS_ENUM = "com.mstar.android.tvapi.common.vo.TvOsType\$EnumInputSource"
    private val ACTION_PANEL = "com.tcl.intent.action.CHOOSE_SOURCE"
    private val PANEL = ComponentName("com.tcl.tv", "com.tcl.insrc.InSrcActivity")
    private val TV_ACTIVITY = ComponentName("com.tcl.tv", "com.tcl.tv.TVActivity")
    private const val EXTRA_SOURCE = "app.orchard.tv.lite.TCL_SOURCE"

    /**
     * setInputSource alone switches the hardware input, but the firmware leaves the Android UI
     * on top and its own onResume storm switches straight back to STORAGE - the source the user
     * picked never appears. Hold that restore for a moment after an explicit switch, and bring
     * the TV's own screen forward so the selected signal is actually displayed (as
     * JustTvLauncher's TvSourceController does with its source panel / TVActivity).
     */
    private const val HOLD_RESTORE_MS = 1200L

    @Volatile
    private var lastSwitchAt = 0L

    /** The port Lite itself set, and therefore the only one Home is allowed to restore. */
    @Volatile
    private var switchedByUs: String? = null

    /**
     * An explicit Home key always wins over the short "just switched" hold: otherwise pressing
     * HOME within [HOLD_RESTORE_MS] of picking a port would leave the external input on screen
     * with Home invisible underneath it. singleTask Home gets a CATEGORY_HOME onNewIntent first.
     * The switch record is deliberately kept - it is what entitles onResume to restore STORAGE.
     */
    fun homeRequested() {
        if (lastSwitchAt != 0L) Log.i(TAG, "Home key requested; clearing switch hold")
        lastSwitchAt = 0L
    }

    /**
     * Every physical input the TCL enum can name, in the order the TV's own panel shows them.
     * This is a display order / allow-list, NOT the ports this set has: those come from the
     * firmware table in computePorts(). Internal sources (STORAGE, OSD, KTV, JPEG, DTV2, MAX,
     * NONE) are deliberately absent - the firmware enables some of them, but they are not ports
     * a viewer can pick. A name this firmware's enum lacks is filtered out too.
     */
    private val CANDIDATES = listOf(
        "EN_TCL_ATV", "EN_TCL_DTV", "EN_TCL_AV1", "EN_TCL_AV2",
        "EN_TCL_YPBPR", "EN_TCL_YPBPR2", "EN_TCL_YPBPR3",
        "EN_TCL_HDMI1", "EN_TCL_HDMI2", "EN_TCL_HDMI3", "EN_TCL_HDMI4",
        "EN_TCL_VGA", "EN_TCL_DV", "EN_TCL_DV2", "EN_TCL_DV3", "EN_TCL_DV4"
    )

    @Volatile private var cachedPorts: List<String>? = null

    /**
     * Whether this firmware's private TV manager is there AND its source table can be read.
     * A feature check, not a model check: a TCL MStar board whose getSourceList() is not
     * available never enters any of this object's paths, including backToStorage().
     */
    fun available(context: Context): Boolean = runCatching {
        Class.forName(MANAGER)
        "EN_TCL_STORAGE" in enumNames() && ports(context).isNotEmpty()
    }.onFailure { Log.d(TAG, "TCL TV manager not present", it) }.getOrDefault(false)

    /**
     * The list Control Center shows. The Intent carries only the enum name; HomeActivity.open
     * turns it into a firmware switch instead of starting an Activity. If the board's source
     * table cannot be read we return nothing, so other TVs keep their original input path
     * instead of being shown a guessed set of ports.
     */
    fun inputs(context: Context): List<Pair<String, Intent>> =
        ports(context).map { name -> label(context, name) to Intent().putExtra(EXTRA_SOURCE, name) }

    /** The board's source table is static, so resolve it once per process. */
    private fun ports(context: Context): List<String> {
        cachedPorts?.let { return it }
        val result = runCatching { computePorts(context) }
            .onFailure { Log.w(TAG, "could not read the firmware source table", it) }
            .getOrDefault(emptyList())
        if (result.isNotEmpty()) cachedPorts = result
        return result
    }

    private fun computePorts(context: Context): List<String> {
        val tclNames = enumNames(Class.forName(ENUM))
        if ("EN_TCL_STORAGE" !in tclNames) return emptyList()
        val flags = sourceFlags() ?: return emptyList()
        val mstarEnum = Class.forName(MSTARS_ENUM)
        val constants = mstarEnum.enumConstants.orEmpty()
        if (constants.isEmpty()) return emptyList()

        // The firmware owns the MStar -> TCL mapping. It is private, but a plain reflection call
        // works on 4.2; if a later build drops it, mapByMstarName() is the same table by name.
        val manager = Class.forName(MANAGER)
        val converter = runCatching {
            manager.getDeclaredMethod("InputSource_MstarToTcl", mstarEnum).apply { isAccessible = true }
        }.getOrNull()
        val receiver = if (converter != null && !Modifier.isStatic(converter.modifiers)) {
            runCatching {
                manager.getMethod("getInstance", Context::class.java)
                    .invoke(null, context.applicationContext)
            }.getOrNull()
        } else null

        val enabled = HashSet<String>()
        constants.forEachIndexed { i, value ->
            if (i >= flags.size || flags[i] == 0) return@forEachIndexed
            val mstar = value as? Enum<*> ?: return@forEachIndexed
            val converted = if (converter != null) {
                runCatching { (converter.invoke(receiver, mstar) as? Enum<*>)?.name }
                    .onFailure { Log.d(TAG, "InputSource_MstarToTcl(${mstar.name}) failed", it) }
                    .getOrNull()
            } else null
            val name = converted ?: mapByMstarName(mstar.name)
            if (name != null) enabled.add(name)
        }
        val result = CANDIDATES.filter { it in tclNames && it in enabled }
        Log.i(TAG, "firmware source table (${flags.size} flags, ${enabled.size} enabled) -> ${result.joinToString()}")
        return result
    }

    /** The board's compiled-in source table, indexed by MStar EnumInputSource.ordinal(). */
    private fun sourceFlags(): IntArray? {
        for (className in listOf(MSTARS_MANAGER, MSTARS_FALLBACK)) {
            val flags = runCatching {
                val c = Class.forName(className)
                val instance = c.getMethod("getInstance").invoke(null)
                c.getMethod("getSourceList").invoke(instance) as? IntArray
            }.onFailure { Log.d(TAG, "$className.getSourceList() unavailable", it) }.getOrNull()
            if (flags != null && flags.isNotEmpty()) return flags
        }
        return null
    }

    /** Fallback if a firmware drops the private converter; mirrors its InputSource_MstarToTcl. */
    private fun mapByMstarName(name: String): String? = when (name) {
        "E_INPUT_SOURCE_ATV" -> "EN_TCL_ATV"
        "E_INPUT_SOURCE_DTV" -> "EN_TCL_DTV"
        "E_INPUT_SOURCE_CVBS" -> "EN_TCL_AV1"
        "E_INPUT_SOURCE_CVBS2" -> "EN_TCL_AV2"
        "E_INPUT_SOURCE_YPBPR" -> "EN_TCL_YPBPR"
        "E_INPUT_SOURCE_YPBPR2" -> "EN_TCL_YPBPR2"
        "E_INPUT_SOURCE_YPBPR3" -> "EN_TCL_YPBPR3"
        "E_INPUT_SOURCE_HDMI" -> "EN_TCL_HDMI1"
        "E_INPUT_SOURCE_HDMI2" -> "EN_TCL_HDMI2"
        "E_INPUT_SOURCE_HDMI3" -> "EN_TCL_HDMI3"
        "E_INPUT_SOURCE_HDMI4" -> "EN_TCL_HDMI4"
        "E_INPUT_SOURCE_VGA" -> "EN_TCL_VGA"
        "E_INPUT_SOURCE_DVI" -> "EN_TCL_DV"
        "E_INPUT_SOURCE_DVI2" -> "EN_TCL_DV2"
        "E_INPUT_SOURCE_DVI3" -> "EN_TCL_DV3"
        "E_INPUT_SOURCE_DVI4" -> "EN_TCL_DV4"
        else -> null // STORAGE / NONE / NUM / JPEG / DTV2 / SCART / SVIDEO / KTV / *_MAX
    }

    /** The port's name in the TV's own words; all strings are shared with the full app. */
    private fun label(context: Context, name: String): String {
        val port = name.removePrefix("EN_TCL_")
        return when {
            port.startsWith("HDMI") -> "HDMI " + port.removePrefix("HDMI")
            port.startsWith("AV") -> context.getString(R.string.input_av) + " " + port.removePrefix("AV")
            port.startsWith("YPBPR") -> "YPbPr" + port.removePrefix("YPBPR")
                .let { if (it.isEmpty()) "" else " $it" }
            port.startsWith("DV") && port != "DV" -> "DV " + port.removePrefix("DV")
            port == "ATV" -> context.getString(R.string.input_antenna)
            port == "DTV" -> context.getString(R.string.input_digital)
            else -> port
        }
    }

    /**
     * Switch to the picked port and immediately bring the TV's own picture forward, so the chosen
     * signal is actually on screen instead of Home's STORAGE framebuffer. HomeActivity.open()
     * closes Control Center after this returns true; it must not start the Intent as an Activity
     * (this one carries an enum name, not a component).
     */
    fun switch(context: Context, intent: Intent): Boolean {
        val name = intent.getStringExtra(EXTRA_SOURCE) ?: return false
        // Only our own Intents carry that extra; keep every other TV's open() path untouched.
        if (!available(context)) return false
        if (!set(context, name)) return false
        // Remember that Lite made this switch: it is the only thing that entitles Home to
        // restore STORAGE later. CEC at boot or the viewer's remote input key sets no record.
        switchedByUs = name
        lastSwitchAt = SystemClock.elapsedRealtime()
        showTvScreen(context)
        return true
    }

    /**
     * setInputSource() changes what the TV decodes, but on MS901 the Android framebuffer (STORAGE)
     * stays on top until TCL's own TV activity is resumed: without this the user picks "HDMI 1" and
     * keeps looking at Home. JustTvLauncher opens this very TVActivity as its fallback after the
     * source panel; the panel itself is only a last resort (it asks the remote to pick again).
     */
    private fun showTvScreen(context: Context): Boolean {
        val tv = Intent(Intent.ACTION_MAIN).setComponent(TV_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (runCatching { context.startActivity(tv); true }
                .onFailure { Log.w(TAG, "could not open TCL TVActivity", it) }.getOrDefault(false)) {
            Log.i(TAG, "opened TCL TVActivity for the selected input")
            return true
        }
        return openPanel(context)
    }

    /**
     * Coming back to Home (PR #1556 review). Two rules, in order:
     *
     *  - Lite never touched the input: leave it alone. CEC selecting HDMI at boot, or the viewer
     *    using the remote's input key, is a choice Lite must not undo on any TCL board.
     *  - Lite switched to a port: restore STORAGE, but only while that same port is still
     *    selected. If the viewer or CEC moved on to another input afterwards, keep theirs.
     *
     * The brief hold after an explicit switch is kept for the firmware's own focus bounce:
     * HomeActivity.onResume can fire before the TV screen appears, and an explicit HOME clears
     * that hold in homeRequested().
     */
    fun backToStorage(context: Context): Boolean {
        if (!available(context)) return false
        if (lastSwitchAt != 0L && SystemClock.elapsedRealtime() - lastSwitchAt < HOLD_RESTORE_MS) {
            Log.i(TAG, "just switched to an external input; leaving it on screen")
            return true
        }
        val ours = switchedByUs
        if (ours == null) {
            Log.i(TAG, "no Lite-initiated input switch; leaving the input alone")
            return false
        }
        val current = current(context) ?: return false
        if (current == "EN_TCL_STORAGE" || current == "EN_TCL_STORAGE2") {
            Log.i(TAG, "already on $current, no switch")
            switchedByUs = null
            return true
        }
        if (current != ours) {
            Log.i(TAG, "input is now $current (Lite set $ours); leaving the viewer's choice alone")
            switchedByUs = null
            return false
        }
        Log.i(TAG, "Home resumed on $current, switching back to STORAGE")
        val ok = set(context, "EN_TCL_STORAGE")
        if (ok) switchedByUs = null
        return ok
    }

    /** The current input's enum name, for the log and for the resume comparison. */
    fun current(context: Context): String? = runCatching {
        val managerClass = Class.forName(MANAGER)
        val manager = managerClass.getMethod("getInstance", Context::class.java)
            .invoke(null, context.applicationContext)
        val value = managerClass.getMethod("getCurrentInputSource").invoke(manager)
        (value as? Enum<*>)?.name ?: value?.toString()
    }.onFailure { Log.w(TAG, "getCurrentInputSource failed", it) }.getOrNull()

    private fun set(context: Context, name: String): Boolean = runCatching {
        val enumClass = Class.forName(ENUM)
        val source = enumNames(enumClass).let { names ->
            enumClass.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == name }
                ?: error("$name is not in the firmware enum (${names.joinToString()})")
        }
        val managerClass = Class.forName(MANAGER)
        val manager = managerClass.getMethod("getInstance", Context::class.java)
            .invoke(null, context.applicationContext)
        val answer = managerClass.getMethod("setInputSource", enumClass).invoke(manager, source)
        Log.i(TAG, "setInputSource($name) -> $answer")
        true
    }.onFailure { Log.w(TAG, "setInputSource($name) failed", it) }.getOrDefault(false)

    /**
     * The TV's own source panel, for firmwares where the manager is absent but the panel entry
     * exists. Same Intent as JustTvLauncher's, with its TVActivity fallback.
     */
    fun openPanel(context: Context): Boolean {
        val pm = context.packageManager
        val panel = Intent(ACTION_PANEL).setComponent(PANEL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val info = runCatching { pm.resolveActivity(panel, 0)?.activityInfo }.getOrNull()
        if (info != null && info.exported && info.enabled) {
            return runCatching { context.startActivity(panel); true }
                .onFailure { Log.w(TAG, "could not open the TCL source panel", it) }.getOrDefault(false)
        }
        val fallback = Intent(Intent.ACTION_MAIN).setComponent(TV_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val fallbackInfo = runCatching { pm.resolveActivity(fallback, 0)?.activityInfo }.getOrNull()
        if (fallbackInfo == null || !fallbackInfo.exported) return false
        return runCatching { context.startActivity(fallback); true }
            .onFailure { Log.w(TAG, "could not open TCL TVActivity", it) }.getOrDefault(false)
    }

    private fun enumNames(): List<String> = runCatching { enumNames(Class.forName(ENUM)) }.getOrDefault(emptyList())

    private fun enumNames(enumClass: Class<*>): List<String> =
        enumClass.enumConstants?.mapNotNull { (it as? Enum<*>)?.name }.orEmpty()
}
