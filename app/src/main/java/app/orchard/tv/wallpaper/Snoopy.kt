package app.orchard.tv.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import app.orchard.tv.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** The twelve opaque tvOS 26 recordings in hitnology/snoopy 0.2.2, fetched only when chosen. */
object Snoopy {
    private const val SOURCE = "https://github.com/hitnology/snoopy/releases/download/0.2.2/snoopy.saver.zip"
    private const val SOURCE_BYTES = 552_867_203L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var root: File
    private lateinit var title: String
    private val pack get() = File(root, "0.2.2")
    private var job: Job? = null

    var ready by mutableStateOf(false); private set
    var downloading by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set
    var progress by mutableFloatStateOf(0f); private set
    val poster: File get() = File(pack, "poster.jpg")

    fun init(context: Context) {
        root = File(context.filesDir, "snoopy").apply { mkdirs() }
        title = context.getString(R.string.wp_snoopy)
        ready = File(pack, "complete").isFile && SnoopyPack.clips.all { File(pack, it.name).length() == it.bytes }
    }

    fun films(): List<Aerial> = if (!ready) emptyList() else SnoopyPack.clips.map {
        val uri = File(pack, it.name).toUri().toString()
        Aerial("snoopy:${it.name}", title, "snoopy", uri, uri, uri, emptyList())
    }

    fun file(id: String): File? = SnoopyPack.clips.firstOrNull { "snoopy:${it.name}" == id }
        ?.let { File(pack, it.name).takeIf(File::isFile) }

    fun cancel() { job?.cancel() }

    fun download() {
        if (ready || job != null) return
        downloading = true
        failed = false
        progress = 0f
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val staging = File(root, "download")
                    // Only our unpublished pack. Uploaded videos live in a different directory.
                    staging.deleteRecursively()
                    check(staging.mkdirs())
                    check(root.usableSpace > SnoopyPack.clips.sumOf { it.bytes } + 32L * 1024 * 1024)
                    val connection = URL(SOURCE).openConnection() as HttpURLConnection
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 20_000
                    try {
                        check(connection.responseCode == HttpURLConnection.HTTP_OK)
                        var received = 0L
                        var lastUpdate = 0L
                        val task = coroutineContext
                        val stream = object : FilterInputStream(connection.inputStream.buffered()) {
                            override fun read(): Int = `in`.read().also { if (it >= 0) count(1) }
                            override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { if (it > 0) count(it) }
                            private fun count(n: Int) {
                                task.ensureActive()
                                received += n
                                check(received <= SOURCE_BYTES)
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastUpdate >= 500) {
                                    lastUpdate = now
                                    val fraction = (received.toFloat() / SOURCE_BYTES).coerceAtMost(0.99f)
                                    scope.launch { if (downloading) progress = fraction }
                                }
                            }
                        }
                        stream.use { SnoopyPack.unpack(it, staging) }
                        coroutineContext.ensureActive()
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(File(staging, SnoopyPack.clips.first().name).path)
                            retriever.getFrameAtTime(5_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
                                File(staging, "poster.jpg").outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                                frame.recycle()
                            }
                        } catch (e: Exception) {
                            Log.w("AerialSnoopy", "could not read a poster frame", e)
                        } finally { runCatching { retriever.release() } }
                        coroutineContext.ensureActive()
                        File(staging, "complete").writeText("0.2.2")
                        // A complete pack is never replaced by an incomplete download.
                        check(!pack.exists() || pack.deleteRecursively())
                        check(staging.renameTo(pack))
                    } finally {
                        connection.disconnect()
                        staging.deleteRecursively()
                    }
                }
                ready = true
                progress = 1f
                Wallpapers.snoopyReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                Log.w("AerialSnoopy", "could not download Snoopy", e)
            } finally {
                downloading = false
                job = null
            }
        }
    }
}

/** Exact paths, sizes and per-film hashes; no extraction of executables or HEVC-alpha scenes. */
internal object SnoopyPack {
    data class Clip(val name: String, val bytes: Long, val sha256: String)
    val clips = listOf(
        Clip("101_AS003.mov", 28302017, "3f8788fa893f8bb38899cf915faf040a48f414214143c2e04cb69104bf45585b"),
        Clip("101_AS004.mov", 38575578, "a89ea072f1e4b58aad52d7e4e63218dbc66935e55ebaad68fcc459a012be6b5d"),
        Clip("101_AS007.mov", 38345027, "37db72197be0549dc5c4cb3022ecb038a36af1bee6bbb139b92cd90fc7fb14e3"),
        Clip("101_AS008.mov", 31846039, "a304c4ced35285b51cbbaff6119d6885b4beced36975696e32bbf57c0910dd87"),
        Clip("101_AS009.mov", 37760195, "f115b68cef511bde6e500765758a7b968c612b612f473da42e77b7b210932f27"),
        Clip("101_AS010.mov", 37481255, "8c4338b88d2a0a9fb3aeaeea33bf98644874a2e836ee765f5302703d5b0b830e"),
        Clip("101_AS011.mov", 31058590, "e5c25743ea894484debb20abe23025bd7df16f8348c9e5d829ef20ae92b2609f"),
        Clip("101_AS012.mov", 29938460, "2250c46a7107e243e52f7b55965f2bbe6a0cb388b06095d8f96f228b874073cb"),
        Clip("101_AS013.mov", 27379915, "5fb3dbc76b2bc8960c9d23471316441bf78817fa7be96a03aa1743b3379b53c9"),
        Clip("101_AS014.mov", 33593611, "6914fd9975886dd2fbb987edd9ad4b26e45aad2f821781311bc17d06e21faf02"),
        Clip("101_AS015.mov", 27941612, "9dee0369fe6ad44fa74a24d775ceec0d580b3e4061e04d156a44692c1c6f757b"),
        Clip("101_AS016.mov", 31078286, "ffeae19c56413bbbda28a80ed85c370be1981cca73242f141c738ed9fe377fab"),
    )

    fun unpack(input: InputStream, target: File, expected: List<Clip> = clips) {
        val remaining = expected.associateBy { "snoopy.saver/Contents/Resources/${it.name}" }.toMutableMap()
        var expanded = 0L
        ZipInputStream(input).use { zip ->
            val buffer = ByteArray(64 * 1024)
            while (remaining.isNotEmpty()) {
                val entry = zip.nextEntry ?: break
                val clip = remaining[entry.name]
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                val output = clip?.let { File(target, it.name).outputStream() }
                try {
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        expanded += n
                        check(expanded <= 800L * 1024 * 1024)
                        if (clip != null) {
                            size += n
                            check(size <= clip.bytes)
                            digest.update(buffer, 0, n)
                            output!!.write(buffer, 0, n)
                        }
                    }
                } finally { output?.close() }
                if (clip != null) {
                    check(size == clip.bytes && digest.digest().joinToString("") { "%02x".format(it) } == clip.sha256)
                    remaining.remove(entry.name)
                }
            }
        }
        check(remaining.isEmpty())
    }
}
