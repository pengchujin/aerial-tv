package app.orchard.tv.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunRoutesTest {
    private val routes = TunRoutes.complement()

    @Test fun publicAddressesAndTheFakeIpRangeGoThroughTheTunnel() {
        listOf("8.8.8.8", "1.1.1.1", "31.13.92.37", "142.250.0.1", "0.0.0.1", "223.255.255.255",
            "198.18.0.0", "198.18.0.1", "198.18.0.2", "198.19.255.255", "172.15.255.255", "172.32.0.0",
            "100.63.255.255", "100.128.0.0", "11.0.0.0", "9.255.255.255", "192.167.255.255", "192.169.0.0")
            .forEach { assertTrue(it, covered(it)) }
        // The whole fake-IP range and TUN /30 are routed, not just pieces of it.
        assertTrue(routes.any { cidr -> contains(cidr, "198.18.0.0") && cidr.prefix <= 15 })
    }

    @Test fun lanLoopbackLinkLocalCgnatMulticastAndReservedStayOffTheTunnel() {
        listOf("192.168.1.1", "192.168.0.0", "192.168.255.255", "10.0.0.1", "10.255.255.255",
            "172.16.0.1", "172.31.255.255", "127.0.0.1", "169.254.1.1", "100.64.0.1", "100.127.255.255",
            "224.0.0.251", "239.255.255.250", "240.0.0.1", "255.255.255.255")
            .forEach { assertFalse(it, covered(it)) }
    }

    @Test fun routesCoverExactlyTheSpaceOutsideTheExcludedRanges() {
        val excluded = TunRoutes.EXCLUDED.sumOf { 1L shl (32 - it.substringAfter('/').toInt()) }
        assertEquals((1L shl 32) - excluded, routes.sumOf { 1L shl (32 - it.prefix) })
    }

    @Test fun routesAreAlignedSortedAndDisjoint() {
        var end = 0L
        routes.forEach { cidr ->
            val start = value(cidr.address)
            val size = 1L shl (32 - cidr.prefix)
            assertEquals(cidr.toString(), 0L, start % size)
            assertTrue(cidr.toString(), start >= end)
            end = start + size
        }
        // The minimal set for these exclusions is 49 blocks, one addRoute each.
        assertEquals(49, routes.size)
    }

    @Test fun edgeCases() {
        assertEquals(listOf(TunRoutes.Cidr("0.0.0.0", 0)), TunRoutes.complement(emptyList()))
        assertEquals(emptyList<TunRoutes.Cidr>(), TunRoutes.complement(listOf("0.0.0.0/0")))
        assertEquals(listOf(TunRoutes.Cidr("128.0.0.0", 1)), TunRoutes.complement(listOf("0.0.0.0/1")))
        // Overlapping exclusions merge.
        assertEquals(TunRoutes.complement(listOf("10.0.0.0/8")), TunRoutes.complement(listOf("10.0.0.0/8", "10.1.0.0/16")))
    }

    private fun covered(ip: String) = routes.any { contains(it, ip) }

    private fun contains(cidr: TunRoutes.Cidr, ip: String): Boolean {
        val size = 1L shl (32 - cidr.prefix)
        val start = value(cidr.address)
        return value(ip) in start until start + size
    }

    private fun value(ip: String) = ip.split('.').fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
}
