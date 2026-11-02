package app.orchard.tv.proxy

import android.content.SharedPreferences
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Credentials and downloaded YAML live only in app-private storage, never in public status. */
internal data class ProxyProfile(
    val id: String,
    val name: String,
    val kind: String,
    val url: String = "",
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    val tls: Boolean = false,
    val refreshHours: Int = 24,
    val updatedAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    val lastError: String? = null,
) {
    fun status(activeId: String?) = JSONObject().put("id", id).put("name", name).put("kind", kind)
        .put("active", id == activeId).put("updatedAt", updatedAt).put("refreshHours", refreshHours)
        .put("lastError", lastError ?: JSONObject.NULL)

    fun privateJson(): JSONObject = status(null).put("url", url).put("host", host).put("port", port)
        .put("username", username).put("password", password).put("tls", tls).put("lastAttemptAt", lastAttemptAt)

    companion object {
        fun read(json: JSONObject): ProxyProfile {
            val id = json.getString("id")
            require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "profile_storage_invalid" }
            val kind = json.getString("kind")
            require(kind in setOf("subscription", "http", "socks5")) { "profile_storage_invalid" }
            return ProxyProfile(id, json.getString("name"), kind, json.optString("url"), json.optString("host"),
                json.optInt("port"), json.optString("username"), json.optString("password"), json.optBoolean("tls"),
                json.optInt("refreshHours", 24).coerceIn(0, 168), json.optLong("updatedAt"), json.optLong("lastAttemptAt"),
                // Android's optString turns a JSON null into the text "null", which matches the code pattern.
                json.optString("lastError").takeIf { !json.isNull("lastError") && it.matches(Regex("[a-z][a-z0-9_]{1,80}")) })
        }
    }
}

/** Accessed under ProxyManager's lock. The index is committed only after its YAML exists. */
internal class ProxyProfiles(private val directory: File, private val prefs: SharedPreferences) {
    private val index = File(directory, "profiles.json")
    private val folder = File(directory, "profiles").apply { mkdirs() }
    var profiles: List<ProxyProfile> = emptyList(); private set
    var activeId: String? = null; private set

    init {
        if (index.isFile || File(directory, "profiles.json.bak").isFile) {
            val text = AtomicFile(index).openRead().use { input ->
                require(input.available() <= 2 * 1024 * 1024) { "profile_storage_invalid" }
                input.bufferedReader().readText()
            }
            val root = JSONObject(text)
            val entries = root.getJSONArray("profiles")
            require(entries.length() <= 64) { "profile_limit_reached" }
            profiles = (0 until entries.length()).map { ProxyProfile.read(entries.getJSONObject(it)) }
            require(profiles.map { it.id }.distinct().size == profiles.size) { "profile_storage_invalid" }
            activeId = root.optString("activeProfileId").takeIf { id -> !root.isNull("activeProfileId") && profiles.any { it.id == id } }
        } else {
            val legacy = File(directory, "subscription.yaml")
            val url = prefs.getString("subscriptionUrl", null)
            if (legacy.isFile && !url.isNullOrBlank()) {
                val profile = ProxyProfile("legacy-subscription", "Subscription 1", "subscription", url = url,
                    updatedAt = prefs.getLong("subscriptionUpdatedAt", 0L))
                // Preserve the old file and URL as a fallback until the new index is durable.
                atomicWrite(file(profile.id), legacy.readText())
                commit(listOf(profile), profile.id)
            }
        }
    }

    fun find(id: String) = profiles.firstOrNull { it.id == id } ?: error("unknown_profile")
    fun active() = profiles.firstOrNull { it.id == activeId }
    fun file(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "invalid_profile_id" }
        return File(folder, "$id.yaml")
    }
    fun newId(): String = UUID.randomUUID().toString()

    fun commit(next: List<ProxyProfile>, active: String?) {
        require(next.size <= 64) { "profile_limit_reached" }
        val root = JSONObject().put("version", 1).put("activeProfileId", active ?: JSONObject.NULL)
            .put("profiles", JSONArray().also { array -> next.forEach { array.put(it.privateJson()) } })
        atomicWrite(index, root.toString())
        profiles = next
        activeId = active
    }

    companion object {
        fun atomicWrite(file: File, text: String) {
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
            catch (error: Exception) { atomic.failWrite(stream); throw error }
        }
    }
}
