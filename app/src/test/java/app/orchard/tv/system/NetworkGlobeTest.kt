package app.orchard.tv.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The status icon's globe comes only from Android's own VPN state (NetworkStatus.global). */
class NetworkGlobeTest {
    private val wifi = NetworkStatus(NetKind.Wifi, "Home", level = 2f / 3f, internet = true)
    private val ethernet = NetworkStatus(NetKind.Ethernet, internet = true)

    @Test
    fun noInternetNeverShowsTheGlobe() {
        assertFalse(wifi.copy(internet = false, vpn = true, vpnValidated = true).global)
        assertFalse(NetworkStatus(vpn = true, vpnValidated = true).global)
        assertFalse(ethernet.copy(internet = false, vpn = true, vpnValidated = true).global)
    }

    @Test
    fun validatedVpnShowsTheGlobe() {
        assertTrue(wifi.copy(vpn = true, vpnValidated = true).global)
    }

    @Test
    fun unvalidatedVpnShowsNoGlobe() {
        assertFalse(wifi.copy(vpn = true, vpnValidated = false).global)
    }

    @Test
    fun noVpnShowsNoGlobeEvenIfValidated() {
        assertFalse(wifi.global)
        assertFalse(wifi.copy(vpnValidated = true).global)
    }

    @Test
    fun ethernetCombinesTheSameWayAndKeepsItsKind() {
        val status = ethernet.copy(vpn = true, vpnValidated = true)
        assertTrue(status.global)
        assertEquals(NetKind.Ethernet, status.kind)
        assertFalse(ethernet.global)
    }
}
