package app.orchard.tv.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ScreensaverVideo(val id: String, val name: String, val file: File)

/** The owner's local films. No videos are bundled with Aerial or fetched from a third-party host. */
object ScreensaverVideos {
    const val MAX_FILE_BYTES = 200L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    private const val MAX_VIDEOS = 64
    private val idPattern = Regex("[a-f0-9]{32}")

    /** Enough to name a film on the TV and the phone without filling either. */
    private const val MAX_TITLE_LENGTH = 120

    /** The poster's size: what the phone page's list shows, as a photo's thumbnail does. */
    private const val POSTER_W = 384
    private const val POSTER_H = 216

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

    /**
     * A frame from the film, for the phone page's list: the names alone say nothing once the films
     * come from many places. Kept beside the film, so a film is decoded once; a film added before
     * posters were kept gets one the first time it is looked at.
     */
    @Synchronized
    fun thumbnail(context: Context, id: String): Bitmap? {
        if (!idPattern.matches(id)) return null
        val dir = File(directory(context), id)
        val poster = File(dir, "thumb.jpg")
        if (poster.isFile && poster.length() > 0) {
            BitmapFactory.decodeFile(poster.absolutePath)?.let { return it }
        }
        val file = File(dir, "video.mp4").takeIf { it.isFile && it.length() > 0 } ?: return null
        val frame = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull() ?: return null
        return writePoster(poster, frame)
    }

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
        // The extension decides whether this is a film at all, so it is read before the title is cut:
        // a long title used to be cut together with its extension, and the film was refused.
        val fileName = name.substringAfterLast('/').substringAfterLast('\\')
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0) return null
        val extension = fileName.substring(dot)
        if (extension.lowercase() !in setOf(".mp4", ".mov")) return null
        val title = cut(fileName.substring(0, dot), MAX_TITLE_LENGTH - extension.length) + extension
        val metadata = MediaMetadataRetriever()
        var frame: Bitmap? = null
        val valid = try {
            metadata.setDataSource(upload.absolutePath)
            val hasVideo = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" &&
                (metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0
            // Taken here, while the film is already open: the phone page never decodes it later.
            if (hasVideo) frame = runCatching { metadata.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
            hasVideo
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
            frame?.let { writePoster(File(staging, "thumb.jpg"), it) }
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

    /** Cuts a title to fit, but never through a surrogate pair: half a character cannot be stored. */
    private fun cut(s: String, max: Int): String {
        if (max <= 0) return ""
        if (s.length <= max) return s
        val end = if (Character.isHighSurrogate(s[max - 1])) max - 1 else max
        return s.substring(0, end)
    }

    /** Writes the poster and returns the picture to show. Failing to write it still shows it once. */
    private fun writePoster(target: File, frame: Bitmap): Bitmap {
        val scaled = Bitmap.createScaledBitmap(frame, POSTER_W, POSTER_H, true)
        if (scaled !== frame) frame.recycle()
        runCatching { FileOutputStream(target).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        return scaled
    }
}
