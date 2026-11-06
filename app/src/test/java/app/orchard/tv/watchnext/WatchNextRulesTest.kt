package app.orchard.tv.watchnext

import org.junit.Assert.*
import org.junit.Test

class WatchNextRulesTest {
    private fun item(id: Long, source: String = "a", time: Long? = null, browsable: Boolean? = null) =
        WatchNextProgram(ProgramKey(source, id), engagementMs = time, browsable = browsable)

    @Test fun mixedTypesSortByRelevantTimeThenStableIdentity() {
        val input = listOf(item(4, "b", 10), item(2, time = null), item(3, time = 10),
            item(1, "b", 20).copy(watchNextType = 2), item(1, time = 10).copy(watchNextType = 3))
        val expected = listOf(ProgramKey("b", 1), ProgramKey("a", 1), ProgramKey("a", 3), ProgramKey("b", 4), ProgramKey("a", 2))
        repeat(20) { assertEquals(expected, effectivePrograms(input.shuffled(), setOf("a", "b"), setOf("a", "b")).map { it.key }) }
    }
    @Test fun visibilityRequiresBothSourcePreferenceAndHostPermission() {
        val input = listOf(item(1), item(2, "hidden"), item(3, "restricted"), item(4, browsable = false))
        assertEquals(listOf(ProgramKey("a", 1), ProgramKey("hidden", 2)),
            effectivePrograms(input, setOf("a", "hidden", "restricted"), setOf("a", "hidden")).map { it.key })
        assertTrue(effectivePrograms(input, emptySet(), setOf("a")).isEmpty())
    }
    @Test fun missingArtAndTitleRemainValidAndSameTitlesAreNotMerged() {
        val input = listOf(item(1), item(2), item(1, "b"))
        assertEquals(3, effectivePrograms(input, setOf("a", "b"), setOf("a", "b")).size)
    }
    @Test fun refreshKeepsIdentityAndRemovalChoosesNeighbour() {
        val before = listOf(item(1), item(2), item(3))
        assertEquals(item(2).key, retainedKey(before, item(2).key, before.reversed()))
        assertEquals(item(3).key, retainedKey(before, item(2).key, listOf(item(1), item(3))))
        assertEquals(item(2).key, retainedKey(before, item(3).key, listOf(item(1), item(2))))
        assertNull(retainedKey(before, item(2).key, emptyList()))
    }
    @Test fun rapidAlternatingNavigationAndBothWrapDirections() {
        assertEquals(2, adjacentIndex(0, -1, 3))
        assertEquals(0, adjacentIndex(2, 1, 3))
        var index = 0
        repeat(1000) { index = adjacentIndex(index, -1, 3); index = adjacentIndex(index, 1, 3) }
        assertEquals(0, index)
        assertEquals(0, adjacentIndex(0, 1, 0))
        assertEquals(0, adjacentIndex(0, -1, 1))
    }
    @Test fun unknownProgressStaysUnknownAndKnownProgressIsClamped() {
        assertNull(item(1).progress)
        assertNull(item(1).copy(durationMs = 0, positionMs = 1).progress)
        assertNull(item(1).copy(durationMs = 100, positionMs = -1).progress)
        assertEquals(1f, item(1).copy(durationMs = 100, positionMs = 200).progress)
        assertEquals(0.5f, item(1).copy(durationMs = 100, positionMs = 50).progress)
    }
    @Test fun programGeometryReservesDotsAndDoesNotCrossText() {
        for (shelf in listOf(182f, 202f, 230f, 272f, 350f, 385f)) {
            val layout = WatchNextHomeLayout.calculate(960f, shelf, shelf > 300f)
            assertEquals(shelf - 12f, layout.indicatorTop + 24f, 0.001f)
            assertTrue(layout.top + layout.artHeight <= layout.indicatorTop - 8f + 0.001f)
            assertTrue(layout.artLeft >= 56f + layout.textWidth + 24f)
            assertTrue(layout.artLeft + layout.artWidth <= 904f)
        }
    }
    @Test fun portraitSquareAndLandscapeShareHeightWhileUltrawideFitsWidth() {
        val sizes = listOf(1200 to 1800, 1200 to 1200, 1920 to 1080).map { (w, h) ->
            artworkSize(w, h, 900, 462, 2f)!!
        }
        assertEquals(1, sizes.map { it.height }.distinct().size)
        assertEquals(414, sizes.first().height)
        assertTrue(sizes[0].width < sizes[1].width && sizes[1].width < sizes[2].width)
        val wide = artworkSize(3000, 1000, 900, 462, 2f)!!
        assertTrue(wide.width <= 852)
        assertTrue(wide.height < sizes.first().height)
        assertEquals(3.0, wide.width.toDouble() / wide.height, 0.01)
        assertNull(artworkSize(100_000, 100, 768, 462, 2f))
        assertNull(artworkSize(100, 0, 768, 462, 2f))
        assertNull(artworkSize(100, 100, 10, 462, 2f))
    }
}
