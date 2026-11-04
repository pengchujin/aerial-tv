package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Konka KKTV U86V9 / Android TV 9652 (Android 9) reports no framework inputs. Its native picker
 * reads KKCommonManager instead. Read the actual port list and enum ordinals: this TV exposes
 * three HDMI ports even though the SDK also has an HDMI4 constant. No vendor jar is bundled.
 * Verified on Konka KKTV U86V9 (MT9652, Android 9), PR #125.
 */
internal object KonkaSources {
    private const val TAG = "AerialKonkaSources"
    private const val MANAGER = "com.konka.android.tv.KKCommonManager"
    private const val SOURCE_ENUM = "com.konka.android.tv.KKCommonManager\$EN_KK_INPUT_SOURCE"
    private val player = ComponentName("com.konka.tvsettings", "com.konka.tvsettings.RootActivity")

    fun load(context: Context): List<TvSource> {
        val activity = runCatching { context.packageManager.getActivityInfo(player, 0) }.getOrNull()
            ?: return emptyList()
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) return emptyList()
        return runCatching {
            val managerClass = Class.forName(MANAGER)
            val sourceClass = Class.forName(SOURCE_ENUM)
            val manager = managerClass.getMethod("getInstance", Context::class.java)
                .invoke(null, context.applicationContext)
            val reported = managerClass.getMethod("getTVInputSourceList").invoke(manager) as? List<*>
                ?: return emptyList()
            // The SDK's spelling is Avaliable. Older firmwares may not expose this method.
            val connected = runCatching {
                managerClass.getMethod("getAvaliableInputSourceList").invoke(manager) as? List<*>
            }.getOrNull().orEmpty()
            val labelMethod = runCatching { managerClass.getMethod("getInputSourceName", sourceClass) }.getOrNull()
            reported.filterIsInstance<Enum<*>>().map { source ->
                val label = runCatching { labelMethod?.invoke(manager, source) as? String }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: source.name
                val kind = when {
                    source.name.startsWith("HDMI") || source.name == "EXTERNAL_8K_HDMI" -> SourceKind.Hdmi
                    source.name == "DP" || source.name == "TYPE_C" || source.name == "EXTERNAL_8K_DP" -> SourceKind.DisplayPort
                    source.name.startsWith("AV") || source.name.startsWith("YPBPR") || source.name.startsWith("SVIDEO") -> SourceKind.Av
                    source.name == "ATV" || source.name == "DTMB" || source.name.startsWith("DVB_") ||
                        source.name == "ATSC" || source.name == "ISDB_T" -> SourceKind.Tuner
                    else -> SourceKind.Other
                }
                TvSource(
                    id = "konka:${source.name}",
                    label = label,
                    kind = kind,
                    // Before Android 6 the list is not what is plugged in: Konka 553 (Hi3751V553,
                    // Android 5.1.1) named only HDMI 1, with nothing in any port (issue #918).
                    connected = android.os.Build.VERSION.SDK_INT >= 23 && source in connected,
                    deviceName = null,
                    // RootActivity expects the public Konka enum ordinal, not its chipset source id.
                    // A new task each time: with its task still there, Android only brought it
                    // forward and the input it was on stayed (Konka 828, Android 5.0.1, issue #710:
                    // HDMI2 then HDMI3 stayed on HDMI2; the task's intent was the first one).
                    intent = Intent("com.konka.tvsettings.intent.action.RootActivity")
                        .setComponent(player)
                        .putExtra("source", source.ordinal)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            }.distinctBy { it.id }.sortedWith(compareBy({ it.kind.ordinal }, { it.label }))
        }.onFailure { Log.w(TAG, "Could not read the native Konka input list", it) }.getOrDefault(emptyList())
    }

    /**
     * Whether [intent] opens a Konka input whose page will not switch by itself: before Android 6
     * RootActivity ignores the `source` extra and shows the input last used; the remote switches
     * because com.konka.tvsettings calls the TV manager in its own process first (Konka 828 /
     * QLED65X80U, MStar, Android 5.0.1, issue #710). Konka U86V9 (Android 9) switches from the
     * extra and is left as it was.
     */
    fun needsSwitch(intent: Intent): Boolean =
        android.os.Build.VERSION.SDK_INT < 23 && intent.component == player && intent.hasExtra("source")

    /**
     * Switches to the input of [intent] the way Konka's settings app does, through
     * KKCommonManager.setInputSource with the public Konka enum. Takes 0.4-0.8 s on the 828, so not
     * on the main thread. All three calls of the diagnostic build switched there (KKCommonManager,
     * MStar's TvCommonManager and TvManager); Konka's own is the one used.
     */
    fun switchTo(context: Context, intent: Intent) {
        val ordinal = intent.getIntExtra("source", -1)
        runCatching {
            val managerClass = Class.forName(MANAGER)
            val sourceClass = Class.forName(SOURCE_ENUM)
            val source = sourceClass.enumConstants?.getOrNull(ordinal) ?: error("no input $ordinal")
            val manager = managerClass.getMethod("getInstance", Context::class.java).invoke(null, context.applicationContext)
            val switched = managerClass.getMethod("setInputSource", sourceClass).invoke(manager, source)
            DiagLog.i("konka: switched to $source before its page: $switched")
        }.onFailure { Log.w(TAG, "Could not switch the Konka input to $ordinal", it) }
    }
}
