package app.orchard.tv.ui.control

import android.net.wifi.WifiManager
import app.orchard.tv.system.NetKind
import app.orchard.tv.system.NetworkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the Network button: how the radio's state reads, what a press asks, and the icon's status. */
class WifiToggleTest {
    @Test fun statesMapToPhases() {
        assertEquals(WifiPhase.Off, WifiPhase.of(WifiManager.WIFI_STATE_DISABLED))
        assertEquals(WifiPhase.TurningOn, WifiPhase.of(WifiManager.WIFI_STATE_ENABLING))
        assertEquals(WifiPhase.On, WifiPhase.of(WifiManager.WIFI_STATE_ENABLED))
        assertEquals(WifiPhase.TurningOff, WifiPhase.of(WifiManager.WIFI_STATE_DISABLING))
        assertEquals(WifiPhase.Unknown, WifiPhase.of(WifiManager.WIFI_STATE_UNKNOWN))
        assertEquals(WifiPhase.Unknown, WifiPhase.of(99))
    }

    @Test fun buttonShowsOnUntilTheRadioGoesDown() {
        assertTrue(WifiPhase.On.shownOn)
        assertTrue(WifiPhase.TurningOn.shownOn)
        assertTrue(WifiPhase.Unknown.shownOn)
        assertFalse(WifiPhase.Off.shownOn)
        assertFalse(WifiPhase.TurningOff.shownOn)
    }

    @Test fun onlyTheMiddleOfASwitchIsTransitional() {
        assertTrue(WifiPhase.TurningOn.transitional)
        assertTrue(WifiPhase.TurningOff.transitional)
        assertFalse(WifiPhase.On.transitional)
        assertFalse(WifiPhase.Off.transitional)
        assertFalse(WifiPhase.Unknown.transitional)
    }

    @Test fun aPressAsksForTheOpposite() {
        assertEquals(false, WifiPhase.On.pressTarget())
        assertEquals(true, WifiPhase.Off.pressTarget())
        assertNull(WifiPhase.TurningOn.pressTarget())
        assertNull(WifiPhase.TurningOff.pressTarget())
        assertNull(WifiPhase.Unknown.pressTarget())
    }

    @Test fun theRadioHasReachedOnlyWhatWasAsked() {
        assertTrue(WifiPhase.On.reached(true))
        assertTrue(WifiPhase.Off.reached(false))
        assertFalse(WifiPhase.TurningOn.reached(true))
        assertFalse(WifiPhase.Off.reached(true))
        assertFalse(WifiPhase.On.reached(false))
        assertFalse(WifiPhase.TurningOff.reached(false))
    }

    @Test fun globeIsDroppedAndTheRestKept() {
        val status = NetworkStatus(
            kind = NetKind.Wifi, name = "home", level = 0.6f,
            vpn = true, internet = true, vpnValidated = true,
        )
        assertTrue(status.global)
        val plain = status.withoutGlobe()
        assertFalse(plain.global)
        assertEquals(status.kind, plain.kind)
        assertEquals(status.name, plain.name)
        assertEquals(status.signal, plain.signal)
        assertEquals(status.internet, plain.internet)
    }

    @Test fun noInternetAndOfflineLooksAreKept() {
        val noInternet = NetworkStatus(kind = NetKind.Wifi, internet = false, vpn = true)
        assertFalse(noInternet.withoutGlobe().internet)
        val offline = NetworkStatus(kind = NetKind.Offline)
        assertEquals(NetKind.Offline, offline.withoutGlobe().kind)
        val wired = NetworkStatus(kind = NetKind.Ethernet, internet = true, vpn = true)
        assertEquals(NetKind.Ethernet, wired.withoutGlobe().kind)
    }

    @Test fun aStatusWithoutGlobeIsLeftAsItIs() {
        val status = NetworkStatus(kind = NetKind.Wifi, level = 1f, internet = true)
        assertSame(status, status.withoutGlobe())
    }

    // The connection step, as the supplicant names it.
    @Test fun detailedStatesFoldToCaptionSteps() {
        assertEquals(WifiLinkPhase.Scanning, WifiLinkPhase.ofName("SCANNING"))
        assertEquals(WifiLinkPhase.Connecting, WifiLinkPhase.ofName("CONNECTING"))
        assertEquals(WifiLinkPhase.Authenticating, WifiLinkPhase.ofName("AUTHENTICATING"))
        assertEquals(WifiLinkPhase.ObtainingIp, WifiLinkPhase.ofName("OBTAINING_IPADDR"))
        assertEquals(WifiLinkPhase.Verifying, WifiLinkPhase.ofName("VERIFYING_POOR_LINK"))
        assertEquals(WifiLinkPhase.CaptivePortal, WifiLinkPhase.ofName("CAPTIVE_PORTAL_CHECK"))
        assertEquals(WifiLinkPhase.Connected, WifiLinkPhase.ofName("CONNECTED"))
    }

    @Test fun idleAndFailedStatesAreNotConnecting() {
        for (name in listOf("IDLE", "DISCONNECTING", "DISCONNECTED", "SUSPENDED", "FAILED", "BLOCKED", "SOMETHING_NEW", "")) {
            assertEquals(name, WifiLinkPhase.None, WifiLinkPhase.ofName(name))
        }
        assertEquals(WifiLinkPhase.None, WifiLinkPhase.ofName(null))
        assertEquals(WifiLinkPhase.None, WifiLinkPhase.of(null))
    }

    @Test fun ssidLosesItsQuotesAndThePlaceholder() {
        assertEquals("Home", cleanSsid("\"Home\""))
        assertEquals("Home", cleanSsid("Home"))
        assertEquals("My \"Wi-Fi\"", cleanSsid("\"My \"Wi-Fi\"\""))
        assertEquals("a b", cleanSsid("  \"a b\"  "))
        assertNull(cleanSsid(null))
        assertNull(cleanSsid(""))
        assertNull(cleanSsid("  "))
        assertNull(cleanSsid("\"\""))
        assertNull(cleanSsid("<unknown ssid>"))
        assertNull(cleanSsid("\"<unknown ssid>\""))
        assertNull(cleanSsid("0x"))
    }

    @Test fun captionIsPlainWhileTheRadioIsOffWhateverTheLinkSays() {
        val stale = WifiLink(WifiLinkPhase.Connected, "Home")
        assertEquals(NetworkCaption(WifiCaption.Plain), networkCaption(false, stale, "Home"))
        assertEquals(NetworkCaption(WifiCaption.Plain), networkCaption(false, WifiLink(WifiLinkPhase.ObtainingIp), null))
    }

    @Test fun captionIsPlainWhenOnButNotConnected() {
        assertEquals(NetworkCaption(WifiCaption.Plain), networkCaption(true, WifiLink(), null))
        // The status pill may still carry a name for a moment after the link let go: not shown.
        assertEquals(NetworkCaption(WifiCaption.Plain), networkCaption(true, WifiLink(WifiLinkPhase.None), "Home"))
    }

    @Test fun captionNamesTheStepWhileConnecting() {
        fun kind(phase: WifiLinkPhase) = networkCaption(true, WifiLink(phase, "ignored"), "ignored").kind
        assertEquals(WifiCaption.Scanning, kind(WifiLinkPhase.Scanning))
        assertEquals(WifiCaption.Connecting, kind(WifiLinkPhase.Connecting))
        assertEquals(WifiCaption.Authenticating, kind(WifiLinkPhase.Authenticating))
        assertEquals(WifiCaption.ObtainingIp, kind(WifiLinkPhase.ObtainingIp))
        assertEquals(WifiCaption.Verifying, kind(WifiLinkPhase.Verifying))
        assertEquals(WifiCaption.CaptivePortal, kind(WifiLinkPhase.CaptivePortal))
        assertNull(networkCaption(true, WifiLink(WifiLinkPhase.Scanning, "x"), "x").name)
    }

    @Test fun captionShowsTheNetworkNameOnceConnected() {
        assertEquals(NetworkCaption(WifiCaption.Name, "Home"), networkCaption(true, WifiLink(WifiLinkPhase.Connected, "\"Home\""), null))
        // The link read got no name (location off): the one the status pill has.
        assertEquals(NetworkCaption(WifiCaption.Name, "Office"), networkCaption(true, WifiLink(WifiLinkPhase.Connected, "<unknown ssid>"), "Office"))
        assertEquals(NetworkCaption(WifiCaption.Name, "Home"), networkCaption(true, WifiLink(WifiLinkPhase.Connected, "Home"), "Office"))
        // No name anywhere: "Wi-Fi".
        assertEquals(NetworkCaption(WifiCaption.Plain), networkCaption(true, WifiLink(WifiLinkPhase.Connected, null), null))
        assertEquals(NetworkCaption(WifiCaption.Plain), networkCaption(true, WifiLink(WifiLinkPhase.Connected, ""), "<unknown ssid>"))
    }

    // The circle's colour.
    @Test fun wifiOnIsBlueAndOffIsGrey() {
        assertEquals(ButtonTone.Blue, networkTone(true))
        assertEquals(ButtonTone.Plain, networkTone(false))
    }

    @Test fun anyConnectedSourceMakesTheSourceButtonYellow() {
        assertEquals(ButtonTone.Yellow, sourceTone(true))
        assertEquals(ButtonTone.Plain, sourceTone(false))
    }

    @Test fun vpnIsGreenWhileOnAsBefore() {
        assertEquals(ButtonTone.Green, vpnTone(true))
        assertEquals(ButtonTone.Plain, vpnTone(false))
    }

    // The icon.
    @Test fun radioOffDrawsTheSlashedWifi() {
        val wifi = NetworkStatus(kind = NetKind.Wifi, name = "home", level = 1f, internet = true)
        assertEquals(NetKind.Offline, networkButtonStatus(false, true, wifi).kind)
        assertEquals(NetKind.Offline, networkButtonStatus(false, true, NetworkStatus(kind = NetKind.Offline)).kind)
        // A cable in use does not turn the glyph into a cable while the radio is off.
        val wired = NetworkStatus(kind = NetKind.Ethernet, internet = true)
        assertEquals(NetKind.Offline, networkButtonStatus(false, true, wired).kind)
    }

    @Test fun noRadioWithACableKeepsTheCableGlyph() {
        val wired = NetworkStatus(kind = NetKind.Ethernet, internet = true)
        assertEquals(NetKind.Ethernet, networkButtonStatus(false, false, wired).kind)
        assertEquals(NetKind.Offline, networkButtonStatus(false, false, NetworkStatus(kind = NetKind.Offline)).kind)
    }

    @Test fun radioOnWithoutANetworkDrawsWifiWithNoBars() {
        val icon = networkButtonStatus(true, true, NetworkStatus(kind = NetKind.Offline))
        assertEquals(NetKind.Wifi, icon.kind)
        assertEquals(0, icon.signal)
        assertTrue(icon.internet)
    }

    @Test fun radioOnDrawsTheStatusWithoutTheGlobe() {
        val status = NetworkStatus(kind = NetKind.Wifi, level = 1f, vpn = true, internet = true, vpnValidated = true)
        val icon = networkButtonStatus(true, true, status)
        assertFalse(icon.global)
        assertEquals(3, icon.signal)
        val wired = NetworkStatus(kind = NetKind.Ethernet, internet = true)
        assertEquals(NetKind.Ethernet, networkButtonStatus(true, true, wired).kind)
    }

    // The caption's marquee.
    @Test fun textThatFitsHasNothingToTravel() {
        assertEquals(0, marqueeTravel(100, 200))
        assertEquals(0, marqueeTravel(200, 200))
        assertEquals(0, marqueeTravel(0, 0))
    }

    @Test fun overflowingTextTravelsByTheOverflow() {
        assertEquals(37, marqueeTravel(237, 200))
        assertEquals(1, marqueeTravel(201, 200))
    }

    @Test fun aLegRunsAtTheGivenSpeed() {
        assertEquals(0, marqueeLegMs(0, 70f))
        assertEquals(0, marqueeLegMs(100, 0f))
        assertEquals(1000, marqueeLegMs(70, 70f))
        assertEquals(2000, marqueeLegMs(140, 70f))
        // Never a zero-length leg for a little overflow.
        assertTrue(marqueeLegMs(1, 70000f) >= 1)
    }

    @Test fun marqueeMovesOnlyWhenOverflowingRunningAndMotionAllowed() {
        assertTrue(marqueeScrolls(10, running = true, reducedMotion = false))
        assertFalse(marqueeScrolls(0, running = true, reducedMotion = false))
        assertFalse(marqueeScrolls(10, running = false, reducedMotion = false))
        assertFalse(marqueeScrolls(10, running = true, reducedMotion = true))
    }
}
