package app.orchard.tv.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowWifiInfo

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class NetworkStatusTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val connectivity = shadowOf(cm)
    private val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Before
    fun clearNetworks() {
        connectivity.setActiveNetworkInfo(null)
        connectivity.clearAllNetworks()
    }

    @Test
    fun wifiNameAndSignalStayTheSameWhenVpnIsToggled() {
        setWifiInfo("Test Wi-Fi", -65)
        val wifi = setDefault(ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
        val expected = SystemProbe.network(context)
        assertEquals(NetKind.Wifi, expected.kind)
        assertEquals("Test Wi-Fi", expected.name)
        assertEquals(WifiManager.calculateSignalLevel(-65, 4) / 3f, expected.level, 0f)

        repeat(3) {
            setDefault(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
            assertEquals(expected.copy(vpn = true, vpnValidated = true), SystemProbe.network(context))
            // The VPN stays among the networks, so only the name and signal are compared here.
            connectivity.setActiveNetworkInfo(cm.getNetworkInfo(wifi))
            assertEquals(expected, SystemProbe.network(context).copy(vpn = false, vpnValidated = false))
        }
    }

    @Test
    fun ethernetStaysEthernetWhenVpnIsToggled() {
        val ethernet = setDefault(ConnectivityManager.TYPE_ETHERNET, NetworkCapabilities.TRANSPORT_ETHERNET)
        assertEquals(NetKind.Ethernet, SystemProbe.network(context).kind)
        setDefault(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
        assertEquals(NetKind.Ethernet, SystemProbe.network(context).kind)
        connectivity.setActiveNetworkInfo(cm.getNetworkInfo(ethernet))
        assertEquals(NetKind.Ethernet, SystemProbe.network(context).kind)
    }

    @Test
    fun disconnectedEthernetDoesNotHideConnectedWifiUnderVpn() {
        setDefault(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
        addPhysical(100, ConnectivityManager.TYPE_ETHERNET, NetworkCapabilities.TRANSPORT_ETHERNET, connected = false)
        addPhysical(101, ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
        assertEquals(NetKind.Wifi, SystemProbe.network(context).kind)
    }

    @Test
    fun disconnectedWifiDoesNotHideConnectedEthernetUnderVpn() {
        setDefault(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
        addPhysical(100, ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI, connected = false)
        addPhysical(101, ConnectivityManager.TYPE_ETHERNET, NetworkCapabilities.TRANSPORT_ETHERNET)
        assertEquals(NetKind.Ethernet, SystemProbe.network(context).kind)
    }

    @Test
    fun dualConnectionsPreferEthernetRegardlessOfEnumerationOrder() {
        for ((wifiId, ethernetId) in listOf(100 to 101, 101 to 100)) {
            clearNetworks()
            setDefault(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
            addPhysical(wifiId, ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
            addPhysical(ethernetId, ConnectivityManager.TYPE_ETHERNET, NetworkCapabilities.TRANSPORT_ETHERNET)
            assertEquals(NetKind.Ethernet, SystemProbe.network(context).kind)
        }
    }

    @Test
    fun anotherVpnsInheritedEthernetIsNotAPhysicalConnection() {
        setDefault(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
        val otherVpn = ShadowNetwork.newInstance(100)
        connectivity.addNetwork(otherVpn, networkInfo(ConnectivityManager.TYPE_VPN))
        connectivity.setNetworkCapabilities(otherVpn, capabilities(
            NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_ETHERNET,
        ))
        addPhysical(101, ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
        assertEquals(NetKind.Wifi, SystemProbe.network(context).kind)
    }

    @Test
    fun defaultVpnsExplicitWifiTransportKeepsExistingPriority() {
        setDefault(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI)
        addPhysical(100, ConnectivityManager.TYPE_ETHERNET, NetworkCapabilities.TRANSPORT_ETHERNET)
        assertEquals(NetKind.Wifi, SystemProbe.network(context).kind)
    }

    @Test
    fun nonVpnDefaultIsNotOverriddenByAnotherPhysicalNetwork() {
        setDefault(ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
        addPhysical(100, ConnectivityManager.TYPE_ETHERNET, NetworkCapabilities.TRANSPORT_ETHERNET)
        assertEquals(NetKind.Wifi, SystemProbe.network(context).kind)
    }

    @Test
    fun missingSsidDoesNotChangeWifiToEthernet() {
        setWifiInfo("<unknown ssid>", -65)
        setDefault(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
        addPhysical(100, ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
        val status = SystemProbe.network(context)
        assertEquals(NetKind.Wifi, status.kind)
        assertNull(status.name)
    }

    @Test
    fun localOnlyWifiDoesNotRequireInternetValidation() {
        setDefault(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
        val wifi = addPhysical(100, ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
        val caps = capabilities(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        connectivity.setNetworkCapabilities(wifi, caps)
        assertEquals(NetKind.Wifi, SystemProbe.network(context).kind)
    }

    @Test
    fun candidateWithoutCapabilitiesDoesNotHideConnectedWifi() {
        setDefault(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
        connectivity.addNetwork(ShadowNetwork.newInstance(100), networkInfo(ConnectivityManager.TYPE_ETHERNET))
        addPhysical(101, ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
        assertEquals(NetKind.Wifi, SystemProbe.network(context).kind)
    }

    @Test
    fun missingPhysicalConnectionEvidenceKeepsExistingVpnFallback() {
        setDefault(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
        val network = ShadowNetwork.newInstance(100)
        connectivity.addNetwork(network, null)
        connectivity.setNetworkCapabilities(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(NetKind.Ethernet, SystemProbe.network(context).kind)
    }

    @Test
    fun unresolvedVpnKeepsExistingFallbackEvenWithStaleWifiInfo() {
        setWifiInfo("Previously connected", -50)
        setDefault(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
        assertEquals(NetKind.Ethernet, SystemProbe.network(context).kind)
    }

    @Test
    fun unknownNonVpnTransportKeepsExistingFallback() {
        setDefault(ConnectivityManager.TYPE_MOBILE, NetworkCapabilities.TRANSPORT_CELLULAR)
        assertEquals(NetKind.Ethernet, SystemProbe.network(context).kind)
    }

    @Test
    fun noDefaultNetworkIsOfflineEvenWithStaleWifiInfo() {
        setWifiInfo("Previously connected", -50)
        assertEquals(NetKind.Offline, SystemProbe.network(context).kind)
    }

    @Test
    @Config(sdk = [23])
    fun vpnOnlyDefaultFindsConnectedWifiOnAndroidSix() {
        setDefault(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
        addPhysical(100, ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
        assertEquals(NetKind.Wifi, SystemProbe.network(context).kind)
    }

    @Test
    fun vpnThatLeavesAerialOutStillShows() {
        setDefault(ConnectivityManager.TYPE_ETHERNET, NetworkCapabilities.TRANSPORT_ETHERNET)
        assertEquals(false, SystemProbe.network(context).vpn)
        addPhysical(100, ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
        assertEquals(NetworkStatus(NetKind.Ethernet, vpn = true, internet = true, vpnValidated = true), SystemProbe.network(context))
    }

    @Test
    fun disconnectedVpnDoesNotShow() {
        setDefault(ConnectivityManager.TYPE_ETHERNET, NetworkCapabilities.TRANSPORT_ETHERNET)
        addPhysical(100, ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN, connected = false)
        assertEquals(false, SystemProbe.network(context).vpn)
    }

    @Test
    fun wifiWithoutValidationShowsNoInternetEvenWhenInternetIsConfigured() {
        val wifi = setDefault(ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
        val caps = capabilities(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        connectivity.setNetworkCapabilities(wifi, caps)
        val status = SystemProbe.network(context)
        assertEquals(NetKind.Wifi, status.kind)
        assertEquals(false, status.internet)
        assertEquals(false, status.global)
    }

    @Test
    @Config(sdk = [28])
    fun validatedVpnRequiresValidatedPhysicalUplinkOnAndroidNine() {
        setDefault(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
        val wifi = addPhysical(100, ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
        val caps = capabilities(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        connectivity.setNetworkCapabilities(wifi, caps)
        val status = SystemProbe.network(context)
        assertEquals(true, status.vpn)
        assertEquals(false, status.internet)
        assertEquals(false, status.global)
    }

    @Test
    fun validatedVpnAndValidatedWifiShowGlobal() {
        setDefault(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
        addPhysical(100, ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
        val status = SystemProbe.network(context)
        assertEquals(NetKind.Wifi, status.kind)
        assertEquals(true, status.internet)
        assertEquals(true, status.global)
    }

    @Test
    fun captivePortalAlwaysSuppressesGlobalEvenWithStaleValidation() {
        setDefault(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
        val wifi = addPhysical(100, ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
        val caps = capabilities(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        connectivity.setNetworkCapabilities(wifi, caps)
        assertEquals(false, SystemProbe.network(context).global)
        assertEquals(false, SystemProbe.network(context).internet)
    }

    @Test
    fun localOnlyWifiWithVpnDoesNotShowGlobal() {
        setDefault(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
        val wifi = addPhysical(100, ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
        val caps = capabilities(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        connectivity.setNetworkCapabilities(wifi, caps)
        assertEquals(NetKind.Wifi, SystemProbe.network(context).kind)
        assertEquals(false, SystemProbe.network(context).global)
    }

    @Test
    fun vpnTransportWithoutItsOwnValidationDoesNotClaimInternet() {
        val vpn = setDefault(ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
        addPhysical(100, ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
        val caps = capabilities(NetworkCapabilities.TRANSPORT_VPN)
        shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        connectivity.setNetworkCapabilities(vpn, caps)
        assertEquals(false, SystemProbe.network(context).internet)
        assertEquals(false, SystemProbe.network(context).global)
    }

    @Test
    fun unvalidatedPerAppVpnDoesNotShowGlobalOverValidatedWifi() {
        setDefault(ConnectivityManager.TYPE_WIFI, NetworkCapabilities.TRANSPORT_WIFI)
        val vpn = addPhysical(100, ConnectivityManager.TYPE_VPN, NetworkCapabilities.TRANSPORT_VPN)
        val caps = capabilities(NetworkCapabilities.TRANSPORT_VPN)
        shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        connectivity.setNetworkCapabilities(vpn, caps)
        val status = SystemProbe.network(context)
        assertEquals(true, status.internet)
        assertEquals(true, status.vpn)
        assertEquals(false, status.global)
    }

    @Test
    fun noInternetWinsOverVpnInIconState() {
        val status = NetworkStatus(NetKind.Wifi, vpn = true, internet = false, vpnValidated = true)
        assertEquals(false, status.global)
        assertEquals(true, status.copy(internet = true).global)
    }

    @Test
    fun wifiSignalHasFourDistinctTiers() {
        for (tier in 0..3) {
            assertEquals(tier, NetworkStatus(NetKind.Wifi, level = tier / 3f).signal)
        }
        assertEquals(0, NetworkStatus(NetKind.Wifi, level = -1f).signal)
        assertEquals(3, NetworkStatus(NetKind.Wifi, level = 2f).signal)
        assertEquals(0, NetworkStatus(NetKind.Wifi, level = Float.NaN).signal)
    }

    private fun setWifiInfo(ssid: String, rssi: Int) {
        val info = ShadowWifiInfo.newInstance()
        shadowOf(info).setSSID(ssid)
        shadowOf(info).setRssi(rssi)
        shadowOf(wm).setConnectionInfo(info)
    }

    private fun setDefault(type: Int, vararg transports: Int): Network {
        connectivity.setActiveNetworkInfo(networkInfo(type))
        val network = ShadowNetwork.newInstance(type)
        connectivity.setNetworkCapabilities(network, capabilities(*transports))
        return network
    }

    private fun addPhysical(id: Int, type: Int, transport: Int, connected: Boolean = true): Network {
        val network = ShadowNetwork.newInstance(id)
        connectivity.addNetwork(network, networkInfo(type, connected))
        connectivity.setNetworkCapabilities(network, capabilities(transport))
        return network
    }

    private fun networkInfo(type: Int, connected: Boolean = true): NetworkInfo = ShadowNetworkInfo.newInstance(
        if (connected) NetworkInfo.DetailedState.CONNECTED else NetworkInfo.DetailedState.DISCONNECTED,
        type, 0, true,
        if (connected) NetworkInfo.State.CONNECTED else NetworkInfo.State.DISCONNECTED,
    )

    private fun capabilities(vararg transports: Int) = ShadowNetworkCapabilities.newInstance().apply {
        transports.forEach { shadowOf(this).addTransportType(it) }
        shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
