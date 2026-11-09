package app.orchard.tv.wallpaper

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.net.URL

/** Where the daily wallpaper comes from when it is not Bing. */
data class DailySourceSettings(
    val host: String = "",
    val port: Int = DailySource.DEFAULT_PORT,
    val token: String = "",
)

/** A resolved LAN endpoint. */
data class DailyEndpoint(val host: String, val port: Int)

/**
 * The daily photo source. Bing is the default. A custom source is an HTTP server on the same LAN;
 * its host, port and optional token are set on the TV or the phone page. Discovery tries the
 * explicit host, the last working IP and finally the TV's own /24, and every candidate must answer
 * /health with the server's magic string (and the token, when one is set).
 *
 * NsdManager is deliberately not used: on the Xiaomi firmware Aerial targets it returned no
 * services even with a MulticastLock, while a plain /24 probe completes in about a second.
 */
object DailySource {
    const val DEFAULT_PORT = 8899
    const val BING_BASE = "https://cn.bing.com"
    const val BING_JSON = "$BING_BASE/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=zh-CN"

    private const val TAG = "AerialDailySource"
    private const val PREFS = "daily_source"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_TOKEN = "token"
    private const val KEY_LAST_IP_PREFIX = "last_ip_"
    private const val TOKEN_HEADER = "X-Aerial-Token"

    fun settings(context: Context): DailySourceSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = p.getString(KEY_HOST, null)?.let(::parseHost) ?: ""
        val port = p.getInt(KEY_PORT, DEFAULT_PORT).takeIf { it in 1..65535 } ?: DEFAULT_PORT
        val token = p.getString(KEY_TOKEN, "").orEmpty()
        return DailySourceSettings(host, port, token)
    }

    /** Saves settings that the caller has already validated and health-checked. */
    fun save(context: Context, host: String, port: Int, token: String): Boolean {
        val normalizedHost = parseHost(host) ?: return false
        if (port !in 1..65535) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_HOST, normalizedHost)
            putInt(KEY_PORT, port)
            putString(KEY_TOKEN, token.trim())
        }
        return true
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_HOST)
            remove(KEY_PORT)
            remove(KEY_TOKEN)
        }
    }

    /** "http://host:port" when a custom host is configured and valid, otherwise null (Bing). */
    fun customBase(context: Context): String? {
        val s = settings(context)
        if (s.host.isBlank()) return null
        return "http://${s.host}:${s.port}"
    }

    fun label(context: Context): String = customBase(context)?.removePrefix("http://") ?: "Bing"

    /** Accepts a host, host:port, or http://host[:port]; returns only the host. */
    fun parseHost(input: String): String? = endpoint(input, "")?.host

    /** Accepts an optional port; blank means the default. */
    fun parsePort(input: String): Int? {
        val text = input.trim()
        if (text.isEmpty()) return DEFAULT_PORT
        return text.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    /**
     * Parses the UI's two fields. The host may also carry a port or an http:// prefix; the explicit
     * port field wins when both are supplied. A blank host is allowed for discovery.
     */
    fun parseEndpoint(hostInput: String, portInput: String): DailyEndpoint? {
        val explicitPort = parsePort(portInput) ?: return null
        val text = hostInput.trim()
        if (text.isEmpty()) return DailyEndpoint("", explicitPort)
        val endpoint = endpoint(text, portInput) ?: return null
        return DailyEndpoint(endpoint.host, explicitPort)
    }

    private fun endpoint(input: String, portInput: String): DailyEndpoint? {
        val text = input.trim().trimEnd('/')
        if (text.isEmpty()) return null
        val withScheme = if (text.startsWith("http://", true)) text else "http://$text"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        if (!uri.scheme.equals("http", ignoreCase = true)) return null
        if (!uri.userInfo.isNullOrEmpty() || !uri.query.isNullOrEmpty() || !uri.fragment.isNullOrEmpty()) return null
        if (!uri.path.isNullOrEmpty()) return null
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        if (!isLanHost(host)) return null
        val embedded = uri.port.takeIf { it != -1 } ?: parsePort(portInput) ?: DEFAULT_PORT
        if (embedded !in 1..65535) return null
        return DailyEndpoint(host, embedded)
    }

    private fun isLanHost(host: String): Boolean {
        if (host.endsWith(".local") && host.length > ".local".length) return true
        val ip = parseIpv4(host) ?: return false
        return isPrivate(ip)
    }

    private fun isPrivate(ip: IntArray): Boolean =
        ip[0] == 10 ||
            (ip[0] == 172 && ip[1] in 16..31) ||
            (ip[0] == 192 && ip[1] == 168)

    private fun parseIpv4(text: String): IntArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            out[i] = n
        }
        return out
    }

    /** A .local name must resolve to a private IPv4; an IPv4 literal is checked syntactically. */
    private fun resolvesToLan(host: String): Boolean {
        if (!host.endsWith(".local")) return true
        return runCatching {
            InetAddress.getAllByName(host).any {
                it is Inet4Address && isPrivate(parseIpv4(it.hostAddress) ?: return@any false)
            }
        }.getOrDefault(false)
    }

    suspend fun health(host: String, port: Int, token: String, timeoutMs: Int = 1500): Boolean =
        withContext(Dispatchers.IO) {
            val normalized = parseHost(host) ?: return@withContext false
            if (port !in 1..65535 || !resolvesToLan(normalized)) return@withContext false
            runCatching {
                val c = URL("http://$normalized:$port/health").openConnection() as HttpURLConnection
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                if (token.isNotBlank()) c.setRequestProperty(TOKEN_HEADER, token.trim())
                val code = c.responseCode
                val body = if (code == 200) c.inputStream.use { it.readBytes().decodeToString() } else ""
                c.disconnect()
                code == 200 && body.contains("aerial-daily")
            }.getOrElse { false }
        }

    /** Validates and saves a manually entered endpoint. */
    suspend fun saveIfHealthy(context: Context, host: String, port: Int, token: String): Boolean {
        if (parseHost(host) == null || port !in 1..65535) return false
        if (!health(host, port, token)) return false
        return save(context, host, port, token)
    }

    /**
     * Finds a LAN daily server. An explicit host is tried first; otherwise the last working IP for
     * this port, then a scan of the TV's own /24. Returns the host and port that answered.
     */
    suspend fun probe(context: Context, hostInput: String, portInput: String, token: String): DailyEndpoint? =
        withContext(Dispatchers.IO) {
            val port = parsePort(portInput) ?: return@withContext null
            val explicit = hostInput.trim().takeIf { it.isNotEmpty() }?.let { parseHost(it) }
            if (hostInput.isNotBlank() && explicit == null) return@withContext null
            if (explicit != null && health(explicit, port, token)) return@withContext DailyEndpoint(explicit, port)

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val last = prefs.getString(KEY_LAST_IP_PREFIX + port, null)
            if (!last.isNullOrBlank() && health(last, port, token)) {
                return@withContext DailyEndpoint(last, port)
            }

            val local = localIpv4() ?: return@withContext null
            if (!isPrivate(parseIpv4(local) ?: return@withContext null)) return@withContext null
            val prefix = local.substringBeforeLast('.') + "."
            val hits = scan(prefix, port)
            val found = coroutineScope {
                hits.map { ip ->
                    async { if (health(ip, port, token, 1200)) ip else null }
                }.awaitAll().firstOrNull { it != null }
            } ?: return@withContext null
            prefs.edit { putString(KEY_LAST_IP_PREFIX + port, found) }
            DailyEndpoint(found, port)
        }

    private suspend fun scan(prefix: String, port: Int): List<String> = coroutineScope {
        val semaphore = Semaphore(32)
        (1..254).map { i ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val ip = prefix + i
                    runCatching {
                        java.net.Socket().use { s ->
                            s.connect(java.net.InetSocketAddress(ip, port), 300)
                            ip
                        }
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun localIpv4(): String? {
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val a = addresses.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) return a.hostAddress
                }
            }
        }.onFailure { Log.w(TAG, "local ip lookup failed", it) }
        return null
    }
}
