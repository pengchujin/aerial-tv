package app.orchard.tv.settings

import android.net.Uri
import org.json.JSONObject
import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import java.security.MessageDigest
import java.util.Locale

/** A user-configured public entry point, with no arbitrary Intent flags or components inferred from a URI. */
internal data class ManualQuickTarget private constructor(
    val type: Type,
    val pkg: String,
    val component: String,
    val action: String,
    val uri: String,
    val extras: Map<String, Any>,
) : QuickTarget {
    enum class Type(val code: String) { Activity("activity"), Broadcast("broadcast") }

    override val icon = "control"
    override val key by lazy {
        val bytes = MessageDigest.getInstance("SHA-256").digest(json().toString().toByteArray(Charsets.UTF_8))
        "manual:" + bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    fun extrasJson() = extras.entries.joinToString(",", "{", "}") { (key, value) ->
        JSONObject.quote(key) + ":" + (if (value is String) JSONObject.quote(value) else value.toString())
    }

    fun json() = JSONObject().put("type", type.code).put("pkg", pkg).put("component", component)
        .put("action", action).put("uri", uri).put("extras", extrasJson())

    companion object {
        const val MAX_EXTRAS = 8192
        private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
        private val classPattern = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
        private val actionPattern = Regex("[A-Za-z_][A-Za-z0-9_.$]*")
        private val schemePattern = Regex("[a-z][a-z0-9+.-]*")
        private val blockedSchemes = setOf("file", "content", "intent", "javascript", "android-app")

        fun from(json: JSONObject): ManualQuickTarget? = create(json.optString("type"), json.optString("pkg"),
            json.optString("component"), json.optString("action"), json.optString("uri"), json.optString("extras"))

        fun create(typeCode: String, packageName: String, className: String, actionName: String,
                   dataUri: String, extraJson: String): ManualQuickTarget? {
            val type = Type.entries.firstOrNull { it.code == typeCode } ?: return null
            val pkg = packageName.trim()
            if (pkg.length > 200 || !packagePattern.matches(pkg) || pkg == "app.orchard.tv" || pkg.startsWith("app.orchard.tv.")) return null
            val enteredClass = className.trim()
            val component = if (enteredClass.startsWith('.')) pkg + enteredClass else enteredClass
            if (component.length > 250 || !classPattern.matches(component)) return null
            val action = actionName.trim()
            if (action.length > 250 || (action.isNotEmpty() && !actionPattern.matches(action))) return null
            if (type == Type.Broadcast && action.isEmpty()) return null
            val uri = dataUri.trim()
            if (uri.length > 2048 || uri.any { it.isWhitespace() || it.isISOControl() }) return null
            if (uri.isNotEmpty()) {
                val scheme = Uri.parse(uri).scheme?.lowercase(Locale.ROOT) ?: return null
                if (!schemePattern.matches(scheme) || scheme in blockedSchemes) return null
            }
            if (extraJson.length > MAX_EXTRAS) return null
            val values = runCatching { parseExtras(extraJson) }.getOrNull() ?: return null
            return ManualQuickTarget(type, pkg, component, action, uri, values)
                .takeIf { it.extrasJson().length <= MAX_EXTRAS }
        }

        private fun parseExtras(text: String): Map<String, Any>? {
            val values = sortedMapOf<String, Any>()
            JsonReader(StringReader(text.ifBlank { "{}" })).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    if (values.size >= 20 || key in values || key.isBlank() || key.length > 100 ||
                        key.any { it.isISOControl() }) return null
                    values[key] = when (reader.peek()) {
                        JsonToken.STRING -> reader.nextString().takeIf { it.length <= 2048 } ?: return null
                        JsonToken.BOOLEAN -> reader.nextBoolean()
                        JsonToken.NUMBER -> {
                            val number = reader.nextString()
                            number.toIntOrNull() ?: number.toLongOrNull()
                                ?: number.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
                        }
                        else -> return null
                    }
                }
                reader.endObject()
                if (reader.peek() != JsonToken.END_DOCUMENT) return null
            }
            return values
        }
    }
}
