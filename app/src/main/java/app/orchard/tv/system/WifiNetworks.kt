package app.orchard.tv.system

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build

/**
 * The TV's Wi-Fi networks, for Control Center's own network page (owner's request): the list is
 * picked and joined inside Aerial, the way the source picker works, instead of leaving Home for the
 * firm's Wi-Fi settings screen. Joining uses the framework's saved networks on Android 9 (the
 * firmwares Aerial targets), and falls back to the system page elsewhere.
 */
object WifiNetworks {

    data class Ap(
        val ssid: String,
        /** 0..4, the wifi bar count. */
        val bars: Int,
        val secured: Boolean,
        val saved: Boolean,
        val connected: Boolean,
    )

    enum class Outcome { Started, Failed, UseSystemPage }

    private fun manager(context: Context): WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun available(context: Context): Boolean = manager(context) != null

    @Suppress("DEPRECATION")
    fun scan(context: Context): List<Ap> {
        val wm = manager(context) ?: return emptyList()
        runCatching { wm.startScan() }
        val configured = runCatching { wm.configuredNetworks }.getOrNull().orEmpty()
        val current = runCatching { wm.connectionInfo?.ssid?.trim('"') }.getOrNull()
        val results = runCatching { wm.scanResults }.getOrNull().orEmpty()

        val out = LinkedHashMap<String, Ap>()
        // Joined networks first: they are what people check.
        configured.forEach { cfg ->
            val ssid = cfg.SSID?.trim('"')?.takeIf { it.isNotBlank() } ?: return@forEach
            val best = results.filter { it.SSID?.trim('"') == ssid }.maxByOrNull { it.level }
            out[ssid] = Ap(
                ssid = ssid,
                bars = best?.let { WifiManager.calculateSignalLevel(it.level, 5) - 1 }?.coerceIn(0, 4) ?: 2,
                secured = true,
                saved = true,
                connected = ssid == current,
            )
        }
        results.filter { it.SSID?.isNotBlank() == true }
            .groupBy { it.SSID.trim('"') }
            .forEach { (ssid, list) ->
                if (out.containsKey(ssid)) return@forEach
                val best = list.maxByOrNull { it.level } ?: return@forEach
                out[ssid] = Ap(
                    ssid = ssid,
                    bars = (WifiManager.calculateSignalLevel(best.level, 5) - 1).coerceIn(0, 4),
                    secured = best.capabilities.orEmpty().let {
                        it.contains("WPA") || it.contains("WEP") || it.contains("SAE") || it.contains("EAP")
                    },
                    saved = false,
                    connected = ssid == current,
                )
            }
        return out.values.sortedWith(compareByDescending<Ap> { it.connected }.thenByDescending { it.bars })
    }

    private fun quote(s: String) = "\"" + s.replace("\"", "\\\"") + "\""

    /** Joins [ap]; [password] is ignored for saved and open networks. */
    @Suppress("DEPRECATION")
    fun connect(context: Context, ap: Ap, password: String): Outcome {
        val wm = manager(context) ?: return Outcome.Failed
        // From Android 10 a plain app can no longer add networks; the system page takes over.
        if (Build.VERSION.SDK_INT >= 29) return Outcome.UseSystemPage
        val existing = runCatching {
            wm.configuredNetworks?.firstOrNull { it.SSID?.trim('"') == ap.ssid }
        }.getOrNull()
        var id = existing?.networkId ?: -1
        if (id == -1 || (ap.secured && !ap.saved && password.isNotEmpty())) {
            val cfg = existing ?: WifiConfiguration()
            cfg.SSID = quote(ap.ssid)
            cfg.allowedKeyManagement.clear()
            if (ap.secured) {
                cfg.preSharedKey = quote(password)
                cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            } else {
                cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            }
            val added = if (existing == null) wm.addNetwork(cfg) else {
                wm.updateNetwork(cfg).takeIf { it != -1 } ?: cfg.networkId
            }
            if (added == -1) return Outcome.Failed
            id = added
            runCatching { wm.saveConfiguration() }
        }
        runCatching { wm.disconnect() }
        if (!wm.enableNetwork(id, true)) return Outcome.Failed
        wm.reconnect()
        return Outcome.Started
    }
}
