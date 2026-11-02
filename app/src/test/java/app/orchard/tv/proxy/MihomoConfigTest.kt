package app.orchard.tv.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MihomoConfigTest {
    // manual()

    @Test fun manualHttpWithoutCredentialsIsNativeOutboundSelectGroupAndMatchRule() {
        val config = manual(ProxyProfile("m1", "Office", "http", host = "proxy.lan", port = 3128))
        val proxy = (config["proxies"] as List<*>).single() as Map<*, *>
        assertEquals("Manual upstream", proxy["name"])
        assertEquals("http", proxy["type"])
        assertEquals("proxy.lan", proxy["server"])
        assertEquals(3128, (proxy["port"] as Number).toInt())
        assertEquals(false, proxy["tls"])
        assertFalse(proxy.containsKey("username"))
        assertFalse(proxy.containsKey("password"))
        assertManualGroupAndRule(config)
    }

    @Test fun manualSocks5WithCredentialsAndTlsKeepsSecretsExact() {
        val user = "user: #1"
        val pass = "p@ss'w\"o:rd #{x} & *y"
        val config = manual(ProxyProfile("m2", "Home", "socks5", host = "10.1.2.3", port = 1080,
            username = user, password = pass, tls = true))
        val proxy = (config["proxies"] as List<*>).single() as Map<*, *>
        assertEquals("socks5", proxy["type"])
        assertEquals("10.1.2.3", proxy["server"])
        assertEquals(1080, (proxy["port"] as Number).toInt())
        assertEquals(true, proxy["tls"])
        assertEquals(user, proxy["username"])
        assertEquals(pass, proxy["password"])
        assertManualGroupAndRule(config)
    }

    @Test fun manualHttpWithCredentialsAndTls() {
        val proxy = (manual(ProxyProfile("m3", "TLS", "http", host = "secure.example.invalid", port = 443,
            username = "alice", password = "secret", tls = true))["proxies"] as List<*>).single() as Map<*, *>
        assertEquals("http", proxy["type"])
        assertEquals(true, proxy["tls"])
        assertEquals("alice", proxy["username"])
        assertEquals("secret", proxy["password"])
    }

    @Test fun manualSocks5WithoutCredentialsOrTls() {
        val proxy = (manual(ProxyProfile("m4", "Plain", "socks5", host = "socks.lan", port = 1080))["proxies"] as List<*>)
            .single() as Map<*, *>
        assertEquals("socks5", proxy["type"])
        assertEquals(false, proxy["tls"])
        assertFalse(proxy.containsKey("username"))
        assertFalse(proxy.containsKey("password"))
    }

    @Test fun manualOutputSurvivesTheRuntimeRewrite() {
        val text = MihomoConfig.manual(ProxyProfile("m5", "Home", "socks5", host = "socks.lan", port = 1080,
            username = "bob", password = "pw"))
        val config = MihomoConfig.parse(MihomoConfig.runtime(text, "rule", false, 7890, 19090, SECRET))
        val proxy = (config["proxies"] as List<*>).single() as Map<*, *>
        assertEquals("socks5", proxy["type"])
        assertEquals("bob", proxy["username"])
        // No always-DIRECT hosts as shipped: the manual MATCH is the only rule.
        assertManualGroupAndRule(config)
        assertEquals("127.0.0.1:19090", config["external-controller"])
    }

    @Test fun manualRejectsInvalidUpstream() {
        listOf("", " proxy.lan", "proxy lan", "proxy.lan/x", "user@proxy.lan", "proxy.lan?x", "proxy.lan#x",
            "proxy\\lan", "http://proxy.lan", "a".repeat(254), "proxy\u0000.lan").forEach { host ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                MihomoConfig.manual(ProxyProfile("m", "M", "http", host = host, port = 8080))
            }
            assertEquals(host, "invalid_upstream", error.message)
        }
        listOf(0, -1, 65536).forEach { port ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                MihomoConfig.manual(ProxyProfile("m", "M", "socks5", host = "proxy.lan", port = port))
            }
            assertEquals("invalid_upstream", error.message)
        }
    }

    @Test fun manualRejectsCredentialsThatCouldBreakTheConfig() {
        listOf(ProxyProfile("m", "M", "http", host = "proxy.lan", port = 8080, username = "a\nb"),
            ProxyProfile("m", "M", "http", host = "proxy.lan", port = 8080, password = "a\rb"),
            ProxyProfile("m", "M", "http", host = "proxy.lan", port = 8080, password = "a\u0000b"),
            ProxyProfile("m", "M", "http", host = "proxy.lan", port = 8080, username = "u".repeat(1025)),
            ProxyProfile("m", "M", "http", host = "proxy.lan", port = 8080, password = "p".repeat(4097)),
        ).forEach { profile ->
            val error = assertThrows(IllegalArgumentException::class.java) { MihomoConfig.manual(profile) }
            assertEquals("invalid_credentials", error.message)
        }
    }

    @Test fun manualRejectsSubscriptionKind() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            MihomoConfig.manual(ProxyProfile("s", "S", "subscription", url = "https://sub.example.invalid/x"))
        }
        assertEquals("invalid_profile_kind", error.message)
    }

    // runtime(): forced settings

    @Test fun runtimeForcesLoopbackControllerWithSecretAndTunOff() {
        val config = runtime(SUBSCRIPTION, allowLan = true)
        assertEquals("127.0.0.1:19090", config["external-controller"])
        assertEquals(SECRET, config["secret"])
        assertEquals(mapOf("allow-origins" to emptyList<String>(), "allow-private-network" to false),
            config["external-controller-cors"])
        assertEquals(mapOf("enable" to false), config["tun"])
        assertEquals(mapOf("enable" to false), config["dns"])
        listOf("external-controller-tls", "external-controller-unix", "external-ui", "authentication",
            "listeners", "tunnels", "script", "interface-name", "routing-mark", "geox-url").forEach {
            assertFalse(it, config.containsKey(it))
        }
        assertEquals("rule", config["mode"])
        assertEquals("silent", config["log-level"])
        assertEquals(false, config["geo-auto-update"])
    }

    @Test fun runtimeForcesListenerPorts() {
        val config = runtime(SUBSCRIPTION, allowLan = false)
        assertEquals(7890, (config["mixed-port"] as Number).toInt())
        listOf("port", "socks-port", "redir-port", "tproxy-port").forEach {
            assertEquals(it, 0, (config[it] as Number).toInt())
        }
    }

    @Test fun lanSharingOnAllowsLoopbackAndRfc1918Only() {
        val config = runtime(SUBSCRIPTION, allowLan = true)
        assertEquals(true, config["allow-lan"])
        assertEquals("0.0.0.0", config["bind-address"])
        val allowed = config["lan-allowed-ips"] as List<*>
        assertTrue(allowed.containsAll(listOf("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")))
        assertTrue(allowed.contains("::1/128"))
        assertFalse(allowed.contains("0.0.0.0/0"))
        assertFalse(allowed.contains("::/0"))
        assertFalse(config.containsKey("lan-disallowed-ips"))
    }

    @Test fun lanSharingOffStillAllowsLoopbackForTheSystemProxy() {
        // Regression: Mihomo applies lan-allowed-ips to 127.0.0.1 too; without it the CONNECT was aborted.
        val config = runtime(SUBSCRIPTION, allowLan = false)
        assertEquals(false, config["allow-lan"])
        assertEquals("127.0.0.1", config["bind-address"])
        val allowed = config["lan-allowed-ips"] as List<*>
        assertTrue(allowed.contains("127.0.0.0/8"))
        assertTrue(allowed.contains("::1/128"))
        assertFalse(config.containsKey("lan-disallowed-ips"))
    }

    @Test fun subscriptionCannotBlockLoopbackOrOpenTheController() {
        val hostile = """
            |external-controller: 0.0.0.0:9090
            |lan-allowed-ips: [0.0.0.0/0]
            |lan-disallowed-ips: [127.0.0.0/8, "::1/128"]
            |tun: {enable: true, stack: system, auto-route: true}
            |proxies:
            |  - {name: a, type: ss, server: a.example.invalid, port: 443, cipher: aes-128-gcm, password: pw}
            |""".trimMargin()
        listOf(true, false).forEach { lan ->
            val config = runtime(hostile, allowLan = lan)
            assertEquals("127.0.0.1:19090", config["external-controller"])
            assertEquals(mapOf("enable" to false), config["tun"])
            assertFalse(config.containsKey("lan-disallowed-ips"))
            val allowed = config["lan-allowed-ips"] as List<*>
            assertFalse(allowed.contains("0.0.0.0/0"))
            assertTrue(allowed.contains("127.0.0.0/8"))
        }
    }

    // runtime(): always-DIRECT hosts (a list a build may fill; empty as shipped)

    @Test fun alwaysDirectHostListIsEmptyAsShipped() {
        assertEquals(emptyList<String>(), MihomoConfig.ROUTER_FILTERED_HOSTS)
        assertEquals(listOf("+.lan", "+.local", "localhost", "+.home.arpa",
            "+.msftconnecttest.com", "+.msftncsi.com"), MihomoConfig.fakeIpFilter())
    }

    @Test fun withoutHostsNothingIsAddedToTheRulesInAnyMode() {
        listOf("rule", "direct").forEach { mode ->
            val config = MihomoConfig.parse(MihomoConfig.runtime(SUBSCRIPTION, mode, false, 7890, 19090, SECRET))
            assertEquals(mode, config["mode"])
            assertEquals(mode, listOf("MATCH,节点选择"), rules(config))
        }
    }

    @Test fun withoutHostsGlobalIsTheCoresOwnGlobalMode() {
        val config = MihomoConfig.parse(MihomoConfig.runtime(SUBSCRIPTION, "global", false, 7890, 19090, SECRET))
        // No emulation: mode stays global, and no MATCH,GLOBAL / MATCH,REJECT tail is written.
        assertEquals("global", config["mode"])
        assertEquals(listOf("MATCH,节点选择"), rules(config))
    }

    @Test fun withoutHostsSubRulesAndHostsEntriesAreLeftAlone() {
        val config = MihomoConfig.parse(MihomoConfig.runtime(HOSTILE, "rule", false, 7890, 19090, SECRET))
        assertEquals("MATCH,PROXY", ((config["sub-rules"] as Map<*, *>)["sub"] as List<*>).single())
        assertEquals(5, (config["hosts"] as Map<*, *>).size)
        assertFalse(MihomoConfig.shadowsFilteredHost("*"))
        assertFalse(MihomoConfig.shadowsFilteredHost("+.com"))
    }

    @Test fun ruleModePutsDirectRulesBeforeTheSubscriptionRules() {
        val config = runtime(SUBSCRIPTION, "rule")
        assertEquals("rule", config["mode"])
        assertEquals(DIRECT_RULES + "MATCH,节点选择", rules(config))
    }

    @Test fun globalModeIsEmulatedWithDirectRulesThenTheBuiltInGlobalSelector() {
        val config = runtime(SUBSCRIPTION, "global")
        // mode: global would skip every rule, the DIRECT ones included.
        assertEquals("rule", config["mode"])
        assertEquals(DIRECT_RULES + listOf("MATCH,GLOBAL", "MATCH,REJECT"), rules(config))
    }

    @Test fun directModeStaysDirect() {
        val config = runtime(SUBSCRIPTION, "direct")
        assertEquals("direct", config["mode"])
        assertDirectRulesFirst(config)
    }

    @Test fun manualHttpAndSocks5ProfilesGetTheDirectRulesInEveryMode() {
        listOf(ProxyProfile("h", "Office", "http", host = "proxy.lan", port = 3128),
            ProxyProfile("s", "Home", "socks5", host = "10.1.2.3", port = 1080, username = "u", password = "p")).forEach { profile ->
            val text = MihomoConfig.manual(profile)
            assertEquals(DIRECT_RULES + "MATCH,Manual proxy", rules(runtime(text, "rule")))
            assertEquals(DIRECT_RULES + listOf("MATCH,GLOBAL", "MATCH,REJECT"), rules(runtime(text, "global")))
            assertDirectRulesFirst(runtime(text, "direct"))
        }
    }

    @Test fun hostileSubscriptionCannotProxyTheFilteredHosts() {
        listOf("rule", "global", "direct").forEach { mode ->
            val config = runtime(HOSTILE, mode)
            assertDirectRulesFirst(config)
            // Mihomo uses the first match: no rule after these can claim the hosts.
            val list = rules(config)
            assertEquals(mode, HOSTS.size, list.indexOfFirst { !it.endsWith(",DIRECT") || !it.startsWith("DOMAIN,") })
        }
        val rule = rules(runtime(HOSTILE, "rule"))
        assertEquals(DIRECT_RULES.size, rule.indexOf("MATCH,PROXY"))
        assertTrue(rule.contains("DOMAIN,ads.vendor.test,PROXY"))
    }

    @Test fun subRuleListsAlsoStartWithTheDirectRules() {
        val sub = (runtime(HOSTILE, "rule")["sub-rules"] as Map<*, *>)["sub"] as List<*>
        assertEquals(DIRECT_RULES + "MATCH,PROXY", sub)
    }

    @Test fun subscriptionHostsCannotRemapTheFilteredHosts() {
        val hosts = runtime(HOSTILE, "rule")["hosts"] as Map<*, *>
        assertEquals(mapOf("router.lan" to "192.168.1.1"), hosts)
    }

    @Test fun subscriptionWithoutRulesStillGetsTheDirectRules() {
        val yaml = "proxies:\n  - {name: a, type: ss, server: a.example.invalid, port: 443, cipher: aes-128-gcm, password: pw}\n"
        assertEquals(DIRECT_RULES, rules(runtime(yaml, "rule")))
    }

    @Test fun malformedRulesSectionsAreRejectedNotDropped() {
        listOf("rules: {a: b}\nproxies: [{name: a, type: ss, server: s, port: 1, cipher: aes-128-gcm, password: p}]\n",
            "$SUBSCRIPTION" + "sub-rules: [MATCH]\n",
            "$SUBSCRIPTION" + "hosts: [a]\n").forEach { yaml ->
            val error = assertThrows(IllegalArgumentException::class.java) { runtime(yaml, "rule") }
            assertEquals(yaml, "subscription_not_clash_yaml", error.message)
        }
    }

    @Test fun unknownModeIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) { runtime(SUBSCRIPTION, "script") }
        assertEquals("invalid_mode", error.message)
    }

    @Test fun hostPatternsThatCouldMatchADirectHostAreDetected() {
        listOf("ads.vendor.test", "ADS.VENDOR.TEST.", "+.vendor.test", "*.ads.vendor.test", ".test",
            "*.*.cloud.test", "+.metrics.test", "*", "+.test").forEach { assertTrue(it, MihomoConfig.shadowsFilteredHost(it, HOSTS)) }
        listOf("vendor.test", "www.vendor.test", "ad.vendor.test", "+.example.com", "router.lan", "*.lan", "")
            .forEach { assertFalse(it, MihomoConfig.shadowsFilteredHost(it, HOSTS)) }
    }

    // runtime(): DNS owned by Aerial, resolved by the router

    @Test fun routerResolversBecomeEveryResolverRole() {
        listOf("rule", "global", "direct").forEach { mode ->
            val dns = runtime(SUBSCRIPTION, mode, listOf("192.168.1.1", "fd00::1"))["dns"] as Map<*, *>
            assertEquals(true, dns["enable"])
            assertEquals("normal", dns["enhanced-mode"])
            listOf("nameserver", "default-nameserver", "proxy-server-nameserver", "direct-nameserver").forEach {
                assertEquals(it, listOf("192.168.1.1", "fd00::1"), dns[it])
            }
            assertEquals(false, dns["respect-rules"])
            // No listener, fake IPs, fallback or policy of any kind.
            listOf("listen", "fallback", "nameserver-policy", "fake-ip-range", "fake-ip-filter").forEach {
                assertFalse(it, dns.containsKey(it))
            }
        }
    }

    @Test fun subscriptionDnsIsNeverKept() {
        val yaml = SUBSCRIPTION.replace("dns: {enable: true, listen: 0.0.0.0:53}",
            "dns: {enable: true, listen: 0.0.0.0:53, nameserver: [\"https://dns.example.invalid/dns-query\"], nameserver-policy: {\"+.vendor.test\": \"8.8.8.8\"}}")
        assertEquals(mapOf("enable" to false), runtime(yaml, "rule")["dns"])
        val dns = runtime(yaml, "rule", listOf("192.168.31.1"))["dns"] as Map<*, *>
        assertEquals(listOf("192.168.31.1"), dns["nameserver"])
        assertFalse(dns.containsKey("nameserver-policy"))
        assertFalse(dns.containsKey("listen"))
    }

    @Test fun resolverListMustBeIpLiterals() {
        listOf(listOf("dns.example.invalid"), listOf("https://1.1.1.1/dns-query"), listOf("192.168.1.256"),
            listOf("cafe"), listOf("1.1.1.1", "1.1.1.2", "1.1.1.3", "1.1.1.4", "1.1.1.5")).forEach { servers ->
            val error = assertThrows(IllegalArgumentException::class.java) { runtime(SUBSCRIPTION, "rule", servers) }
            assertEquals(servers.toString(), "invalid_dns_server", error.message)
        }
    }

    // runtime(): TUN

    @Test fun tunUsesTheVpnServiceFdWithoutRoutesOrInterfaceMonitoring() {
        listOf("rule", "global", "direct").forEach { mode ->
            val tun = tunRuntime(SUBSCRIPTION, mode)["tun"] as Map<*, *>
            assertEquals(true, tun["enable"])
            assertEquals("gvisor", tun["stack"])
            assertEquals(3, (tun["file-descriptor"] as Number).toInt())
            assertEquals(MihomoConfig.TUN_MTU, (tun["mtu"] as Number).toInt())
            assertEquals(false, tun["auto-route"])
            assertEquals(false, tun["auto-detect-interface"])
            assertEquals(false, tun["auto-redirect"])
            assertEquals(false, tun["strict-route"])
            assertEquals(listOf("any:53"), tun["dns-hijack"])
            // Nothing that would make the core touch routes, packages or other interfaces.
            listOf("device", "include-package", "exclude-package", "include-uid", "route-address", "inet6-address")
                .forEach { assertFalse(it, tun.containsKey(it)) }
        }
    }

    @Test fun tunDnsIsFakeIpOverTheRouterWithIpv6Off() {
        val dns = tunRuntime(SUBSCRIPTION, "rule", listOf("192.168.1.1"))["dns"] as Map<*, *>
        assertEquals(true, dns["enable"])
        assertEquals("fake-ip", dns["enhanced-mode"])
        assertEquals("198.18.0.1/16", dns["fake-ip-range"])
        assertEquals(false, dns["ipv6"])
        assertEquals(false, dns["respect-rules"])
        listOf("nameserver", "default-nameserver", "proxy-server-nameserver", "direct-nameserver").forEach {
            assertEquals(it, listOf("192.168.1.1"), dns[it])
        }
        listOf("listen", "fallback", "nameserver-policy", "fallback-filter").forEach { assertFalse(it, dns.containsKey(it)) }
        val filter = dns["fake-ip-filter"] as List<*>
        assertTrue(filter.containsAll(HOSTS))
        assertTrue(filter.containsAll(listOf("+.lan", "+.local", "localhost", "+.home.arpa",
            "+.msftconnecttest.com", "+.msftncsi.com")))
        assertEquals("blacklist", dns["fake-ip-filter-mode"])
    }

    @Test fun tunAddressAndDnsMatchWhatTheCoreDerivesFromTheFakeIpRange() {
        // config.parseTun: first address of fake-ip-range with /30; sing_tun: DNS at the next address.
        assertEquals("198.18.0.1/16", MihomoConfig.TUN_FAKE_IP_RANGE)
        assertEquals(MihomoConfig.TUN_FAKE_IP_RANGE.substringBefore('/'), MihomoConfig.TUN_ADDRESS)
        assertEquals(30, MihomoConfig.TUN_PREFIX)
        assertEquals("198.18.0.2", MihomoConfig.TUN_DNS)
        assertEquals(3, MihomoConfig.TUN_FD)
    }

    @Test fun tunReplacesTheSubscriptionSniffer() {
        val sniffer = tunRuntime(HOSTILE_TUN, "rule")["sniffer"] as Map<*, *>
        assertEquals(true, sniffer["enable"])
        assertEquals(true, sniffer["parse-pure-ip"])
        assertEquals(true, sniffer["force-dns-mapping"])
        assertEquals(false, sniffer["override-destination"])
        listOf("skip-domain", "force-domain", "skip-dst-address", "skip-src-address", "sniffing", "port-whitelist")
            .forEach { assertFalse(it, sniffer.containsKey(it)) }
        assertEquals(setOf("HTTP", "TLS", "QUIC"), (sniffer["sniff"] as Map<*, *>).keys)
    }

    @Test fun hostileSubscriptionTunDnsAndSnifferAreIgnoredUnderTun() {
        val config = tunRuntime(HOSTILE_TUN, "rule")
        val tun = config["tun"] as Map<*, *>
        assertEquals("gvisor", tun["stack"])
        assertEquals(3, (tun["file-descriptor"] as Number).toInt())
        assertEquals(false, tun["auto-route"])
        assertFalse(tun.containsKey("device"))
        assertFalse(tun.containsKey("include-package"))
        val dns = config["dns"] as Map<*, *>
        assertEquals(listOf("192.168.1.1"), dns["nameserver"])
        assertFalse(dns.containsKey("nameserver-policy"))
        assertFalse(dns.containsKey("listen"))
        assertFalse((dns["fake-ip-filter"] as List<*>).contains("*"))
        assertEquals("198.18.0.1/16", dns["fake-ip-range"])
        assertEquals(DIRECT_RULES, rules(config).take(DIRECT_RULES.size))
        assertEquals(false, (config["profile"] as Map<*, *>)["store-fake-ip"])
        assertEquals("off", config["find-process-mode"])
        assertEquals("127.0.0.1:19090", config["external-controller"])
        assertEquals(SECRET, config["secret"])
    }

    @Test fun tunWithoutRouterResolversIsRefused() {
        val error = assertThrows(IllegalArgumentException::class.java) { tunRuntime(SUBSCRIPTION, "rule", emptyList()) }
        assertEquals("tun_dns_unavailable", error.message)
        assertEquals("invalid_dns_server", assertThrows(IllegalArgumentException::class.java) {
            tunRuntime(SUBSCRIPTION, "rule", listOf("dns.example.invalid"))
        }.message)
    }

    @Test fun withoutTunTheOutputIsUnchanged() {
        listOf(emptyList<String>(), listOf("192.168.1.1")).forEach { dns ->
            val plain = MihomoConfig.runtime(HOSTILE_TUN, "rule", false, 7890, 19090, SECRET, dns)
            assertEquals(plain, MihomoConfig.runtime(HOSTILE_TUN, "rule", false, 7890, 19090, SECRET, dns, tun = false))
            val config = MihomoConfig.parse(plain)
            assertEquals(mapOf("enable" to false), config["tun"])
            // Without TUN the subscription's sniffer is left as it was (it only sees the mixed port).
            assertEquals(listOf("+.example.com"), (config["sniffer"] as Map<*, *>)["skip-domain"])
            if (dns.isNotEmpty()) assertEquals("normal", (config["dns"] as Map<*, *>)["enhanced-mode"])
        }
    }

    // runtime(): rejected options

    @Test fun nestedScriptsAndPrivateFilePathsAreRejected() {
        listOf(
            "proxies:\n  - {name: a, type: ss, server: s, port: 1, cipher: aes-128-gcm, password: p, script: x}\n",
            "proxies: [{name: a, type: ss, server: s, port: 1}]\nproxy-groups:\n  - {name: g, type: select, proxies: [a], scripts: [x]}\n",
            "proxies:\n  - {name: a, type: ssh, server: s, port: 22, username: u}\n",
            "proxies:\n  - {name: a, type: script, server: s, port: 1}\n",
            "proxies:\n  - {name: a, type: ssh, server: s, port: 22, private-key-path: /data/data/app.orchard.tv/files/x}\n",
            "proxies:\n  - {name: a, type: trojan, server: s, port: 1, password: p, certificate: /data/data/app.orchard.tv/shared_prefs/proxy.xml}\n",
            "proxies:\n  - {name: a, type: trojan, server: s, port: 1, password: p, ca: ../profiles.json}\n",
            "proxies:\n  - {name: a, type: ss, server: s, port: 1, cipher: aes-128-gcm, password: p, dialer-proxy: b}\n",
            "proxies:\n  - {name: a, type: ss, server: s, port: 1, cipher: aes-128-gcm, password: p, interface-name: wlan0}\n",
        ).forEach { yaml ->
            val error = assertThrows(IllegalArgumentException::class.java) { runtime(yaml, allowLan = false) }
            assertEquals(yaml, "subscription_unsupported_option", error.message)
        }
    }

    @Test fun topLevelScriptIsDroppedNotExecuted() {
        val config = runtime("script:\n  code: |\n    def main(ctx, metadata): return 'DIRECT'\n$SUBSCRIPTION", allowLan = false)
        assertFalse(config.containsKey("script"))
        assertFalse(config.containsKey("scripts"))
    }

    @Test fun fileProvidersAreRejected() {
        listOf("proxy-providers", "rule-providers").forEach { key ->
            val yaml = "$SUBSCRIPTION$key:\n  local: {type: file, path: /data/data/app.orchard.tv/files/proxy/profiles.json}\n"
            val error = assertThrows(IllegalArgumentException::class.java) { runtime(yaml, allowLan = false) }
            assertEquals(key, "subscription_file_provider_not_allowed", error.message)
        }
    }

    @Test fun httpProviderPathIsReplacedWithPrivateCachePath() {
        val yaml = "$SUBSCRIPTION" +
            "proxy-providers:\n  remote: {type: http, url: \"https://sub.example.invalid/p\", path: ../../shared_prefs/proxy.xml, interval: 3600}\n" +
            "rule-providers:\n  ads: {type: http, behavior: domain, url: \"https://sub.example.invalid/r\", path: /sdcard/ads.yaml}\n"
        val config = runtime(yaml, allowLan = false)
        assertEquals("./providers/provider-0.yaml", ((config["proxy-providers"] as Map<*, *>)["remote"] as Map<*, *>)["path"])
        assertEquals("./rules/provider-0.yaml", ((config["rule-providers"] as Map<*, *>)["ads"] as Map<*, *>)["path"])
    }

    @Test fun providerWithNonHttpUrlIsRejected() {
        val yaml = "$SUBSCRIPTION" + "proxy-providers:\n  remote: {type: http, url: \"file:///data/data/app.orchard.tv/files/x\"}\n"
        val error = assertThrows(IllegalArgumentException::class.java) { runtime(yaml, allowLan = false) }
        assertEquals("invalid_subscription_url", error.message)
    }

    // parse(): limits

    @Test fun oversizeInputIsRejectedBeforeParsing() {
        val ascii = "proxies: [a]\n#" + "x".repeat(MihomoConfig.MAX_BYTES)
        assertEquals("subscription_too_large", assertThrows(IllegalArgumentException::class.java) { MihomoConfig.parse(ascii) }.message)
        // The limit counts UTF-8 bytes, not characters.
        val cjk = "proxies: [a]\n#" + "香".repeat(MihomoConfig.MAX_BYTES / 3 + 1)
        assertEquals("subscription_too_large", assertThrows(IllegalArgumentException::class.java) { MihomoConfig.parse(cjk) }.message)
    }

    @Test fun nestingDeeperThanFortyIsRejected() {
        fun nested(levels: Int) = "proxies: " + "[".repeat(levels) + "1" + "]".repeat(levels) + "\n"
        // The top-level mapping counts as the first level.
        assertTrue((MihomoConfig.parse(nested(39))["proxies"] as List<*>).isNotEmpty())
        val error = assertThrows(IllegalArgumentException::class.java) { MihomoConfig.parse(nested(40)) }
        assertEquals("subscription_too_complex", error.message)
        assertEquals("subscription_too_complex",
            assertThrows(IllegalArgumentException::class.java) { MihomoConfig.parse(nested(5000)) }.message)
    }

    @Test fun moreThanTwentyCollectionAliasesAreRejected() {
        fun aliases(count: Int) = "x-base: &b [1]\nproxies: [" + List(count) { "*b" }.joinToString(",") + "]\n"
        assertEquals(20, (MihomoConfig.parse(aliases(20))["proxies"] as List<*>).size)
        val error = assertThrows(Exception::class.java) { MihomoConfig.parse(aliases(21)) }
        assertEquals("subscription_not_clash_yaml", SubscriptionFailures.code(error))
    }

    @Test fun aliasExpansionIsBoundedByNodeCount() {
        // 20 aliases, but expansion would build about 110k nodes.
        val base = "[" + List(1100) { "1" }.joinToString(",") + "]"
        val yaml = "x-base: &b $base\nx-mid: &m [" + List(10) { "*b" }.joinToString(",") + "]\n" +
            "proxies: [" + List(10) { "*m" }.joinToString(",") + "]\n"
        val error = assertThrows(IllegalArgumentException::class.java) { MihomoConfig.parse(yaml) }
        assertEquals("subscription_too_complex", error.message)
    }

    @Test fun recursiveAliasIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) { MihomoConfig.parse("proxies: &a [*a]\n") }
        assertEquals("subscription_recursive_alias", error.message)
    }

    @Test fun typedTagsAndNonClashDocumentsAreRejected() {
        assertEquals("subscription_unsupported_tag",
            assertThrows(IllegalStateException::class.java) { MihomoConfig.parse("proxies: !!binary aGVsbG8=\n") }.message)
        listOf("proxies: !foo bar\n", "proxies: [1]\nproxies: [2]\n").forEach { yaml ->
            assertEquals(yaml, "subscription_not_clash_yaml", SubscriptionFailures.code(assertThrows(Exception::class.java) { MihomoConfig.parse(yaml) }))
        }
        listOf("- a\n- b\n", "mixed-port: 7890\n", "proxies: []\nrules: []\n", "just text\n").forEach { yaml ->
            assertEquals(yaml, "subscription_not_clash_yaml",
                assertThrows(IllegalArgumentException::class.java) { MihomoConfig.parse(yaml) }.message)
        }
    }

    @Test fun subscriptionUrlMustBePlainHttpOrHttps() {
        assertEquals("https://sub.example.invalid/api?token=abc", MihomoConfig.validUrl(" https://sub.example.invalid/api?token=abc "))
        listOf("", "ftp://sub.example.invalid/x", "file:///data/x", "https://user:pw@sub.example.invalid/x",
            "https://sub.example.invalid/x#frag", "https:///x", "not a url").forEach { url ->
            val error = assertThrows(Exception::class.java) { MihomoConfig.validUrl(url) }
            assertEquals(url, "invalid_subscription_url", error.message)
        }
    }

    private fun manual(profile: ProxyProfile) = MihomoConfig.parse(MihomoConfig.manual(profile))

    private fun runtime(text: String, allowLan: Boolean) =
        MihomoConfig.parse(MihomoConfig.runtime(text, "rule", allowLan, 7890, 19090, SECRET))

    private fun assertManualGroupAndRule(config: Map<String, Any?>, rules: List<String> = listOf("MATCH,Manual proxy")) {
        val group = (config["proxy-groups"] as List<*>).single() as Map<*, *>
        assertEquals("Manual proxy", group["name"])
        assertEquals("select", group["type"])
        assertEquals(listOf("Manual upstream"), group["proxies"])
        assertEquals(rules, config["rules"])
        assertNull(config["proxy-providers"])
    }

    private fun runtime(text: String, mode: String, dns: List<String> = emptyList()) =
        MihomoConfig.parse(MihomoConfig.runtime(text, mode, false, 7890, 19090, SECRET, dns, directHosts = HOSTS))

    private fun tunRuntime(text: String, mode: String, dns: List<String> = listOf("192.168.1.1")) =
        MihomoConfig.parse(MihomoConfig.runtime(text, mode, false, 7890, 19090, SECRET, dns, tun = true, directHosts = HOSTS))

    private fun rules(config: Map<String, Any?>) = (config["rules"] as List<*>).map { it as String }

    private fun assertDirectRulesFirst(config: Map<String, Any?>) {
        assertEquals(DIRECT_RULES, rules(config).take(DIRECT_RULES.size))
    }

    private companion object {
        const val SECRET = "4f0c2a9e-secret"
        /** Injected: the shipped list is empty, so every ordering and hostile-input test brings its own. */
        val HOSTS = listOf("ads.vendor.test", "store.vendor.test", "api.cloud.test", "api.metrics.test", "data.metrics.test")
        val DIRECT_RULES = HOSTS.map { "DOMAIN,$it,DIRECT" }
        val HOSTILE = """
            |hosts:
            |  ads.vendor.test: 1.2.3.4
            |  "+.vendor.test": 5.6.7.8
            |  "*.cloud.test": 5.6.7.8
            |  ".metrics.test": proxy.example.invalid
            |  router.lan: 192.168.1.1
            |proxies:
            |  - {name: hk, type: ss, server: hk.example.invalid, port: 443, cipher: aes-128-gcm, password: pw}
            |proxy-groups:
            |  - {name: PROXY, type: select, proxies: [hk, DIRECT]}
            |sub-rules:
            |  sub: ["MATCH,PROXY"]
            |rules:
            |  - MATCH,PROXY
            |  - DOMAIN,ads.vendor.test,PROXY
            |  - DOMAIN-SUFFIX,vendor.test,PROXY
            |  - SUB-RULE,(NETWORK,tcp),sub
            |""".trimMargin()
        val HOSTILE_TUN = """
            |tun: {enable: true, stack: system, device: utun9, auto-route: true, file-descriptor: 0, include-package: [x.y]}
            |dns:
            |  enable: true
            |  listen: 0.0.0.0:53
            |  enhanced-mode: fake-ip
            |  fake-ip-range: 10.0.0.1/8
            |  fake-ip-filter: ["*"]
            |  nameserver: ["https://dns.example.invalid/dns-query"]
            |  nameserver-policy: {"+.vendor.test": "8.8.8.8"}
            |sniffer:
            |  enable: true
            |  override-destination: true
            |  skip-domain: ["+.example.com"]
            |  force-domain: ["+.vendor.test"]
            |  sniff: {TLS: {ports: [443]}}
            |proxies:
            |  - {name: hk, type: ss, server: hk.example.invalid, port: 443, cipher: aes-128-gcm, password: pw}
            |proxy-groups:
            |  - {name: PROXY, type: select, proxies: [hk, DIRECT]}
            |rules:
            |  - DOMAIN-SUFFIX,vendor.test,PROXY
            |  - MATCH,PROXY
            |""".trimMargin()
        val SUBSCRIPTION = """
            |mixed-port: 1234
            |port: 7891
            |socks-port: 7892
            |redir-port: 7893
            |allow-lan: true
            |bind-address: "*"
            |mode: global
            |log-level: debug
            |external-controller: 0.0.0.0:9090
            |external-controller-tls: 0.0.0.0:9443
            |external-ui: /sdcard/ui
            |secret: ""
            |authentication: ["user:pass"]
            |listeners:
            |  - {name: open, type: socks, port: 1080, listen: 0.0.0.0}
            |tun: {enable: true, stack: system}
            |dns: {enable: true, listen: 0.0.0.0:53}
            |interface-name: wlan0
            |geox-url: {mmdb: "https://geo.example.invalid/country.mmdb"}
            |proxies:
            |  - {name: "香港 01", type: ss, server: hk.example.invalid, port: 443, cipher: aes-128-gcm, password: pw}
            |proxy-groups:
            |  - {name: 节点选择, type: select, proxies: ["香港 01", DIRECT]}
            |rules:
            |  - MATCH,节点选择
            |""".trimMargin()
    }
}
