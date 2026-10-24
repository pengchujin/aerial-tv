package app.orchard.tv.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NetworkStatusTest {
    private fun caps(vararg transports: Int): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { caps ->
            transports.forEach { shadowOf(caps).addTransportType(it) }
        }

    @Test
    fun wifiBehindVpnIsNotEthernet() {
        val vpn = caps(TRANSPORT_VPN)
        val status = NetworkProbe.summarize(vpn, listOf(caps(TRANSPORT_WIFI), vpn))
        assertEquals(NetKind.Wifi, status.kind)
        assertTrue(status.wifiConnected)
        assertFalse(status.ethernetConnected)
        assertTrue(status.vpnConnected)
    }

    @Test
    fun ethernetBehindVpnDoesNotHideVpn() {
        val vpn = caps(TRANSPORT_VPN)
        val status = NetworkProbe.summarize(vpn, listOf(caps(TRANSPORT_ETHERNET), vpn))
        assertEquals(NetKind.Ethernet, status.kind)
        assertFalse(status.wifiConnected)
        assertTrue(status.ethernetConnected)
        assertTrue(status.vpnConnected)
    }

    @Test
    fun allThreeConnectionsRemainIndependent() {
        val vpn = caps(TRANSPORT_VPN)
        val status = NetworkProbe.summarize(vpn, listOf(caps(TRANSPORT_WIFI), caps(TRANSPORT_ETHERNET), vpn))
        assertTrue(status.wifiConnected)
        assertTrue(status.ethernetConnected)
        assertTrue(status.vpnConnected)
        assertEquals(NetKind.Other, status.kind)
    }

    @Test
    fun bothPhysicalLinksWithoutVpnPreserveTheDefaultRoute() {
        val wifi = caps(TRANSPORT_WIFI)
        val ethernet = caps(TRANSPORT_ETHERNET)
        for ((active, kind) in listOf(wifi to NetKind.Wifi, ethernet to NetKind.Ethernet)) {
            val status = NetworkProbe.summarize(active, listOf(wifi, ethernet))
            assertEquals(kind, status.kind)
            assertTrue(status.wifiConnected)
            assertTrue(status.ethernetConnected)
            assertFalse(status.vpnConnected)
        }
    }

    @Test
    fun vpnWithInheritedTransportsDoesNotInventPhysicalConnections() {
        val vpn = caps(TRANSPORT_VPN, TRANSPORT_WIFI, TRANSPORT_ETHERNET)
        val status = NetworkProbe.summarize(vpn, listOf(vpn))
        assertFalse(status.wifiConnected)
        assertFalse(status.ethernetConnected)
        assertTrue(status.vpnConnected)
        assertEquals(NetKind.Other, status.kind)
    }

    @Test
    fun unknownTransportIsNotEthernet() {
        val cellular = caps(TRANSPORT_CELLULAR)
        val status = NetworkProbe.summarize(cellular, listOf(cellular))
        assertEquals(NetKind.Other, status.kind)
        assertFalse(status.ethernetConnected)
        assertFalse(status.wifiConnected)
        assertFalse(status.vpnConnected)
    }

    @Test
    fun losingVpnKeepsWifiConnected() {
        val wifi = caps(TRANSPORT_WIFI)
        val status = NetworkProbe.summarize(wifi, listOf(wifi))
        assertTrue(status.wifiConnected)
        assertFalse(status.ethernetConnected)
        assertFalse(status.vpnConnected)
    }

    @Test
    fun noNetworksMeansAllDisconnected() {
        val status = NetworkProbe.summarize(null, emptyList())
        assertEquals(NetKind.Offline, status.kind)
        assertFalse(status.wifiConnected)
        assertFalse(status.ethernetConnected)
        assertFalse(status.vpnConnected)
    }

    @Test
    fun localWifiWithoutDefaultInternetIsStillConnected() {
        val status = NetworkProbe.summarize(null, listOf(caps(TRANSPORT_WIFI)))
        assertTrue(status.wifiConnected)
        assertEquals(NetKind.Offline, status.kind)
    }

    @Test
    fun observationIncludesVpnAndUnvalidatedLocalNetworks() {
        val request = NetworkProbe.request()
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertFalse(request.hasTransport(TRANSPORT_WIFI))
        assertFalse(request.hasTransport(TRANSPORT_ETHERNET))
    }

    @Suppress("DEPRECATION")
    @Test
    fun platformReadIgnoresDisconnectedInterfacesAndDetectsVpn() {
        val context = RuntimeEnvironment.getApplication()
        val cm = shadowOf(context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
        cm.clearAllNetworks()
        val entries = listOf(
            Triple(ConnectivityManager.TYPE_WIFI, TRANSPORT_WIFI, true),
            Triple(ConnectivityManager.TYPE_ETHERNET, TRANSPORT_ETHERNET, false),
            Triple(ConnectivityManager.TYPE_VPN, TRANSPORT_VPN, true),
        )
        entries.forEach { (type, transport, connected) ->
            val network = ShadowNetwork.newInstance(type)
            val info = ShadowNetworkInfo.newInstance(
                if (connected) NetworkInfo.DetailedState.CONNECTED else NetworkInfo.DetailedState.DISCONNECTED,
                type, 0, true, connected,
            )
            cm.addNetwork(network, info)
            cm.setNetworkCapabilities(network, caps(transport))
            if (type == ConnectivityManager.TYPE_VPN) cm.setActiveNetworkInfo(info)
        }
        val status = NetworkProbe.read(context)
        assertTrue(status.wifiConnected)
        assertFalse(status.ethernetConnected)
        assertTrue(status.vpnConnected)
    }

    @Test
    fun wifiDisabledIsDifferentFromDisconnectedAndDoesNotExposeStaleSsid() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).clearAllNetworks()
        val wifi = shadowOf(context.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        wifi.setWifiState(WifiManager.WIFI_STATE_DISABLED)
        val disabled = NetworkProbe.read(context)
        assertEquals(false, disabled.wifiEnabled)
        assertFalse(disabled.wifiConnected)
        assertEquals(null, disabled.name)
        wifi.setWifiState(WifiManager.WIFI_STATE_ENABLED)
        val disconnected = NetworkProbe.read(context)
        assertEquals(true, disconnected.wifiEnabled)
        assertFalse(disconnected.wifiConnected)
    }
}
