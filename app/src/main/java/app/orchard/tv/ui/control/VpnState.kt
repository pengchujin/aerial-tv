package app.orchard.tv.ui.control

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.orchard.tv.proxy.ProxyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What Control Center shows of the embedded proxy: ProxyManager.status(), parsed off the main thread. */
internal data class VpnStatus(
    val available: Boolean,
    val running: Boolean,
    val starting: Boolean,
    val stopping: Boolean,
    val configured: Boolean,
    val hasProfiles: Boolean,
    val profileName: String?,
    val mode: String,
    val tun: Boolean,
    val tunActive: Boolean,
    val vpnPermissionRequired: Boolean,
    val tunError: String?,
    val error: String?,
    /** The core is there (bundled, or downloaded and verified). Older status JSON: same as [available]. */
    val coreInstalled: Boolean = available,
    /** This TV can run the pinned core, so the download is offered. */
    val coreDownloadSupported: Boolean = false,
    /** idle, downloading, verifying or failed (proxy/CoreInstaller). */
    val coreState: String = "idle",
    val coreReceived: Long = 0,
    val coreTotal: Long = 0,
    val coreSpeed: Long = 0,
    val coreError: String? = null,
) {
    val coreBusy get() = coreState == "downloading" || coreState == "verifying"
    /** Nothing to run yet, and the download is the way forward. */
    val needsCore get() = !coreInstalled && coreDownloadSupported
    /** 0..100 while the size is known. */
    val corePercent get() = if (coreTotal > 0) (coreReceived * 100 / coreTotal).toInt().coerceIn(0, 100) else 0
}

/** A proxy group of the running core: [now] is its current member, [all] its members in order. */
internal data class VpnGroup(val name: String, val type: String, val now: String, val all: List<String>) {
    val selectable get() = type.equals("Selector", ignoreCase = true)
}

/** A data class: a poll that finds nothing changed leaves the rows as they are. */
internal data class VpnGroups(val groups: List<VpnGroup>, val delays: Map<String, Int>, val types: Map<String, String>)

/** One node's latency test: running, a result in ms, or failed (timeout, unreachable). */
internal sealed interface DelayResult {
    data object Testing : DelayResult
    data class Ms(val value: Int) : DelayResult
    data object Failed : DelayResult
}

private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

internal fun parseStatus(json: JSONObject): VpnStatus {
    val profiles = json.optJSONArray("profiles")
    val activeId = json.text("activeProfileId")
    var name: String? = null
    if (profiles != null) for (i in 0 until profiles.length()) {
        val p = profiles.optJSONObject(i) ?: continue
        if (p.optString("id") == activeId) name = p.text("name")
    }
    return VpnStatus(
        available = json.optBoolean("available", true),
        running = json.optBoolean("running"),
        starting = json.optBoolean("starting"),
        stopping = json.optBoolean("stopping"),
        configured = json.optBoolean("configured"),
        hasProfiles = (profiles?.length() ?: 0) > 0,
        profileName = name,
        mode = json.optString("mode", "rule").ifEmpty { "rule" },
        tun = json.optBoolean("tun"),
        tunActive = json.optBoolean("tunActive"),
        vpnPermissionRequired = json.optBoolean("vpnPermissionRequired"),
        tunError = json.text("tunError"),
        error = json.text("error"),
        coreInstalled = json.optBoolean("coreInstalled", json.optBoolean("available", true)),
        coreDownloadSupported = json.optBoolean("coreDownloadSupported"),
        coreState = json.optJSONObject("coreDownload")?.optString("state", "idle")?.ifEmpty { "idle" } ?: "idle",
        coreReceived = json.optJSONObject("coreDownload")?.optLong("received") ?: 0,
        coreTotal = json.optJSONObject("coreDownload")?.optLong("total") ?: 0,
        coreSpeed = json.optJSONObject("coreDownload")?.optLong("speed") ?: 0,
        coreError = json.optJSONObject("coreDownload")?.text("error"),
    )
}

internal fun parseGroups(json: JSONObject): VpnGroups {
    val delays = HashMap<String, Int>()
    val types = HashMap<String, String>()
    json.optJSONArray("nodes")?.let { nodes ->
        for (i in 0 until nodes.length()) {
            val n = nodes.optJSONObject(i) ?: continue
            val name = n.optString("name")
            if (name.isEmpty()) continue
            types[name] = n.optString("type")
            val d = n.optInt("delay", 0)
            if (d > 0) delays[name] = d
        }
    }
    val groups = ArrayList<VpnGroup>()
    json.optJSONArray("groups")?.let { list ->
        for (i in 0 until list.length()) {
            val g = list.optJSONObject(i) ?: continue
            val name = g.optString("name")
            if (name.isEmpty()) continue
            val all = g.optJSONArray("all")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } } }.orEmpty()
            groups += VpnGroup(name, g.optString("type"), g.optString("now"), all)
        }
    }
    // The controller lists proxies by name; GLOBAL's members keep the subscription's own order.
    val order = groups.firstOrNull { it.name == GLOBAL }?.all.orEmpty().withIndex().associate { (i, n) -> n to i }
    val sorted = groups.sortedWith(compareBy<VpnGroup>({ it.name != GLOBAL }, { order[it.name] ?: Int.MAX_VALUE }, { it.name }))
    return VpnGroups(sorted, delays, types)
}

internal const val GLOBAL = "GLOBAL"

/** Groups that matter in [mode]: GLOBAL carries all traffic only in global mode; direct uses none. */
internal fun VpnGroups.shown(mode: String): List<VpnGroup> = when (mode) {
    "global" -> groups
    "direct" -> emptyList()
    else -> groups.filter { it.name != GLOBAL }
}

/** Built-in outbounds whose latency means nothing (they never reach a server). */
internal fun VpnGroups.testable(node: String): Boolean =
    types[node]?.lowercase() !in setOf("reject", "rejectdrop", "pass", "compatible")

/** Result of a press on the VPN button. */
internal const val NEEDS_SETUP = "needs_setup"

/** Result of a press on the VPN button when the core is not on the TV but can be downloaded. */
internal const val NEEDS_CORE = "needs_core"

/** Bytes per second as "850 KB/s" or "1.2 MB/s"; the units are not translated. */
internal fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond < 1000 * 1000 -> "${(bytesPerSecond + 500) / 1000} KB/s"
    else -> "%d.%d MB/s".format(bytesPerSecond / 1_000_000, bytesPerSecond % 1_000_000 / 100_000)
}

/** Error codes from ProxyManager are fixed strings; anything else is shown as a generic failure. */
internal fun errorCode(e: Throwable, fallback: String = "proxy_request_failed"): String =
    e.message?.takeIf { it.matches(Regex("^[a-z][a-z0-9_]{1,80}$")) } ?: fallback

/**
 * Control Center's view of the proxy, kept while Home lives so a reopened panel shows the last
 * state at once. Every ProxyManager call runs on Dispatchers.IO: status(), start(), select() and
 * configure() take its lock, which a starting core holds for up to half a minute.
 */
internal class VpnState(private val context: Context, private val scope: CoroutineScope) {
    var status by mutableStateOf<VpnStatus?>(null); private set
    var groups by mutableStateOf<VpnGroups?>(null); private set
    /** ProxyManager could not be read at all (proxy_not_initialized: its profiles were unreadable). */
    var unavailable by mutableStateOf<String?>(null); private set
    var pending by mutableStateOf<Pending?>(null); private set
    var modePending by mutableStateOf<String?>(null); private set
    var selecting by mutableStateOf(false); private set
    /** The last failed action, a fixed code, shown on the VPN page; the next action clears it. */
    var actionError by mutableStateOf<String?>(null)
    /** A start the service failed after start() had returned; whoever shows it clears it. */
    var failed by mutableStateOf<String?>(null)
    /** The group page open on the VPN page; Back closes it before leaving the page. */
    var openGroup by mutableStateOf<String?>(null)
    /** The group page last left, so the overview puts focus back on its row. */
    var lastGroup: String? = null
    /** Coming back from the VPN page: the main page focuses the VPN button, not its first one. */
    var focusButton = false
    val delays = mutableStateMapOf<String, DelayResult>()
    /** The group whose latency test is running. */
    var testing by mutableStateOf<String?>(null); private set

    enum class Pending { Start, Stop }

    /** Bumped by every action, so a poll that began before it cannot overwrite its result. */
    private var serial = 0L
    private var testJob: Job? = null
    private var testRound = 0L
    /** Two latency tests at a time, across rounds: a cancelled round's request may still be running. */
    private val permits = Semaphore(2)

    val on: Boolean get() = when (pending) {
        Pending.Start -> true
        Pending.Stop -> false
        null -> status?.let { (it.running || it.starting) && !it.stopping } == true
    }
    val busy: Boolean get() = pending != null || status?.let { it.starting || it.stopping } == true

    /** The core download is running (downloading or checking the file). */
    val coreBusy: Boolean get() = status?.coreBusy == true

    /** The VPN page's "download the core" row: starts, or cancels a running download. */
    fun toggleCore() {
        val s = status ?: return
        val cancel = s.coreBusy
        serial++
        actionError = null
        scope.launch {
            try {
                status = withContext(Dispatchers.IO) { parseStatus(if (cancel) ProxyManager.cancelCoreDownload() else ProxyManager.downloadCore()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionError = errorCode(e)
            }
        }
    }

    /** One poll: the status and, with [withGroups], the running core's groups. */
    suspend fun refresh(withGroups: Boolean) {
        val before = serial
        val result = withContext(Dispatchers.IO) {
            runCatching {
                ProxyManager.init(context)
                val s = parseStatus(ProxyManager.status())
                val g = if (withGroups && s.running) runCatching { parseGroups(ProxyManager.groups()) }.getOrNull() else null
                s to g
            }
        }
        if (before != serial || pending != null || modePending != null || selecting) return
        result.onSuccess { (s, g) ->
            val was = status
            if (was != null && was.starting && !s.running && !s.starting && s.error != null) {
                failed = s.error
                actionError = s.error
            }
            unavailable = null
            status = s
            if (!s.running) {
                groups = null
                if (testing != null || delays.isNotEmpty()) { cancelTests(); delays.clear() }
            } else if (g != null) groups = g
        }.onFailure { e -> unavailable = errorCode(e, "proxy_not_initialized") }
    }
    /**
     * The VPN button: stops a running or starting proxy, starts a stopped one. Returns null when
     * done, [NEEDS_SETUP] when there is nothing to start (no subscription chosen on the phone page),
     * or a fixed error code.
     */
    suspend fun toggle(): String? {
        if (pending != null) return null
        if (status == null) refresh(withGroups = false)
        val s = status ?: return unavailable ?: "proxy_not_initialized"
        if (s.stopping) return null
        val stop = s.running || s.starting
        if (!stop && !s.available) return if (s.needsCore) NEEDS_CORE else "core_unavailable_for_device".also { actionError = it }
        if (!stop && !s.configured) return NEEDS_SETUP
        serial++
        actionError = null
        failed = null
        pending = if (stop) Pending.Stop else Pending.Start
        return try {
            val next = withContext(Dispatchers.IO) {
                if (stop) ProxyManager.stop() else ProxyManager.start()
                // start() and stop() answer before the service has acted; read the state again.
                parseStatus(ProxyManager.status())
            }
            status = next
            if (!stop && !next.running && !next.starting && next.error != null) next.error.also { actionError = it } else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = errorCode(e)
            if (code == "subscription_required") NEEDS_SETUP else code.also { actionError = it }
        } finally {
            pending = null
        }
    }

    fun press(onResult: (String?) -> Unit) {
        scope.launch { onResult(toggle()) }
    }

    /** rule, global or direct; a running core reloads its config, which can take seconds. */
    fun setMode(mode: String) {
        if (mode !in MODES || modePending != null || status?.mode == mode) return
        serial++
        actionError = null
        modePending = mode
        scope.launch {
            try {
                status = withContext(Dispatchers.IO) { parseStatus(ProxyManager.configure(mapOf("mode" to mode))) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionError = errorCode(e)
            } finally {
                modePending = null
            }
        }
    }

    /** Chooses [node] in the Selector group [group]; the check moves at once and is confirmed by the core. */
    fun select(group: String, node: String) {
        val current = groups ?: return
        val target = current.groups.firstOrNull { it.name == group } ?: return
        if (selecting || !target.selectable || target.now == node || node !in target.all) return
        serial++
        actionError = null
        selecting = true
        groups = VpnGroups(current.groups.map { if (it.name == group) it.copy(now = node) else it }, current.delays, current.types)
        scope.launch {
            try {
                val (s, g) = withContext(Dispatchers.IO) {
                    val s = parseStatus(ProxyManager.select(group, node))
                    s to runCatching { parseGroups(ProxyManager.groups()) }.getOrNull()
                }
                status = s
                g?.let { groups = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionError = errorCode(e)
                groups = current
            } finally {
                selecting = false
            }
        }
    }
    /**
     * Tests the latency of [group]'s members, two at a time (each up to about 6 s in the core).
     * Pressed again while that group is testing, it stops. The ms are latency to a fixed HTTPS
     * 204 address through the node, not bandwidth.
     */
    fun test(group: String) {
        val same = testing == group
        cancelTests()
        if (same) return
        val g = groups ?: return
        val nodes = g.groups.firstOrNull { it.name == group }?.all.orEmpty().distinct().filter { g.testable(it) }
        if (nodes.isEmpty()) return
        val round = ++testRound
        testing = group
        nodes.forEach { delays[it] = DelayResult.Testing }
        testJob = scope.launch {
            try {
                coroutineScope {
                    nodes.forEach { node ->
                        launch {
                            permits.withPermit {
                                val ms = withContext(Dispatchers.IO) {
                                    runCatching { ProxyManager.delay(node).optInt("delay", 0) }.getOrDefault(0)
                                }
                                delays[node] = if (ms > 0) DelayResult.Ms(ms) else DelayResult.Failed
                            }
                        }
                    }
                }
            } finally {
                // A cancelled round was cleaned up by cancelTests, which also moved the round on.
                if (round == testRound) { testing = null; testJob = null }
            }
        }
    }

    /** Stops a running latency test; nodes it had not reached lose their "testing" mark. */
    fun cancelTests() {
        testRound++
        testJob?.cancel()
        testJob = null
        testing = null
        delays.filterValues { it == DelayResult.Testing }.keys.toList().forEach { delays.remove(it) }
    }

    /** Control Center closed: nothing keeps running for a panel that is not there. */
    fun onClosed() {
        cancelTests()
        openGroup = null
        lastGroup = null
        focusButton = false
    }

    companion object {
        val MODES = listOf("rule", "global", "direct")
    }
}
