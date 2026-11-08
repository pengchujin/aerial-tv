package app.orchard.tv.wallpaper

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread

/** CloudPhotos against a small WebDAV server on the loopback: PROPFIND over the socket, folders, and auth. */
@RunWith(RobolectricTestRunner::class)
class CloudPhotosTest {
    private lateinit var server: ServerSocket
    private val seenAuth = ArrayList<String?>()
    private val seenPaths = ArrayList<String>()

    private fun multistatus(vararg entries: Pair<String, Boolean>) =
        """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">""" + entries.joinToString("") { (href, folder) ->
            "<D:response><D:href>$href</D:href><D:propstat><D:prop><D:resourcetype>" +
                (if (folder) "<D:collection/>" else "") + "</D:resourcetype></D:prop></D:propstat></D:response>"
        } + "</D:multistatus>"

    @Before
    fun start() {
        server = ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { return@thread }
                socket.use { s ->
                    val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val first = input.readLine() ?: return@use
                    var auth: String? = null
                    var length = 0
                    while (true) {
                        val h = input.readLine() ?: break
                        if (h.isEmpty()) break
                        if (h.startsWith("Authorization:", true)) auth = h.substringAfter(':').trim()
                        if (h.startsWith("Content-Length:", true)) length = h.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { input.read() }
                    val path = first.split(' ')[1]
                    seenAuth += auth; seenPaths += path
                    val out = s.getOutputStream()
                    if (auth == null) {
                        out.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    } else {
                        val body = when (path) {
                            "/dav/photos/" -> multistatus(
                                "/dav/photos/" to true, "/dav/photos/a%20b.JPG" to false, "/dav/photos/notes.txt" to false,
                                "/dav/photos/trip/" to true,
                            )
                            "/dav/photos/trip/" -> multistatus("/dav/photos/trip/" to true, "/dav/photos/trip/c.png" to false)
                            else -> ""
                        }
                        // The second folder goes out chunked, as Nextcloud's does.
                        if (path == "/dav/photos/trip/") {
                            val half = body.length / 2
                            val chunks = listOf(body.substring(0, half), body.substring(half))
                            out.write("HTTP/1.1 207 Multi-Status\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray())
                            chunks.forEach { out.write("${it.length.toString(16)}\r\n$it\r\n".toByteArray()) }
                            out.write("0\r\n\r\n".toByteArray())
                        } else {
                            out.write("HTTP/1.1 207 Multi-Status\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                        }
                    }
                    out.flush()
                }
            }
        }
    }

    @After
    fun stop() = server.close()

    private fun root() = URL("http://127.0.0.1:${server.localPort}/dav/photos/")

    @Test
    fun listsPicturesInTheFolderAndItsSubfolders() {
        val images = CloudPhotos.listImages(root(), "Basic dGVzdDp0ZXN0")
        assertEquals(listOf("/dav/photos/a%20b.JPG", "/dav/photos/trip/c.png"), images.map { it.url.path })
        assertTrue("the folder is asked for with Depth 1 once per folder", seenPaths == listOf("/dav/photos/", "/dav/photos/trip/"))
    }

    @Test
    fun aRefusedLoginIsToldApartFromAnUnreachableServer() {
        try {
            CloudPhotos.listImages(root(), null)
            fail("expected AuthFailed")
        } catch (e: CloudPhotos.AuthFailed) {
            // expected
        }
    }

    @Test
    fun aBareAddressGetsASchemeAndAClosingSlash() {
        assertEquals("http://192.168.1.5:5244/dav/photos/", CloudPhotos.normalize(" 192.168.1.5:5244/dav/photos "))
        assertEquals("https://nas.local/photos/", CloudPhotos.normalize("https://nas.local/photos/"))
        assertEquals("", CloudPhotos.normalize("  "))
    }
}
