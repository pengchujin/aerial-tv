package app.orchard.tv.system

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.orchard.tv.R
import java.io.File

/**
 * Inputs of old Hisense VIDAA (4.1, Android 5.1.1, Hi3751V551: LED65N3000U, issue #1386; also
 * #580). No TvInputManager inputs, no SourceManager or tile transition service (HisenseSources):
 * the ports are in /system/etc/silo_config.xml, readable by any app, and a broadcast with no
 * package switches them. The firmware's receiver is registered at run time and asks no
 * permission. Checked by the reporter on the TV, from an ordinary app's uid (untrusted_app) too:
 * id 1281 opened HDMI 1, 1282 HDMI 2, 257 live TV, each read back from persist.sys.last_source.
 * Hdmi3 (1283) and Av1 (1537) are listed in the file but the firmware ignored them, so they are left
 * out. Not where the tile transition package is installed: newer VIDAA takes the broadcast only
 * addressed to it (HisenseSources).
 */
internal object VidaaSiloSources {
    private const val TAG = "AerialVidaaSources"
    private const val CONFIG = "/system/etc/silo_config.xml"
    private const val SWITCH_ACTION = "com.jamdeo.tv.policy.SILO_SWITCH_EVENT"
    private const val SOURCE_EXTRA = "silo_input_src_id"
    private const val ID_PREFIX = "vidaa:"
    private const val LIVE_TV = 257

    /** HDMI port number -> the broadcast's id, those that switched on the reporter's TV. */
    private val HDMI_IDS = mapOf(1 to 1281, 2 to 1282)

    private val physical = Regex("<physical_inputs>(.*?)</physical_inputs>", RegexOption.DOT_MATCHES_ALL)
    private val silo = Regex("<silo\\s+name=\"([^\"]+)\"[^>]*>(.*?)</silo>", RegexOption.DOT_MATCHES_ALL)
    private val type = Regex("<type>\\s*([A-Z_]+)\\s*</type>")
    private val input = Regex("<input>\\s*HDMI\\s*(\\d+)\\s*</input>", RegexOption.IGNORE_CASE)

    fun load(context: Context): List<TvSource> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return emptyList()
        if (runCatching { context.packageManager.getPackageInfo("com.hisense.tv.tiletransition", 0) }.isSuccess) return emptyList()
        val file = File(CONFIG)
        if (!file.canRead()) return emptyList()
        val xml = runCatching { file.readText() }.onFailure { Log.w(TAG, "could not read $CONFIG", it) }.getOrNull() ?: return emptyList()
        val block = physical.find(xml)?.groupValues?.get(1) ?: return emptyList()
        val current = lastSource()
        val hdmi = silo.findAll(block).mapNotNull { m ->
            val body = m.groupValues[2]
            if (type.find(body)?.groupValues?.get(1) != "HDMI") return@mapNotNull null
            val number = input.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val id = HDMI_IDS[number] ?: return@mapNotNull null
            source(id, "HDMI $number", SourceKind.Hdmi, current)
        }.distinctBy { it.id }.sortedBy { it.label }.toList()
        if (hdmi.isEmpty()) return emptyList()
        return hdmi + source(LIVE_TV, context.getString(R.string.input_live_tv), SourceKind.Tuner, current)
    }

    private fun source(id: Int, label: String, kind: SourceKind, current: Int) = TvSource(
        id = "$ID_PREFIX$id",
        label = label,
        kind = kind,
        connected = kind == SourceKind.Hdmi && current == id,
        deviceName = null,
        intent = Intent(SWITCH_ACTION).putExtra(SOURCE_EXTRA, id),
    )

    /** A tile of these, opened: the broadcast as it is, with no package (see the class comment). */
    fun open(context: Context, intent: Intent): Boolean {
        if (intent.action != SWITCH_ACTION || intent.`package` != null || intent.component != null ||
            intent.getIntExtra(SOURCE_EXTRA, 0) !in HDMI_IDS.values + LIVE_TV) return false
        context.sendBroadcast(Intent(intent))
        return true
    }

    /** The input the firmware shows last, 0 when unknown. */
    private fun lastSource(): Int = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
            .invoke(null, "persist.sys.last_source", "")?.toString()?.toIntOrNull() ?: 0
    }.getOrDefault(0)
}
