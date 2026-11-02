package app.orchard.tv.update

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReleaseNotesTest {
    private val json = """
        {"releases":[
          {"v":"1.77","zh":["音量修了"],"en":["Volume fixed"]},
          {"v":"1.76","zh":[],"en":["Only English"]},
          {"v":"1.75","zh":["守护不再崩溃"],"en":["Guard no longer crashes"]},
          {"v":"1.74","zh":["已装的版本"],"en":["Installed"]},
          {"v":"","zh":["no version"]}
        ]}
    """.trimIndent()

    @Test fun releasesAfterThisOneUpToTheLatest() {
        val notes = ReleaseNotesParser.parse(json, current = "1.74", latest = "1.77", zh = true)
        assertEquals(listOf("1.77", "1.76", "1.75"), notes.map { it.version })
        assertEquals(listOf("音量修了"), notes[0].changes)
        // No Chinese for 1.76: the English stands in.
        assertEquals(listOf("Only English"), notes[1].changes)
    }

    @Test fun nothingPastTheVersionTheCheckFound() {
        val notes = ReleaseNotesParser.parse(json, current = "1.74", latest = "1.76", zh = false)
        assertEquals(listOf("1.76", "1.75"), notes.map { it.version })
        assertEquals(listOf("Guard no longer crashes"), notes[1].changes)
    }

    @Test fun newestFirstWhateverTheFileOrder() {
        val shuffled = """{"releases":[{"v":"1.9","en":["a"]},{"v":"1.10","en":["b"]}]}"""
        assertEquals(listOf("1.10", "1.9"), ReleaseNotesParser.parse(shuffled, "1.8", "1.10", zh = false).map { it.version })
    }
}
