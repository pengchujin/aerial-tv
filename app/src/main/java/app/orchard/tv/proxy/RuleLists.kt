package app.orchard.tv.proxy

import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * Rule sources for "rule" mode, built from Loyalsoldier/clash-rules (GPL-3.0, release branch;
 * README "白名单模式" / "黑名单模式"). Pure JVM: no Android types, so it is unit-tested directly.
 *
 * Every list is an Aerial-managed file in the core's home directory ([FOLDER]), referenced as a
 * `type: file` rule-provider with `format: text` (one entry per line). The core parses text lists
 * line by line (rules/provider/provider.go rulesParse); its YAML format runs a YAML decoder for
 * every line, far slower on an ARMv7 TV for the 110k-line direct list.
 */
internal object RuleLists {
    const val SUBSCRIPTION = "subscription"
    const val BLACKLIST = "blacklist"
    const val WHITELIST = "whitelist"
    val MODES = listOf(SUBSCRIPTION, BLACKLIST, WHITELIST)
    const val DEFAULT_MODE = BLACKLIST

    /** URL sets: one per list mode, plus the ad-block list shared by all three rule sources. */
    const val ADBLOCK = "adblock"
    val SETS = listOf(BLACKLIST, WHITELIST, ADBLOCK)

    /** Relative to the core's -d directory. Subscription providers use ./rules and ./providers. */
    const val FOLDER = "aerial-rules"
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_ENTRIES = 400_000
    private const val MAX_LINE = 1024
    private const val MAX_URL = 2048

    /** Provider names in the runtime config; a prefix no subscription convention uses. */
    fun provider(name: String) = "aerial-$name"

    enum class Behavior(val key: String) { DOMAIN("domain"), IPCIDR("ipcidr"), CLASSICAL("classical") }

    /** [target] null means the proxy group chosen at runtime (README's `PROXY`). */
    data class Entry(val name: String, val behavior: Behavior, val target: String?)

    private fun direct(name: String, b: Behavior = Behavior.DOMAIN) = Entry(name, b, "DIRECT")
    private fun proxied(name: String, b: Behavior = Behavior.DOMAIN) = Entry(name, b, null)
    private val APPLICATIONS = direct("applications", Behavior.CLASSICAL)
    private val PRIVATE = direct("private")
    val REJECT = Entry("reject", Behavior.DOMAIN, "REJECT")

    /** README order without `reject`, which [rules] inserts after `private` when ad blocking is on. */
    val BLACKLIST_ENTRIES = listOf(APPLICATIONS, PRIVATE, proxied("tld-not-cn"), proxied("gfw"),
        proxied("telegramcidr", Behavior.IPCIDR))
    /**
     * README order. `GEOIP,CN,DIRECT` is dropped: it needs Country.mmdb, which Aerial does not ship
     * and must not download, and `cncidr` (same 17mon china_ip_list source) already covers it.
     * `GEOIP,LAN,DIRECT` stays: the core matches "lan" without any database (rules/common/geoip.go).
     */
    val WHITELIST_ENTRIES = listOf(APPLICATIONS, PRIVATE, direct("icloud"), direct("apple"), proxied("google"),
        proxied("proxy"), direct("direct"), direct("lancidr", Behavior.IPCIDR), direct("cncidr", Behavior.IPCIDR),
        proxied("telegramcidr", Behavior.IPCIDR))

    fun entries(set: String): List<Entry> = when (set) {
        BLACKLIST -> BLACKLIST_ENTRIES
        WHITELIST -> WHITELIST_ENTRIES
        ADBLOCK -> listOf(REJECT)
        else -> error("invalid_rule_set")
    }

    fun entry(set: String, name: String): Entry = entries(set).firstOrNull { it.name == name } ?: error("invalid_rule_list")

    const val RAW = "https://raw.githubusercontent.com/Loyalsoldier/clash-rules/release/"
    const val JSDELIVR = "https://cdn.jsdelivr.net/gh/Loyalsoldier/clash-rules@release/"

    /**
     * Official sources only: GitHub's raw file first, then jsDelivr's mirror of the same branch
     * (it lags up to 12 h). A list can be pointed elsewhere on the phone page (RuleStore.setUrls);
     * whatever a mirror serves is validated like any list (domains and CIDRs only, see [convert]).
     */
    fun defaultUrls(name: String) = listOf("$RAW$name.txt", "$JSDELIVR$name.txt")

    /** Like subscription URLs (MihomoConfig.validUrl), but https only: lists come from public mirrors. */
    fun validUrl(value: String): String {
        // 4 URLs of at most 2048 characters fit the phone server's 16 KiB form limit.
        require(value.length <= MAX_URL) { "invalid_rule_url" }
        val url = runCatching { MihomoConfig.validUrl(value) }.getOrNull() ?: error("invalid_rule_url")
        require(url.startsWith("https://")) { "invalid_rule_url" }
        return url
    }

    /**
     * What a runtime config needs for "rule" mode. [lists]: list name -> path relative to the core's
     * home directory, for the files that exist (a missing list is left out instead of failing the
     * core). [group]: the target group the user chose, or "" for the default.
     */
    data class Setup(val mode: String = SUBSCRIPTION, val adBlock: Boolean = false, val group: String = "",
                     val lists: Map<String, String> = emptyMap()) {
        init { require(mode in MODES) { "invalid_rule_mode" } }
    }

    /** The rules after the always-DIRECT head (MihomoConfig) for a list mode; [group] is the PROXY target. */
    fun rules(setup: Setup, group: String): List<String> {
        val head = listOf(APPLICATIONS, PRIVATE) + (if (setup.adBlock) listOf(REJECT) else emptyList())
        val body = when (setup.mode) {
            BLACKLIST -> head + BLACKLIST_ENTRIES.drop(2)
            WHITELIST -> head + WHITELIST_ENTRIES.drop(2)
            else -> error("invalid_rule_mode")
        }
        val sets = body.filter { it.name in setup.lists }.map { "RULE-SET,${provider(it.name)},${it.target ?: group}" }
        return sets + if (setup.mode == BLACKLIST) listOf("MATCH,DIRECT") else listOf("GEOIP,LAN,DIRECT", "MATCH,$group")
    }

    /** `type: file` providers for [rules]; paths are Aerial's own, checked to stay in [FOLDER]. */
    fun providers(setup: Setup): Map<String, Any?> {
        val names = when (setup.mode) {
            BLACKLIST -> BLACKLIST_ENTRIES
            WHITELIST -> WHITELIST_ENTRIES
            else -> emptyList()
        } + (if (setup.adBlock) listOf(REJECT) else emptyList())
        return linkedMapOf<String, Any?>().apply {
            names.forEach { entry ->
                val path = setup.lists[entry.name] ?: return@forEach
                require(PATH.matches(path)) { "invalid_rule_path" }
                put(provider(entry.name), linkedMapOf("type" to "file", "behavior" to entry.behavior.key,
                    "format" to "text", "path" to path))
            }
        }
    }

    private val PATH = Regex("^\\./$FOLDER/(blacklist|whitelist|adblock)/[a-z-]{1,40}\\.list$")

    /** Built-in policies: never a group the user picks (DIRECT/REJECT would defeat the mode). */
    private val BUILT_IN = setOf("DIRECT", "REJECT", "REJECT-DROP", "COMPATIBLE", "PASS", "PASS-RULE")

    /** A name the core reads back intact as a rule target: no comma (field separator), no padding. */
    fun usableGroupName(name: String): Boolean = name.length in 1..256 && name !in BUILT_IN &&
        name.none { it == ',' || it.code < 32 || it.code == 127 } && name.trim() == name

    /** The subscription's proxy-group names (in order) that can be a rule target. */
    fun groups(config: Map<String, Any?>): List<String> =
        (config["proxy-groups"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("name") as? String }
            .filter(::usableGroupName).distinct()

    /**
     * The first `select` group: by convention the main selector the user picks nodes in. Without
     * one, the core's built-in GLOBAL selector (always present; its first choice is DIRECT until
     * the user picks a node).
     */
    fun defaultGroup(config: Map<String, Any?>): String =
        (config["proxy-groups"] as? List<*>).orEmpty().firstNotNullOfOrNull { item ->
            val group = item as? Map<*, *> ?: return@firstNotNullOfOrNull null
            val name = group["name"] as? String
            if (name != null && usableGroupName(name) && group["type"] == "select") name else null
        } ?: "GLOBAL"

    /** The user's [override] if the config still has that group, else [defaultGroup]. */
    fun targetGroup(config: Map<String, Any?>, override: String): String = pick(groups(config), defaultGroup(config), override)

    fun pick(groups: List<String>, default: String, override: String): String =
        if (override.isNotEmpty() && (override == "GLOBAL" || override in groups)) override else default

    /*
     * List conversion. Accepts the upstream YAML (`payload:` then `  - 'entry'` lines, as
     * Loyalsoldier publishes) or plain text (one entry per line), and writes the core's text format.
     * Every entry must have exactly the shape of the list's behaviour; anything else (another key,
     * a nested structure, a rule type outside the allowed set, a target, a control character)
     * rejects the whole list, so a hostile or broken download can never add a rule of its own.
     */

    class Result(val count: Int, val sha256: String)

    fun convert(input: InputStream, behavior: Behavior, output: OutputStream): Result {
        val digest = MessageDigest.getInstance("SHA-256")
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = BufferedReader(InputStreamReader(Capped(input, MAX_BYTES), decoder))
        val out = DigestOutputStream(output, digest).bufferedWriter(Charsets.UTF_8)
        var count = 0
        var yaml: Boolean? = null
        try {
            var first = true
            while (true) {
                var line = reader.readLine() ?: break
                if (first) { line = line.removePrefix("﻿"); first = false }
                require(line.length <= MAX_LINE) { "rule_list_invalid" }
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                if (yaml == null) {
                    require(!trimmed.startsWith("<")) { "rule_list_html_response" }
                    yaml = trimmed == "payload:"
                    if (yaml) continue
                }
                val value = if (yaml) yamlItem(line) else trimmed
                require(valid(value, behavior)) { "rule_list_invalid" }
                require(++count <= MAX_ENTRIES) { "rule_list_too_large" }
                out.write(value); out.write("\n")
            }
            out.flush()
        } catch (e: CharacterCodingException) {
            error("rule_list_invalid")
        }
        require(count > 0) { "rule_list_empty" }
        return Result(count, digest.digest().joinToString("") { "%02x".format(it) })
    }

    /** One `- item` of the payload list: a plain, single- or double-quoted scalar without escapes. */
    private fun yamlItem(line: String): String {
        require(line.startsWith(" ") || line.startsWith("-")) { "rule_list_invalid" }
        val item = line.trim()
        require(item.startsWith("- ")) { "rule_list_invalid" }
        val raw = item.substring(2).trim()
        require(raw.isNotEmpty()) { "rule_list_invalid" }
        return when (raw[0]) {
            '\'', '"' -> {
                val quote = raw[0]
                require(raw.length >= 2 && raw.last() == quote) { "rule_list_invalid" }
                raw.substring(1, raw.length - 1).also { require(it.none { c -> c == '\'' || c == '"' || c == '\\' }) { "rule_list_invalid" } }
            }
            else -> {
                // Plain scalars starting with a YAML indicator (alias, anchor, tag, flow, block) are not entries.
                require(raw[0] !in "*&!{}[]|>%@`'\"#?:" && !raw.contains(" #") && !raw.contains(": ")) { "rule_list_invalid" }
                raw
            }
        }
    }

    fun valid(value: String, behavior: Behavior): Boolean = when (behavior) {
        Behavior.DOMAIN -> domainPattern(value)
        Behavior.IPCIDR -> cidr(value)
        Behavior.CLASSICAL -> classical(value)
    }

    private val LABEL = Regex("^[A-Za-z0-9_-]{1,63}$")

    /** A domain-set entry: `example.com`, `+.example.com`, `.example.com`, `*.example.com`. */
    fun domainPattern(value: String): Boolean {
        val host = when {
            value.startsWith("+.") -> value.substring(2)
            value.startsWith("*.") -> value.substring(2)
            value.startsWith(".") -> value.substring(1)
            else -> value
        }
        return host.length in 1..253 && host.split('.').all { it == "*" || LABEL.matches(it) }
    }

    private fun plainDomain(value: String) =
        value.length in 1..253 && value.split('.').all { LABEL.matches(it) }

    fun cidr(value: String): Boolean {
        val slash = value.indexOf('/')
        if (slash <= 0 || slash != value.lastIndexOf('/')) return false
        val address = value.substring(0, slash)
        val bits = value.substring(slash + 1)
        if (bits.isEmpty() || bits.length > 3 || bits.any { !it.isDigit() } || (bits.length > 1 && bits[0] == '0')) return false
        val prefix = bits.toInt()
        return if (address.contains(':')) ipv6(address) && prefix <= 128 else ipv4(address) && prefix <= 32
    }

    private fun ipv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { p ->
            p.length in 1..3 && p.all(Char::isDigit) && (p.length == 1 || p[0] != '0') && p.toInt() <= 255
        }
    }

    private fun ipv6(value: String): Boolean {
        if (value.length > 45) return false
        fun hex(g: String) = g.length in 1..4 && g.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        // 16-bit groups in one side of "::"; a trailing IPv4 address counts as two. null: invalid.
        fun groups(part: String, ipv4Tail: Boolean): Int? {
            if (part.isEmpty()) return 0
            val items = part.split(':')
            var n = 0
            items.forEachIndexed { i, g ->
                n += when {
                    ipv4Tail && i == items.lastIndex && g.contains('.') -> if (ipv4(g)) 2 else return null
                    hex(g) -> 1
                    else -> return null
                }
            }
            return n
        }
        val pieces = value.split("::")
        return when (pieces.size) {
            1 -> groups(value, true) == 8
            2 -> {
                val left = groups(pieces[0], false) ?: return false
                val right = groups(pieces[1], true) ?: return false
                left + right <= 7
            }
            else -> false
        }
    }

    private val CLASSICAL_TYPES = setOf("DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "IP-CIDR", "IP-CIDR6",
        "DST-PORT", "PROCESS-NAME")
    private val KEYWORD = Regex("^[A-Za-z0-9_.-]{1,253}$")
    private val PORTS = Regex("^\\d{1,5}(-\\d{1,5})?$")

    /**
     * `TYPE,payload` or `TYPE,payload,no-resolve` for the matching types only: no target, no
     * logical, rule-set, sub-rule, GEO or script rule. PROCESS-NAME stays inert on Android (the
     * runtime config sets find-process-mode: off) but keeps the upstream list complete.
     */
    fun classical(value: String): Boolean {
        val parts = value.split(',')
        if (parts.size !in 2..3) return false
        val type = parts[0]
        val payload = parts[1]
        if (type !in CLASSICAL_TYPES || payload.isEmpty() || payload.trim() != payload) return false
        if (parts.size == 3 && !(parts[2] == "no-resolve" && type.startsWith("IP-CIDR"))) return false
        return when (type) {
            "DOMAIN", "DOMAIN-SUFFIX" -> plainDomain(payload)
            "DOMAIN-KEYWORD" -> KEYWORD.matches(payload)
            "IP-CIDR", "IP-CIDR6" -> cidr(payload)
            "DST-PORT" -> PORTS.matches(payload) && payload.split('-').all { it.toInt() in 0..65535 }
            "PROCESS-NAME" -> payload.length <= 255 && payload.none { it.code < 32 || it.code == 127 || it in "'\"\\" }
            else -> false
        }
    }

    /** Counts raw bytes: a list may not exceed [limit] however it is encoded or compressed. */
    class Capped(input: InputStream, private val limit: Int) : FilterInputStream(input) {
        private var total = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it) }
        private fun count(n: Int) { total += n; require(total <= limit) { "rule_list_too_large" } }
    }
}
