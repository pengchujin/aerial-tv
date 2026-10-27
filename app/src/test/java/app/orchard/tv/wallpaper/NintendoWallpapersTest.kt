package app.orchard.tv.wallpaper

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NintendoWallpapersTest {
    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun extractsOnlyTheExactDesktopPicture() {
        val bytes = archive("other.jpg" to byteArrayOf(1), "folder/desktop.jpg" to byteArrayOf(2, 3))
        val out = ByteArrayOutputStream()
        NintendoWallpapers.extract(ByteArrayInputStream(bytes), "folder/desktop.jpg", out)
        assertArrayEquals(byteArrayOf(2, 3), out.toByteArray())
    }

    @Test fun missingPictureFails() {
        val bytes = archive("../desktop.jpg" to byteArrayOf(1))
        assertThrows(IllegalStateException::class.java) {
            NintendoWallpapers.extract(ByteArrayInputStream(bytes), "desktop.jpg", ByteArrayOutputStream())
        }
    }

    @Test fun oversizedPictureFails() {
        val bytes = archive("desktop.jpg" to ByteArray((NintendoWallpapers.MAX_PICTURE + 1).toInt()))
        assertThrows(IllegalStateException::class.java) {
            NintendoWallpapers.extract(ByteArrayInputStream(bytes), "desktop.jpg", ByteArrayOutputStream())
        }
    }
}
