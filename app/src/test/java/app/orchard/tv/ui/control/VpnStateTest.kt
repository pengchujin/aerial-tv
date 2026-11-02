package app.orchard.tv.ui.control

import app.orchard.tv.system.NetKind
import app.orchard.tv.system.NetworkStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class VpnStateTest {
    private fun group(name: String, type: String, now: String, vararg all: String) =
        JSONObject().put("name", name).put("type", type).put("now", now).put("all", JSONArray(all.toList()))

    private fun node(name: String, type: String, delay: Int? = null) =
        JSONObject().put("name", name).put("type", type).put("delay", delay ?: JSONObject.NULL)

    @Test fun statusReadsTheActiveProfileName() {
        val json = JSONObject().put("running", true).put("configured", true).put("activeProfileId", "b")
            .put("profiles", JSONArray().put(JSONObject().put("id", "a").put("name", "One"))
                .put(JSONObject().put("id", "b").put("name", "Two")))
        val status = parseStatus(json)
        assertEquals("Two", status.profileName)
        assertTrue(status.hasProfiles)
        assertEquals("rule", status.mode)
        assertNull(status.error)
    }

    @Test fun emptyStatusMeansAvailableButNotRunning() {
        val status = parseStatus(JSONObject())
        assertTrue(status.available)
        assertFalse(status.running)
        assertFalse(status.hasProfiles)
        assertNull(status.profileName)
    }

    @Test fun globalComesFirstThenTheSubscriptionsOwnOrder() {
        val json = JSONObject()
            .put("groups", JSONArray().put(group("Zeta", "Selector", "a", "a")).put(group("GLOBAL", "Selector", "Alpha", "Beta", "Alpha", "Zeta"))
                .put(group("Alpha", "URLTest", "n1", "n1")).put(group("Beta", "Selector", "n1", "n1")))
            .put("nodes", JSONArray().put(node("n1", "Shadowsocks", 120)).put(node("DIRECT", "Direct")).put(node("REJECT", "Reject")))
        val groups = parseGroups(json)
        assertEquals(listOf("GLOBAL", "Beta", "Alpha", "Zeta"), groups.groups.map { it.name })
        assertEquals(mapOf("n1" to 120), groups.delays)
        assertTrue(groups.groups.first { it.name == "Beta" }.selectable)
        assertFalse(groups.groups.first { it.name == "Alpha" }.selectable)
    }

    @Test fun modeDecidesWhichGroupsMatter() {
        val groups = VpnGroups(listOf(VpnGroup("GLOBAL", "Selector", "x", listOf("x")), VpnGroup("Main", "Selector", "x", listOf("x"))),
            emptyMap(), emptyMap())
        assertEquals(listOf("Main"), groups.shown("rule").map { it.name })
        assertEquals(listOf("GLOBAL", "Main"), groups.shown("global").map { it.name })
        assertEquals(emptyList<String>(), groups.shown("direct").map { it.name })
    }

    @Test fun builtInOutboundsAreNotLatencyTested() {
        val groups = VpnGroups(emptyList(), emptyMap(), mapOf("a" to "Shadowsocks", "REJECT" to "Reject", "PASS" to "Pass", "c" to "Compatible"))
        assertTrue(groups.testable("a"))
        assertFalse(groups.testable("REJECT"))
        assertFalse(groups.testable("PASS"))
        assertFalse(groups.testable("c"))
    }

    @Test fun onlyFixedErrorCodesPassThrough() {
        assertEquals("core_start_failed", errorCode(IllegalStateException("core_start_failed")))
        assertEquals("proxy_request_failed", errorCode(IllegalStateException("https://u:p@host/?token=1")))
        assertEquals("proxy_request_failed", errorCode(RuntimeException()))
        assertEquals("proxy_not_initialized", errorCode(IllegalStateException("Boom"), "proxy_not_initialized"))
    }

    @Test fun coreDownloadStatusIsParsed() {
        val json = JSONObject().put("available", false).put("coreInstalled", false).put("coreDownloadSupported", true)
            .put("coreDownload", JSONObject().put("state", "downloading").put("received", 5_000_000L)
                .put("total", 20_000_000L).put("speed", 850_000L).put("error", JSONObject.NULL))
        val status = parseStatus(json)
        assertTrue(status.needsCore)
        assertTrue(status.coreBusy)
        assertEquals(25, status.corePercent)
        assertEquals(850_000L, status.coreSpeed)
        assertNull(status.coreError)
        val failed = parseStatus(JSONObject().put("available", false).put("coreDownloadSupported", true)
            .put("coreDownload", JSONObject().put("state", "failed").put("error", "core_integrity_failed")))
        assertFalse(failed.coreBusy)
        assertEquals("core_integrity_failed", failed.coreError)
    }

    @Test fun anOlderStatusWithoutCoreFieldsMeansTheCoreIsThereIfAvailable() {
        assertTrue(parseStatus(JSONObject().put("available", true)).coreInstalled)
        assertFalse(parseStatus(JSONObject().put("available", false)).coreInstalled)
        // Not offered where the TV cannot run it.
        assertFalse(parseStatus(JSONObject().put("available", false)).needsCore)
        assertEquals(0, parseStatus(JSONObject()).corePercent)
    }

    @Test fun speedIsShownInKilobytesOrMegabytesPerSecond() {
        assertEquals("0 KB/s", formatSpeed(0))
        assertEquals("85 KB/s", formatSpeed(85_000))
        assertEquals("1.0 MB/s", formatSpeed(1_000_000))
        assertEquals("1.2 MB/s", formatSpeed(1_250_000))
    }

    @Test fun proxyProbeIsTunneledButNotAnAndroidVpn() {
        val plain = NetworkStatus(NetKind.Wifi, "Home")
        assertFalse(plain.tunneled)
        val proxied = plain.withProxy(true)
        assertTrue(proxied.proxyConnected)
        assertFalse(proxied.vpn)
        assertTrue(proxied.tunneled)
        assertTrue(plain.copy(vpn = true).tunneled)
        // No change, no new object: the status state is not rewritten for nothing.
        assertSame(proxied, proxied.withProxy(true))
        assertFalse(proxied.withProxy(false).tunneled)
    }
}
