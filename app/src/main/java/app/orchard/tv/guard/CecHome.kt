package app.orchard.tv.guard

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.util.Log
import app.orchard.tv.apps.SourceDevice
import app.orchard.tv.apps.SourceLooks
import java.io.File
import java.util.concurrent.Executors

/** Opt-in Amlogic raw CEC transport, verified on OBE_NEW X7 (Android 9, driver version 5).
 * The ordinary HDMI service requires a system signature. This firmware exposes /dev/cec instead.
 * Never change driver addresses or settings: share the existing TV logical address (0).
 */
internal object CecHome {
    private val sender = Executors.newSingleThreadExecutor()

    private fun prefs(context: Context) = context.getSharedPreferences("system", Context.MODE_PRIVATE)

    // This is an Amlogic raw-frame ABI, not Linux's unrelated /dev/cec0 ioctl ABI.
    // X7 version 5 is verified; other versions of this ABI require device testing.
    fun enabled(context: Context): Boolean =
        GuardSetup.isUserUnlocked(context) && prefs(context).getString("cec_home_input", null) != null &&
            File("/dev/cec").canWrite() && File("/sys/class/cec/cec_version").canRead()

    /** The setting that names the input in front, while the feature is configured; else null. */
    fun inputSettingKey(context: Context): String? {
        // Home Guard binds before credential storage opens, even when CEC Home is disabled.
        // Reading it here crashed Aerial at boot on Hisense H55E9A (Android 8).
        if (!GuardSetup.isUserUnlocked(context)) return null
        val config = prefs(context)
        if (config.getString("cec_home_input", null) == null) return null
        return config.getString("cec_home_input_setting", null) ?: "tv_current_inputid"
    }

    private data class Target(val input: String, val address: Int)
    private var lastReport: String? = null

    private fun target(context: Context, frontPackage: String?): Target? = runCatching {
        if (frontPackage == null || !enabled(context)) return null
        val config = prefs(context)
        val key = inputSettingKey(context) ?: return null
        val current = Settings.System.getString(context.contentResolver, key)
            ?: Settings.Global.getString(context.contentResolver, key) ?: return null
        val configured = config.getString("cec_home_input", null)
        if (configured != "*" && configured != current) return null
        val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return null
        val inputs = manager.tvInputList
        val selected = inputs.firstOrNull { it.id == current } ?: return null
        val parent = selected.parentId ?: selected.id
        val port = inputs.firstOrNull { it.id == parent && it.type == TvInputInfo.TYPE_HDMI } ?: return null
        val player = config.getString("cec_home_player", null)
            ?: context.packageManager.resolveActivity(
                Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(port.id)), 0,
            )?.activityInfo?.packageName
            ?: "com.droidlogic.tvsource"
        if (frontPackage != player) return null
        if (manager.getInputState(selected.id) != TvInputManager.INPUT_STATE_CONNECTED) return null
        val devices = if (selected.parentId != null) listOf(selected)
            else inputs.filter { it.parentId == parent }
        // A physical AVR port alone does not identify which of several downstream devices is active.
        if (devices.size != 1) return null
        SourceLooks.init(context)
        val manualAppleTv = SourceLooks.of(port.id)?.device == SourceDevice.AppleTv
        val apple = devices.mapNotNull { device ->
            val label = runCatching { device.loadLabel(context).toString() }.getOrDefault("")
            val hdmi by lazy {
                runCatching { device.javaClass.getMethod("getHdmiDeviceInfo").invoke(device) }.getOrNull()
            }
            // Manual tile selection comes first; CEC identity is only the fallback.
            val recognized = manualAppleTv || run {
                val vendor = hdmi?.let { runCatching { it.javaClass.getMethod("getVendorId").invoke(it) as Int }.getOrNull() }
                val name = hdmi?.let { runCatching { it.javaClass.getMethod("getDisplayName").invoke(it) as String }.getOrNull() } ?: label
                isAppleTv(vendor, name)
            }
            val report = "CEC input=${port.id} device=$label manualApple=$manualAppleTv apple=$recognized"
            if (report != lastReport) { lastReport = report; Log.i("AerialGuard", report) }
            if (!recognized) return@mapNotNull null
            val address = Regex("HDMI[0-9a-fA-F]{4}([0-9a-fA-F]{2})$")
                .find(device.id)?.groupValues?.get(1)?.toIntOrNull(16)
                ?: hdmi?.let { runCatching { it.javaClass.getMethod("getLogicalAddress").invoke(it) as Int }.getOrNull() }
                ?: return@mapNotNull null
            address.takeIf { it in 1..14 }?.let { Target(port.id, it) }
        }
        // Do not guess when several CEC devices share an AVR/switch input.
        apple.singleOrNull()
    }.getOrNull()

    internal fun isAppleTv(vendor: Int?, name: String): Boolean =
        vendor == 0x0010fa || ((vendor == null || vendor == 0) &&
            Regex("""(?i)^apple[ _-]*tv(?:\s.*)?$""").matches(name.trim()))

    fun active(context: Context, frontPackage: String?): Boolean = target(context, frontPackage) != null

    fun send(context: Context, frontPackage: String?) {
        val address = target(context, frontPackage)?.address ?: return
        sender.execute {
            runCatching {
                val fd = Os.open("/dev/cec", OsConstants.O_WRONLY, 0)
                try {
                    // One syscall per frame: X7 reports a CEC status (zero on success), not a
                    // byte count. Stream APIs may retry forever or duplicate the command.
                    try {
                        val press = byteArrayOf(address.toByte(), 0x44, 0x09)
                        val result = Os.write(fd, press, 0, press.size)
                        Log.i("AerialGuard", "CEC Home press result=$result")
                        Thread.sleep(100)
                    } finally {
                        val release = byteArrayOf(address.toByte(), 0x45)
                        val result = Os.write(fd, release, 0, release.size)
                        Log.i("AerialGuard", "CEC Home release result=$result")
                    }
                } finally {
                    Os.close(fd)
                }
                Log.i("AerialGuard", "CEC Home sent to logical address $address")
            }.onFailure { Log.w("AerialGuard", "CEC Home send failed", it) }
        }
    }
}
