package app.orchard.tv.watchnext

import android.os.CancellationSignal
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Fetch only the provider-supplied image, with bounded redirects, time and size. */
internal fun remoteArtwork(address: String, signal: CancellationSignal): ByteArray {
    var url = URL(address)
    val deadline = System.nanoTime() + WatchNextDefaults.RemoteDeadlineMs * 1_000_000L
    fun checkActive() {
        signal.throwIfCanceled()
        check(System.nanoTime() < deadline) { "Artwork request timed out" }
    }
    repeat(WatchNextDefaults.RemoteRedirects + 1) { hop ->
        checkActive()
        require(url.protocol in listOf("http", "https") && url.userInfo == null) { "Unsupported artwork URL" }
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = WatchNextDefaults.RemoteTimeoutMs
        connection.readTimeout = WatchNextDefaults.RemoteTimeoutMs
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Accept", "image/*")
        signal.setOnCancelListener { connection.disconnect() }
        try {
            checkActive()
            val status = connection.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                check(hop < WatchNextDefaults.RemoteRedirects) { "Too many artwork redirects" }
                val next = URL(url, checkNotNull(connection.getHeaderField("Location")))
                require(url.protocol != "https" || next.protocol == "https") { "Insecure artwork redirect" }
                url = next
            } else {
                check(status == HttpURLConnection.HTTP_OK) { "Artwork response $status" }
                val length = connection.getHeaderField("Content-Length")?.toLongOrNull()
                check(length == null || length <= WatchNextDefaults.RemoteMaxBytes) { "Artwork too large" }
                val bytes = connection.inputStream.use { readArtworkBytes(it, WatchNextDefaults.RemoteMaxBytes, ::checkActive) }
                check(bytes.isNotEmpty()) { "Empty artwork" }
                check(length == null || length < 0 || length == bytes.size.toLong()) { "Incomplete artwork" }
                checkActive()
                return bytes
            }
        } finally {
            signal.setOnCancelListener(null)
            connection.disconnect()
        }
    }
    error("No artwork response")
}

internal fun readArtworkBytes(input: InputStream, limit: Int, checkActive: () -> Unit): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        checkActive()
        val count = input.read(buffer)
        if (count < 0) break
        check(count <= limit - output.size()) { "Artwork too large" }
        output.write(buffer, 0, count)
    }
    checkActive()
    return output.toByteArray()
}
