package app.orchard.tv.system

import android.content.Context
import android.content.Intent

/** EUI's desktop service owns inputs on older LeTV sets without a TV input service. */
internal object LetvSources {
    private const val MANAGER = "letv.desktop.DesktopManager"
    private const val ACTION = "app.orchard.tv.LETV_INPUT"
    private const val PORT = "port"

    fun load(context: Context): List<TvSource> = runCatching {
        context.packageManager.getPackageInfo("com.stv.signalsourcemanager", 0)
        val type = Class.forName(MANAGER)
        val manager = type.getMethod("get").invoke(null)
        val ports = type.getMethod("getSupportInput").invoke(manager) as? Array<*>
        fromPorts(ports?.filterIsInstance<String>().orEmpty())
    }.getOrDefault(emptyList())

    internal fun fromPorts(ports: List<String>): List<TvSource> = ports.distinct().mapNotNull { port ->
        val kind = when {
            Regex("HDMI [1-9][0-9]*").matches(port) -> SourceKind.Hdmi
            port == "Analog TV" || port == "Digital TV" -> SourceKind.Tuner
            port == "AV" -> SourceKind.Av
            else -> return@mapNotNull null
        }
        TvSource(
            id = "letv:$port", label = port, kind = kind, connected = false, deviceName = null,
            intent = Intent(ACTION).setPackage("com.stv.signalsourcemanager")
                .putExtra(PORT, port).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Do not launch MainActivity directly: it crashes when EUI's desktop registry is absent. */
    fun open(context: Context, intent: Intent): Boolean {
        if (intent.action != ACTION || intent.`package` != "com.stv.signalsourcemanager") return false
        val port = intent.getStringExtra(PORT)
        require(load(context).any { it.intent.getStringExtra(PORT) == port }) { "Unknown LeTV input" }
        val type = Class.forName(MANAGER)
        val manager = type.getMethod("get").invoke(null)
        check(type.getMethod("getEnabledDesktopTags").invoke(manager) != null) {
            "EUI desktop registry is unavailable; native input player cannot start safely"
        }
        check(type.getMethod("switchToSignalDesktop", String::class.java).invoke(manager, port) == true) {
            "EUI refused to open the input"
        }
        return true
    }
}
