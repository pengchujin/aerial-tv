package app.orchard.tv.proxy

/**
 * The IPv4 routes Aerial's VPN takes: everything except the ranges that never leave the house or
 * are not unicast. LAN casting, DLNA, the phone page and the router stay on Wi-Fi/Ethernet because
 * Android sends a destination without a VPN route to the underlying network.
 */
internal object TunRoutes {
    /** RFC 1918, link-local, CGNAT, loopback, multicast and reserved space. */
    val EXCLUDED = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16",
        "100.64.0.0/10", "127.0.0.0/8", "224.0.0.0/4", "240.0.0.0/4",
    )

    data class Cidr(val address: String, val prefix: Int)

    /** The smallest set of CIDR blocks covering the whole IPv4 space minus [excluded]. */
    fun complement(excluded: List<String> = EXCLUDED): List<Cidr> {
        // Half-open [start, end) intervals as longs, merged.
        val ranges = excluded.map { parse(it) }.sortedBy { it.first }
        val merged = mutableListOf<LongArray>()
        for ((start, end) in ranges) {
            val last = merged.lastOrNull()
            if (last != null && start <= last[1]) last[1] = maxOf(last[1], end) else merged += longArrayOf(start, end)
        }
        val routes = mutableListOf<Cidr>()
        var cursor = 0L
        for ((start, end) in merged.map { it[0] to it[1] } + (FULL to FULL)) {
            if (cursor < start) routes += blocks(cursor, start)
            cursor = maxOf(cursor, end)
        }
        return routes
    }

    private const val FULL = 1L shl 32

    private fun parse(cidr: String): Pair<Long, Long> {
        val (address, bits) = cidr.split('/').let { it[0] to it[1].toInt() }
        require(bits in 0..32) { "invalid_route" }
        val parts = address.split('.').map { it.toInt() }
        require(parts.size == 4 && parts.all { it in 0..255 }) { "invalid_route" }
        val value = parts.fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
        val size = 1L shl (32 - bits)
        require(value % size == 0L) { "invalid_route" }
        return value to value + size
    }

    /** Splits [start, end) into aligned power-of-two blocks. */
    private fun blocks(start: Long, end: Long): List<Cidr> {
        val out = mutableListOf<Cidr>()
        var at = start
        while (at < end) {
            var size = if (at == 0L) FULL else java.lang.Long.lowestOneBit(at)
            while (at + size > end) size = size shr 1
            out += Cidr(format(at), 32 - java.lang.Long.numberOfTrailingZeros(size))
            at += size
        }
        return out
    }

    private fun format(value: Long): String =
        (3 downTo 0).joinToString(".") { ((value shr (it * 8)) and 0xff).toString() }
}
