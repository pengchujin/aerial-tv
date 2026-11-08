package app.orchard.tv.watchnext

import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WatchNextRemoteArtworkTest {
    @Test fun boundedReadAcceptsExactLimitAndRejectsOverflow() {
        val bytes = ByteArray(33) { it.toByte() }
        assertArrayEquals(bytes, readArtworkBytes(ByteArrayInputStream(bytes), 33) {})
        assertThrows(IllegalStateException::class.java) { readArtworkBytes(ByteArrayInputStream(bytes), 32) {} }
    }

    @Test fun cancellationDiscardsBytesAlreadyRead() {
        var checks = 0
        assertThrows(OperationCanceledException::class.java) {
            readArtworkBytes(ByteArrayInputStream(ByteArray(20_000)), 30_000) {
                if (++checks == 2) throw OperationCanceledException()
            }
        }
    }

    @Test fun remoteRedirectReturnsCompleteBody() = server { origin ->
        val body = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(body, remoteArtwork("$origin/redirect", CancellationSignal()))
    }

    @Test fun errorsAndRedirectLoopsAreNotArtwork() = server { origin ->
        assertThrows(IllegalStateException::class.java) { remoteArtwork("$origin/error", CancellationSignal()) }
        assertThrows(IllegalStateException::class.java) { remoteArtwork("$origin/loop", CancellationSignal()) }
    }

    @Test fun redirectCannotReadLocalFiles() = server { origin ->
        assertThrows(IllegalArgumentException::class.java) { remoteArtwork("$origin/file", CancellationSignal()) }
    }

    @Test fun cancelledRequestDoesNotOpenConnection() {
        val signal = CancellationSignal().apply { cancel() }
        assertThrows(OperationCanceledException::class.java) { remoteArtwork("https://example.invalid/image", signal) }
    }

    private fun server(test: (String) -> Unit) {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                socket.use {
                    it.soTimeout = 2_000
                    val reader = it.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1]
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val headers = when (path) {
                        "/image" -> "200 OK\r\nContent-Length: 4"
                        "/redirect" -> "302 Found\r\nLocation: /image\r\nContent-Length: 0"
                        "/loop" -> "302 Found\r\nLocation: /loop\r\nContent-Length: 0"
                        "/file" -> "302 Found\r\nLocation: file:///private/image\r\nContent-Length: 0"
                        else -> "503 Unavailable\r\nContent-Length: 0"
                    }
                    it.getOutputStream().write("HTTP/1.1 $headers\r\nConnection: close\r\n\r\n".toByteArray())
                    if (path == "/image") it.getOutputStream().write(byteArrayOf(1, 2, 3, 4))
                }
            }
        }
        try { test("http://127.0.0.1:${server.localPort}") } finally { server.close(); worker.join(3_000) }
    }
}
