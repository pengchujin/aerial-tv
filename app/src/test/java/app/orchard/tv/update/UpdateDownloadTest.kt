package app.orchard.tv.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection

class UpdateDownloadTest {
    @Test
    fun resumeSucceedsWhenEachRetryMakesForwardProgress() = runBlocking {
        var bytes = 0L
        var tries = 0
        val resumedAt = mutableListOf<Long>()

        downloadWithResume(
            maxTries = 3,
            currentBytes = { bytes },
            onResume = { at, _ -> resumedAt += at },
        ) {
            when (++tries) {
                1 -> {
                    bytes += 512_000L
                    throw IOException("drop 1")
                }
                2 -> {
                    bytes += 512_000L
                    throw IOException("drop 2")
                }
                3 -> bytes += 512_000L
            }
        }

        assertEquals(3, tries)
        assertEquals(1_536_000L, bytes)
        assertEquals(listOf(512_000L, 1_024_000L), resumedAt)
    }

    @Test
    fun resumeFailsFastWhenRetryDeliversNoNewBytes() = runBlocking {
        var bytes = 0L
        var tries = 0
        val resumedAt = mutableListOf<Long>()

        val error = assertThrows(IOException::class.java) {
            runBlocking {
                downloadWithResume(
                    maxTries = 3,
                    currentBytes = { bytes },
                    onResume = { at, _ -> resumedAt += at },
                ) {
                    when (++tries) {
                        1 -> {
                            bytes += 512_000L
                            throw IOException("drop 1")
                        }
                        2 -> throw IOException("stalled with zero new bytes")
                        else -> error("must not waste try 3")
                    }
                }
            }
        }

        assertEquals("stalled with zero new bytes", error.message)
        assertEquals(2, tries)
        assertEquals(listOf(512_000L), resumedAt)
    }

    @Test
    fun resumeFailsFastWhenRetryRewritesFewerBytesThanBefore() = runBlocking {
        var bytes = 0L
        var tries = 0

        assertThrows(IOException::class.java) {
            runBlocking {
                downloadWithResume(maxTries = 3, currentBytes = { bytes }) {
                    when (++tries) {
                        1 -> {
                            bytes = 512_000L
                            throw IOException("drop 1")
                        }
                        2 -> {
                            // Server ignored Range (HTTP 200), truncated, and dropped before reaching 512 KB.
                            bytes = 128_000L
                            throw IOException("drop 2")
                        }
                        else -> error("must not waste try 3")
                    }
                }
            }
        }

        assertEquals(2, tries)
    }

    @Test
    fun firstTryWithZeroBytesFailsImmediately() {
        var tries = 0
        var resumed = false

        assertThrows(IOException::class.java) {
            runBlocking {
                downloadWithResume(
                    maxTries = 3,
                    currentBytes = { 0L },
                    onResume = { _, _ -> resumed = true },
                ) {
                    tries++
                    throw IOException("unreachable")
                }
            }
        }

        assertEquals(1, tries)
        assertFalse(resumed)
    }

    @Test
    fun resumeStopsAfterMaxTriesEvenWithProgress() {
        var bytes = 0L
        var tries = 0

        assertThrows(IOException::class.java) {
            runBlocking {
                downloadWithResume(maxTries = 3, currentBytes = { bytes }) {
                    tries++
                    bytes += 100_000L
                    throw IOException("drop $tries")
                }
            }
        }

        assertEquals(3, tries)
        assertEquals(300_000L, bytes)
    }

    @Test
    fun cancellationIsNotRetriedEvenAfterProgress() {
        var bytes = 0L
        var tries = 0
        var resumed = false

        assertThrows(CancellationException::class.java) {
            runBlocking {
                downloadWithResume(
                    maxTries = 3,
                    currentBytes = { bytes },
                    onResume = { _, _ -> resumed = true },
                ) {
                    tries++
                    bytes += 100_000L
                    throw CancellationException("cancelled")
                }
            }
        }

        assertEquals(1, tries)
        assertFalse(resumed)
    }

    @Test
    fun http206WithMatchingContentRangeAppendsSafely() {
        val plan = planDownloadResponse(
            code = HttpURLConnection.HTTP_PARTIAL,
            from = 1024L,
            contentLength = 1024L,
            contentRange = "bytes 1024-2047/2048",
        )

        assertTrue(plan.append)
        assertEquals(1024L, plan.startOffset)
        assertEquals(2048L, plan.totalBytes)
    }

    @Test
    fun http206WithUnknownContentLengthOrStarTotalCalculatesTotalFromRange() {
        val chunked = planDownloadResponse(
            code = HttpURLConnection.HTTP_PARTIAL,
            from = 1024L,
            contentLength = -1L,
            contentRange = "bytes 1024-2047/2048",
        )
        assertEquals(DownloadPlan(append = true, startOffset = 1024L, totalBytes = 2048L), chunked)

        val starTotal = planDownloadResponse(
            code = HttpURLConnection.HTTP_PARTIAL,
            from = 1024L,
            contentLength = 1024L,
            contentRange = "bytes 1024-2047/*",
        )
        assertEquals(DownloadPlan(append = true, startOffset = 1024L, totalBytes = 2048L), starTotal)

        val partialChunk = planDownloadResponse(
            code = HttpURLConnection.HTTP_PARTIAL,
            from = 1024L,
            contentLength = 512L,
            contentRange = "bytes 1024-1535/2048",
        )
        assertEquals(DownloadPlan(append = true, startOffset = 1024L, totalBytes = 2048L), partialChunk)
    }

    @Test
    fun http206WithMismatchedOrInvalidContentRangeIsRejected() {
        val badHeaders = listOf(
            null,
            "",
            "   ",
            "bytes 0-2047/2048",
            "bytes 512-2047/2048",
            "bytes 1025-2047/2048",
            "bytes */2048",
            "bytes 1024-1023/2048",
            "bytes 1024-2048/2048",
            "bytes 1024-2047",
            "items 1024-2047/2048",
            "garbage",
        )
        for (header in badHeaders) {
            assertThrows("must reject Content-Range=$header", IllegalStateException::class.java) {
                planDownloadResponse(
                    code = HttpURLConnection.HTTP_PARTIAL,
                    from = 1024L,
                    contentLength = -1L,
                    contentRange = header,
                )
            }
        }
    }

    @Test
    fun http206WithContradictoryContentLengthOrZeroFromIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            planDownloadResponse(
                code = HttpURLConnection.HTTP_PARTIAL,
                from = 1024L,
                contentLength = 512L,
                contentRange = "bytes 1024-2047/2048",
            )
        }
        assertThrows(IllegalStateException::class.java) {
            planDownloadResponse(
                code = HttpURLConnection.HTTP_PARTIAL,
                from = 0L,
                contentLength = 2048L,
                contentRange = "bytes 0-2047/2048",
            )
        }
    }

    @Test
    fun http200RestartsCleanlyFromZeroEvenWhenFromIsPositive() {
        val restarted = planDownloadResponse(
            code = HttpURLConnection.HTTP_OK,
            from = 1024L,
            contentLength = 2048L,
            contentRange = null,
        )
        assertEquals(DownloadPlan(append = false, startOffset = 0L, totalBytes = 2048L), restarted)

        val unknownLength = planDownloadResponse(
            code = HttpURLConnection.HTTP_OK,
            from = 0L,
            contentLength = -1L,
            contentRange = null,
        )
        assertEquals(DownloadPlan(append = false, startOffset = 0L, totalBytes = -1L), unknownLength)
    }

    @Test
    fun http416AndOtherErrorStatusesAreRejected() {
        assertThrows(IllegalStateException::class.java) {
            planDownloadResponse(
                code = 416,
                from = 1024L,
                contentLength = 0L,
                contentRange = "bytes */1024",
            )
        }
        assertThrows(IllegalStateException::class.java) {
            planDownloadResponse(
                code = HttpURLConnection.HTTP_NOT_FOUND,
                from = 0L,
                contentLength = -1L,
                contentRange = null,
            )
        }
    }

    @Test
    fun parseContentRangeHandlesValidAndEdgeCases() {
        assertEquals(ByteRange(1024L, 2047L, 2048L), parseContentRange("bytes 1024-2047/2048"))
        assertEquals(ByteRange(0L, 0L, 1L), parseContentRange("  Bytes 0-0/1  "))
        assertEquals(ByteRange(1024L, 2047L, null), parseContentRange("bytes 1024-2047/*"))
        assertNull(parseContentRange("bytes 0-9223372036854775807/*"))
        assertNull(parseContentRange("bytes 0-99999999999999999999/99999999999999999999"))
        assertNull(parseContentRange("bytes 0-10/100, 20-30/100"))
        assertNull(parseContentRange("bytes -1-10/100"))
    }

    @Test
    fun parseContentLengthSupportsLongHeaderAndFallback() {
        assertEquals(3_000_000_000L, parseContentLength(" 3000000000 ", fallback = -1))
        assertEquals(2048L, parseContentLength(null, fallback = 2048))
        assertEquals(2048L, parseContentLength("not-a-number", fallback = 2048))
        assertEquals(-1L, parseContentLength("-5", fallback = -1))
        assertEquals(0L, parseContentLength("0", fallback = -1))
    }
}
