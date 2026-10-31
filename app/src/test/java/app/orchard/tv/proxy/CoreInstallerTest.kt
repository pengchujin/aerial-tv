package app.orchard.tv.proxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class CoreInstallerTest {
    private lateinit var dir: File

    /** A small stand-in for the core: 5,000 bytes of seeded random data, so its gzip is about as long. */
    private val binary = ByteArray(5000).also { java.util.Random(42).nextBytes(it) }
    private val asset = gzip(binary)
    private val spec = CoreSpec("9.9.9", sha(asset), asset.size.toLong(), sha(binary), binary.size.toLong(),
        maxAssetBytes = 64 * 1024, maxBinaryBytes = 64 * 1024)
    private val a = "https://a.example.invalid/core.gz"
    private val b = "https://b.example.invalid/core.gz"

    @Before fun setUp() { dir = Files.createTempDirectory("core").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    // Fixtures

    private fun gzip(bytes: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Serves [data] like a server: ranged when [ranges], else always the whole file; records every open. */
    private class Server(
        val data: ByteArray, val ranges: Boolean = true, val calls: MutableList<String> = mutableListOf(),
        /** Throws a stall after this many bytes of a response (null: never). */
        val stallAfter: Int? = null, val total: Long? = null, val onRead: () -> Unit = {},
    ) : CoreFetch {
        override fun open(url: String, from: Long): CoreReply {
            calls += "$url@$from"
            val whole = total ?: data.size.toLong()
            if (from > 0 && ranges) {
                if (from >= data.size) return CoreReply(416, 0, -1, ByteArrayInputStream(ByteArray(0)))
                return CoreReply(206, from, whole, body(data.copyOfRange(from.toInt(), data.size)))
            }
            return CoreReply(200, 0, whole, body(data))
        }

        private fun body(bytes: ByteArray): InputStream = object : InputStream() {
            var at = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                onRead()
                if (at >= bytes.size) return -1
                if (stallAfter != null && at >= stallAfter) throw SocketTimeoutException("Read timed out")
                val cap = if (stallAfter != null) minOf(len, stallAfter - at, 700) else minOf(len, 700)
                val n = minOf(cap, bytes.size - at)
                System.arraycopy(bytes, at, b, off, n)
                at += n
                return n
            }
        }
    }

    private fun failing(status: Int, calls: MutableList<String> = mutableListOf()) = CoreFetch { url, from ->
        calls += "$url@$from"; throw CoreHttpException(status)
    }

    private fun router(vararg routes: Pair<String, CoreFetch>) = CoreFetch { url, from ->
        routes.first { it.first == url }.second.open(url, from)
    }

    private fun installer(fetch: CoreFetch, sources: List<String> = listOf(a), spec: CoreSpec = this.spec,
                          selfTest: (File) -> String? = { null }, mark: (File) -> Boolean = { it.setExecutable(true, true) },
                          clock: () -> Long = System::currentTimeMillis) =
        CoreInstaller(File(dir, "core"), sources, fetch, spec, selfTest, mark, clock)

    private fun leftovers() = File(dir, "core").listFiles().orEmpty().map { it.name }.sorted()

    // Install

    @Test fun downloadsVerifiesAndInstallsTheCore() {
        val server = Server(asset)
        val core = installer(server)
        assertFalse(core.installed())
        val result = core.run()
        assertEquals("idle", result.state)
        assertNull(result.error)
        assertTrue(core.installed())
        assertTrue(binary.contentEquals(core.file.readBytes()))
        assertEquals(listOf("$a@0"), server.calls)
        // Nothing but the core is left behind.
        assertEquals(listOf("mihomo"), leftovers())
    }

    @Test fun anInstalledCoreIsNotDownloadedAgain() {
        val server = Server(asset)
        val core = installer(server)
        core.run()
        server.calls.clear()
        core.run()
        assertEquals(emptyList<String>(), server.calls)
    }

    @Test fun markExecutableRunsOnlyAfterBothHashesPass() {
        val order = mutableListOf<String>()
        val core = installer(Server(asset), mark = { order += "chmod:" + (it.length() == binary.size.toLong()); true })
        core.run()
        // Called once, on the fully extracted file, before the rename.
        assertEquals(listOf("chmod:true"), order)
    }

    @Test fun aCompressedHashMismatchDeletesEverythingAndNeverMakesAnythingExecutable() {
        val bad = asset.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val marks = mutableListOf<File>()
        val server = Server(bad)
        val core = installer(server, mark = { marks += it; true })
        val result = core.run()
        assertEquals("failed", result.state)
        assertEquals(CoreError.INTEGRITY, result.error)
        assertEquals(emptyList<File>(), marks)
        assertFalse(core.file.exists())
        assertEquals(emptyList<String>(), leftovers())
        // A wrong file from a fresh download is not asked for again from the same source.
        assertEquals(listOf("$a@0"), server.calls)
    }

    @Test fun anExtractedHashMismatchDeletesEverything() {
        // A genuine gzip of other bytes whose compressed hash the spec pins: the extraction check catches it.
        val other = ByteArray(binary.size) { 1 }
        val otherAsset = gzip(other)
        val s = CoreSpec("9.9.9", sha(otherAsset), otherAsset.size.toLong(), sha(binary), binary.size.toLong(), 64 * 1024, 64 * 1024)
        val marks = mutableListOf<File>()
        val result = installer(Server(otherAsset), spec = s, mark = { marks += it; true }).run()
        assertEquals(CoreError.INTEGRITY, result.error)
        assertEquals(emptyList<File>(), marks)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun aCompressionBombIsCutOffAtTheExtractedSize() {
        val bomb = gzip(ByteArray(60_000))
        val s = CoreSpec("9.9.9", sha(bomb), bomb.size.toLong(), sha(binary), binary.size.toLong(), 64 * 1024, 64 * 1024)
        val marks = mutableListOf<File>()
        val result = installer(Server(bomb), spec = s, mark = { marks += it; true }).run()
        assertEquals(CoreError.INTEGRITY, result.error)
        assertEquals(emptyList<File>(), marks)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun anExtractedFileOfTheWrongSizeIsRejected() {
        val shorter = binary.copyOf(binary.size - 1)
        val shortAsset = gzip(shorter)
        val s = CoreSpec("9.9.9", sha(shortAsset), shortAsset.size.toLong(), sha(binary), binary.size.toLong(), 64 * 1024, 64 * 1024)
        assertEquals(CoreError.INTEGRITY, installer(Server(shortAsset), spec = s).run().error)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun aSourceThatSaysAnotherSizeIsNotServingThePinnedFile() {
        val server = Server(asset, total = asset.size + 10L)
        val result = installer(server).run()
        assertEquals(CoreError.INTEGRITY, result.error)
        assertEquals(emptyList<String>(), leftovers())
        assertEquals(listOf("$a@0"), server.calls)
    }

    @Test fun moreBytesThanThePinnedSizeAreCutOff() {
        // The server announces the pinned size but keeps sending: cut off mid-stream.
        val padded = asset + ByteArray(5000)
        val server = Server(padded, total = asset.size.toLong())
        val result = installer(server).run()
        assertEquals(CoreError.INTEGRITY, result.error)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun theAssetSizeCapAppliesEvenIfThePinWereHigher() {
        val big = ByteArray(200)
        val s = CoreSpec("9.9.9", sha(big), 10_000_000L, sha(big), 200, maxAssetBytes = 1000, maxBinaryBytes = 1000)
        val result = installer(Server(ByteArray(5000), total = 10_000_000L), spec = s).run()
        assertEquals(CoreError.INTEGRITY, result.error)
    }

    @Test fun aSelfTestRefusalDeletesTheCoreWithTheFixedCode() {
        val result = installer(Server(asset), selfTest = { CoreError.EXEC_NOT_PERMITTED }).run()
        assertEquals("failed", result.state)
        assertEquals(CoreError.EXEC_NOT_PERMITTED, result.error)
        assertFalse(File(dir, "core/mihomo").exists())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun aFailedMarkExecutableLeavesNoCore() {
        val core = installer(Server(asset), mark = { false })
        val result = core.run()
        assertEquals(CoreError.STORAGE, result.error)
        assertFalse(core.installed())
        // The verified download may stay for the next try; no half-made core does.
        assertFalse(File(dir, "core/mihomo").exists())
        assertFalse(File(dir, "core/mihomo.tmp").exists())
    }

    // Resume

    private fun seedPart(url: String, length: Int) {
        File(dir, "core").mkdirs()
        File(dir, "core/mihomo.gz.part").writeBytes(asset.copyOf(length))
        File(dir, "core/mihomo.gz.src").writeText(url)
    }

    @Test fun aPartFromTheSameUrlContinuesWithARangeRequest() {
        seedPart(a, 1200)
        val server = Server(asset)
        val core = installer(server)
        assertEquals(1200L, core.resumeOffset(a))
        assertNull(core.run().error)
        assertEquals(listOf("$a@1200"), server.calls)
        assertTrue(binary.contentEquals(core.file.readBytes()))
    }

    @Test fun aServerThatIgnoresRangeGetsAFreshStart() {
        seedPart(a, 1200)
        val server = Server(asset, ranges = false)
        val core = installer(server)
        assertNull(core.run().error)
        // Asked to continue, answered with the whole file (200): the old bytes were dropped, not kept.
        assertEquals(listOf("$a@1200", "$a@0"), server.calls)
        assertTrue(binary.contentEquals(core.file.readBytes()))
    }

    @Test fun aRangeAnswerAtAnotherOffsetIsNotSplicedIn() {
        seedPart(a, 1200)
        val wrongOffset = CoreFetch { url, from ->
            if (from > 0) CoreReply(206, from - 100, asset.size.toLong(), ByteArrayInputStream(asset.copyOfRange((from - 100).toInt(), asset.size)))
            else CoreReply(200, 0, asset.size.toLong(), ByteArrayInputStream(asset))
        }
        assertNull(installer(wrongOffset).run().error)
    }

    @Test fun aPartFromAnotherUrlIsNeverContinued() {
        seedPart(b, 1200)
        val core = installer(Server(asset))
        assertEquals(0L, core.resumeOffset(a))
        val server = Server(asset)
        assertNull(installer(server).run().error)
        assertEquals(listOf("$a@0"), server.calls)
    }

    @Test fun aPartWithoutAMarkerOrAlreadyFullLengthIsNotContinued() {
        File(dir, "core").mkdirs()
        File(dir, "core/mihomo.gz.part").writeBytes(asset.copyOf(300))
        assertEquals(0L, installer(Server(asset)).resumeOffset(a))
        seedPart(a, asset.size)
        assertEquals(0L, installer(Server(asset)).resumeOffset(a))
    }

    @Test fun aRangeNotSatisfiableAnswerRestartsFromZero() {
        seedPart(a, 1200)
        val calls = mutableListOf<String>()
        val server = CoreFetch { url, from ->
            calls += "$url@$from"
            if (from > 0) CoreReply(416, 0, -1, ByteArrayInputStream(ByteArray(0)))
            else CoreReply(200, 0, asset.size.toLong(), ByteArrayInputStream(asset))
        }
        assertNull(installer(server).run().error)
        assertEquals(listOf("$a@1200", "$a@0"), calls)
    }

    @Test fun aResumedFileThatFailsItsHashGetsOneFreshTryFromZero() {
        // The first 1200 bytes on disk are from a different version of the file.
        File(dir, "core").mkdirs()
        File(dir, "core/mihomo.gz.part").writeBytes(ByteArray(1200) { 9 })
        File(dir, "core/mihomo.gz.src").writeText(a)
        val server = Server(asset)
        val result = installer(server).run()
        assertNull(result.error)
        assertEquals(listOf("$a@1200", "$a@0"), server.calls)
        assertTrue(binary.contentEquals(File(dir, "core/mihomo").readBytes()))
    }

    // Stalls and several sources

    @Test fun aStalledSourceMovesOnToTheNextAndNeverSplicesTheTwo() {
        val stalling = Server(asset, stallAfter = 1000)
        val good = Server(asset)
        val core = installer(router(a to stalling, b to good), sources = listOf(a, b))
        val result = core.run()
        assertNull(result.error)
        assertEquals(listOf("$a@0"), stalling.calls)
        // The second source starts at byte 0: A's 1000 bytes belong to A only.
        assertEquals(listOf("$b@0"), good.calls)
        assertTrue(binary.contentEquals(core.file.readBytes()))
    }

    @Test fun aSourceThatDroppedIsContinuedInTheSecondRound() {
        val dropping = object : CoreFetch {
            val calls = mutableListOf<String>()
            override fun open(url: String, from: Long): CoreReply {
                calls += "$url@$from"
                // First visit: 1000 bytes then a stall. Later: the rest, via Range.
                return if (calls.size == 1) Server(asset, stallAfter = 1000).open(url, from) else Server(asset).open(url, from)
            }
        }
        val unavailable = failing(503)
        val core = installer(router(a to dropping, b to unavailable), sources = listOf(a, b))
        assertNull(core.run().error)
        // B failing to open did not cost A its bytes: A is resumed where it stopped.
        assertEquals(listOf("$a@0", "$a@1000"), dropping.calls)
    }

    @Test fun whenEverySourceFailsTheErrorIsFixedAndThePartFileStays() {
        val stalling = Server(asset, stallAfter = 1000)
        val core = installer(stalling)
        val result = core.run()
        assertEquals("failed", result.state)
        assertEquals(CoreError.DOWNLOAD_FAILED, result.error)
        assertTrue(File(dir, "core/mihomo.gz.part").length() in 1 until asset.size.toLong())
        assertEquals(2, stalling.calls.size)
        assertFalse(core.installed())
    }

    @Test fun aPermanentHttpErrorIsNotRetried() {
        val calls = mutableListOf<String>()
        val result = installer(failing(404, calls)).run()
        assertEquals(CoreError.DOWNLOAD_FAILED, result.error)
        assertEquals(listOf("$a@0"), calls)
    }

    @Test fun aServerErrorIsAskedAgainInTheSecondRound() {
        val calls = mutableListOf<String>()
        val result = installer(failing(503, calls)).run()
        assertEquals(CoreError.DOWNLOAD_FAILED, result.error)
        assertEquals(2, calls.size)
    }

    @Test fun theWorseFailureWinsWhenSourcesFailDifferently() {
        val bad = asset.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val result = installer(router(a to failing(503), b to Server(bad)), sources = listOf(a, b)).run()
        assertEquals(CoreError.INTEGRITY, result.error)
    }

    // Cancel, progress, busy

    @Test fun cancellingKeepsThePartAndReturnsToIdleWithoutAnError() {
        lateinit var core: CoreInstaller
        var reads = 0
        val server = Server(asset, onRead = { if (++reads == 3) core.cancel() })
        core = installer(server)
        val result = core.run()
        assertEquals("idle", result.state)
        assertNull(result.error)
        assertFalse(core.installed())
        val kept = File(dir, "core/mihomo.gz.part").length()
        assertTrue(kept in 1 until asset.size.toLong())
        // Starting again continues where it stopped.
        val again = Server(asset)
        val second = CoreInstaller(File(dir, "core"), listOf(a), again, spec)
        assertNull(second.run().error)
        assertEquals(listOf("$a@$kept"), again.calls)
    }

    @Test fun progressCountsBytesAndMeasuresSpeedOverAboutASecond() {
        var now = 0L
        val seen = mutableListOf<CoreProgress>()
        lateinit var core: CoreInstaller
        val server = Server(asset, onRead = { now += 300; seen += core.progress() })
        core = installer(server, clock = { now })
        core.run()
        val downloading = seen.filter { it.state == "downloading" }
        assertTrue(downloading.isNotEmpty())
        assertEquals(asset.size.toLong(), downloading.last().total)
        assertTrue(downloading.zipWithNext().all { (x, y) -> y.received >= x.received })
        assertTrue(downloading.any { it.speed > 0 })
        assertEquals(CoreProgress(), core.progress())
    }

    @Test fun aSecondStartWhileOneRunsIsRefused() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val core = installer(Server(asset, onRead = { gate.await() }))
        assertTrue(core.start())
        assertFalse(core.start())
        assertEquals("downloading", core.progress().state)
        gate.countDown()
        val until = System.currentTimeMillis() + 10_000
        while (core.progress().state == "downloading" && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(core.installed())
    }

    @Test fun aWriteThatRunsOutOfSpaceIsAStorageError() {
        val full = CoreFetch { _, _ ->
            CoreReply(200, 0, asset.size.toLong(), object : InputStream() {
                override fun read(): Int = throw IOException("No space left on device")
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("No space left on device")
            })
        }
        assertEquals(CoreError.STORAGE, installer(full).run().error)
    }

    @Test fun storageAndOtherFailuresMapToFixedCodes() {
        assertTrue(CoreError.isStorage(IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(CoreError.isStorage(IOException("Read-only file system")))
        assertFalse(CoreError.isStorage(IOException("Connection reset")))
        assertEquals(CoreError.INTEGRITY, CoreError.worse(CoreError.STORAGE, CoreError.INTEGRITY))
        assertEquals(CoreError.STORAGE, CoreError.worse(CoreError.DOWNLOAD_FAILED, CoreError.STORAGE))
        assertEquals(CoreError.STORAGE, CoreError.worse(CoreError.STORAGE, CoreError.DOWNLOAD_FAILED))
        assertTrue(CoreError.ALL.all { it.startsWith("core_") })
    }
}
