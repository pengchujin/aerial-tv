package app.orchard.tv.watchnext

import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WatchNextImageFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedDownloadDoesNotCacheOrChangeTheProviderAddress() {
        val directory = temporary.newFolder()
        val address = "https://example.org/poster.jpg?token=private"
        val requests = mutableListOf<String>()
        val cache = WatchNextImageFiles(directory) { original, _ ->
            requests += original
            throw java.io.IOException("Origin unavailable")
        }
        repeat(2) {
            assertThrows(java.io.IOException::class.java) { cache.get(address, CancellationSignal()) }
        }
        assertEquals(listOf(address, address), requests)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun encodedImageSurvivesNewCacheInstance() {
        val directory = temporary.newFolder()
        val expected = byteArrayOf(1, 2, 3)
        val first = WatchNextImageFiles(directory) { _, _ -> expected }
        assertArrayEquals(expected, first.get("https://example/image", CancellationSignal()))
        val next = WatchNextImageFiles(directory) { _, _ -> error("Must use disk") }
        assertArrayEquals(expected, next.get("https://example/image", CancellationSignal()))
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun backgroundMissDoesNotDownloadAndLateDownloadOnlyHelpsNextVisit() {
        var clock = 100L
        var downloads = 0
        val cache = WatchNextImageFiles(temporary.newFolder(), now = { clock }) { _, _ ->
            downloads++; byteArrayOf(7)
        }
        val signal = CancellationSignal()
        assertThrows(IllegalStateException::class.java) { cache.get("image", signal, cachedBefore = 100) }
        assertEquals(0, downloads)
        clock = 110
        cache.get("image", signal)
        assertThrows(IllegalStateException::class.java) { cache.get("image", signal, cachedBefore = 100) }
        assertArrayEquals(byteArrayOf(7), cache.get("image", signal, cachedBefore = 120))
        assertEquals(1, downloads)
    }

    @Test fun expiryUsesDownloadTimeRatherThanLastAccess() {
        var clock = 100L
        var downloads = 0
        val cache = WatchNextImageFiles(temporary.newFolder(), lifetimeMs = 50, now = { clock }) { _, _ ->
            byteArrayOf((++downloads).toByte())
        }
        assertEquals(1, cache.get("https://example/image", CancellationSignal())[0].toInt())
        clock = 149
        assertEquals(1, cache.get("https://example/image", CancellationSignal())[0].toInt())
        clock = 151
        assertEquals(2, cache.get("https://example/image", CancellationSignal())[0].toInt())
    }

    @Test fun cacheEvictsLeastRecentlyUsedWithoutTouchingOtherFiles() {
        val directory = temporary.newFolder()
        val other = java.io.File(directory, "unrelated.txt").apply { writeText("keep") }
        var clock = 100L
        val downloads = mutableMapOf<String, Int>()
        val cache = WatchNextImageFiles(directory, maxBytes = 24, now = { clock++ }) { address, _ ->
            downloads[address] = (downloads[address] ?: 0) + 1
            byteArrayOf(1, 2, 3, 4)
        }
        fun read(key: String) = cache.get(key, CancellationSignal())
        read("a"); read("b"); read("a"); read("c"); read("a")
        assertEquals(1, downloads["a"])
        read("b")
        assertEquals(2, downloads["b"])
        assertTrue(directory.listFiles()!!.filter { it.extension == "img" }.sumOf { it.length() } <= 24)
        assertEquals("keep", other.readText())
    }

    @Test fun cancellationDoesNotPublishPartialCacheEntry() {
        val directory = temporary.newFolder()
        val signal = CancellationSignal()
        val cache = WatchNextImageFiles(directory) { _, _ -> signal.cancel(); byteArrayOf(1) }
        assertThrows(OperationCanceledException::class.java) { cache.get("image", signal) }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun simultaneousPrefetchAndDisplayShareOneDownload() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val workers = Executors.newFixedThreadPool(2)
        val cache = WatchNextImageFiles(temporary.newFolder()) { _, _ ->
            count.incrementAndGet(); started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            byteArrayOf(7)
        }
        try {
            val first = workers.submit<ByteArray> { cache.get("same", CancellationSignal()) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) {
                cache.get("same", CancellationSignal(), cachedBefore = System.currentTimeMillis())
            }
            val second = workers.submit<ByteArray> { cache.get("same", CancellationSignal()) }
            release.countDown()
            assertArrayEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            assertEquals(1, count.get())
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun cachedReadsDoNotWaitForAnotherAddressInTheSameRequestBucket() {
        // These distinct URLs share a request stripe; only their own disk entries matter on a hit.
        val downloading = "https://example.org/image/1"
        val cached = "https://example.org/image/2"
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val workers = Executors.newFixedThreadPool(2)
        val cache = WatchNextImageFiles(temporary.newFolder()) { address, _ ->
            count.incrementAndGet()
            if (address == downloading) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            byteArrayOf(7)
        }
        val signal = CancellationSignal()
        cache.get(cached, signal)
        val enteredAt = System.currentTimeMillis()
        try {
            val fetch = workers.submit<ByteArray> { cache.get(downloading, signal) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val hit = workers.submit {
                assertArrayEquals(byteArrayOf(7), cache.get(cached, signal, cachedBefore = enteredAt))
                assertArrayEquals(byteArrayOf(7), cache.get(cached, signal))
            }
            hit.get(2, TimeUnit.SECONDS)
            assertEquals(2, count.get())
            release.countDown()
            fetch.get(5, TimeUnit.SECONDS)
        } finally { release.countDown(); workers.shutdownNow() }
    }
}
