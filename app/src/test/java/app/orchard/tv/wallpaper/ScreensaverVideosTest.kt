package app.orchard.tv.wallpaper

import android.content.Context
import android.media.MediaMetadataRetriever
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScreensaverVideosTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val root get() = File(context.filesDir, "screensaver-videos")

    private fun upload(): File = File.createTempFile("video", ".mov", context.cacheDir).apply {
        writeText("uploaded bytes")
        ShadowMediaMetadataRetriever.addMetadata(absolutePath, MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO, "yes")
        ShadowMediaMetadataRetriever.addMetadata(absolutePath, MediaMetadataRetriever.METADATA_KEY_DURATION, "2000")
    }

    private fun stored(id: String, bytes: Long = 1) = File(root, id).apply {
        mkdirs()
        File(this, "info.json").writeText(JSONObject().put("name", "scene.mov").toString())
        RandomAccessFile(File(this, "video.mp4"), "rw").use { it.setLength(bytes) }
    }

    @Test
    fun uploadIsPublishedWithItsNameAndBytesButDoesNotSelectIt() {
        val original = upload()
        val bytes = original.readText()
        val video = ScreensaverVideos.add(context, original, "../phone/scene.mov")
        assertNotNull(video)
        assertEquals("scene.mov", video!!.name)
        assertEquals(bytes, video.file.readText())
        assertEquals(listOf(video), ScreensaverVideos.all(context))
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith("import-") })
        assertFalse(context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE).contains("screensaver"))
    }

    @Test
    fun rejectsEmptyFilesAudioAndUnknownExtensionsWithoutPublishing() {
        val empty = File.createTempFile("empty", ".mp4", context.cacheDir)
        assertNull(ScreensaverVideos.add(context, empty, "empty.mp4"))
        val audio = upload()
        ShadowMediaMetadataRetriever.addMetadata(audio.absolutePath, MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO, "no")
        assertNull(ScreensaverVideos.add(context, audio, "audio.mov"))
        assertNull(ScreensaverVideos.add(context, upload(), "script.html"))
        assertTrue(ScreensaverVideos.all(context).isEmpty())
    }

    @Test
    fun rejectsOversizedUploadBeforeCopyingAndPreservesTheLibrary() {
        val old = stored("a".repeat(32))
        val large = upload()
        RandomAccessFile(large, "rw").use { it.setLength(ScreensaverVideos.MAX_FILE_BYTES + 1) }
        assertNull(ScreensaverVideos.add(context, large, "large.mov"))
        assertEquals(listOf(old.name), ScreensaverVideos.all(context).map { it.id })
    }

    @Test
    fun totalQuotaDoesNotEvictExistingFilms() {
        repeat(3) { stored(it.toString().padStart(32, '0'), 160L * 1024 * 1024) }
        val extra = upload()
        RandomAccessFile(extra, "rw").use { it.setLength(40L * 1024 * 1024) }
        assertNull(ScreensaverVideos.add(context, extra, "one-more.mp4"))
        assertEquals(3, ScreensaverVideos.all(context).size)
    }

    @Test
    fun partialAndMalformedImportsAreNotPlayable() {
        val good = stored("a".repeat(32))
        stored("b".repeat(32)).resolve("info.json").writeText("broken")
        stored("import-" + "c".repeat(32))
        stored("d".repeat(32)).resolve("video.mp4").delete()
        assertEquals(listOf(good.name), ScreensaverVideos.all(context).map { it.id })
    }

    @Test
    fun deletionAcceptsOnlyLibraryIdsAndLeavesOtherOwnerFilesAlone() {
        val photo = File(context.filesDir, "keep.jpg").apply { writeText("owner photo") }
        assertFalse(ScreensaverVideos.delete(context, "../keep.jpg"))
        assertTrue(photo.exists())
        val film = stored("e".repeat(32))
        assertTrue(ScreensaverVideos.delete(context, film.name))
        assertTrue(photo.exists())
        assertFalse(ScreensaverVideos.delete(context, film.name))
    }

    @Test
    fun longTitleKeepsItsExtension() {
        val title = ScreensaverVideos.titleOf("C:\\films\\" + "a".repeat(200) + ".MP4")
        assertEquals(120, title!!.length)
        assertTrue(title.endsWith(".MP4"))
        assertEquals("trip.mov", ScreensaverVideos.titleOf("/sdcard/trip.mov"))
        assertNull(ScreensaverVideos.titleOf("photo.jpg"))
        assertNull(ScreensaverVideos.titleOf("noextension"))
        // Not cut through a surrogate pair.
        val emoji = "a".repeat(115) + "\uD83C\uDF04" + ".mp4"
        assertEquals("a".repeat(115) + ".mp4", ScreensaverVideos.titleOf(emoji))
    }
}
