package app.orchard.tv.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream

class RuleListsTest {
    // Rule assembly per rule source

    @Test fun blacklistIsDirectHostsFirstThenReadmeOrderThenMatchDirect() {
        val config = runtime(SUBSCRIPTION, setup(RuleLists.BLACKLIST))
        assertEquals(DIRECT_RULES + listOf(
            "RULE-SET,aerial-applications,DIRECT", "RULE-SET,aerial-private,DIRECT",
            "RULE-SET,aerial-tld-not-cn,🚀 节点选择", "RULE-SET,aerial-gfw,🚀 节点选择",
            "RULE-SET,aerial-telegramcidr,🚀 节点选择", "MATCH,DIRECT"), rules(config))
    }

    @Test fun whitelistIsDirectHostsFirstThenReadmeOrderWithoutGeoipCnThenMatchGroup() {
        val config = runtime(SUBSCRIPTION, setup(RuleLists.WHITELIST))
        assertEquals(DIRECT_RULES + listOf(
            "RULE-SET,aerial-applications,DIRECT", "RULE-SET,aerial-private,DIRECT",
            "RULE-SET,aerial-icloud,DIRECT", "RULE-SET,aerial-apple,DIRECT",
            "RULE-SET,aerial-google,🚀 节点选择", "RULE-SET,aerial-proxy,🚀 节点选择",
            "RULE-SET,aerial-direct,DIRECT", "RULE-SET,aerial-lancidr,DIRECT", "RULE-SET,aerial-cncidr,DIRECT",
            "RULE-SET,aerial-telegramcidr,🚀 节点选择", "GEOIP,LAN,DIRECT", "MATCH,🚀 节点选择"), rules(config))
        // GEOIP,CN would need Country.mmdb, which Aerial neither ships nor downloads.
        assertFalse(rules(config).any { it.startsWith("GEOIP,CN") || it.startsWith("GEOSITE") })
    }

    @Test fun adBlockGoesRightAfterApplicationsAndPrivateInBothListModes() {
        listOf(RuleLists.BLACKLIST, RuleLists.WHITELIST).forEach { mode ->
            val list = rules(runtime(SUBSCRIPTION, setup(mode, adBlock = true)))
            assertEquals(mode, DIRECT_RULES, list.take(DIRECT_RULES.size))
            assertEquals(mode, listOf("RULE-SET,aerial-applications,DIRECT", "RULE-SET,aerial-private,DIRECT",
                "RULE-SET,aerial-reject,REJECT"), list.drop(DIRECT_RULES.size).take(3))
            assertEquals(1, list.count { it.contains("aerial-reject") })
            assertTrue(list.last().startsWith("MATCH,"))
            val providers = config(runtime(SUBSCRIPTION, setup(mode, adBlock = true)))["rule-providers"] as Map<*, *>
            assertEquals(mapOf("type" to "file", "behavior" to "domain", "format" to "text",
                "path" to "./aerial-rules/adblock/reject.list"), providers["aerial-reject"])
        }
    }

    @Test fun adBlockOffHasNoRejectRuleOrProvider() {
        RuleLists.MODES.forEach { mode ->
            val config = config(runtime(SUBSCRIPTION, setup(mode, adBlock = false)))
            assertFalse(mode, rules(config).any { it.contains("aerial-reject") })
            assertFalse(mode, (config["rule-providers"] as? Map<*, *>)?.containsKey("aerial-reject") == true)
        }
    }

    @Test fun subscriptionModeIsTodaysOutputExactly() {
        val before = MihomoConfig.runtime(SUBSCRIPTION, "rule", false, 7890, 19090, SECRET, listOf("192.168.1.1"),
            directHosts = HOSTS)
        val after = MihomoConfig.runtime(SUBSCRIPTION, "rule", false, 7890, 19090, SECRET, listOf("192.168.1.1"),
            lists = setup(RuleLists.SUBSCRIPTION), directHosts = HOSTS)
        assertEquals(before, after)
        val config = config(after)
        assertEquals(DIRECT_RULES + listOf("RULE-SET,ads,REJECT", "SUB-RULE,(NETWORK,tcp),sub", "MATCH,🚀 节点选择"), rules(config))
        assertEquals(setOf("ads"), (config["rule-providers"] as Map<*, *>).keys)
        assertEquals(DIRECT_RULES + "MATCH,🚀 节点选择", (config["sub-rules"] as Map<*, *>)["sub"])
    }

    @Test fun subscriptionModeWithAdBlockPutsRejectAheadOfTheSubscriptionRules() {
        val config = config(runtime(SUBSCRIPTION, setup(RuleLists.SUBSCRIPTION, adBlock = true)))
        assertEquals(DIRECT_RULES + listOf("RULE-SET,aerial-reject,REJECT", "RULE-SET,ads,REJECT",
            "SUB-RULE,(NETWORK,tcp),sub", "MATCH,🚀 节点选择"), rules(config))
        val providers = config["rule-providers"] as Map<*, *>
        assertEquals(setOf("ads", "aerial-reject"), providers.keys)
        // The subscription's own provider is still sanitized as before.
        assertEquals("./rules/provider-0.yaml", (providers["ads"] as Map<*, *>)["path"])
        // Without the reject file (not downloaded yet) nothing references it.
        val missing = config(runtime(SUBSCRIPTION, RuleLists.Setup(RuleLists.SUBSCRIPTION, true, "", emptyMap())))
        assertFalse(rules(missing).any { it.contains("aerial-reject") })
    }

    @Test fun listModesReplaceSubscriptionRulesProvidersAndSubRulesButKeepProxies() {
        listOf(RuleLists.BLACKLIST, RuleLists.WHITELIST).forEach { mode ->
            val config = config(runtime(SUBSCRIPTION, setup(mode)))
            val list = rules(config)
            assertFalse(mode, list.any { it.contains("ads") || it.startsWith("SUB-RULE") || it == "DOMAIN-SUFFIX,example.org,REJECT" })
            assertFalse(mode, config.containsKey("sub-rules"))
            val providers = config["rule-providers"] as Map<*, *>
            assertTrue(mode, providers.keys.all { (it as String).startsWith("aerial-") })
            providers.values.forEach { p ->
                p as Map<*, *>
                assertEquals("file", p["type"]); assertEquals("text", p["format"])
                assertTrue((p["path"] as String).startsWith("./aerial-rules/$mode/"))
            }
            assertEquals(2, (config["proxies"] as List<*>).size)
            assertEquals(2, (config["proxy-groups"] as List<*>).size)
            assertEquals(setOf("remote"), (config["proxy-providers"] as Map<*, *>).keys)
        }
    }

    @Test fun listModesStillRejectSubscriptionFileProxyProviders() {
        val yaml = SUBSCRIPTION.replace("type: http, url: \"https://sub.example.invalid/p\"", "type: file, path: /data/x")
        val error = assertThrows(IllegalArgumentException::class.java) { runtime(yaml, setup(RuleLists.BLACKLIST)) }
        assertEquals("subscription_file_provider_not_allowed", error.message)
    }

    @Test fun listModesDropAnUnusedSubscriptionFileRuleProviderInsteadOfFailing() {
        val yaml = SUBSCRIPTION.replace("ads: {type: http, behavior: domain, url: \"https://sub.example.invalid/ads.yaml\", path: ./x.yaml, interval: 86400}",
            "ads: {type: file, behavior: domain, path: /data/x}")
        assertTrue(yaml.contains("type: file, behavior: domain"))
        assertThrows(IllegalArgumentException::class.java) { runtime(yaml, setup(RuleLists.SUBSCRIPTION)) }
        assertFalse((config(runtime(yaml, setup(RuleLists.WHITELIST)))["rule-providers"] as Map<*, *>).containsKey("ads"))
    }

    @Test fun missingListFilesAreLeftOutNotReferenced() {
        val partial = RuleLists.Setup(RuleLists.WHITELIST, false, "", mapOf("direct" to "./aerial-rules/whitelist/direct.list"))
        val config = config(runtime(SUBSCRIPTION, partial))
        assertEquals(DIRECT_RULES + listOf("RULE-SET,aerial-direct,DIRECT", "GEOIP,LAN,DIRECT", "MATCH,🚀 节点选择"), rules(config))
        assertEquals(setOf("aerial-direct"), (config["rule-providers"] as Map<*, *>).keys)
    }

    @Test fun globalAndDirectIgnoreTheRuleSource() {
        RuleLists.MODES.forEach { ruleMode ->
            listOf(true, false).forEach { ad ->
                val global = config(MihomoConfig.runtime(SUBSCRIPTION, "global", false, 7890, 19090, SECRET,
                    lists = setup(ruleMode, ad), directHosts = HOSTS))
                assertEquals(DIRECT_RULES + listOf("MATCH,GLOBAL", "MATCH,REJECT"), rules(global))
                assertEquals(MihomoConfig.runtime(SUBSCRIPTION, "global", false, 7890, 19090, SECRET),
                    MihomoConfig.runtime(SUBSCRIPTION, "global", false, 7890, 19090, SECRET, lists = setup(ruleMode, ad)))
                assertEquals(MihomoConfig.runtime(SUBSCRIPTION, "direct", false, 7890, 19090, SECRET),
                    MihomoConfig.runtime(SUBSCRIPTION, "direct", false, 7890, 19090, SECRET, lists = setup(ruleMode, ad)))
            }
        }
    }

    @Test fun tunBlockDnsAndSnifferAreIdenticalAcrossRuleSources() {
        fun tun(setup: RuleLists.Setup) = config(MihomoConfig.runtime(SUBSCRIPTION, "rule", false, 7890, 19090, SECRET,
            listOf("192.168.1.1"), tun = true, lists = setup))
        val reference = tun(setup(RuleLists.SUBSCRIPTION))
        RuleLists.MODES.forEach { mode -> listOf(true, false).forEach { ad ->
            val other = tun(setup(mode, ad))
            listOf("tun", "dns", "sniffer", "mixed-port", "external-controller", "secret", "allow-lan", "lan-allowed-ips").forEach {
                assertEquals("$mode/$ad/$it", reference[it], other[it])
            }
        } }
    }

    @Test fun manualProfilesTargetTheManualProxyGroup() {
        val text = MihomoConfig.manual(ProxyProfile("m", "Office", "http", host = "proxy.lan", port = 3128))
        val config = config(MihomoConfig.runtime(text, "rule", false, 7890, 19090, SECRET, lists = setup(RuleLists.BLACKLIST)))
        assertTrue(rules(config).contains("RULE-SET,aerial-gfw,Manual proxy"))
        assertEquals("MATCH,DIRECT", rules(config).last())
        val white = config(MihomoConfig.runtime(text, "rule", false, 7890, 19090, SECRET, lists = setup(RuleLists.WHITELIST)))
        assertEquals("MATCH,Manual proxy", rules(white).last())
    }

    // Target group

    @Test fun defaultTargetIsTheFirstSelectGroup() {
        val config = MihomoConfig.parse(SUBSCRIPTION)
        assertEquals("🚀 节点选择", RuleLists.defaultGroup(config))
        assertEquals(listOf("♻️ 自动选择", "🚀 节点选择"), RuleLists.groups(config))
    }

    @Test fun userOverrideIsUsedOnlyWhileTheGroupExists() {
        val config = MihomoConfig.parse(SUBSCRIPTION)
        assertEquals("♻️ 自动选择", RuleLists.targetGroup(config, "♻️ 自动选择"))
        assertEquals("GLOBAL", RuleLists.targetGroup(config, "GLOBAL"))
        assertEquals("🚀 节点选择", RuleLists.targetGroup(config, "Gone"))
        assertEquals("🚀 节点选择", RuleLists.targetGroup(config, ""))
        assertEquals("🚀 节点选择", RuleLists.targetGroup(config, "DIRECT"))
        val override = config(runtime(SUBSCRIPTION, setup(RuleLists.WHITELIST, group = "♻️ 自动选择")))
        assertEquals("MATCH,♻️ 自动选择", rules(override).last())
        val stale = config(runtime(SUBSCRIPTION, setup(RuleLists.WHITELIST, group = "Gone")))
        assertEquals("MATCH,🚀 节点选择", rules(stale).last())
    }

    @Test fun withoutSelectGroupsTheTargetIsGlobal() {
        val yaml = "proxies:\n  - {name: a, type: ss, server: s.example.invalid, port: 443, cipher: aes-128-gcm, password: p}\n" +
            "proxy-groups:\n  - {name: auto, type: url-test, proxies: [a], url: \"https://cp.cloudflare.com/generate_204\", interval: 300}\n"
        assertEquals("GLOBAL", RuleLists.defaultGroup(MihomoConfig.parse(yaml)))
        assertEquals("MATCH,GLOBAL", rules(config(runtime(yaml, setup(RuleLists.WHITELIST)))).last())
        assertEquals("GLOBAL", RuleLists.defaultGroup(MihomoConfig.parse("proxies:\n  - {name: a, type: ss, server: s, port: 1, cipher: aes-128-gcm, password: p}\n")))
    }

    @Test fun groupNamesThatCannotBeARuleTargetAreSkipped() {
        val yaml = "proxies:\n  - {name: a, type: ss, server: s.example.invalid, port: 443, cipher: aes-128-gcm, password: p}\n" +
            "proxy-groups:\n" +
            "  - {name: \"A,B\", type: select, proxies: [a]}\n" +
            "  - {name: \" padded\", type: select, proxies: [a]}\n" +
            "  - {name: \"real\", type: select, proxies: [a]}\n"
        val config = MihomoConfig.parse(yaml)
        assertEquals(listOf("real"), RuleLists.groups(config))
        assertEquals("real", RuleLists.defaultGroup(config))
        assertEquals("real", RuleLists.targetGroup(config, "A,B"))
    }

    @Test fun providerPathsMustStayInAerialsFolder() {
        listOf("../profiles.json", "./aerial-rules/blacklist/../../x.list", "/data/x.list", "./rules/provider-0.yaml").forEach { path ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                RuleLists.providers(RuleLists.Setup(RuleLists.BLACKLIST, false, "", mapOf("gfw" to path)))
            }
            assertEquals(path, "invalid_rule_path", error.message)
        }
        assertThrows(IllegalArgumentException::class.java) { RuleLists.Setup("script") }
    }

    // List conversion and validation

    @Test fun upstreamYamlBecomesCoreTextFormat() {
        val yaml = "payload:\n  - '+.google.com'\n  - 'example.org'\n  - \"*.cdn.example\"\n  - plain.example\n\n# note\n"
        val (out, result) = convert(yaml, RuleLists.Behavior.DOMAIN)
        assertEquals("+.google.com\nexample.org\n*.cdn.example\nplain.example\n", out)
        assertEquals(4, result.count)
        assertEquals(64, result.sha256.length)
        val (cidr, _) = convert("payload:\n  - '1.0.1.0/24'\n  - '2001:db8::/32'\n  - '::/127'\n  - '64:ff9b::/96'\n", RuleLists.Behavior.IPCIDR)
        assertEquals("1.0.1.0/24\n2001:db8::/32\n::/127\n64:ff9b::/96\n", cidr)
        val (classical, _) = convert("payload:\n  - PROCESS-NAME,frpc\n  - DOMAIN-SUFFIX,example.com\n  - IP-CIDR,10.0.0.0/8,no-resolve\n  - PROCESS-NAME,Surge 3\n", RuleLists.Behavior.CLASSICAL)
        assertEquals("PROCESS-NAME,frpc\nDOMAIN-SUFFIX,example.com\nIP-CIDR,10.0.0.0/8,no-resolve\nPROCESS-NAME,Surge 3\n", classical)
    }

    @Test fun plainTextListsAreAcceptedToo() {
        val (out, result) = convert("\uFEFF# comment\r\n+.example.com\r\nexample.net\r\n", RuleLists.Behavior.DOMAIN)
        assertEquals("+.example.com\nexample.net\n", out)
        assertEquals(2, result.count)
    }

    @Test fun hostileOrBrokenListsAreRejectedWhole() {
        val domain = listOf(
            "payload:\n  - 'a.example'\nproxies:\n  - {name: x, type: ss}\n",
            "payload:\n  - 'a.example,DIRECT'\n",
            "payload:\n  - 'a.example'\n  - *alias\n",
            "payload:\n  - {a: b}\n",
            "payload:\n  - [a.example]\n",
            "payload:\n  - |\n    a.example\n",
            "payload:\n  - 'a.example' # c\n",
            "payload:\n  - 'it''s.example'\n",
            "payload:\n  - \"a\\nb.example\"\n",
            "payload:\n  - 'a b.example'\n",
            "payload:\n  - 'a.example/x'\n",
            "payload:\n  - 'xn--a.exa\u0000mple'\n",
            "payload:\n  - 'a.example'\n  -\n",
            "payload:\n  - 'a.example'\n- 'RULE-SET,x,DIRECT'\n",
            "a.example\nMATCH,DIRECT\n",
            "payload:\n  - '" + "a".repeat(64) + ".example'\n",
        )
        domain.forEach { text ->
            val error = assertThrows(IllegalArgumentException::class.java) { convert(text, RuleLists.Behavior.DOMAIN) }
            assertEquals(text, "rule_list_invalid", error.message)
        }
        val classical = listOf("MATCH,DIRECT", "RULE-SET,x", "SUB-RULE,(NETWORK,tcp),x", "AND,((DOMAIN,a.example)),DIRECT",
            "DOMAIN,a.example,DIRECT", "GEOIP,CN", "GEOSITE,cn", "SCRIPT,x", "domain,a.example", "DOMAIN-SUFFIX, a.example",
            "IP-CIDR,10.0.0.0/8,DIRECT", "DOMAIN,a.example,no-resolve", "PROCESS-NAME,a'b", "DST-PORT,70000", "DOMAIN-REGEX,.*")
        classical.forEach { rule ->
            val error = assertThrows(IllegalArgumentException::class.java) { convert("payload:\n  - $rule\n", RuleLists.Behavior.CLASSICAL) }
            assertEquals(rule, "rule_list_invalid", error.message)
        }
        listOf("1.2.3.4", "1.2.3.4/33", "01.2.3.4/8", "1.2.3/8", "256.0.0.0/8", "2001:db8::1::/64", "::/129", "1.2.3.4/08",
            "a.example/8", "1.2.3.4/8/8", "fffff::/8", "1:2:3:4:5:6:7:8:9/64").forEach { cidr ->
            val error = assertThrows(IllegalArgumentException::class.java) { convert("payload:\n  - '$cidr'\n", RuleLists.Behavior.IPCIDR) }
            assertEquals(cidr, "rule_list_invalid", error.message)
        }
        // A domain is not an address: the wrong behaviour for a list fails it.
        assertThrows(IllegalArgumentException::class.java) { convert("payload:\n  - '+.example.com'\n", RuleLists.Behavior.IPCIDR) }
    }

    @Test fun htmlEmptyOversizeAndBadEncodingHaveTheirOwnCodes() {
        assertEquals("rule_list_html_response", assertThrows(IllegalArgumentException::class.java) {
            convert("<!DOCTYPE html><html>login</html>\n", RuleLists.Behavior.DOMAIN) }.message)
        assertEquals("rule_list_empty", assertThrows(IllegalArgumentException::class.java) {
            convert("payload:\n# nothing\n", RuleLists.Behavior.DOMAIN) }.message)
        assertEquals("rule_list_empty", assertThrows(IllegalArgumentException::class.java) { convert("", RuleLists.Behavior.DOMAIN) }.message)
        val big = StringBuilder("payload:\n").apply { while (length <= RuleLists.MAX_BYTES) append("  - 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.example'\n") }
        assertEquals("rule_list_too_large", assertThrows(IllegalArgumentException::class.java) {
            convert(big.toString(), RuleLists.Behavior.DOMAIN) }.message)
        val bytes = "payload:\n  - 'a.example'\n".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28, 10)
        assertEquals("rule_list_invalid", assertThrows(IllegalStateException::class.java) {
            RuleLists.convert(ByteArrayInputStream(bytes), RuleLists.Behavior.DOMAIN, ByteArrayOutputStream()) }.message)
    }

    @Test fun ruleUrlsMustBeHttpsWithoutCredentials() {
        assertEquals("https://cdn.example.invalid/a.txt", RuleLists.validUrl(" https://cdn.example.invalid/a.txt "))
        listOf("http://cdn.example.invalid/a.txt", "https://user:pw@cdn.example.invalid/a", "https://cdn.example.invalid/a#x",
            "file:///data/a", "ftp://x.example/a", "", "https:///a").forEach { url ->
            assertEquals(url, "invalid_rule_url", assertThrows(Exception::class.java) { RuleLists.validUrl(url) }.message)
        }
        val long = "https://mirror.example/" + "a".repeat(2048 - 23)
        assertEquals(long, RuleLists.validUrl(long))
        assertEquals("invalid_rule_url", assertThrows(Exception::class.java) { RuleLists.validUrl(long + "a") }.message)
        // Official sources only: GitHub's raw file, then jsDelivr's mirror of the same branch.
        assertEquals(listOf("https://raw.githubusercontent.com/Loyalsoldier/clash-rules/release/gfw.txt",
            "https://cdn.jsdelivr.net/gh/Loyalsoldier/clash-rules@release/gfw.txt"), RuleLists.defaultUrls("gfw"))
        // The defaults must themselves be valid rule URLs (https, within the length cap).
        RuleLists.defaultUrls("gfw").forEach { assertEquals(it, RuleLists.validUrl(it)) }
    }

    @Test fun everyBundledSnapshotListConvertsCompletely() {
        val folder = assets()
        assumeTrue("bundled snapshot not found", folder != null)
        val expected = mapOf("applications" to 98, "private" to 132, "tld-not-cn" to 829, "gfw" to 4443, "telegramcidr" to 12,
            "icloud" to 52, "apple" to 165, "google" to 112, "proxy" to 27316, "direct" to 110560, "lancidr" to 28, "cncidr" to 10012)
        (RuleLists.BLACKLIST_ENTRIES + RuleLists.WHITELIST_ENTRIES).distinct().forEach { entry ->
            val file = File(folder, "${entry.name}.txt.gz")
            assertTrue(entry.name, file.isFile)
            val result = GZIPInputStream(file.inputStream()).use { RuleLists.convert(it, entry.behavior, ByteArrayOutputStream()) }
            // Every upstream line is an entry: nothing was dropped silently.
            assertEquals(entry.name, expected[entry.name], result.count)
        }
        assertNull("reject.txt is downloaded, not bundled", File(folder, "reject.txt.gz").takeIf { it.isFile })
    }

    /**
     * Opt-in: AERIAL_MIHOMO = path of a desktop mihomo build (same 1.19.33 config parser as the
     * TV's). Extracts the real bundled snapshot into a home directory like RuleStore.prepare does
     * and runs `-t` on every rule source, with and without ad block and TUN.
     */
    @Test fun everyRuleSourceWithTheBundledSnapshotPassesTheCoresConfigCheck() {
        val core = System.getenv("AERIAL_MIHOMO")?.let(::File)
        val folder = assets()
        assumeTrue("AERIAL_MIHOMO not set", core != null && core.isFile && folder != null)
        val home = java.nio.file.Files.createTempDirectory("aerial-core-check").toFile()
        try {
            val store = RuleStore(home, { name -> File(folder, "$name.txt.gz").takeIf { it.isFile }?.let { GZIPInputStream(it.inputStream()) } }, 1L)
            // reject.txt is not bundled: a small stand-in with the same shape.
            val rejectFile = store.file(RuleLists.ADBLOCK, "reject").also { it.writeText("+.ads.example.invalid\n") }
            RuleLists.MODES.forEach { mode -> listOf(false, true).forEach { ad -> listOf(false, true).forEach { tun ->
                val sets = (if (mode == RuleLists.SUBSCRIPTION) emptyList() else listOf(mode)) + (if (ad) listOf(RuleLists.ADBLOCK) else emptyList())
                val lists = RuleLists.Setup(mode, ad, "", store.prepare(sets))
                assertTrue(rejectFile.isFile)
                val yaml = MihomoConfig.runtime(SUBSCRIPTION, "rule", false, 17890, 19090, SECRET, listOf("192.168.1.1"), tun = tun, lists = lists)
                val file = File(home, "check.yaml").also { it.writeText(yaml) }
                val process = ProcessBuilder(core!!.path, "-t", "-d", home.path, "-f", file.path).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals("$mode ad=$ad tun=$tun: $output", 0, process.waitFor())
            } } }
        } finally { home.deleteRecursively() }
    }

    // Bundled snapshot lookup: the APK carries `<name>.txt`, the repository `<name>.txt.gz`

    private fun gzip(text: String) = ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()

    private fun opener(files: Map<String, ByteArray>): (String) -> java.io.InputStream? = { path ->
        files[path]?.let { ByteArrayInputStream(it) } ?: throw java.io.FileNotFoundException(path)
    }

    @Test fun bundledListReadsThePlainAssetTheApkCarries() {
        val files = mapOf("proxy/rules/apple.txt" to "payload:\n  - '+.apple.com'\n".toByteArray())
        assertEquals("payload:\n  - '+.apple.com'\n", openBundledList("apple", opener(files))!!.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    @Test fun bundledListPrefersThePlainAssetOverTheGzipOne() {
        val files = mapOf("proxy/rules/apple.txt" to "plain".toByteArray(), "proxy/rules/apple.txt.gz" to gzip("zipped"))
        assertEquals("plain", openBundledList("apple", opener(files))!!.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    @Test fun bundledListFallsBackToTheGzipAssetWhenThereIsNoPlainOne() {
        val files = mapOf("proxy/rules/apple.txt.gz" to gzip("zipped"))
        assertEquals("zipped", openBundledList("apple", opener(files))!!.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    @Test fun bundledListIsNullWhenNeitherAssetExists() {
        assertNull(openBundledList("apple", opener(emptyMap())))
        assertNull(openBundledList("apple") { null })
    }

    @Test fun bundledListIsNullForACorruptGzipAsset() {
        val files = mapOf("proxy/rules/apple.txt.gz" to "this is not gzip data at all".toByteArray())
        assertNull(openBundledList("apple", opener(files)))
    }

    @Test fun bundledListReadsTheCommittedGzipSourcesThroughTheFallback() {
        val folder = assets()
        assumeTrue("bundled snapshot not found", folder != null)
        val names = (RuleLists.BLACKLIST_ENTRIES + RuleLists.WHITELIST_ENTRIES).map { it.name }.distinct()
        val open = { path: String -> File(folder, path.removePrefix("proxy/rules/")).takeIf { it.isFile }?.inputStream() }
        names.forEach { name -> assertTrue("$name unreadable", openBundledList(name, open)?.use { it.read() >= 0 } == true) }
    }

    private fun convert(text: String, behavior: RuleLists.Behavior): Pair<String, RuleLists.Result> {
        val out = ByteArrayOutputStream()
        val result = RuleLists.convert(ByteArrayInputStream(text.toByteArray()), behavior, out)
        return out.toString("UTF-8") to result
    }

    private fun setup(mode: String, adBlock: Boolean = false, group: String = ""): RuleLists.Setup {
        val sets = (if (mode == RuleLists.SUBSCRIPTION) emptyList() else listOf(mode)) + (if (adBlock) listOf(RuleLists.ADBLOCK) else emptyList())
        val lists = sets.flatMap { set -> RuleLists.entries(set).map { it.name to "./aerial-rules/$set/${it.name}.list" } }.toMap()
        return RuleLists.Setup(mode, adBlock, group, lists)
    }

    private fun runtime(text: String, setup: RuleLists.Setup) =
        MihomoConfig.runtime(text, "rule", false, 7890, 19090, SECRET, lists = setup, directHosts = HOSTS)

    private fun config(text: String) = MihomoConfig.parse(text)
    private fun rules(config: Map<String, Any?>) = (config["rules"] as List<*>).map { it as String }
    private fun rules(text: String) = rules(config(text))

    companion object {
        const val SECRET = "s3cret"
        /** Injected: the shipped list is empty, the ordering must hold for any list. */
        val HOSTS = listOf("telemetry.vendor.test", "api.vendor.test")
        val DIRECT_RULES = HOSTS.map { "DOMAIN,$it,DIRECT" }

        fun assets(): File? = listOf("src/main/assets/proxy/rules", "app/src/main/assets/proxy/rules")
            .map(::File).firstOrNull { it.isDirectory }

        val SUBSCRIPTION = """
            |proxies:
            |  - {name: "香港 01", type: ss, server: hk.example.invalid, port: 443, cipher: aes-128-gcm, password: pw}
            |  - {name: "日本 01", type: ss, server: jp.example.invalid, port: 443, cipher: aes-128-gcm, password: pw}
            |proxy-providers:
            |  remote: {type: http, url: "https://sub.example.invalid/p", interval: 3600}
            |proxy-groups:
            |  - {name: "♻️ 自动选择", type: url-test, proxies: ["香港 01", "日本 01"], url: "https://cp.cloudflare.com/generate_204", interval: 300}
            |  - {name: "🚀 节点选择", type: select, proxies: ["♻️ 自动选择", "香港 01", "日本 01", DIRECT]}
            |rule-providers:
            |  ads: {type: http, behavior: domain, url: "https://sub.example.invalid/ads.yaml", path: ./x.yaml, interval: 86400}
            |sub-rules:
            |  sub: ["MATCH,🚀 节点选择"]
            |rules:
            |  - RULE-SET,ads,REJECT
            |  - SUB-RULE,(NETWORK,tcp),sub
            |  - MATCH,🚀 节点选择
            |""".trimMargin()
    }
}
