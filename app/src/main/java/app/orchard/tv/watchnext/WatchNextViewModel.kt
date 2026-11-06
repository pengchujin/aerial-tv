package app.orchard.tv.watchnext

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.orchard.tv.apps.AppEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class WatchNextViewModel internal constructor(app: Application, private val repository: WatchNextRepository) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, WatchNextRepository(app))
    val store = WatchNextStore(app)
    val artwork = WatchNextArtwork(app)
    var capability by mutableStateOf(repository.capability()); private set
    var master by mutableStateOf(store.enabled); private set
    var sources by mutableStateOf(store.sources); private set
    var status by mutableStateOf(WatchNextStatus.Disabled); private set
    var records by mutableStateOf<List<WatchNextProgram>>(emptyList()); private set
    var currentKey by mutableStateOf<ProgramKey?>(null); private set
    var navigationDirection by mutableStateOf(1); private set
    val current get() = records.firstOrNull { it.key == currentKey }
    var catalog by mutableStateOf<Map<String, AppEntry>>(emptyMap()); private set
    private var allowed = emptySet<String>()
    private var raw = emptyList<WatchNextProgram>()
    private var foreground = false
    private var reader: Job? = null
    private var carousel: Job? = null
    private var pending: String? = null
    private var pendingEnable = false
    private var loaded = false
    private var failed = false
    private var generation = 0L
    private var carouselToken: Pair<Boolean, Long>? = null

    fun bind(entries: Map<String, AppEntry>, permitted: Set<String>, resumed: Boolean) {
        if (catalog == entries && allowed == permitted && foreground == resumed) return
        if (catalog != entries) {
            catalog = entries
            store.discover(entries.keys)
            sources = store.sources
        }
        allowed = permitted
        if (foreground != resumed) {
            foreground = resumed
            capability = repository.capability()
            if (resumed && pendingEnable && capability.granted) finishEnable()
        }
        reconcile()
    }

    fun recheckPermission(): Boolean = repository.capability().granted

    fun refreshPermission() {
        capability = repository.capability()
        if (pendingEnable && capability.granted) finishEnable()
        reconcile()
    }

    /** Returns true only when a user command needs the host's permission UI. */
    fun setMaster(on: Boolean): Boolean {
        if (!on) {
            pendingEnable = false; pending = null
            store.master(false, catalog.keys); master = false
            reconcile()
            return false
        }
        return enable(null)
    }
    fun toggleSource(pkg: String): Boolean {
        if (sourceOn(pkg)) {
            store.source(pkg, false); sources = store.sources; reconcile()
            return false
        }
        return enable(pkg)
    }
    fun sourceOn(pkg: String) = capability.granted && master && pkg in sources
    private fun enable(pkg: String?): Boolean {
        capability = repository.capability()
        if (!capability.supported) return false
        pending = pkg; pendingEnable = true
        if (capability.granted) { finishEnable(); reconcile(); return false }
        reconcile()
        return true
    }
    fun permissionResult(granted: Boolean) {
        capability = repository.capability()
        if (granted && capability.granted && pendingEnable) finishEnable()
        else { pendingEnable = false; pending = null }
        reconcile()
    }
    private fun finishEnable() {
        // Restore an existing selection; an empty saved list starts with all ordinary apps.
        store.master(true, catalog.keys)
        pending?.let { store.source(it, true) }
        master = true; sources = store.sources
        pending = null; pendingEnable = false
    }
    private fun reconcile() {
        val canRead = capability.supported && capability.granted && master && foreground
        if (!canRead) {
            generation++
            reader?.cancel(); reader = null
            carousel?.cancel(); carousel = null; carouselToken = null
            if (!master || !capability.granted || !capability.supported) {
                raw = emptyList(); loaded = false; failed = false
            }
            artwork.clear()
        }
        derive()
        // A failed observer leaves a completed Job; an explicit retry must be able to replace it.
        if (canRead && reader?.isActive != true) {
            val token = ++generation
            reader = viewModelScope.launch {
                repository.observe().catch {
                    if (token == generation) {
                        capability = repository.capability(); failed = true
                        if (!capability.granted || !capability.supported) reconcile() else derive()
                    }
                }.collect { result ->
                    if (token != generation) return@collect
                    capability = repository.capability()
                    result.onSuccess { raw = it; loaded = true; failed = false }.onFailure { failed = true }
                    if (!capability.granted || !capability.supported) reconcile() else derive()
                }
            }
        }
    }
    private fun derive() {
        val available = capability.supported && capability.granted && master
        val next = if (available) effectivePrograms(raw, sources, allowed.intersect(catalog.keys)) else emptyList()
        currentKey = retainedKey(records, currentKey, next) ?: currentKey
        records = next
        artwork.prefetch(if (foreground && available) next else emptyList())
        status = when {
            !capability.supported -> WatchNextStatus.Unsupported
            !capability.granted -> WatchNextStatus.Unauthorized
            !master -> WatchNextStatus.Disabled
            failed -> WatchNextStatus.Failed
            !loaded -> WatchNextStatus.Loading
            next.isEmpty() -> WatchNextStatus.Empty
            else -> WatchNextStatus.Ready
        }
    }
    fun launched(key: ProgramKey, result: WatchNextLaunchResult) {
        if (result == WatchNextLaunchResult.Unavailable) {
            raw = raw.filterNot { it.key == key }
            derive()
        }
    }
    fun advance(delta: Int) {
        if (records.size < 2 || delta == 0) return
        navigationDirection = if (delta < 0) -1 else 1
        currentKey = records[adjacentIndex(records.indexOfFirst { it.key == currentKey }.coerceAtLeast(0), delta, records.size)].key
    }
    fun carousel(eligible: Boolean, inputAt: Long) {
        val token = (eligible && foreground && master && capability.granted && records.size > 1) to inputAt
        if (carouselToken == token) return
        carouselToken = token
        carousel?.cancel(); carousel = null
        if (token.first) carousel = viewModelScope.launch {
            while (true) {
                delay(WatchNextDefaults.DwellMs + WatchNextDefaults.TransitionMs)
                advance(1)
            }
        }
    }
    fun pauseCarousel() { carousel?.cancel(); carousel = null; carouselToken = null }
    override fun onCleared() { reader?.cancel(); carousel?.cancel(); repository.close(); artwork.close() }
}
