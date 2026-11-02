package app.orchard.tv.proxy

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** A real TLS/HTTP 204 request through the local proxy; no permissive TLS or redirect fallback. */
internal object ProxyHealth {
    const val URL_204 = "https://cp.cloudflare.com/generate_204"
    fun check(port: Int): Boolean {
        if (port !in 1024..65535) return false
        val connection = URL(URL_204).openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))) as HttpURLConnection
        return try {
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.responseCode == 204
        } catch (_: Exception) { false } finally { connection.disconnect() }
    }
}
