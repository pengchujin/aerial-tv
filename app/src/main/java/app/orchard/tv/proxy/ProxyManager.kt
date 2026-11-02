package app.orchard.tv.proxy

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns the embedded core. Sensitive URLs and YAML remain exclusively in private app storage. */
object ProxyManager {
    private const val CORE_VERSION = "1.19.33"
    /**
     * PUT /configs applies the config before answering, rule-providers included (executor.ApplyConfig
     * loadProvider waits for them): the whitelist plus ad-block lists (about 340k entries) took
     * 0.6 s on a desktop and may take several seconds on the TV's ARMv7 CPU.
     */
    private const val RELOAD_TIMEOUT_MS = 45000
    private val lock = Any()
    @Volatile private var initialized = false
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var directory: File
    private lateinit var profiles: ProxyProfiles
    private val connectedState = MutableStateFlow(false)

    /**
     * [proxyConnected] for observers (Home's status icon). True only after the latest HTTPS 204
     * probe through the local port succeeded for the current core, profile, node and mode; a running
     * core alone is not connected. The rule/global/direct outbound mode does not matter: a proxy in
     * direct mode that passes the probe is connected too.
     */
    val connected: StateFlow<Boolean> = connectedState.asStateFlow()

    /** Written only under [lock]; every write also publishes [connected]. */
    @Volatile var proxyConnected = false
        private set(value) { field = value; connectedState.value = value }
    private var healthGeneration = 0L
    private val refreshWorker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "AerialProxyRefresh").apply { isDaemon = true }
    }
    private val healthWorker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "AerialProxyHealth").apply { isDaemon = true }
    }
    @Volatile private var process: Process? = null
    @Volatile private var controller: MihomoController? = null
    @Volatile private var running = false
    @Volatile private var starting = false
    @Volatile private var stopping = false
    @Volatile private var lastError: String? = null
    @Volatile private var importError: String? = null
    @Volatile private var activePort = 0
    /** Router/system resolvers in the running core's DNS block; empty while its DNS is off. */
    private var activeDns: List<String> = emptyList()
    /**
     * TUN state. [activeTun]: the running core's config has the tun inbound, so every reload must
     * keep it (a different tun block makes the core close the listener, listener.ReCreateTun).
     * [tunActive]: Android gave the fd and the core reported tun enabled.
     */
    @Volatile private var activeTun = false
    @Volatile private var tunActive = false
    /** VpnService.prepare asked for the user's confirmation, which has not been given yet. */
    @Volatile private var vpnPermissionRequired = false
    /** Another VPN took over or the user disconnected: no TUN until the user turns it on again. */
    @Volatile private var tunRevoked = false
    /** The automatic VPN confirmation is shown at most once per process. */
    @Volatile private var consentAsked = false
    /** Why TUN is not active although wanted; a fixed code, kept apart from [lastError]. */
    @Volatile private var tunError: String? = null
    private var controllerPort = 0
    private var secret = ""
    private var launchSerial = 0L
    private val source get() = profiles.active()?.let { profiles.file(it.id) } ?: File(directory, "unconfigured.yaml")
    private val runtime get() = File(directory, "runtime.yaml")
    private val bundledBinary get() = File(context.applicationInfo.nativeLibraryDir, "libmihomo.so")
    /**
     * The core to run: the one bundled in the APK if this build has it, else the downloaded one
     * (CoreInstaller: verified against pinned SHA-256 values before it was made executable), else a
     * path that does not exist, which every caller treats as "core unavailable".
     */
    private val binary get() = bundledBinary.takeIf { it.isFile } ?: core.file
    private lateinit var core: CoreInstaller

    /*
     * Rule lists for "rule" mode (RuleLists/RuleStore): downloads run on their own worker, never
     * under [lock]; only the reload that follows takes it. [rulesPending] > 0 while one is queued
     * or running, shown as status.rulesUpdating.
     */
    private lateinit var rules: RuleStore
    private var snapshotAt = 0L
    private val rulesWorker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "AerialRules").apply { isDaemon = true }
    }
    private val rulesPending = java.util.concurrent.atomic.AtomicInteger()

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            this.context = context.applicationContext
            prefs = context.getSharedPreferences("proxy", Context.MODE_PRIVATE)
            directory = File(context.filesDir, "proxy").apply { mkdirs() }
            core = CoreInstaller(File(directory, "core"), CoreSources.URLS, HttpCoreFetch(),
                selfTest = { f -> CoreSelfTest.run(f, CoreSpec.PINNED.version) },
                log = { app.orchard.tv.system.DiagLog.i("proxy: $it") })
            SystemProxy.recover(context)
            // A damaged profiles.json must not take Home down with it on every start: stay
            // uninitialized (the proxy API answers proxy_not_initialized) and keep every file as is.
            profiles = try {
                ProxyProfiles(directory, prefs)
            } catch (e: Exception) {
                app.orchard.tv.system.DiagLog.i("proxy: profiles could not be read (${e.javaClass.simpleName}); proxy off")
                return
            }
            snapshotAt = runCatching {
                java.util.Properties().apply { context.assets.open("proxy/rules/manifest.properties").use { load(it) } }
                    .getProperty("fetchedAt")?.toLongOrNull() ?: 0L
            }.getOrDefault(0L)
            rules = RuleStore(directory, ::bundledRuleList, snapshotAt)
            initialized = true
            // Once per process, off the main thread: shows on the TV whether the bundled asset names
            // match what the app opens (they once did not: .txt.gz in source, .txt in the APK).
            rulesWorker.execute {
                runCatching {
                    val names = (RuleLists.BLACKLIST_ENTRIES + RuleLists.WHITELIST_ENTRIES).map { it.name }.distinct()
                    val readable = names.count { name -> bundledRuleList(name)?.use { true } == true }
                    app.orchard.tv.system.DiagLog.i("rules: bundled snapshot readable $readable of ${names.size} lists")
                }
            }
            healthWorker.scheduleWithFixedDelay({ runCatching { probeHealth() } }, 3, 30, TimeUnit.SECONDS)
            // Lists due for their daily download, only while the core runs in rule mode; the first
            // check waits until startup work on the TV is done.
            rulesWorker.scheduleWithFixedDelay({ runRulesJob(force = false, auto = true) }, 90, 600, TimeUnit.SECONDS)
            refreshWorker.scheduleWithFixedDelay({
                runCatching { refreshDueProfiles() }
                runCatching { refreshDns() }
            }, 15, 60, TimeUnit.SECONDS)
        }
    }

    /** Once per app process, from Home startup. A manual stop lasts until the next app start. */
    @Volatile private var startupChecked = false

    fun startWithApplication() {
        synchronized(lock) {
            if (startupChecked || !initialized) return
            startupChecked = true
            if (!source.isFile) return
        }
        start()
    }

    fun status(): JSONObject = synchronized(lock) {
        check(initialized) { "proxy_not_initialized" }
        JSONObject().put("available", binary.isFile)
            .put("coreInstalled", binary.isFile).put("coreSource", coreSource())
            .put("coreDownloadSupported", coreDownloadSupported())
            .put("coreDownload", coreDownloadJson())
            .put("running", running).put("starting", starting).put("stopping", stopping)
            .put("mode", prefs.getString("mode", "rule"))
            .put("systemProxy", prefs.getBoolean("systemProxy", true))
            .put("systemProxyApplied", SystemProxy.isApplied(context))
            .put("allowLan", prefs.getBoolean("allowLan", false))
            .put("mixedPort", prefs.getInt("mixedPort", 7890))
            .put("tun", tunPreference()).put("tunSupported", tunSupported())
            .put("tunActive", running && tunActive).put("vpnPermissionRequired", vpnPermissionRequired)
            .put("tunError", tunError ?: JSONObject.NULL)
            .put("configured", source.isFile).put("proxyConnected", proxyConnected)
            .put("systemDns", running && activeDns.isNotEmpty())
            .put("activeProfileId", profiles.activeId ?: JSONObject.NULL)
            .put("profiles", JSONArray().also { list -> profiles.profiles.forEach { list.put(it.status(profiles.activeId)) } })
            .put("subscriptionConfigured", profiles.active()?.kind == "subscription" && source.isFile)
            .put("subscriptionUpdatedAt", profiles.active()?.updatedAt ?: 0L)
            .put("coreVersion", CORE_VERSION).put("error", lastError ?: JSONObject.NULL)
            .put("importError", importError ?: JSONObject.NULL)
            .put("lanAddress", lanAddress() ?: JSONObject.NULL)
            .put("ruleMode", ruleMode()).put("adBlock", adBlock())
            .put("rulesUpdating", rulesPending.get() > 0).put("rulesSnapshotAt", snapshotAt)
            .apply {
                val groups = sourceGroups()
                put("proxyGroup", RuleLists.pick(groups.names, groups.default, groupOverride(profiles.activeId)))
                put("proxyGroupAuto", groupOverride(profiles.activeId).let { it.isEmpty() || (it != "GLOBAL" && it !in groups.names) })
                put("proxyGroups", JSONArray(groups.names))
                put("ruleLists", JSONArray().also { list ->
                    ruleSets(ruleMode(), adBlock()).forEach { set ->
                        rules.status(set).forEach { s ->
                            list.put(JSONObject().put("set", set).put("name", s.name).put("behavior", s.behavior)
                                .put("count", s.count).put("updatedAt", s.updatedAt).put("source", s.source ?: JSONObject.NULL)
                                .put("lastError", s.error ?: JSONObject.NULL).put("custom", s.custom))
                        }
                    }
                })
            }
    }

    private fun coreSource() = if (bundledBinary.isFile) "bundled" else if (core.installed()) "downloaded" else "none"

    /** Only the armeabi-v7a asset is pinned, so only a TV that can run 32-bit ARM code is offered it. */
    private fun coreDownloadSupported() = CoreSelfTest.abiSupported(Build.SUPPORTED_32_BIT_ABIS)

    private fun coreDownloadJson(): JSONObject = core.progress().let {
        JSONObject().put("state", it.state).put("received", it.received).put("total", it.total)
            .put("speed", it.speed).put("error", it.error ?: JSONObject.NULL)
    }

    /**
     * Starts the user's "download the core" request. Runs on the installer's own thread, so neither
     * the caller nor [lock] waits for the network; progress is part of [status].
     */
    fun downloadCore(): JSONObject {
        check(initialized) { "proxy_not_initialized" }
        require(coreDownloadSupported()) { CoreError.UNSUPPORTED }
        if (!bundledBinary.isFile && !core.installed()) core.start()
        return synchronized(lock) { status() }
    }

    fun cancelCoreDownload(): JSONObject {
        check(initialized) { "proxy_not_initialized" }
        core.cancel()
        return synchronized(lock) { status() }
    }

    fun start(): JSONObject {
        synchronized(lock) {
            if (running || starting) return status()
            require(binary.isFile) { "core_unavailable_for_device" }
            require(source.isFile) { "subscription_required" }
            invalidateHealth()
            starting = true
            stopping = false
            lastError = null
        }
        try { ProxyService.requestStart(context) } catch (_: Exception) {
            starting = false
            lastError = "proxy_service_start_failed"
        }
        return status()
    }

    fun stop(): JSONObject {
        if (!initialized) return JSONObject().put("running", false)
        synchronized(lock) { invalidateHealth(); stopping = true }
        ProxyService.requestStop(context)
        return status()
    }

    /**
     * Called by the foreground service's serial worker, never the UI thread. [service] is the
     * running VpnService, the only object that can open Aerial's tunnel.
     */
    fun onServiceStart(context: Context, service: ProxyService): Boolean {
        init(context)
        synchronized(lock) {
            if (running) { starting = false; return true }
            starting = true
            lastError = null
            return try {
                require(binary.isFile && source.isFile) { "subscription_required" }
                controllerPort = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
                secret = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
                activePort = prefs.getInt("mixedPort", 7890)
                val dns = SystemDns.servers(this.context)
                val text = source.readText()
                val lists = ruleSetup()
                // TUN first when wanted; a core that cannot use the fd is killed (taking the VPN with
                // it) and the proxy starts once more without TUN, as it ran before TUN existed.
                val tunFd = openTun(service, dns)
                var core = tunFd?.let { fd -> runCatching { launch(text, dns, fd, lists) }.getOrNull() }
                if (core != null && !tunReported(core.second)) { stopProcess(core.first); core = null }
                val withTun = core != null
                val (launched, client) = core ?: launch(text, dns, null, lists)
                if (tunFd != null && !withTun) tunError = "tun_start_failed"
                activeDns = dns
                activeTun = withTun
                tunActive = withTun
                process = launched
                val serial = ++launchSerial
                controller = client
                invalidateHealth()
                running = true
                starting = false
                stopping = false
                applySystemProxy()
                requestHealthProbe()
                Thread({
                    runCatching { launched.waitFor() }
                    synchronized(lock) {
                        if (serial == launchSerial && process === launched) {
                            invalidateHealth()
                            // The core held the last tun fd: its exit already took the VPN down.
                            process = null; controller = null; running = false; starting = false
                            activeTun = false; tunActive = false
                            SystemProxy.restore(this.context)
                            if (!stopping) lastError = "core_stopped_unexpectedly"
                            ProxyService.requestStop(this.context)
                        }
                    }
                }, "AerialProxyExit").apply { isDaemon = true; start() }
                true
            } catch (e: Exception) {
                lastError = safeError(e, "core_start_failed")
                stopEngine()
                starting = false
                stopping = false
                false
            }
        }
    }

    fun onServiceStop(context: Context) {
        init(context)
        synchronized(lock) { stopEngine(); starting = false; stopping = false }
    }

    /**
     * TUN turned on or off (phone page, VPN confirmation, revoke): a reload cannot add or drop the
     * tun inbound, so the core restarts. The system proxy stays applied across the gap.
     */
    fun onServiceRestart(context: Context, service: ProxyService): Boolean {
        init(context)
        synchronized(lock) {
            if (stopping || (!running && !starting)) { starting = false; return false }
            stopEngine(restoreSystemProxy = false)
            return onServiceStart(context, service)
        }
    }

    private fun stopEngine(restoreSystemProxy: Boolean = true) {
        invalidateHealth()
        ++launchSerial
        running = false
        controller = null
        activeTun = false
        tunActive = false
        val old = process
        process = null
        // The parent closed its tun fd at launch; killing the core closes the last one and Android
        // removes the VPN (Vpn.java interfaceRemoved), so no path leaves a tunnel without a reader.
        old?.let(::stopProcess)
        if (restoreSystemProxy) SystemProxy.restore(context)
        activePort = 0
        activeDns = emptyList()
        runtime.delete(); File(directory, "runtime.yaml.bak").delete()
    }

    private fun stopProcess(p: Process) {
        p.destroy()
        if (!waitFor(p, 2)) kill(p)
    }

    /**
     * Starts the core with [text] as its runtime config and waits for its controller. With [tunFd]
     * the config gets the tun inbound and the fd becomes fd [MihomoConfig.TUN_FD] in the core; the
     * parent's copy is closed here on every path, so the core holds the only one.
     */
    private fun launch(text: String, dns: List<String>, tunFd: ParcelFileDescriptor?, lists: RuleLists.Setup): Pair<Process, MihomoController> {
        val launched = try {
            writeAtomic(runtime, MihomoConfig.runtime(text, mode(), allowLan(), activePort, controllerPort, secret, dns,
                tun = tunFd != null, lists = lists))
            if (tunFd == null) {
                ProcessBuilder(binary.path, "-d", directory.path, "-f", runtime.path).redirectErrorStream(true).start()
            } else if (Build.VERSION.SDK_INT >= 26) {
                startWithTun(tunFd)
            } else error("tun_not_supported")
        } finally { tunFd?.let { runCatching { it.close() } } }
        try {
            Thread({
                // A configuration may contain credentials in core diagnostics: never forward stdout.
                runCatching { launched.inputStream.use { input -> val buffer = ByteArray(4096); while (input.read(buffer) >= 0) Unit } }
            }, "AerialProxyOutput").apply { isDaemon = true; start() }
            val client = MihomoController(controllerPort, secret)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18)
            var ready = false
            while (System.nanoTime() < deadline && alive(launched)) {
                if (runCatching { client.request("GET", "/version", timeout = 500) }.isSuccess) { ready = true; break }
                Thread.sleep(150)
            }
            require(ready) { "core_start_failed" }
            return launched to client
        } catch (e: Exception) {
            stopProcess(launched)
            throw e
        }
    }

    /**
     * Android's ProcessBuilder passes only fds 0-2 to the child (ProcessImpl closes the rest), and
     * the core needs the tun on an fd >= 1 (fd 0 makes sing-tun open /dev/net/tun itself). So the
     * tun fd is put on this process's fd 0 just for the fork, inherited as the child's stdin, and
     * moved to fd 3 by the shell, which then execs the core with /dev/null as stdin (verified with
     * the TV's mksh). Called with [lock] held: every process start in this object holds it too, and
     * those use pipes for stdin, so no other child can inherit the tun meanwhile. fd 0 is restored
     * before returning. Redirect.INHERIT needs Android 8.
     */
    @androidx.annotation.RequiresApi(26)
    private fun startWithTun(tunFd: ParcelFileDescriptor): Process {
        val saved = Os.dup(FileDescriptor.`in`)
        try {
            Os.dup2(tunFd.fileDescriptor, 0)
            return ProcessBuilder("/system/bin/sh", "-c", "exec \"\$0\" \"\$@\" ${MihomoConfig.TUN_FD}<&0 0</dev/null",
                binary.path, "-d", directory.path, "-f", runtime.path)
                .redirectInput(ProcessBuilder.Redirect.INHERIT).redirectErrorStream(true).start()
        } finally {
            try { Os.dup2(saved, 0) } catch (e: Exception) {
                // Never keep the tunnel open on our own fd 0: it would outlive the core.
                runCatching { Os.close(FileDescriptor.`in`) }
            } finally { runCatching { Os.close(saved) } }
        }
    }

    /** The core opens the tun inbound before its controller (executor.ApplyConfig), so this is final. */
    private fun tunReported(client: MihomoController): Boolean = runCatching {
        client.request("GET", "/configs", timeout = 2000).optJSONObject("tun")?.optBoolean("enable", false) == true
    }.getOrDefault(false)

    private fun tunPreference() = prefs.getBoolean("tun", true)
    private fun tunSupported() = Build.VERSION.SDK_INT >= 26 && binary.isFile

    /**
     * The tunnel for this start, or null to run without TUN (not wanted, revoked, no router DNS,
     * no permission yet, or Android refused). Never asks the user more than once per process on its
     * own; the phone page's TUN switch asks again.
     */
    private fun openTun(service: ProxyService, dns: List<String>): ParcelFileDescriptor? {
        tunError = null
        if (!tunPreference() || !tunSupported()) { vpnPermissionRequired = false; return null }
        if (tunRevoked) { tunError = "vpn_revoked"; return null }
        if (dns.isEmpty()) { tunError = "tun_dns_unavailable"; return null }
        val consent = runCatching { VpnService.prepare(context) }.getOrElse { tunError = "tun_start_failed"; return null }
        if (consent != null) {
            vpnPermissionRequired = true
            if (!consentAsked) requestConsent()
            tunError = consentResult
            return null
        }
        vpnPermissionRequired = false
        return runCatching { service.establishTun() }.getOrNull() ?: run { tunError = "tun_start_failed"; null }
    }

    /** The last answer to the VPN confirmation in this process: null, or a fixed error code. */
    @Volatile private var consentResult: String? = null

    private fun requestConsent() {
        consentAsked = true
        consentResult = null
        try {
            context.startActivity(Intent(context, VpnConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (_: Exception) {
            consentResult = "vpn_consent_unavailable"
            tunError = consentResult
        }
    }

    /**
     * From [VpnConsentActivity] on the main thread, so it never waits for [lock] (a start can hold
     * it for half a minute). A grant restarts a running core with TUN.
     */
    internal fun onVpnConsent(granted: Boolean, error: String? = null) {
        if (!initialized) return
        if (granted) {
            consentResult = null; vpnPermissionRequired = false; tunError = null
            if (running || starting) requestRestart()
        } else {
            consentResult = error ?: "vpn_permission_denied"
            tunError = consentResult
        }
    }

    /**
     * Another VPN app took the slot or the user disconnected Aerial's VPN in Settings. The proxy
     * keeps running without TUN, and Aerial does not take the VPN back until the user turns TUN on
     * again (main thread, from [ProxyService.onRevoke]).
     */
    internal fun onVpnRevoked() {
        if (!initialized) return
        tunRevoked = true
        tunError = "vpn_revoked"
        tunActive = false
        if (running || starting) requestRestart()
    }

    private fun requestRestart(): Boolean = try { ProxyService.requestRestart(context); true } catch (_: Exception) {
        lastError = "proxy_service_start_failed"
        false
    }

    fun configure(form: Map<String, String>): JSONObject = synchronized(lock) {
        invalidateHealth()
        val nextMode = form["mode"] ?: mode()
        require(nextMode in setOf("rule", "global", "direct")) { "invalid_mode" }
        fun bool(key: String, fallback: Boolean): Boolean = form[key]?.let {
            require(it == "true" || it == "false") { "invalid_boolean" }; it == "true"
        } ?: fallback
        val tunField = form["tun"]?.let { bool("tun", false) }
        require(tunField != true || tunSupported()) { "tun_not_supported" }
        val nextTun = tunField ?: tunPreference()
        if (tunField == true) {
            // The user's own "on" overrides an earlier revoke or refusal, and may ask again.
            tunRevoked = false; consentAsked = false; consentResult = null; tunError = null
        }
        if (tunField == false) { vpnPermissionRequired = false; tunError = null }
        val nextLan = bool("allowLan", allowLan())
        val nextSystem = bool("systemProxy", prefs.getBoolean("systemProxy", true))
        val nextPort = form["mixedPort"]?.let { it.toIntOrNull() ?: error("invalid_proxy_port") } ?: prefs.getInt("mixedPort", 7890)
        require(nextPort in 1024..65535 && nextPort != 8790 && nextPort != controllerPort) { "invalid_proxy_port" }
        val nextRuleMode = form["ruleMode"] ?: ruleMode()
        require(nextRuleMode in RuleLists.MODES) { "invalid_rule_mode" }
        val nextAdBlock = bool("adBlock", adBlock())
        // The target group belongs to the profile in use: "" = automatic, else one of its groups or GLOBAL.
        val nextGroup = form["proxyGroup"]?.let { group ->
            val active = profiles.activeId ?: error("profile_not_configured")
            require(group.isEmpty() || group == "GLOBAL" || group in sourceGroups().names) { "invalid_proxy_group" }
            active to group
        }
        // TUN on/off changes the core's listeners: restart instead of reload (listener.ReCreateTun).
        val consentNeeded = running && tunField == true && !activeTun &&
            runCatching { VpnService.prepare(context) != null }.getOrDefault(false)
        val restart = running && tunField != null && !consentNeeded && (nextTun && tunSupported()) != activeTun
        if (running && !restart) {
            val dns = dnsServers()
            val lists = ruleSetup(nextRuleMode, nextAdBlock, nextGroup?.second ?: groupOverride(profiles.activeId), nextMode)
            val content = MihomoConfig.runtime(source.readText(), nextMode, nextLan, nextPort, controllerPort, secret, dns, activeTun, lists)
            // Unchanged config (e.g. only the system proxy switch): no reload, no re-parse of large lists.
            if (runtime.takeIf(File::isFile)?.readText() != content) reload(content)
            activePort = nextPort
            activeDns = dns
        }
        prefs.edit().putString("mode", nextMode).putBoolean("allowLan", nextLan)
            .putBoolean("systemProxy", nextSystem).putInt("mixedPort", nextPort)
            .putString("ruleMode", nextRuleMode).putBoolean("adBlock", nextAdBlock)
            .apply {
                if (tunField != null) putBoolean("tun", tunField)
                nextGroup?.let { (id, group) -> if (group.isEmpty()) remove("ruleGroup:$id") else putString("ruleGroup:$id", group) }
            }.commit()
        lastError = null
        if (consentNeeded) {
            // Ask first; the grant restarts the core with TUN (onVpnConsent). Until then it keeps running.
            vpnPermissionRequired = true
            requestConsent()
        }
        if (restart) {
            starting = true
            if (!requestRestart()) starting = false
        } else if (running) { applySystemProxy(); requestHealthProbe() }
        // A newly needed list (first ad block, another rule source) downloads in the background.
        if (nextMode == "rule" && rules.dueAny(ruleSets(nextRuleMode, nextAdBlock))) requestRulesUpdate(force = false)
        status()
    }

    /** Compatibility import updates the active subscription, or adds one beside manual profiles. */
    fun importSubscription(url: String): JSONObject {
        val active = synchronized(lock) { profiles.active()?.takeIf { it.kind == "subscription" } }
        val fields = mutableMapOf("kind" to "subscription", "url" to url, "activate" to "true")
        active?.let { fields["id"] = it.id; fields["name"] = it.name }
        return saveProfile(fields)
    }

    fun refreshSubscription(): JSONObject {
        val id = synchronized(lock) { profiles.active()?.takeIf { it.kind == "subscription" }?.id }
            ?: error("subscription_required")
        return refreshProfile(id)
    }

    /** Legacy clear removes only the active profile; unrelated saved subscriptions survive. */
    fun clearSubscription(): JSONObject {
        val id = synchronized(lock) { profiles.activeId } ?: return status()
        return deleteProfile(id)
    }

    fun saveProfile(form: Map<String, String>): JSONObject {
        val previous: ProxyProfile?
        val next: ProxyProfile
        val activate: Boolean
        synchronized(lock) {
            previous = form["id"]?.takeIf { it.isNotBlank() }?.let { profiles.find(it) }
            require(previous != null || profiles.profiles.size < 64) { "profile_limit_reached" }
            val kind = form["kind"] ?: previous?.kind ?: "subscription"
            require(kind in setOf("subscription", "http", "socks5")) { "invalid_profile_kind" }
            require(previous == null || previous.kind == kind) { "profile_kind_fixed" }
            val name = (form["name"] ?: previous?.name ?: "Profile ${profiles.profiles.size + 1}").trim()
            require(name.length in 1..80 && name.none { it.code < 32 }) { "invalid_profile_name" }
            val hours = form["refreshHours"]?.toIntOrNull() ?: if (form.containsKey("refreshHours")) error("invalid_refresh_hours") else previous?.refreshHours ?: 24
            require(hours in 0..168) { "invalid_refresh_hours" }
            fun bool(key: String, fallback: Boolean): Boolean = form[key]?.let {
                require(it == "true" || it == "false") { "invalid_boolean" }; it == "true"
            } ?: fallback
            activate = bool("activate", true) || previous?.id == profiles.activeId
            next = ProxyProfile(previous?.id ?: profiles.newId(), name, kind,
                url = if (kind == "subscription") MihomoConfig.validUrl(form["url"]?.takeIf { it.isNotBlank() } ?: previous?.url.orEmpty()) else "",
                host = if (kind == "subscription") "" else form["host"]?.trim() ?: previous?.host.orEmpty(),
                port = if (kind == "subscription") 0 else form["port"]?.let { it.toIntOrNull() ?: error("invalid_upstream") } ?: previous?.port ?: 0,
                username = if (kind == "subscription") "" else form["username"] ?: previous?.username.orEmpty(),
                password = if (kind == "subscription") "" else form["password"] ?: previous?.password.orEmpty(),
                tls = bool("tls", previous?.tls ?: false), refreshHours = if (kind == "subscription") hours else 0,
                updatedAt = previous?.updatedAt ?: 0L)
        }
        try {
            val cached = synchronized(lock) {
                previous?.takeIf { it.kind == "subscription" && it.url == next.url }?.let { profiles.file(it.id).takeIf(File::isFile)?.readText() }
            }
            val text = if (next.kind == "subscription") cached ?: download(next.url) else MihomoConfig.manual(next)
            synchronized(lock) {
                require(previous == profiles.profiles.firstOrNull { it.id == next.id }) { "profile_changed_retry" }
                val now = System.currentTimeMillis()
                val saved = next.copy(updatedAt = if (cached != null) next.updatedAt else now, lastAttemptAt = now)
                installProfile(saved, text, activate)
                importError = null
                if (activate && !running && !starting) start()
                return status()
            }
        } catch (error: Exception) {
            val code = profileError(error)
            synchronized(lock) {
                importError = code
                if (previous != null && profiles.profiles.firstOrNull { it.id == previous.id } == previous) {
                    profiles.commit(profiles.profiles.map { if (it.id == previous.id) it.copy(lastAttemptAt = System.currentTimeMillis(), lastError = code) else it }, profiles.activeId)
                }
            }
            throw IllegalArgumentException(code)
        }
    }

    private fun installProfile(profile: ProxyProfile, text: String, activate: Boolean) {
        val dns = dnsServers()
        // A running TUN core keeps its tun block on reload; a stopped one only validates (-t never opens the fd).
        val content = MihomoConfig.runtime(text, mode(), allowLan(), prefs.getInt("mixedPort", 7890),
            if (controllerPort == 0) 19090 else controllerPort, if (secret.isEmpty()) "validation-only" else secret, dns,
            running && activeTun, ruleSetup(group = groupOverride(profile.id)))
        validate(content)
        val file = profiles.file(profile.id)
        val previousText = file.takeIf(File::isFile)?.readText()
        val previousRuntime = runtime.takeIf(File::isFile)?.readText()
        val nextProfiles = profiles.profiles.filterNot { it.id == profile.id } + profile
        try {
            writeAtomic(file, text)
            if (activate && running) { reload(content); activeDns = dns }
            profiles.commit(nextProfiles, if (activate) profile.id else profiles.activeId)
        } catch (error: Exception) {
            if (previousText != null) writeAtomic(file, previousText) else file.delete()
            if (activate && running && previousRuntime != null) runCatching { reload(previousRuntime) }
            throw error
        }
        if (activate) {
            invalidateHealth()
            syncLegacyProfile(profile, text)
            if (running) requestHealthProbe()
        }
        lastError = null
    }

    fun activateProfile(id: String): JSONObject = synchronized(lock) {
        val profile = profiles.find(id)
        val text = profiles.file(profile.id).takeIf(File::isFile)?.readText() ?: error("profile_not_configured")
        installProfile(profile, text, true)
        if (!running && !starting) start()
        status()
    }

    fun deleteProfile(id: String): JSONObject = synchronized(lock) {
        profiles.find(id)
        if (id == profiles.activeId) {
            stopEngine(); starting = false; stopping = false
            ProxyService.requestStop(context)
        }
        profiles.commit(profiles.profiles.filterNot { it.id == id }, profiles.activeId?.takeUnless { it == id })
        profiles.file(id).delete(); File(profiles.file(id).path + ".bak").delete()
        prefs.edit().remove("ruleGroup:$id").commit()
        if (profiles.activeId == null) syncLegacyProfile(null, null)
        importError = null; lastError = null
        status()
    }

    fun refreshProfile(id: String): JSONObject {
        val original = synchronized(lock) { profiles.find(id).also { require(it.kind == "subscription") { "profile_not_subscription" } } }
        try {
            val text = download(original.url)
            synchronized(lock) {
                require(profiles.find(id) == original) { "profile_changed_retry" }
                val now = System.currentTimeMillis()
                installProfile(original.copy(updatedAt = now, lastAttemptAt = now, lastError = null), text, id == profiles.activeId)
                importError = null
                return status()
            }
        } catch (error: Exception) {
            val code = profileError(error)
            synchronized(lock) {
                if (profiles.profiles.firstOrNull { it.id == id } == original) {
                    profiles.commit(profiles.profiles.map { if (it.id == id) it.copy(lastAttemptAt = System.currentTimeMillis(), lastError = code) else it }, profiles.activeId)
                }
                importError = code
            }
            throw IllegalArgumentException(code)
        }
    }

    private fun syncLegacyProfile(profile: ProxyProfile?, text: String?) {
        val editor = prefs.edit()
        if (profile?.kind == "subscription" && text != null) {
            writeAtomic(File(directory, "subscription.yaml"), text)
            editor.putString("subscriptionUrl", profile.url).putLong("subscriptionUpdatedAt", profile.updatedAt)
        } else editor.remove("subscriptionUrl").remove("subscriptionUpdatedAt")
        editor.commit()
    }

    private fun refreshDueProfiles() {
        val now = System.currentTimeMillis()
        val due = synchronized(lock) { profiles.profiles.filter {
            it.kind == "subscription" && it.refreshHours > 0 &&
                now - maxOf(it.updatedAt, it.lastAttemptAt) >= TimeUnit.HOURS.toMillis(it.refreshHours.toLong())
        }.map { it.id } }
        due.forEach { id -> runCatching { refreshProfile(id) } }
    }

    private fun invalidateHealth() { ++healthGeneration; proxyConnected = false }
    private fun requestHealthProbe() { healthWorker.execute { runCatching { probeHealth() } } }

    /**
     * The Android uplink changed (other transport or Wi-Fi, or Internet lost/regained): a 204 that
     * passed over the previous uplink says nothing about this one. Clears the state and probes again
     * at once instead of at the next 30 s tick. Safe from the main thread: [lock] can be held through
     * a core validation, so the clear and the probe both run on the health worker.
     */
    fun onUplinkChanged() {
        if (!initialized) return
        healthWorker.execute {
            runCatching {
                synchronized(lock) { invalidateHealth() }
                probeHealth()
            }
        }
        // Another uplink can bring another router resolver. Runs on the refresh worker: it may reload.
        refreshWorker.execute { runCatching { refreshDns() } }
    }

    /**
     * The uplink's resolvers for the next runtime config. While a network switch has none yet, the
     * running core keeps the resolvers it has instead of falling back to public ones.
     */
    private fun dnsServers(): List<String> = SystemDns.servers(context).ifEmpty { if (running) activeDns else emptyList() }

    /** Reloads only when the uplink's resolvers really changed; the core keeps running meanwhile. */
    private fun refreshDns() {
        val next = SystemDns.servers(context)
        synchronized(lock) {
            if (!running || starting || stopping || controller == null || next.isEmpty() || next == activeDns) return
            if (!source.isFile) return
            reload(MihomoConfig.runtime(source.readText(), mode(), allowLan(), activePort, controllerPort, secret, next, activeTun, ruleSetup()))
            activeDns = next
            requestHealthProbe()
        }
    }
    private fun probeHealth() {
        val snapshot = synchronized(lock) {
            if (!running || starting || stopping || controller == null) { proxyConnected = false; return }
            Triple(healthGeneration, activePort, launchSerial)
        }
        val connected = ProxyHealth.check(snapshot.second)
        synchronized(lock) {
            if (snapshot.first == healthGeneration && snapshot.third == launchSerial && running && !starting && !stopping) {
                proxyConnected = connected
            }
        }
    }

    private fun profileError(error: Exception): String {
        val safe = error.message?.takeIf { it.matches(Regex("^(profile_[a-z_]+|unknown_profile|invalid_[a-z_]+)$")) }
        return safe ?: SubscriptionFailures.code(error)
    }

    fun groups(): JSONObject {
        val client = controller ?: return JSONObject().put("groups", JSONArray()).put("nodes", JSONArray())
        return client.groups()
    }

    fun delay(node: String): JSONObject {
        require(node.length in 1..512 && node.none { it.code < 32 }) { "invalid_node" }
        return (controller ?: error("proxy_not_running")).delay(node)
    }

    fun select(group: String, node: String): JSONObject = synchronized(lock) {
        invalidateHealth()
        require(group.length in 1..512 && node.length in 1..512) { "invalid_node" }
        (controller ?: error("proxy_not_running")).select(group, node)
        requestHealthProbe()
        status()
    }

    /* Rule lists ("rule" mode rule source, ad block, target group). */

    private fun ruleMode() = prefs.getString("ruleMode", RuleLists.DEFAULT_MODE)?.takeIf { it in RuleLists.MODES } ?: RuleLists.DEFAULT_MODE
    private fun adBlock() = prefs.getBoolean("adBlock", false)
    /** The user's target group for profile [id]; "" = the automatic choice. */
    private fun groupOverride(id: String?): String = id?.let { prefs.getString("ruleGroup:$it", "") }.orEmpty()

    /** The URL sets a rule source needs: its own lists, plus the ad-block list when that is on. */
    private fun ruleSets(mode: String, adBlock: Boolean): List<String> =
        (if (mode == RuleLists.SUBSCRIPTION) emptyList() else listOf(mode)) + (if (adBlock) listOf(RuleLists.ADBLOCK) else emptyList())

    /**
     * The rule setup for a runtime config. Extracts a bundled list on first use (seconds for the
     * large whitelist list on the TV), so it runs where the config is generated, under [lock].
     */
    private fun ruleSetup(mode: String = ruleMode(), adBlock: Boolean = adBlock(), group: String = groupOverride(profiles.activeId),
                          proxyMode: String = mode()): RuleLists.Setup =
        // Global and direct ignore the rule source (MihomoConfig.runtime): nothing to extract then.
        if (proxyMode != "rule") RuleLists.Setup(mode, adBlock, group)
        else RuleLists.Setup(mode, adBlock, group, rules.prepare(ruleSets(mode, adBlock)))

    private fun bundledRuleList(name: String): java.io.InputStream? = openBundledList(name, context.assets::open)

    private class SourceGroups(val key: String, val names: List<String>, val default: String)
    private var groupCache: SourceGroups? = null

    /** The active profile's groups for the picker, parsed again only when its file changes. */
    private fun sourceGroups(): SourceGroups {
        val file = source
        val key = "${file.path}:${file.lastModified()}:${file.length()}"
        groupCache?.takeIf { it.key == key }?.let { return it }
        val parsed = runCatching { MihomoConfig.parse(file.readText()) }.getOrNull()
        return SourceGroups(key, parsed?.let(RuleLists::groups).orEmpty(), parsed?.let(RuleLists::defaultGroup) ?: "GLOBAL")
            .also { groupCache = it }
    }

    /** "Update rules now" from the phone page: every list of the current source, in the background. */
    fun updateRules(): JSONObject {
        check(initialized) { "proxy_not_initialized" }
        requestRulesUpdate(force = true)
        return synchronized(lock) { status() }
    }

    private fun requestRulesUpdate(force: Boolean) {
        rulesPending.incrementAndGet()
        try {
            rulesWorker.execute { try { runRulesJob(force, auto = false) } finally { rulesPending.decrementAndGet() } }
        } catch (e: Exception) { rulesPending.decrementAndGet() }
    }

    /**
     * Downloads the current source's lists ([force]: all of them, else the due ones). The core's
     * local port is the first route while it runs, so lists come through the node where GitHub is
     * blocked; direct is the second. [auto]: the periodic check, only while the core runs in rule mode.
     */
    private fun runRulesJob(force: Boolean, auto: Boolean) {
        runCatching {
            if (auto && rulesPending.get() > 0) return
            val (sets, port) = synchronized(lock) {
                if (!initialized) return
                if (auto && (!running || starting || stopping || mode() != "rule")) return
                ruleSets(ruleMode(), adBlock()) to (if (running && !starting && !stopping) activePort else 0)
            }
            if (sets.isEmpty()) return
            val routes = if (port > 0) listOf(Proxy(Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", port)), Proxy.NO_PROXY)
                else listOf(Proxy.NO_PROXY)
            val changed = sets.flatMap { set ->
                val names = RuleLists.entries(set).map { it.name }.filter { force || rules.due(set, it) }
                if (names.isEmpty()) emptyList() else rules.update(set, names, routes)
            }
            if (changed.isNotEmpty()) applyRuleFiles(changed.toSet())
        }.onFailure { app.orchard.tv.system.DiagLog.i("proxy: rule list update failed (${it.javaClass.simpleName})") }
    }

    /**
     * New list files reach a running core. If the config itself changes (a list that was missing
     * now exists), a reload; otherwise each changed provider re-reads its file, outside [lock]
     * (a large list takes seconds to parse on the TV). The core's own file watcher may already
     * have done it; then the request finds the same hash and does nothing.
     */
    private fun applyRuleFiles(changed: Set<String>) {
        val (client, names) = synchronized(lock) {
            if (!running || starting || stopping || controller == null || mode() != "rule" || !source.isFile) return
            val lists = ruleSetup()
            val content = MihomoConfig.runtime(source.readText(), mode(), allowLan(), activePort, controllerPort, secret, activeDns, activeTun, lists)
            if (runtime.takeIf(File::isFile)?.readText() != content) {
                reload(content)
                requestHealthProbe()
                return
            }
            controller to RuleLists.providers(lists).keys.filter { key -> changed.any { RuleLists.provider(it) == key } }
        }
        names.forEach { name ->
            runCatching { client?.updateRuleProvider(name) }
                .onFailure { app.orchard.tv.system.DiagLog.i("proxy: rule provider refresh failed (${it.javaClass.simpleName})") }
        }
    }

    /** Each URL set's lists and their URLs (public mirror addresses) for the phone page's editor. */
    fun ruleUrls(): JSONObject {
        check(initialized) { "proxy_not_initialized" }
        return JSONObject().put("sets", JSONArray().also { sets ->
            RuleLists.SETS.forEach { set ->
                sets.put(JSONObject().put("set", set).put("lists", JSONArray().also { lists ->
                    RuleLists.entries(set).forEach { entry ->
                        lists.put(JSONObject().put("name", entry.name).put("behavior", entry.behavior.key)
                            .put("urls", JSONArray(rules.urls(set, entry.name))).put("defaults", JSONArray(RuleLists.defaultUrls(entry.name)))
                            .put("custom", rules.custom(set, entry.name)))
                    }
                }))
            }
        })
    }

    /**
     * [form]: set, and either name + urls (newline separated, 1–4 https URLs; empty = defaults) or
     * reset=true (every list of the set, or just name). The edited lists download in the background.
     */
    fun setRuleUrls(form: Map<String, String>): JSONObject {
        check(initialized) { "proxy_not_initialized" }
        val set = form["set"].orEmpty()
        require(set in RuleLists.SETS) { "invalid_rule_set" }
        val name = form["name"].orEmpty()
        when {
            form["reset"] == "true" && name.isEmpty() -> rules.resetSet(set)
            form["reset"] == "true" -> rules.setUrls(set, name, emptyList())
            else -> {
                require(name.isNotEmpty()) { "invalid_rule_list" }
                rules.setUrls(set, name, form["urls"].orEmpty().split('\n'))
            }
        }
        val inUse = synchronized(lock) { set in ruleSets(ruleMode(), adBlock()) }
        if (inUse) requestRulesUpdate(force = false)
        return ruleUrls()
    }

    /** Recent connections through the core, for the phone page's diagnostic view. */
    fun connections(): JSONObject {
        check(initialized) { "proxy_not_initialized" }
        return (controller ?: error("proxy_not_running")).connections(50)
    }

    private fun applySystemProxy() {
        if (prefs.getBoolean("systemProxy", true)) {
            if (!SystemProxy.apply(context, "127.0.0.1", activePort)) lastError = "system_proxy_unavailable"
        } else SystemProxy.restore(context)
    }

    private fun reload(content: String) {
        invalidateHealth()
        validate(content)
        val candidate = File(directory, "candidate.yaml")
        writeAtomic(candidate, content)
        try {
            (controller ?: error("proxy_not_running")).request("PUT", "/configs?force=true", JSONObject().put("path", candidate.path), RELOAD_TIMEOUT_MS)
            writeAtomic(runtime, content)
        } finally { candidate.delete() }
    }

    private fun validate(content: String) {
        require(binary.isFile) { "core_unavailable_for_device" }
        val file = File(directory, "validate.yaml")
        writeAtomic(file, content)
        val check = ProcessBuilder(binary.path, "-t", "-d", directory.path, "-f", file.path)
            .redirectErrorStream(true).redirectOutput(File(directory, "validation.log")).start()
        try {
            if (!waitFor(check, 25)) { kill(check); error("subscription_validation_timeout") }
            if (check.exitValue() != 0) {
                // Classify locally: core diagnostics may contain subscription credentials.
                val detail = File(directory, "validation.log").takeIf { it.isFile }?.inputStream()?.use {
                    val bytes = ByteArray(64 * 1024)
                    val n = it.read(bytes)
                    if (n > 0) String(bytes, 0, n, Charsets.UTF_8) else ""
                }.orEmpty().lowercase()
                val geo = listOf("geoip", "geosite", "mmdb", "country.mmdb").any(detail::contains) &&
                    listOf("download", "no such file", "timeout", "connect", "no route", "resolve").any(detail::contains)
                error(if (geo) "subscription_geo_download_failed" else "subscription_core_rejected")
            }
        } finally { file.delete(); File(directory, "validation.log").delete() }
    }

    private fun download(address: String): String {
        var current = address
        repeat(6) { redirect ->
            val connection = URL(current).openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                connection.connectTimeout = 15000; connection.readTimeout = 20000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "clash.meta/1.19.33")
                connection.setRequestProperty("Accept", "application/yaml,text/yaml,text/plain,*/*")
                when (connection.responseCode) {
                    in 300..399 -> {
                        require(redirect < 5) { "subscription_too_many_redirects" }
                        val next = URL(URL(current), connection.getHeaderField("Location") ?: error("subscription_download_failed"))
                        require(!(URL(current).protocol == "https" && next.protocol != "https")) { "subscription_insecure_redirect" }
                        current = MihomoConfig.validUrl(next.toString())
                    }
                    in 200..299 -> {
                        require((connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L) <= MihomoConfig.MAX_BYTES) { "subscription_too_large" }
                        val bytes = connection.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) { val n = input.read(buffer); if (n < 0) break
                                require(out.size() + n <= MihomoConfig.MAX_BYTES) { "subscription_too_large" }
                                out.write(buffer, 0, n)
                            }
                            out.toByteArray()
                        }
                        return SubscriptionFailures.checkBody(bytes.toString(Charsets.UTF_8).removePrefix("﻿"))
                    }
                    401 -> error("subscription_http_unauthorized")
                    403 -> error("subscription_http_forbidden")
                    404 -> error("subscription_http_not_found")
                    429 -> error("subscription_http_rate_limited")
                    in 500..599 -> error("subscription_http_server_error")
                    else -> error("subscription_download_failed")
                }
            } finally { connection.disconnect() }
        }
        error("subscription_download_failed")
    }

    private fun mode() = prefs.getString("mode", "rule") ?: "rule"
    private fun allowLan() = prefs.getBoolean("allowLan", false)
    private fun alive(p: Process) = runCatching { p.exitValue(); false }.getOrDefault(true)
    private fun waitFor(p: Process, seconds: Long): Boolean {
        if (Build.VERSION.SDK_INT >= 26) return p.waitFor(seconds, TimeUnit.SECONDS)
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (alive(p) && System.nanoTime() < until) Thread.sleep(50)
        return !alive(p)
    }
    private fun kill(p: Process) {
        if (Build.VERSION.SDK_INT >= 26) p.destroyForcibly() else p.destroy()
    }
    private fun writeAtomic(file: File, text: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    private fun safeError(error: Exception, fallback: String): String = error.message
        ?.takeIf { it.matches(Regex("^[a-z][a-z0-9_]{1,80}$")) } ?: fallback
    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().asSequence().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}
