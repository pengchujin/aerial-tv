package app.orchard.tv.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class CoreHttpTest {
    // Sources

    @Test fun everySourceIsAnHttpsUrlOfThePinnedAsset() {
        val asset = "mihomo-linux-armv7-v${CoreSpec.PINNED.version}.gz"
        assertTrue(CoreSources.URLS.isNotEmpty())
        CoreSources.URLS.forEach { url ->
            assertTrue(url, url.startsWith("https://"))
            assertTrue(url, url.endsWith("/MetaCubeX/mihomo/releases/download/v${CoreSpec.PINNED.version}/$asset"))
            assertEquals(url, CoreHttp.checkedUrl(url))
        }
        assertEquals(CoreSources.URLS.size, CoreSources.URLS.toSet().size)
        // Whatever the order, the official release is among them.
        assertTrue(CoreSources.URLS.any { it.startsWith("https://github.com/MetaCubeX/mihomo/releases/download/") })
    }

    @Test fun thePinsMatchTheFetchScriptAndTheNotice() {
        val spec = CoreSpec.PINNED
        val files = listOf("tools/fetch-mihomo.sh", "tools/fetch-mihomo.ps1", "src/main/assets/proxy/NOTICE.txt")
        files.forEach { path ->
            val f = listOf(File(path), File("app/$path"), File("../$path")).firstOrNull { it.isFile }
            org.junit.Assume.assumeTrue("$path not found from the test's working directory", f != null)
            val text = f!!.readText()
            assertTrue(path, spec.assetSha256 in text)
            assertTrue(path, spec.binarySha256 in text)
        }
    }

    @Test fun thePinnedSizesAndCapsAreTheOnesDocumented() {
        val spec = CoreSpec.PINNED
        assertEquals(21_190_617L, spec.assetBytes)
        assertEquals(56_230_014L, spec.binaryBytes)
        assertEquals(32L shl 20, spec.maxAssetBytes)
        assertEquals(80L shl 20, spec.maxBinaryBytes)
        assertTrue(spec.assetBytes <= spec.maxAssetBytes && spec.binaryBytes <= spec.maxBinaryBytes)
    }

    // URLs and redirects

    @Test fun onlyPlainHttpsUrlsWithoutCredentialsAreAccepted() {
        listOf("http://github.com/x", "ftp://x.example/a", "file:///data/x", "https://user:pw@host.example/a", "https:///a", "not a url", "")
            .forEach { url -> assertEquals(url, "core_bad_url", assertThrows(IOException::class.java) { CoreHttp.checkedUrl(url) }.message) }
        assertEquals("https://example.com/a%20b", CoreHttp.checkedUrl(" https://example.com/a%20b "))
    }

    @Test fun redirectsStayOnHttpsAndMayBeRelative() {
        assertEquals("https://objects.example/x.gz", CoreHttp.redirect("https://github.com/a/b", "https://objects.example/x.gz"))
        assertEquals("https://github.com/other/x.gz", CoreHttp.redirect("https://github.com/a/b", "/other/x.gz"))
        assertEquals("core_bad_url", assertThrows(IOException::class.java) { CoreHttp.redirect("https://github.com/a", "http://evil.example/x") }.message)
        assertEquals("core_bad_redirect", assertThrows(IOException::class.java) { CoreHttp.redirect("https://github.com/a", null) }.message)
        assertEquals("core_bad_redirect", assertThrows(IOException::class.java) { CoreHttp.redirect("https://github.com/a", " ") }.message)
        assertEquals(5, CoreHttp.MAX_REDIRECTS)
    }

    @Test fun aSourceIsGivenUpAfterTwentySecondsWithoutBytes() {
        assertEquals(20_000, CoreHttp.STALL_MS)
    }

    // Range

    @Test fun contentRangeIsParsedStrictly() {
        assertEquals(100L to 1000L, CoreHttp.parseContentRange("bytes 100-999/1000"))
        assertEquals(0L to -1L, CoreHttp.parseContentRange("bytes 0-9/*"))
        listOf(null, "", "bytes */1000", "bytes 5-4/10", "bytes 0-10/10", "items 0-1/2", "bytes 0-1/x", "bytes -5/10")
            .forEach { assertNull(it, CoreHttp.parseContentRange(it)) }
    }

    // Self-test

    @Test fun anExecRefusalIsTheFixedNotPermittedCode() {
        listOf("Cannot run program \"/data/user/0/app/files/proxy/core/mihomo\": error=13, Permission denied",
            "error=1, Operation not permitted", "EACCES (Permission denied)")
            .forEach { assertEquals(it, CoreError.EXEC_NOT_PERMITTED, CoreSelfTest.classifyStartFailure(it)) }
        assertEquals(CoreError.SELFTEST_FAILED, CoreSelfTest.classifyStartFailure("error=8, Exec format error"))
        assertEquals(CoreError.SELFTEST_FAILED, CoreSelfTest.classifyStartFailure(null))
    }

    @Test fun theSelfTestNeedsTheVersionInTimeOutput() {
        val version = CoreSpec.PINNED.version
        assertNull(CoreSelfTest.classifyOutput("Mihomo Meta v$version linux arm", true, version))
        assertEquals(CoreError.SELFTEST_FAILED, CoreSelfTest.classifyOutput("Mihomo Meta v1.0.0 linux arm", true, version))
        assertEquals(CoreError.SELFTEST_FAILED, CoreSelfTest.classifyOutput("Mihomo Meta v$version", false, version))
        assertEquals(CoreError.SELFTEST_FAILED, CoreSelfTest.classifyOutput("", true, version))
        assertEquals(5_000L, CoreSelfTest.TIMEOUT_MS)
    }

    @Test fun theDownloadIsOfferedOnlyWhereA32BitArmProcessCanRun() {
        assertTrue(CoreSelfTest.abiSupported(arrayOf("armeabi-v7a", "armeabi")))
        assertTrue(CoreSelfTest.abiSupported(arrayOf("armeabi-v7a")))
        assertFalse(CoreSelfTest.abiSupported(emptyArray()))
        assertFalse(CoreSelfTest.abiSupported(arrayOf("x86")))
        assertFalse(CoreSelfTest.abiSupported(arrayOf("arm64-v8a")))
        assertFalse(CoreSelfTest.abiSupported(null))
    }
}
