package app.orchard.tv.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

@Composable
internal fun rememberArtwork(uri: Uri?): State<Bitmap?> {
    val resolver = LocalContext.current.contentResolver
    return produceState<Bitmap?>(null, uri, resolver) {
        value = null
        if (uri == null) return@produceState
        value = withContext(Dispatchers.IO) {
            if (uri.scheme == "https") readNetworkArtwork(uri) else readArtwork { resolver.openInputStream(uri) }
        }
    }
}

internal fun sessionArtworkUri(packageName: String, uri: Uri?): Uri? {
    return when (uri?.scheme) {
        ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_ANDROID_RESOURCE -> uri
        "http", "https" -> {
            // NetEase TV publishes cleartext artwork URIs. Its artwork CDN also serves HTTPS.
            val host = uri.host?.lowercase()
            if (packageName == "com.netease.cloudmusic.tv" && host?.endsWith(".music.126.net") == true &&
                uri.userInfo == null && uri.port == -1
            ) uri.buildUpon().scheme("https").build() else null
        }
        else -> null
    }
}

private fun readNetworkArtwork(uri: Uri): Bitmap? {
    return try {
        val connection = URL(uri.toString()).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            if (connection.responseCode != 200) return null
            val bytes = connection.inputStream.use { input ->
                val data = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (data.size() + count > 1024 * 1024) return null
                    data.write(buffer, 0, count)
                }
                data.toByteArray()
            }
            readArtwork { ByteArrayInputStream(bytes) }
        } finally {
            connection.disconnect()
        }
    } catch (_: IOException) {
        null
    }
}

private fun readArtwork(open: () -> InputStream?): Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        val maxSide = maxOf(options.outWidth, options.outHeight)
        var sample = 1
        while (maxSide / sample > 512) sample *= 2
        options.inSampleSize = sample
        options.inJustDecodeBounds = false
        open()?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
