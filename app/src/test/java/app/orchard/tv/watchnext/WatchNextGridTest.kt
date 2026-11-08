package app.orchard.tv.watchnext

import androidx.compose.ui.unit.dp
import app.orchard.tv.ui.home.GridMetrics
import app.orchard.tv.ui.home.gridGlassAmount
import app.orchard.tv.ui.home.shelfContentAlpha
import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual host geometry, not a second implementation of its grid. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class WatchNextGridTest {
    @Test fun fixedProgramAndDeepRowsKeepTheirOwnSpaceInEveryLayout() {
        for (columns in 4..6) for (oneRow in listOf(false, true))
            for (names in listOf(false, true)) for (plate in listOf(false, true)) for (peek in listOf(false, true))
            for (largeClock in listOf(false, true)) for (clockRight in listOf(false, true)) {
                val grid = GridMetrics(960.dp, 540.dp, columns, oneRow, names && plate, peek, plate,
                    largeClock = largeClock, clockRight = clockRight, programArea = true)
                val layout = WatchNextHomeLayout.calculate(960f, grid.topRowY.value - 20f, oneRow, largeClock, clockRight)
                val label = "columns=$columns oneRow=$oneRow names=$names plate=$plate peek=$peek largeClock=$largeClock clockRight=$clockRight"
                assertTrue(label, layout.top >= 64f)
                assertTrue(label, layout.artHeight >= 74f)
                if (largeClock) {
                    assertEquals(label, 0f, layout.artWidth, 0f)
                    if (clockRight) assertTrue(label, layout.textLeft + layout.textWidth <= 456f)
                    else assertTrue(label, layout.textLeft >= 504f)
                } else assertEquals(label, 56f, layout.textLeft, 0f)
                assertTrue(label, layout.top + layout.artHeight <= layout.indicatorTop - 8f + 0.001f)
                for ((w, h) in listOf(640 to 360, 1200 to 1800, 6000 to 1000)) {
                    val image = artworkSize(w, h, (layout.artWidth * 2).toInt(), (layout.contentHeight * 2).toInt(), 2f)
                    if (image != null) {
                        assertTrue(label, image.width / 2f + 24f <= layout.artWidth + 0.01f)
                        assertTrue(label, image.height / 2f + 24f <= layout.contentHeight + 0.01f)
                    }
                }
                assertEquals(label, grid.topRowY.value - 32f, layout.indicatorTop + 24f, 0.001f)
                for (row in grid.shelfRows..7) {
                    val top = grid.slot(row * columns, onTop = false).second - grid.scrollFor(row - (grid.shelfRows - 1), 8)
                    assertTrue(label, top >= grid.topRowY - grid.shelfLift)
                    // The original 50dp lower room includes names and the focus scale/outline.
                    assertTrue(label, top.value + grid.tileH.value <= 490.01f)
                }
            }
    }
    @Test fun disabledLayoutRetainsTheBaselineFiveColumnGeometry() {
        val grid = GridMetrics(960.dp, 540.dp, 5, true, false, true, true, true, false)
        assertEquals(395.2f, grid.topRowY.value, 0.01f)
        assertEquals(204.8f, grid.restY.value, 0.01f)
        assertEquals(323.2f, grid.scrollFor(1, 8).value, 0.01f)
    }
    @Test fun deepRowsHideProgramContentAndCoverTheBackgroundInBothLayouts() {
        for (columns in 4..6) for (oneRow in listOf(false, true))
            for (names in listOf(false, true)) for (plate in listOf(false, true))
            for (peek in listOf(false, true)) for (largeClock in listOf(false, true)) {
                val grid = GridMetrics(960.dp, 540.dp, columns, oneRow, names && plate, peek, plate,
                    largeClock = largeClock, clockRight = false, programArea = true)
                for (row in 0..5) {
                    val inGrid = row >= grid.shelfRows
                    val scroll = grid.scrollFor(if (inGrid) row - (grid.shelfRows - 1) else 0, 8).value
                    val label = "columns=$columns oneRow=$oneRow names=$names plate=$plate peek=$peek largeClock=$largeClock row=$row"
                    val glass = gridGlassAmount(scroll, grid.collapse.value)
                    val alpha = shelfContentAlpha(scroll, grid.collapse.value)
                    if (!inGrid) {
                        assertEquals(label, 0f, glass, 0.001f)
                        assertEquals(label, 1f, alpha, 0.001f)
                    } else {
                        assertTrue(label, glass > 0f)
                        assertTrue(label, alpha < 1f)
                        if (glass >= 0.99f) assertEquals(label, 0f, alpha, 0.001f)
                        if (row > grid.shelfRows) assertTrue(label, glass >= 0.99f)
                    }
                }
            }
    }
}
