package app.orchard.tv.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * The resolvers Android uses for the TV's real uplink (usually the router, from DHCP), handed to
 * the core so DIRECT hosts resolve exactly where the rest of the TV resolves them.
 */
internal object SystemDns {
    fun servers(context: Context): List<String> = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val all = manager.allNetworks.toList()
        val active = if (Build.VERSION.SDK_INT >= 23) manager.activeNetwork else null
        // Another app's VPN can be the default network: its resolvers are that app's, not the router's.
        val uplinks = (listOfNotNull(active) + all).distinct().filter { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@filter false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        uplinks.asSequence().map { network ->
            manager.getLinkProperties(network)?.dnsServers.orEmpty().filter(::usable).mapNotNull(::literal)
        }.firstOrNull { it.isNotEmpty() }.orEmpty().distinct().take(4)
    }.getOrDefault(emptyList())

    private fun usable(address: InetAddress): Boolean =
        !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isMulticastAddress &&
            // Link-local IPv6 needs a zone the core's config cannot carry.
            !(address is Inet6Address && address.isLinkLocalAddress)

    private fun literal(address: InetAddress): String? = when (address) {
        is Inet4Address -> address.hostAddress
        is Inet6Address -> address.hostAddress?.substringBefore('%')
        else -> null
    }
}
