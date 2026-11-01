package app.orchard.tv.wallpaper

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ScreensaverVideo(val id: String, val name: String, val file: File)

/** The owner's local films. No videos are bundled with Aerial or fetched from a third-party host. */
object ScreensaverVideos {
    const val MAX_FILE_BYTES = 200L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    private const val MAX_VIDEOS = 64
    private val idPattern = Regex("[a-f0-9]{32}")

    private fun directory(context: Context) = File(context.filesDir, "screensaver-videos").apply { mkdirs() }

    /** Call off the main thread, including when assembling the phone page's state. */
    @Synchronized
    fun all(context: Context): List<ScreensaverVideo> = directory(context).listFiles().orEmpty()
        .filter { it.isDirectory && idPattern.matches(it.name) }
        .mapNotNull { dir ->
            runCatching {
                val file = File(dir, "video.mp4").takeIf { it.isFile && it.length() > 0 } ?: return@runCatching null
                val name = JSONObject(File(dir, "info.json").readText()).getString("name")
                ScreensaverVideo(dir.name, name, file)
            }.getOrNull()
        }.sortedBy { it.id }

    /** The upload is already on disk and is taken over. Validate it first, and publish a complete directory. */
    @Synchronized
    fun add(context: Context, upload: File, name: String): ScreensaverVideo? {
        val root = directory(context)
        // Only abandoned imports, never a film that has been published or one being copied now.
        root.listFiles()?.filter { it.name.startsWith("import-") && it.lastModified() < System.currentTimeMillis() - 3_600_000 }
            ?.forEach { it.deleteRecursively() }
        val size = upload.length()
        val films = all(context)
        if (size <= 0 || size > MAX_FILE_BYTES || films.size >= MAX_VIDEOS ||
            films.sumOf { it.file.length() } + size > MAX_TOTAL_BYTES || root.usableSpace < size + 16L * 1024 * 1024) return null
        val title = titleOf(name) ?: return null
        val metadata = MediaMetadataRetriever()
        val valid = try {
            metadata.setDataSource(upload.absolutePath)
            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" &&
                (metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0
        } catch (_: Exception) { false } finally { runCatching { metadata.release() } }
        if (!valid) return null

        val id = UUID.randomUUID().toString().replace("-", "")
        val staging = File(root, "import-$id")
        val target = File(root, id)
        return try {
            check(staging.mkdir())
            // The upload is the phone server's temp file in the cache, on the same volume: moving it
            // saves writing up to 200 MB again on a TV's slow storage. Copy where it cannot move.
            val film = File(staging, "video.mp4")
            if (!upload.renameTo(film)) upload.copyTo(film)
            File(staging, "info.json").writeText(JSONObject().put("name", title).toString())
            check(staging.renameTo(target))
            ScreensaverVideo(id, title, File(target, "video.mp4"))
        } catch (e: Exception) {
            Log.w("AerialVideos", "could not store a screensaver video", e)
            null
        } finally { staging.deleteRecursively() }
    }

    @Synchronized
    fun delete(context: Context, id: String): Boolean {
        if (!idPattern.matches(id)) return false
        val dir = File(directory(context), id)
        return dir.isDirectory && dir.deleteRecursively()
    }

    private const val MAX_TITLE_LENGTH = 120

    /**
     * The film's title from the uploaded file's [name], or null if it is no MP4 or MOV. The
     * extension is read before the title is cut to [MAX_TITLE_LENGTH]: cut first, a long name lost
     * its .mp4 and the film was refused (found by geitezhang, PR #1033).
     */
    internal fun titleOf(name: String): String? {
        val fileName = name.substringAfterLast('/').substringAfterLast('\\')
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0) return null
        val extension = fileName.substring(dot)
        if (extension.lowercase() !in setOf(".mp4", ".mov")) return null
        var end = minOf(dot, MAX_TITLE_LENGTH - extension.length)
        // Never through a surrogate pair: half a character cannot be stored.
        if (end in 1 until dot && Character.isHighSurrogate(fileName[end - 1])) end--
        return fileName.substring(0, end) + extension
    }
}
