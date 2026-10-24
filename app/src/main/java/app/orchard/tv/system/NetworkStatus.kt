package app.orchard.tv.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build

enum class NetKind { Wifi, Ethernet, Other, Offline }

data class NetworkStatus(
    val kind: NetKind = NetKind.Offline,
    val name: String? = null,
    val level: Float = 1f,
    val wifiConnected: Boolean = false,
    val ethernetConnected: Boolean = false,
    val vpnConnected: Boolean = false,
    val wifiEnabled: Boolean? = null,
)

internal object NetworkProbe {
    // A default request excludes VPNs. Observe all visible networks, including non-default links.
    fun request(): NetworkRequest = NetworkRequest.Builder().clearCapabilities().build()

    fun read(context: Context): NetworkStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkStatus()
        @Suppress("DEPRECATION")
        val networks = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        val active = runCatching {
            if (Build.VERSION.SDK_INT >= 23) cm.activeNetwork else {
                @Suppress("DEPRECATION")
                val type = cm.activeNetworkInfo?.takeIf { it.isConnected }?.type
                @Suppress("DEPRECATION")
                networks.firstOrNull { type != null && cm.getNetworkInfo(it)?.type == type }
            }
        }.getOrNull()
        val caps = (networks + listOfNotNull(active)).distinct().mapNotNull { network ->
            runCatching {
                // A tracked network can still be connecting or waiting to be removed.
                @Suppress("DEPRECATION")
                if (cm.getNetworkInfo(network)?.isConnected == false) null
                else cm.getNetworkCapabilities(network)
            }.getOrNull()
        }
        val activeCaps = active?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val enabled = runCatching { wm?.isWifiEnabled }.getOrNull()
        val status = summarize(activeCaps, caps).copy(wifiEnabled = enabled)
        if (!status.wifiConnected) return status
        @Suppress("DEPRECATION")
        val info = runCatching { wm?.connectionInfo }.getOrNull()
        val ssid = info?.ssid?.trim('"')?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID }
        @Suppress("DEPRECATION")
        val level = if (info == null) 1f else WifiManager.calculateSignalLevel(info.rssi, 4) / 3f
        return status.copy(name = ssid, level = level)
    }

    internal fun summarize(active: NetworkCapabilities?, networks: List<NetworkCapabilities>): NetworkStatus {
        // A VPN may advertise underlying transports too; that is not a separate physical link.
        // Hisense H55E9A / Android 8 reports VPN alone, which used to fall through to Ethernet.
        val physical = networks.filterNot { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        val wifi = physical.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        val ethernet = physical.any { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
        val vpn = networks.any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        val kind = when {
            active == null -> NetKind.Offline
            active.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> when {
                wifi && !ethernet -> NetKind.Wifi
                ethernet && !wifi -> NetKind.Ethernet
                else -> NetKind.Other
            }
            active.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetKind.Ethernet
            active.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetKind.Wifi
            else -> NetKind.Other
        }
        return NetworkStatus(kind = kind, wifiConnected = wifi, ethernetConnected = ethernet, vpnConnected = vpn)
    }
}
