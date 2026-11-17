package app.orchard.tv.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Built-in controls and validated manual entry points. */
internal sealed interface QuickTarget {
    val key: String
    val icon: String

    enum class BuiltIn(override val key: String, override val icon: String) : QuickTarget {
        Network("system:network", "network"), Bluetooth("system:bluetooth", "bluetooth"),
        Mute("system:mute", "mute"), Inputs("system:inputs", "inputs"), Settings("system:settings", "settings"),
    }

    enum class Vpn(override val key: String, val client: Client, val command: Command) : QuickTarget {
        Clash("clash", Client.Clash, Command.Toggle),
        V2rayConnect("vpn:v2rayng:connect", Client.V2ray, Command.Connect),
        V2rayDisconnect("vpn:v2rayng:disconnect", Client.V2ray, Command.Disconnect),
        ShadowsocksConnect("vpn:shadowsocks:connect", Client.Shadowsocks, Command.Connect),
        ShadowsocksDisconnect("vpn:shadowsocks:disconnect", Client.Shadowsocks, Command.Disconnect),
        TailscaleConnect("vpn:tailscale:connect", Client.Tailscale, Command.Connect),
        TailscaleDisconnect("vpn:tailscale:disconnect", Client.Tailscale, Command.Disconnect);

        override val icon = "vpn"

        enum class Client(val title: String) {
            Clash("Clash Meta"), V2ray("v2rayNG"), Shadowsocks("Shadowsocks"), Tailscale("Tailscale"),
        }

        enum class Command(val code: String) {
            Toggle("toggle"), Connect("connect"), Disconnect("disconnect"),
        }
    }

    companion object {
        fun builtIn(key: String): QuickTarget? =
            BuiltIn.entries.firstOrNull { it.key == key } ?: Vpn.entries.firstOrNull { it.key == key }
    }
}

internal data class QuickAction(
    val title: String,
    val target: QuickTarget,
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
) {
    fun displayJson() = JSONObject().put("id", id).put("title", title).put("enabled", enabled)
        .put("key", target.key).put("icon", target.icon).put("manual", target is ManualQuickTarget)
        .apply { if (target is QuickTarget.Vpn) put("vpnCommand", target.command.code) }

    fun json() = displayJson().apply { if (target is ManualQuickTarget) put("manual", target.json()) }

    fun valid(): Boolean = validTitle(title) && id.length in 1..80 && idPattern.matches(id)

    companion object {
        private val idPattern = Regex("[A-Za-z0-9_-]+")
        fun validTitle(title: String) = title.isNotBlank() && title.length <= 64

        fun from(json: JSONObject): QuickAction? {
            val target = json.optJSONObject("manual")?.let(ManualQuickTarget::from)
                ?: QuickTarget.builtIn(json.optString("key")) ?: return null
            return QuickAction(json.optString("title").trim(), target, json.optString("id"), json.optBoolean("enabled", true))
                .takeIf { it.valid() }
        }
    }
}

internal sealed interface QuickEdit {
    data class EnableArea(val enabled: Boolean) : QuickEdit
    data class Add(val item: QuickAction) : QuickEdit
    data class Rename(val id: String, val title: String) : QuickEdit
    data class Configure(val id: String, val title: String, val target: ManualQuickTarget) : QuickEdit
    data class Show(val id: String, val visible: Boolean) : QuickEdit
    data class Remove(val id: String) : QuickEdit
    data class Move(val id: String, val position: Int) : QuickEdit
}

/** Main-thread mutations shared by both editors, with a revision guard for concurrent edits. */
internal class QuickControlStore(context: Context) {
    private data class Snapshot(val enabled: Boolean, val items: List<QuickAction>, val revision: Long)
    private val prefs = context.getSharedPreferences("quick_controls", Context.MODE_PRIVATE)
    private var snapshot by mutableStateOf(load())
    val enabled get() = snapshot.enabled
    val items get() = snapshot.items
    val revision get() = snapshot.revision

    private fun load(): Snapshot {
        val array = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrElse { JSONArray() }
        val items = (0 until array.length()).asSequence().mapNotNull { array.optJSONObject(it)?.let(QuickAction::from) }
            .distinctBy { it.id }.distinctBy { it.target.key }.take(MAX_ITEMS).toList()
        return Snapshot(prefs.getBoolean("enabled", false), items, prefs.getLong("revision", 0L))
    }

    fun json(encode: (QuickAction) -> JSONObject): JSONObject = snapshot.let { current ->
        JSONObject().put("enabled", current.enabled).put("revision", current.revision)
            .put("items", JSONArray().apply { current.items.forEach { put(encode(it)) } })
    }

    fun enabledAction(id: String): QuickAction? = snapshot.let { current ->
        current.items.firstOrNull { current.enabled && it.enabled && it.id == id }
    }

    fun change(edit: QuickEdit, expected: Long = revision): String? {
        val current = snapshot
        if (expected != current.revision) return "conflict"
        val items = current.items
        val next = when (edit) {
            is QuickEdit.EnableArea -> current.copy(enabled = edit.enabled)
            is QuickEdit.Add -> {
                if (!edit.item.valid() || items.any { it.id == edit.item.id }) return "invalid"
                if (items.any { it.target.key == edit.item.target.key }) return "exists"
                if (items.size >= MAX_ITEMS) return "limit"
                current.copy(enabled = current.enabled || items.isEmpty(), items = items + edit.item)
            }
            is QuickEdit.Rename -> {
                if (items.none { it.id == edit.id }) return "missing"
                if (!QuickAction.validTitle(edit.title)) return "invalid"
                current.copy(items = items.map { if (it.id == edit.id) it.copy(title = edit.title) else it })
            }
            is QuickEdit.Configure -> {
                val item = items.firstOrNull { it.id == edit.id } ?: return "missing"
                if (item.target !is ManualQuickTarget || !QuickAction.validTitle(edit.title)) return "invalid"
                if (items.any { it.id != edit.id && it.target.key == edit.target.key }) return "exists"
                current.copy(items = items.map { if (it.id == edit.id) it.copy(title = edit.title, target = edit.target) else it })
            }
            is QuickEdit.Show -> {
                if (items.none { it.id == edit.id }) return "missing"
                current.copy(items = items.map { if (it.id == edit.id) it.copy(enabled = edit.visible) else it })
            }
            is QuickEdit.Remove -> {
                if (items.none { it.id == edit.id }) return "missing"
                current.copy(items = items.filterNot { it.id == edit.id })
            }
            is QuickEdit.Move -> {
                val index = items.indexOfFirst { it.id == edit.id }
                if (index < 0) return "missing"
                if (edit.position !in items.indices) return "invalid"
                current.copy(items = items.toMutableList().apply { add(edit.position, removeAt(index)) })
            }
        }
        if (next != current) {
            snapshot = next.copy(revision = current.revision + 1)
            persist(snapshot)
        }
        return null
    }

    private fun persist(value: Snapshot) = prefs.edit {
        putBoolean("enabled", value.enabled)
        putLong("revision", value.revision)
        putString("items", JSONArray().apply { value.items.forEach { put(it.json()) } }.toString())
    }

    companion object { const val MAX_ITEMS = 12 }
}

internal object QuickControls {
    lateinit var store: QuickControlStore; private set
    fun init(context: Context) { if (!::store.isInitialized) store = QuickControlStore(context.applicationContext) }
}
