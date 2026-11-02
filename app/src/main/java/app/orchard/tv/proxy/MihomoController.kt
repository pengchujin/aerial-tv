package app.orchard.tv.proxy

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder

/** The secret-bearing controller is accessible only within this app, on loopback. */
internal class MihomoController(private val port: Int, private val secret: String) {
    fun request(method: String, path: String, payload: JSONObject? = null, timeout: Int = 2500): JSONObject {
        val connection = URL("http://127.0.0.1:$port$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        return try {
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $secret")
            connection.setRequestProperty("Content-Type", "application/json")
            if (payload != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            require(status in 200..299) { "core_request_failed" }
            val text = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= 2 * 1024 * 1024) { "core_response_too_large" }
                    out.write(buffer, 0, count)
                }
                out.toString("UTF-8")
            }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { connection.disconnect() }
    }

    fun groups(): JSONObject {
        val entries = request("GET", "/proxies").optJSONObject("proxies") ?: JSONObject()
        val groups = org.json.JSONArray()
        val nodes = org.json.JSONArray()
        entries.keys().forEach { name ->
            val proxy = entries.optJSONObject(name) ?: return@forEach
            val history = proxy.optJSONArray("history")
            val delay = history?.takeIf { it.length() > 0 }?.optJSONObject(history.length() - 1)?.optInt("delay", 0) ?: 0
            nodes.put(JSONObject().put("name", name).put("type", proxy.optString("type"))
                .put("delay", if (delay > 0) delay else JSONObject.NULL))
            val choices = proxy.optJSONArray("all") ?: return@forEach
            groups.put(JSONObject().put("name", name).put("type", proxy.optString("type"))
                .put("now", proxy.optString("now")).put("all", choices))
        }
        return JSONObject().put("groups", groups).put("nodes", nodes)
    }

    fun delay(node: String): JSONObject {
        val encoded = URLEncoder.encode(node, "UTF-8").replace("+", "%20")
        val entries = request("GET", "/proxies").optJSONObject("proxies") ?: JSONObject()
        require(entries.has(node)) { "unknown_node" }
        return try {
            val url = URLEncoder.encode(ProxyHealth.URL_204, "UTF-8")
            val result = request("GET", "/proxies/$encoded/delay?timeout=5000&url=$url&expected=204", timeout = 6500)
            val delay = result.optInt("delay", 0)
            require(delay > 0) { "node_delay_failed" }
            JSONObject().put("name", node).put("delay", delay).put("error", JSONObject.NULL)
        } catch (_: Exception) {
            JSONObject().put("name", node).put("delay", JSONObject.NULL).put("error", "node_delay_failed")
        }
    }

    /**
     * The core re-reads one of Aerial's file rule-providers (PUT /providers/rules/{name}); a file
     * whose hash did not change is not parsed again. Large lists take seconds on an ARMv7 TV.
     */
    fun updateRuleProvider(name: String) {
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        request("PUT", "/providers/rules/$encoded", timeout = 45000)
    }

    /**
     * The newest [limit] open connections (GET /connections: the core keeps only open ones), for
     * the phone page's diagnostic: host or address, matched rule and payload, and the chain from
     * the group the rule chose to the node actually used. No source address, process or traffic
     * identifiers beyond byte counts. [memory]: the core's RSS as it last measured it.
     */
    fun connections(limit: Int = 50): JSONObject {
        val snapshot = request("GET", "/connections", timeout = 5000)
        val all = snapshot.optJSONArray("connections") ?: org.json.JSONArray()
        val items = (0 until all.length()).mapNotNull { all.optJSONObject(it) }
            .sortedByDescending { startMillis(it.optString("start")) }.take(limit)
        val list = org.json.JSONArray()
        items.forEach { c ->
            val meta = c.optJSONObject("metadata") ?: JSONObject()
            val host = meta.optString("host").ifEmpty { meta.optString("sniffHost") }
            val chains = c.optJSONArray("chains")
            // The core lists the node first and the rule's group last.
            val chain = org.json.JSONArray().apply { if (chains != null) for (i in chains.length() - 1 downTo 0) put(chains.optString(i)) }
            list.put(JSONObject().put("host", host.take(253)).put("ip", meta.optString("destinationIP").take(45))
                .put("port", meta.optString("destinationPort").take(5)).put("network", meta.optString("network").take(8))
                .put("rule", c.optString("rule").take(40)).put("payload", c.optString("rulePayload").take(256))
                .put("chain", chain).put("start", startMillis(c.optString("start")))
                .put("upload", c.optLong("upload")).put("download", c.optLong("download")))
        }
        return JSONObject().put("connections", list).put("total", all.length())
            .put("memory", snapshot.optLong("memory", 0L))
    }

    /** RFC 3339 from the core (Go trims fractional zeros, so the text does not sort); 0 if unreadable. */
    private fun startMillis(text: String): Long = runCatching {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:\\d{2})$").find(text) ?: return 0L
        val g = m.groupValues
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        val millis = g[7].padEnd(3, '0').take(3).toInt()
        val offset = if (g[8] == "Z") 0 else (g[8].substring(1, 3).toInt() * 60 + g[8].substring(4, 6).toInt()) * (if (g[8][0] == '-') -1 else 1)
        cal.timeInMillis + millis - offset * 60_000L
    }.getOrDefault(0L)

    fun select(group: String, node: String) {
        val encoded = URLEncoder.encode(group, "UTF-8").replace("+", "%20")
        val proxy = request("GET", "/proxies/$encoded")
        require(proxy.optString("type").equals("Selector", ignoreCase = true)) { "group_not_selectable" }
        val choices = proxy.optJSONArray("all") ?: error("group_not_selectable")
        require((0 until choices.length()).any { choices.optString(it) == node }) { "unknown_node" }
        request("PUT", "/proxies/$encoded", JSONObject().put("name", node))
    }
}
