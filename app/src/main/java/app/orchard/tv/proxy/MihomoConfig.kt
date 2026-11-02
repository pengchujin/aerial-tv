package app.orchard.tv.proxy

import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.schema.JsonSchema
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.events.Event
import java.net.URI
import java.util.IdentityHashMap

/** A bounded, data-only Clash configuration. Subscription text is never executed as code. */
internal object MihomoConfig {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_NODES = 100_000
    private const val MAX_DEPTH = 40

    /**
     * Hosts that always go DIRECT, for a household whose router filters them (ads, telemetry,
     * cloud control). Traffic sent through a proxy node leaves the house encrypted, so the router
     * could no longer block these hosts. Each one becomes an exact `DOMAIN,<host>,DIRECT` rule
     * ahead of every subscription or manual rule, in every mode, and DIRECT resolves them with the
     * router's DNS (see [runtime]).
     *
     * Empty as shipped: nothing is special-cased, and global mode is the core's own `mode: global`.
     * A build for a known network fills this list; [runtime] and [shadowsFilteredHost] take the
     * list as a parameter, so tests use their own.
     */
    val ROUTER_FILTERED_HOSTS: List<String> = emptyList()

    private fun directRules(hosts: List<String>) = hosts.map { "DOMAIN,$it,DIRECT" }

    /*
     * TUN. Mihomo puts its TUN address at the first address of dns.fake-ip-range with a /30
     * (config.parseTun: 198.18.0.1/16 -> 198.18.0.1/30) and answers DNS at the next address
     * (listener/sing_tun/server.go: Inet4Address.Addr().Next():53); the fake-IP pool starts at .4
     * (component/fakeip/pool.go). ProxyService's VpnService.Builder uses exactly these values.
     * MTU 9000 is the core's own default (server.go) and CMFA's: gVisor terminates TCP itself, so
     * a large MTU only means fewer packets through the fd; the physical uplink keeps its own MTU.
     */
    const val TUN_FAKE_IP_RANGE = "198.18.0.1/16"
    const val TUN_ADDRESS = "198.18.0.1"
    const val TUN_PREFIX = 30
    const val TUN_DNS = "198.18.0.2"
    const val TUN_MTU = 9000
    /** The tun fd number in the core process (ProxyManager moves it there; fds 0-2 are taken). */
    const val TUN_FD = 3

    /**
     * Hosts that get their real address from the router instead of a fake IP: the always-DIRECT
     * hosts (so the router still sees and answers those lookups), local names and the common
     * connectivity checks.
     */
    fun fakeIpFilter(directHosts: List<String> = ROUTER_FILTERED_HOSTS): List<String> = directHosts + listOf(
        "+.lan", "+.local", "localhost", "+.home.arpa",
        "+.msftconnecttest.com", "+.msftncsi.com",
    )

    fun parse(text: String): MutableMap<String, Any?> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "subscription_too_large" }
        val settings = LoadSettings.builder()
            .setLabel("Clash subscription")
            .setAllowDuplicateKeys(false)
            .setAllowRecursiveKeys(false)
            .setMaxAliasesForCollections(20)
            .setCodePointLimit(MAX_BYTES)
            .setSchema(JsonSchema())
            .build()
        // Bound structure before recursive YAML composition can consume the native stack.
        var nesting = 0
        var events = 0
        Parse(settings).parseString(text).forEach { event ->
            require(++events <= MAX_NODES * 4) { "subscription_too_complex" }
            when (event.eventId) {
                Event.ID.MappingStart, Event.ID.SequenceStart -> {
                    require(++nesting <= MAX_DEPTH) { "subscription_too_complex" }
                }
                Event.ID.MappingEnd, Event.ID.SequenceEnd -> nesting--
                else -> Unit
            }
        }
        val value = Load(settings).loadFromString(text)
        require(value is Map<*, *>) { "subscription_not_clash_yaml" }
        val seen = IdentityHashMap<Any, Boolean>()
        var count = 0
        fun copy(item: Any?, depth: Int): Any? {
            require(depth <= MAX_DEPTH && ++count <= MAX_NODES) { "subscription_too_complex" }
            return when (item) {
                is Map<*, *> -> {
                    require(seen.put(item, true) == null) { "subscription_recursive_alias" }
                    linkedMapOf<String, Any?>().apply {
                        item.forEach { (key, child) ->
                            require(key is String && key.length <= 4096) { "subscription_invalid_key" }
                            put(key, copy(child, depth + 1))
                        }
                    }.also { seen.remove(item) }
                }
                is List<*> -> {
                    require(seen.put(item, true) == null) { "subscription_recursive_alias" }
                    item.map { copy(it, depth + 1) }.also { seen.remove(item) }
                }
                null, is String, is Boolean, is Number -> item
                else -> error("subscription_unsupported_tag")
            }
        }
        @Suppress("UNCHECKED_CAST")
        val result = copy(value, 0) as MutableMap<String, Any?>
        require((result["proxies"] as? List<*>)?.isNotEmpty() == true ||
            (result["proxy-providers"] as? Map<*, *>)?.isNotEmpty() == true ||
            (result["rules"] as? List<*>)?.isNotEmpty() == true) { "subscription_not_clash_yaml" }
        return result
    }

    fun validUrl(value: String): String {
        require(value.length in 1..8192) { "invalid_subscription_url" }
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: error("invalid_subscription_url")
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawFragment == null) { "invalid_subscription_url" }
        return uri.toASCIIString()
    }

    /** Official Mihomo HTTP/SOCKS5 outbound, bridged behind the same local HTTP listener. */
    fun manual(profile: ProxyProfile): String {
        require(profile.kind in setOf("http", "socks5")) { "invalid_profile_kind" }
        val host = profile.host
        require(host.length in 1..253 && host.none { it.isWhitespace() || it.code in setOf(0, 47, 64, 63, 35, 92) } &&
            !host.contains("://") && profile.port in 1..65535) { "invalid_upstream" }
        require(profile.username.length <= 1024 && profile.password.length <= 4096 &&
            (profile.username + profile.password).none { it.code in setOf(0, 10, 13) }) { "invalid_credentials" }
        val proxy = linkedMapOf<String, Any?>("name" to "Manual upstream", "type" to profile.kind,
            "server" to host, "port" to profile.port, "tls" to profile.tls)
        if (profile.username.isNotEmpty()) proxy["username"] = profile.username
        if (profile.password.isNotEmpty()) proxy["password"] = profile.password
        return Dump(DumpSettings.builder().build()).dumpToString(linkedMapOf(
            "proxies" to listOf(proxy),
            "proxy-groups" to listOf(mapOf("name" to "Manual proxy", "type" to "select", "proxies" to listOf("Manual upstream"))),
            "rules" to listOf("MATCH,Manual proxy"),
        ))
    }

    /**
     * [dns] is the router/system resolver list of the TV's current uplink ([SystemDns]); empty keeps
     * the core's DNS off, as before. [tun] adds the TUN inbound on fd [TUN_FD] with Aerial's
     * fake-IP DNS and sniffer; it needs [dns] (the router) and rejects an empty list.
     */
    /*
     * [lists] is the rule source for `mode: rule` (RuleLists): the default keeps the subscription's
     * own rules exactly as before. Global and direct ignore it.
     */
    fun runtime(text: String, mode: String, allowLan: Boolean, mixedPort: Int, controllerPort: Int, secret: String,
                dns: List<String> = emptyList(), tun: Boolean = false,
                lists: RuleLists.Setup = RuleLists.Setup(),
                directHosts: List<String> = ROUTER_FILTERED_HOSTS): String {
        require(mode in setOf("rule", "global", "direct")) { "invalid_mode" }
        val config = parse(text)
        // Never import privileged sockets, scripts, arbitrary listeners, or a subscription's dashboard.
        listOf("external-controller", "external-controller-tls", "external-controller-unix",
            "external-controller-pipe", "external-controller-routing-mark", "external-controller-cors",
            "external-ui", "external-ui-url", "external-ui-name", "external-doh-server",
            "secret", "authentication", "skip-auth-prefixes", "listeners", "tunnels",
            "tun", "iptables", "tuic-server", "script", "scripts", "interface-name",
            "routing-mark", "experimental", "clash-for-android", "tls", "ntp", "dns",
            "profile", "geox-url").forEach(config::remove)
        config["mixed-port"] = mixedPort
        listOf("port", "socks-port", "redir-port", "tproxy-port").forEach { config[it] = 0 }
        config["allow-lan"] = allowLan
        config["bind-address"] = if (allowLan) "0.0.0.0" else "127.0.0.1"
        // Mihomo checks this list on every inbound connection, even with allow-lan off, so loopback
        // must stay in it or the system proxy on 127.0.0.1 gets its CONNECT aborted.
        config["lan-allowed-ips"] = listOf("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
        config.remove("lan-disallowed-ips")
        applyDirectRules(config, mode, lists, directHosts)
        config["log-level"] = "silent"
        config["external-controller"] = "127.0.0.1:$controllerPort"
        config["secret"] = secret
        config["external-controller-cors"] = mapOf("allow-origins" to emptyList<String>(), "allow-private-network" to false)
        if (tun) {
            config["tun"] = tunBlock()
            config["dns"] = fakeIpDnsBlock(dns, directHosts)
            // A subscription's sniffer could skip or re-point hosts; under TUN Aerial's is the only one.
            config["sniffer"] = snifferBlock()
        } else {
            config["tun"] = mapOf("enable" to false)
            config["dns"] = dnsBlock(dns)
        }
        config["ntp"] = mapOf("enable" to false)
        config["profile"] = mapOf("store-selected" to true, "store-fake-ip" to false)
        config["geo-auto-update"] = false
        config["find-process-mode"] = "off"
        sanitizeProviders(config, "proxy-providers", "providers")
        sanitizeProviders(config, "rule-providers", "rules")
        // After the sanitizer: subscription content still may not name a file provider; these are Aerial's.
        if (mode == "rule") addRuleLists(config, lists)
        // Advanced socket/file options from untrusted YAML may not read private app files.
        fun inspect(item: Any?, depth: Int = 0) {
            require(depth <= MAX_DEPTH) { "subscription_too_complex" }
            when (item) {
                is Map<*, *> -> item.forEach { (key, value) ->
                    require(key !in setOf("private-key-path", "certificate",
                        "certificate-path", "ca", "ca-str", "dialer-proxy", "interface-name",
                        "routing-mark", "script", "scripts")) { "subscription_unsupported_option" }
                    if (key == "type") require(value !in setOf("ssh", "script")) { "subscription_unsupported_option" }
                    inspect(value, depth + 1)
                }
                is List<*> -> item.forEach { inspect(it, depth + 1) }
            }
        }
        inspect(config)
        return Dump(DumpSettings.builder().setDefaultFlowStyle(org.snakeyaml.engine.v2.common.FlowStyle.BLOCK).build()).dumpToString(config)
    }

    /**
     * Puts the [directHosts] rules first, whatever the subscription says. Mihomo stops at the first
     * matching rule (tunnel.match), and DIRECT is a built-in a subscription cannot rename or
     * redefine (config.parseProxies rejects the duplicate name), so a later `DOMAIN,...,PROXY` or a
     * leading `MATCH` cannot win over them.
     *
     * With [directHosts] empty (the default) none of this applies: the subscription's rules (or
     * Aerial's rule lists) are used as they are and global mode is the core's own `mode: global`.
     *
     * With hosts listed, `mode: global` would skip rules altogether (tunnel.resolveMetadata) and
     * proxy these hosts, so global runs as `mode: rule` with the DIRECT rules and then
     * `MATCH,GLOBAL`: the same built-in GLOBAL selector the controller lists and switches, so node
     * selection keeps working. The trailing `MATCH,REJECT` keeps native global's behaviour for UDP
     * through a node without UDP (fails instead of falling through to DIRECT). Status keeps
     * reporting "global".
     */
    private fun applyDirectRules(config: MutableMap<String, Any?>, mode: String, lists: RuleLists.Setup,
                                 directHosts: List<String>) {
        val rules = config["rules"]
        require(rules == null || rules is List<*>) { "subscription_not_clash_yaml" }
        val head = directRules(directHosts)
        val emulateGlobal = mode == "global" && directHosts.isNotEmpty()
        config["mode"] = if (emulateGlobal) "rule" else mode
        val listMode = mode == "rule" && lists.mode != RuleLists.SUBSCRIPTION
        val tail: List<Any?> = when {
            emulateGlobal -> listOf("MATCH,GLOBAL", "MATCH,REJECT")
            // Blacklist/whitelist replace the subscription's rules, rule-providers and sub-rules;
            // its proxies, proxy-groups and proxy-providers stay. The target group must exist.
            listMode -> {
                config.remove("rule-providers"); config.remove("sub-rules")
                RuleLists.rules(lists, RuleLists.targetGroup(config, lists.group))
            }
            // Subscription rules: as before, with the ad-block list ahead of them when it is on.
            mode == "rule" && lists.adBlock && RuleLists.REJECT.name in lists.lists ->
                listOf("RULE-SET,${RuleLists.provider(RuleLists.REJECT.name)},REJECT") + (rules as List<*>?).orEmpty()
            else -> (rules as List<*>?).orEmpty()
        }
        config["rules"] = head + tail
        if (head.isEmpty()) return
        // A SUB-RULE list is matched on its own; it gets the same head.
        val subRules = config["sub-rules"]
        if (subRules != null) {
            require(subRules is Map<*, *>) { "subscription_not_clash_yaml" }
            config["sub-rules"] = linkedMapOf<String, Any?>().apply {
                subRules.forEach { (name, list) ->
                    require(name is String && list is List<*>) { "subscription_not_clash_yaml" }
                    put(name, head + list)
                }
            }
        }
        // `hosts` is applied before rules (tunnel.preHandleMetadata) and could rename or re-point
        // these hosts, so a subscription entry that could match one of them is dropped.
        val hosts = config["hosts"]
        if (hosts != null) {
            require(hosts is Map<*, *>) { "subscription_not_clash_yaml" }
            config["hosts"] = hosts.filterKeys { !(it is String && shadowsFilteredHost(it, directHosts)) }
        }
    }

    /**
     * Aerial's rule lists as `type: file`, `format: text` providers on its own files (RuleStore).
     * Only the lists the rules above reference: in subscription mode just the ad-block list.
     */
    private fun addRuleLists(config: MutableMap<String, Any?>, lists: RuleLists.Setup) {
        val ours = RuleLists.providers(lists)
        if (ours.isEmpty()) return
        val existing = config["rule-providers"] as? Map<*, *>
        config["rule-providers"] = linkedMapOf<String, Any?>().apply {
            existing?.forEach { (k, v) -> if (k is String && k !in ours) put(k, v) }
            putAll(ours)
        }
    }

    /** Conservative: any wildcard pattern whose literal tail fits one of [directHosts] counts as a match. */
    internal fun shadowsFilteredHost(pattern: String, directHosts: List<String> = ROUTER_FILTERED_HOSTS): Boolean {
        if (directHosts.isEmpty()) return false
        val key = pattern.trim().lowercase().trimEnd('.')
        if (key.isEmpty()) return false
        val wild = key.contains('*') || key.startsWith("+.") || key.startsWith(".")
        if (!wild) return key in directHosts
        val tail = key.substringAfterLast('*').removePrefix("+").trimStart('.')
        return tail.isEmpty() || directHosts.any { it == tail || it.endsWith(".$tail") }
    }

    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    private val IPV6 = Regex("^[0-9a-fA-F:.]{2,45}$")

    /**
     * The DNS block is Aerial's, never the subscription's (its `dns` is removed above). With DNS off,
     * the bundled core (the official statically linked linux-armv7 build, run on Android) resolves
     * DIRECT hosts with its own fallback: /etc/resolv.conf, which Android does not keep, then
     * 114.114.114.114 and 8.8.8.8 (dns/system.go, system_posix.go; Android's real resolver is only
     * wired in CMFA builds, dns/patch_android.go). That would ask public resolvers for the
     * hosts the router filters, so when the uplink's resolvers are known, every resolver role
     * (nameserver, default, proxy-server, direct) is set to exactly them: plain DNS to the router,
     * as Android itself would ask. No listener, no fake IPs, no third-party server.
     */
    private fun dnsBlock(servers: List<String>): Map<String, Any?> {
        if (servers.isEmpty()) return mapOf("enable" to false)
        checkServers(servers)
        return linkedMapOf("enable" to true, "ipv6" to true, "enhanced-mode" to "normal",
            "use-hosts" to true, "respect-rules" to false,
            // Separate copies: one shared list would be dumped as YAML anchors and aliases.
            "default-nameserver" to servers.toList(), "nameserver" to servers.toList(),
            "proxy-server-nameserver" to servers.toList(), "direct-nameserver" to servers.toList(),
            "direct-nameserver-follow-policy" to false)
    }

    private fun checkServers(servers: List<String>) {
        require(servers.size <= 4 && servers.all { (IPV4.matches(it) || (it.contains(':') && IPV6.matches(it))) }) { "invalid_dns_server" }
    }

    /**
     * The fd comes from Android's VpnService (ProxyService), so the core must not create a device,
     * routes or rules (sing-tun tun_linux.go New: FileDescriptor > 0 skips configure) and must not
     * watch interfaces through netlink. Every DNS query the tunnel carries (port 53, any address)
     * goes to the core's resolver.
     */
    private fun tunBlock(): Map<String, Any?> = linkedMapOf(
        "enable" to true, "stack" to "gvisor", "file-descriptor" to TUN_FD,
        "mtu" to TUN_MTU, "auto-route" to false, "auto-detect-interface" to false,
        "auto-redirect" to false, "strict-route" to false, "dns-hijack" to listOf("any:53"),
    )

    /**
     * Fake-IP mode: apps in the tunnel get an address from [TUN_FAKE_IP_RANGE] and the core
     * resolves the real host on the node (proxied) or at the router (DIRECT), so a poisoned local
     * answer no longer decides where a proxied app connects. [fakeIpFilter] hosts get the real
     * answer from the router. IPv6 answers are off: the VPN has no IPv6 route, Android blocks IPv6
     * for apps in it, and an empty AAAA makes them use IPv4 at once.
     */
    private fun fakeIpDnsBlock(servers: List<String>, directHosts: List<String>): Map<String, Any?> {
        require(servers.isNotEmpty()) { "tun_dns_unavailable" }
        checkServers(servers)
        return linkedMapOf("enable" to true, "ipv6" to false, "enhanced-mode" to "fake-ip",
            "fake-ip-range" to TUN_FAKE_IP_RANGE, "fake-ip-filter-mode" to "blacklist",
            "fake-ip-filter" to fakeIpFilter(directHosts),
            "use-hosts" to true, "use-system-hosts" to true, "respect-rules" to false,
            "default-nameserver" to servers.toList(), "nameserver" to servers.toList(),
            "proxy-server-nameserver" to servers.toList(), "direct-nameserver" to servers.toList(),
            "direct-nameserver-follow-policy" to false)
    }

    /**
     * Recovers the host name of connections that reached the tunnel by IP (an app with its own
     * resolver or a cached answer), so the DOMAIN rules, the always-DIRECT ones included, still
     * match. override-destination false: the core still connects to the address the app chose.
     */
    private fun snifferBlock(): Map<String, Any?> = linkedMapOf(
        "enable" to true, "force-dns-mapping" to true, "parse-pure-ip" to true,
        "override-destination" to false,
        "sniff" to linkedMapOf(
            "HTTP" to linkedMapOf("ports" to listOf("80", "8080-8880")),
            "TLS" to linkedMapOf("ports" to listOf("443", "8443")),
            "QUIC" to linkedMapOf("ports" to listOf("443", "8443")),
        ),
    )

    private fun sanitizeProviders(config: MutableMap<String, Any?>, key: String, folder: String) {
        val providers = config[key] as? Map<*, *> ?: return
        val safe = linkedMapOf<String, Any?>()
        providers.entries.forEachIndexed { index, entry ->
            val name = entry.key as? String ?: error("subscription_invalid_provider")
            val raw = entry.value as? Map<*, *> ?: error("subscription_invalid_provider")
            require(raw["type"] == "http" || raw["type"] == "inline") { "subscription_file_provider_not_allowed" }
            val copy = linkedMapOf<String, Any?>()
            raw.forEach { (k, v) -> if (k is String) copy[k] = v }
            if (copy["type"] == "http") {
                copy["url"] = validUrl(copy["url"] as? String ?: error("subscription_invalid_provider"))
                copy["path"] = "./$folder/provider-$index.yaml"
                // Provider-specific credentials stay in this private configuration.
                val hc = copy["health-check"] as? Map<*, *>
                if (hc != null) copy["health-check"] = hc.filterKeys { it is String }.toMutableMap().apply {
                    (get("url") as? String)?.let { put("url", validUrl(it)) }
                }
            }
            safe[name] = copy
        }
        config[key] = safe
    }
}
