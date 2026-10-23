package app.orchard.tv.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SnoopyPackTest {
    @get:Rule val temp = TemporaryFolder()
    private val prefix = "snoopy.saver/Contents/Resources/"
    private val content = "a complete recording".toByteArray()
    private fun clip(name: String = "101_AS003.mov") = SnoopyPack.Clip(
        name, content.size.toLong(), MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) },
    )
    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
        }
    }.toByteArray()

    @Test fun extractsOnlyThePinnedRecordingsAndNeverArchivePaths() {
        val target = temp.newFolder()
        val zip = archive("../outside" to content, (prefix + "../outside.mov") to content, (prefix + clip().name) to content)
        SnoopyPack.unpack(zip.inputStream(), target, listOf(clip()))
        assertEquals(listOf(clip().name), target.listFiles()!!.map { it.name })
        assertEquals(String(content), File(target, clip().name).readText())
        assertFalse(File(target.parentFile, "outside").exists())
    }

    @Test fun refusesARecordingWithTheRightNameButWrongDigest() {
        val target = temp.newFolder()
        val bad = content.copyOf().also { it[0] = 0 }
        assertTrue(runCatching { SnoopyPack.unpack(archive((prefix + clip().name) to bad).inputStream(), target, listOf(clip())) }.isFailure)
    }

    @Test fun missingRecordingDoesNotCountAsACompletePack() {
        val target = temp.newFolder()
        val clips = listOf(clip(), clip("101_AS004.mov"))
        assertTrue(runCatching { SnoopyPack.unpack(archive((prefix + clip().name) to content).inputStream(), target, clips) }.isFailure)
        assertFalse(File(target, "complete").exists())
    }

    @Test fun sizeLimitStopsUnexpectedDataAndLeavesOtherFilesAlone() {
        val target = temp.newFolder()
        val owner = File(target, "owner-video.mp4").apply { writeText("keep") }
        val zip = archive((prefix + clip().name) to content.copyOf(content.size + 1))
        assertTrue(runCatching { SnoopyPack.unpack(zip.inputStream(), target, listOf(clip())) }.isFailure)
        assertEquals("keep", owner.readText())
    }

    @Test fun incompleteTransferCannotBeAccepted() {
        val target = temp.newFolder()
        val zip = archive((prefix + clip().name) to content)
        val interrupted = zip.copyOf(30 + (prefix + clip().name).toByteArray().size + 5)
        assertTrue(runCatching { SnoopyPack.unpack(interrupted.inputStream(), target, listOf(clip())) }.isFailure)
    }
}
