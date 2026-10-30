package app.orchard.tv.agent

import app.orchard.tv.Overlay
import org.json.JSONArray
import org.json.JSONObject

/**
 * A setting an agent (or a script) can read and change by its key, without pressing keys on the
 * remote. [set] takes the value as text and answers "ok" or "error: ..."; it changes nothing when it
 * answers an error.
 */
class AgentSetting(
    val key: String,
    /** "bool", "int" or "choice". */
    val kind: String,
    /** The values a choice takes; empty for the others. */
    val options: List<String>,
    /** An int's range as "4-6"; null for the others. */
    val range: String?,
    val get: () -> String,
    val set: (String) -> String,
) {
    companion object {
        fun bool(key: String, get: () -> Boolean, set: (Boolean) -> Unit) = AgentSetting(key, "bool", emptyList(), null, { get().toString() }) { v ->
            when (v.lowercase()) {
                "true", "on", "1" -> { set(true); "ok" }
                "false", "off", "0" -> { set(false); "ok" }
                else -> "error: $key takes true or false, not \"$v\""
            }
        }

        fun int(key: String, range: IntRange, get: () -> Int, set: (Int) -> Unit) = AgentSetting(key, "int", emptyList(), "${range.first}-${range.last}", { get().toString() }) { v ->
            val n = v.toIntOrNull()
            if (n == null || n !in range) "error: $key takes ${range.first} to ${range.last}, not \"$v\"" else { set(n); "ok" }
        }

        fun choice(key: String, options: List<String>, get: () -> String, set: (String) -> Unit) = AgentSetting(key, "choice", options, null, get) { v ->
            if (v in options) { set(v); "ok" } else "error: $key takes ${options.joinToString(", ")}, not \"$v\""
        }
    }
}

/** What an agent asked for, from the extras of the broadcast ([AgentApi.parse]). */
sealed interface AgentCommand {
    data object State : AgentCommand
    data object Settings : AgentCommand
    data object Help : AgentCommand
    data class Set(val key: String, val value: String) : AgentCommand
    data class Open(val target: String) : AgentCommand
    data class Focus(val target: String) : AgentCommand
    data class Invalid(val message: String) : AgentCommand
}

/** The pure part of the agent interface: reading a command, running a setting, writing the answers as JSON. */
object AgentApi {
    /** The command in [extras] (`agent` and what it needs), or null when there is none. */
    fun parse(extras: Map<String, String?>): AgentCommand? {
        val name = extras["agent"] ?: return null
        fun need(vararg keys: String): List<String>? = keys.map { extras[it]?.takeIf { v -> v.isNotEmpty() } ?: return null }
        return when (name) {
            "state" -> AgentCommand.State
            "settings" -> AgentCommand.Settings
            "help" -> AgentCommand.Help
            "set" -> need("key", "value")?.let { (k, v) -> AgentCommand.Set(k, v) } ?: AgentCommand.Invalid("set needs key and value")
            "open" -> need("target")?.let { AgentCommand.Open(it[0]) } ?: AgentCommand.Invalid("open needs target")
            "focus" -> need("target")?.let { AgentCommand.Focus(it[0]) } ?: AgentCommand.Invalid("focus needs target")
            else -> AgentCommand.Invalid("no command \"$name\"; try help")
        }
    }

    fun set(settings: List<AgentSetting>, key: String, value: String): String =
        settings.firstOrNull { it.key == key }?.set?.invoke(value) ?: "error: no setting \"$key\"; try settings"

    /** Every setting with its kind, options or range, and value: what an agent reads before it sets one. */
    fun describe(settings: List<AgentSetting>): JSONArray = JSONArray(settings.map { s ->
        JSONObject().put("key", s.key).put("kind", s.kind).put("value", s.get()).apply {
            if (s.options.isNotEmpty()) put("options", JSONArray(s.options))
            s.range?.let { put("range", it) }
        }
    })

    /** The word an agent uses for an [Overlay]; matched by type, since R8 renames the classes in a release build. */
    fun overlayName(overlay: Overlay): String = when (overlay) {
        Overlay.None -> "none"
        is Overlay.ControlCenter -> "control_center"
        Overlay.Wallpaper -> "personalize"
        Overlay.HiddenApps -> "hidden_apps"
        is Overlay.AppMenu -> "app_menu"
        is Overlay.Folder -> "folder"
        is Overlay.Pin -> "pin"
    }

    /** What is on screen as one JSON object: the overlay, the focused tile, the tiles in order, the settings' values. */
    fun stateJson(overlay: String, focus: String?, tiles: List<Pair<String, String>>, settings: List<AgentSetting>): String =
        JSONObject()
            .put("overlay", overlay)
            .put("focus", focus ?: JSONObject.NULL)
            .put("tiles", JSONArray(tiles.map { (key, label) -> JSONObject().put("key", key).put("label", label) }))
            .put("settings", JSONObject().apply { settings.forEach { put(it.key, it.get()) } })
            .toString()

    const val HELP = "agent=state|settings|set|open|focus|help; set needs key,value; open needs target " +
        "(none|control_center|personalize|hidden_apps); focus needs target (a tile's key, see state)"
}
