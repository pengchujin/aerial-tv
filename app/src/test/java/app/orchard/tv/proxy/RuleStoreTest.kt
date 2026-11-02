package app.orchard.tv.proxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.nio.file.Files

class RuleStoreTest {
    private lateinit var home: File
    private var clock = 1_000_000_000_000L
    private val bundled = mutableMapOf<String, String>()

    @Before fun setUp() {
        home = Files.createTempDirectory("rules").toFile()
        RuleLists.BLACKLIST_ENTRIES.forEach { bundled[it.name] = sample(it) }
    }

    @After fun tearDown() { home.deleteRecursively() }

    private fun store() = RuleStore(home, { name -> bundled[name]?.let { ByteArrayInputStream(it.toByteArray()) } }, SNAPSHOT_AT, { clock })

    private fun sample(entry: RuleLists.Entry) = when (entry.behavior) {
        RuleLists.Behavior.DOMAIN -> "payload:\n  - '+.${entry.name}.example'\n"
        RuleLists.Behavior.IPCIDR -> "payload:\n  - '91.108.4.0/22'\n"
        RuleLists.Behavior.CLASSICAL -> "payload:\n  - PROCESS-NAME,frpc\n"
    }

    private fun serving(vararg bodies: Pair<String, String>, calls: MutableList<String> = mutableListOf()) = RuleStore.Fetch { url, route, read ->
        calls += "${if (route == Proxy.NO_PROXY) "direct" else "proxy"} $url"
        val body = bodies.firstOrNull { url.endsWith(it.first) }?.second ?: throw UnknownHostException("x")
        read(ByteArrayInputStream(body.toByteArray()))
    }

    @Test fun prepareExtractsBundledSnapshotsAndReportsThem() {
        val store = store()
        val lists = store.prepare(listOf(RuleLists.BLACKLIST))
        assertEquals(RuleLists.BLACKLIST_ENTRIES.map { it.name }.toSet(), lists.keys)
        assertEquals("./aerial-rules/blacklist/gfw.list", lists["gfw"])
        assertEquals("+.gfw.example\n", store.file(RuleLists.BLACKLIST, "gfw").readText())
        val gfw = store.status(RuleLists.BLACKLIST).single { it.name == "gfw" }
        assertEquals("snapshot", gfw.source); assertEquals(1, gfw.count); assertEquals(SNAPSHOT_AT, gfw.updatedAt)
        assertTrue(gfw.present); assertNull(gfw.error)
        // A snapshot is not a download: the list is still due for one.
        assertTrue(store.due(RuleLists.BLACKLIST, "gfw"))
    }

    @Test fun listsWithoutABundledCopyStayMissingUntilDownloaded() {
        val store = store()
        assertEquals(emptyMap<String, String>(), store.prepare(listOf(RuleLists.ADBLOCK)))
        val reject = store.status(RuleLists.ADBLOCK).single()
        assertFalse(reject.present); assertNull(reject.source); assertEquals(0, reject.count)
        assertEquals(listOf("reject"), store.update(RuleLists.ADBLOCK, null, listOf(Proxy.NO_PROXY), serving("reject.txt" to "payload:\n  - '+.ads.example'\n")))
        assertEquals(mapOf("reject" to "./aerial-rules/adblock/reject.list"), store.prepare(listOf(RuleLists.ADBLOCK)))
    }

    @Test fun downloadReplacesTheFileAndReportsChangesOnlyWhenContentChanges() {
        val store = store()
        store.prepare(listOf(RuleLists.BLACKLIST))
        val body = "payload:\n  - '+.new.example'\n  - '+.other.example'\n"
        val calls = mutableListOf<String>()
        val changed = store.update(RuleLists.BLACKLIST, listOf("gfw"), listOf(PROXY, Proxy.NO_PROXY), serving("gfw.txt" to body, calls = calls))
        assertEquals(listOf("gfw"), changed)
        assertEquals("+.new.example\n+.other.example\n", store.file(RuleLists.BLACKLIST, "gfw").readText())
        // First URL over the first route (the core's local port) was enough.
        assertEquals(listOf("proxy ${RuleLists.RAW}gfw.txt"), calls)
        val gfw = store.status(RuleLists.BLACKLIST).single { it.name == "gfw" }
        assertEquals("download", gfw.source); assertEquals(2, gfw.count); assertEquals(clock, gfw.updatedAt)
        assertFalse(store.due(RuleLists.BLACKLIST, "gfw"))
        clock += 60_000
        assertEquals(emptyList<String>(), store.update(RuleLists.BLACKLIST, listOf("gfw"), listOf(Proxy.NO_PROXY), serving("gfw.txt" to body)))
        assertFalse(File(store.file(RuleLists.BLACKLIST, "gfw").parentFile, "gfw.download.part").exists())
    }

    @Test fun failedDownloadKeepsTheLastGoodFileAndRecordsAFixedCode() {
        val store = store()
        store.prepare(listOf(RuleLists.BLACKLIST))
        val calls = mutableListOf<String>()
        assertEquals(emptyList<String>(), store.update(RuleLists.BLACKLIST, listOf("gfw"), listOf(PROXY, Proxy.NO_PROXY), serving(calls = calls)))
        assertEquals("+.gfw.example\n", store.file(RuleLists.BLACKLIST, "gfw").readText())
        val gfw = store.status(RuleLists.BLACKLIST).single { it.name == "gfw" }
        assertEquals("rule_list_dns_failed", gfw.error); assertEquals("snapshot", gfw.source)
        // Both sources over both routes were tried, in order.
        assertEquals(listOf("proxy ${RuleLists.RAW}gfw.txt", "direct ${RuleLists.RAW}gfw.txt",
            "proxy ${RuleLists.JSDELIVR}gfw.txt", "direct ${RuleLists.JSDELIVR}gfw.txt"), calls)
        // Retried an hour after the failed attempt, not at once.
        assertFalse(store.due(RuleLists.BLACKLIST, "gfw"))
        clock += 61 * 60_000L
        assertTrue(store.due(RuleLists.BLACKLIST, "gfw"))
    }

    @Test fun hostileContentFallsBackToTheMirrorAndNeverReachesTheFile() {
        val store = store()
        store.prepare(listOf(RuleLists.BLACKLIST))
        val calls = mutableListOf<String>()
        val fetch = RuleStore.Fetch { url, route, read ->
            calls += "${if (route == Proxy.NO_PROXY) "direct" else "proxy"} $url"
            val body = if (url.startsWith(RuleLists.RAW)) "payload:\n  - '+.ok.example'\n  - 'MATCH,DIRECT'\n" else "payload:\n  - '+.mirror.example'\n"
            read(ByteArrayInputStream(body.toByteArray()))
        }
        assertEquals(listOf("gfw"), store.update(RuleLists.BLACKLIST, listOf("gfw"), listOf(PROXY, Proxy.NO_PROXY), fetch))
        assertEquals("+.mirror.example\n", store.file(RuleLists.BLACKLIST, "gfw").readText())
        // Bad content is not retried over another route: the same file would come back.
        assertEquals(listOf("proxy ${RuleLists.RAW}gfw.txt", "proxy ${RuleLists.JSDELIVR}gfw.txt"), calls)

        val bad = RuleStore.Fetch { _, _, read -> read(ByteArrayInputStream("payload:\n  - 'a.example'\nrules:\n  - MATCH,x\n".toByteArray())) }
        store.update(RuleLists.BLACKLIST, listOf("gfw"), listOf(Proxy.NO_PROXY), bad)
        assertEquals("+.mirror.example\n", store.file(RuleLists.BLACKLIST, "gfw").readText())
        assertEquals("rule_list_invalid", store.status(RuleLists.BLACKLIST).single { it.name == "gfw" }.error)
    }

    @Test fun listsBecomeDueADayAfterTheirLastDownload() {
        val store = store()
        store.update(RuleLists.BLACKLIST, null, listOf(Proxy.NO_PROXY), serving(*RuleLists.BLACKLIST_ENTRIES.map { "${it.name}.txt" to sample(it) }.toTypedArray()))
        assertFalse(store.dueAny(listOf(RuleLists.BLACKLIST)))
        clock += 23 * 3_600_000L
        assertFalse(store.dueAny(listOf(RuleLists.BLACKLIST)))
        clock += 3_600_000L
        assertTrue(store.dueAny(listOf(RuleLists.BLACKLIST)))
        // State survives a new process.
        val again = store()
        assertTrue(again.due(RuleLists.BLACKLIST, "gfw"))
        assertEquals("download", again.status(RuleLists.BLACKLIST).single { it.name == "gfw" }.source)
    }

    @Test fun customUrlsAreHttpsOnlyAndNeverGetTheBundledCopy() {
        val store = store()
        store.setUrls(RuleLists.BLACKLIST, "gfw", listOf(" https://mirror.example.invalid/gfw.txt ", ""))
        assertEquals(listOf("https://mirror.example.invalid/gfw.txt"), store.urls(RuleLists.BLACKLIST, "gfw"))
        assertTrue(store.custom(RuleLists.BLACKLIST, "gfw"))
        assertFalse("gfw" in store.prepare(listOf(RuleLists.BLACKLIST)))
        assertTrue("private" in store.prepare(listOf(RuleLists.BLACKLIST)))
        listOf(listOf("http://mirror.example.invalid/gfw.txt"), listOf("https://u:p@mirror.example.invalid/x"),
            List(5) { "https://m$it.example.invalid/x" }).forEach { urls ->
            assertEquals(urls.toString(), "invalid_rule_url",
                assertThrows(Exception::class.java) { store.setUrls(RuleLists.BLACKLIST, "gfw", urls) }.message)
        }
        assertEquals("invalid_rule_list", assertThrows(IllegalStateException::class.java) {
            store.setUrls(RuleLists.BLACKLIST, "direct", listOf("https://x.example.invalid/a")) }.message)
        // Reset goes back to the defaults; setting the defaults is the same as a reset.
        store.resetSet(RuleLists.BLACKLIST)
        assertFalse(store.custom(RuleLists.BLACKLIST, "gfw"))
        store.setUrls(RuleLists.BLACKLIST, "gfw", RuleLists.defaultUrls("gfw"))
        assertFalse(store.custom(RuleLists.BLACKLIST, "gfw"))
        assertEquals(RuleLists.defaultUrls("gfw"), store().urls(RuleLists.BLACKLIST, "gfw"))
    }

    @Test fun editingAUrlMakesTheListDueAgain() {
        val store = store()
        store.update(RuleLists.BLACKLIST, listOf("gfw"), listOf(Proxy.NO_PROXY), serving("gfw.txt" to sample(RuleLists.entry(RuleLists.BLACKLIST, "gfw"))))
        assertFalse(store.due(RuleLists.BLACKLIST, "gfw"))
        store.setUrls(RuleLists.BLACKLIST, "gfw", listOf("https://mirror.example.invalid/gfw.txt"))
        assertTrue(store.due(RuleLists.BLACKLIST, "gfw"))
    }

    @Test fun errorCodesAreFixed() {
        assertEquals("rule_list_dns_failed", RuleStore.code(UnknownHostException("secret-token.example")))
        assertEquals("rule_list_timeout", RuleStore.code(java.net.SocketTimeoutException()))
        assertEquals("rule_list_download_failed", RuleStore.code(IllegalStateException("https://u:p@x/?token=1")))
        assertEquals("rule_list_http_not_found", RuleStore.code(IllegalStateException("rule_list_http_not_found")))
        assertEquals("rule_list_download_failed", RuleStore.code(null))
    }

    @Test fun aDamagedStateFileStartsEmpty() {
        File(home, "aerial-rules").mkdirs()
        File(home, "aerial-rules/state.properties").writeBytes(ByteArray(300 * 1024) { 'x'.code.toByte() })
        val store = store()
        assertTrue(store.due(RuleLists.BLACKLIST, "gfw"))
        assertEquals(RuleLists.BLACKLIST_ENTRIES.size, store.prepare(listOf(RuleLists.BLACKLIST)).size)
    }

    private companion object {
        const val SNAPSHOT_AT = 1_793_333_422_910L
        val PROXY = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", 7890))
    }
}
