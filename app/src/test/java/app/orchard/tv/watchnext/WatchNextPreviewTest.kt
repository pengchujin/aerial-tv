package app.orchard.tv.watchnext

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.TileArt
import app.orchard.tv.ui.components.GlassSource
import app.orchard.tv.ui.components.LocalGlassSource
import app.orchard.tv.ui.home.GridMetrics
import app.orchard.tv.ui.home.ShelfClock
import app.orchard.tv.ui.theme.LocalReducedMotion
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-xhdpi")
class WatchNextPreviewTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ActivityController<ComponentActivity>? = null
    @After fun close() { compose.runOnUiThread { host?.close() } }

    @Test fun inlineSourceAndCompactDetailsLeavePlayVisibleAboveDotsInEveryGrid() {
        val layout = mutableStateOf(WatchNextHomeLayout.calculate(960f, 385f, true))
        val clockSide = mutableStateOf<Boolean?>(null)
        val clockTop = mutableStateOf(80f)
        val title = "A very long programme title with additional words that must never cover the play button"
        val program = mutableStateOf(WatchNextProgram(ProgramKey("test.source", 1), title = title,
            season = "100", episode = "200", episodeTitle = "Episode details: $title",
            durationMs = 100_000, positionMs = 50_000, engagementMs = 1_000, watchNextType = 0,
            description = title.repeat(5), genre = "TECH_SCIENCE", releaseDate = "2026-11-03",
            reviewRating = "4.5", reviewRatingStyle = 0))
        var play = ""
        compose.runOnUiThread {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            play = host!!.get().getString(R.string.watch_next_play)
            val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
            val source = AppEntry("test.source", "Nuvio", ComponentName("test.source", "Main"), true, false,
                TileArt(image, null, Rect.Zero, Color.Black, image))
            host!!.get().setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalReducedMotion provides true,
                    LocalGlassSource provides GlassSource("test", null)) {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        clockSide.value?.let { right ->
                            ShelfClock(LocalDateTime.of(2026, 11, 3, 23, 58), alignEnd = right,
                                modifier = Modifier.align(if (right) Alignment.TopEnd else Alignment.TopStart)
                                    .offset(y = clockTop.value.dp).padding(horizontal = 56.dp))
                        }
                        WatchNextPreview(program.value, source, listOf(program.value), layout.value, null, true, true,
                            remember { FocusRequester() }, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.waitForIdle()
        for (columns in 4..6) for (oneRow in listOf(false, true))
            for (names in listOf(false, true)) for (plate in listOf(false, true)) for (peek in listOf(false, true))
            for (largeClock in listOf(false, true)) for (clockRight in listOf(false, true)) {
                val grid = GridMetrics(960.dp, 540.dp, columns, oneRow, names && plate, peek, plate,
                    largeClock = largeClock, clockRight = clockRight, programArea = true)
                val next = WatchNextHomeLayout.calculate(960f, grid.topRowY.value - 20f, oneRow, largeClock, clockRight)
                compose.runOnIdle {
                    layout.value = next
                    clockSide.value = clockRight.takeIf { largeClock }
                    clockTop.value = grid.clockY.value
                }
                compose.onNodeWithText("Nuvio").assertDoesNotExist()
                val icon = compose.onNodeWithContentDescription("Nuvio").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val titleBounds = compose.onNodeWithText(title).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("Title must remain below the corner controls", titleBounds.top >= 64f)
                if (next.artHeight in 166f..<210f) {
                    assertTrue("Compact content should sit farther below the clock", titleBounds.top >= next.contentTop - 0.5f)
                    assertEquals(66f, icon.width, 0.5f)
                    assertEquals(38f, icon.height, 0.5f)
                }
                assertTrue("Title must not be reduced to a clipped fragment", titleBounds.height >= 21f)
                if (largeClock) {
                    val clock = compose.onNodeWithText("23:58").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertTrue("Program must leave the original large clock clear",
                        if (clockRight) titleBounds.right <= clock.left else titleBounds.left >= clock.right)
                }
                val button = compose.onNodeWithText(play).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val action = compose.onNodeWithText(play).onParent().fetchSemanticsNode().boundsInRoot
                assertEquals("Source and play must have the same height", icon.height, action.height, 0.5f)
                assertEquals("Content should be centered in its fixed display region",
                    next.contentTop + next.contentHeight / 2f,
                    (titleBounds.top + action.bottom) / 2f, 1f)
                assertTrue("Action crossed indicator", action.bottom < next.indicatorTop)
                assertTrue("Source icon must be left of play", icon.right < button.left)
                assertTrue("Source icon must share the action row", kotlin.math.abs(icon.center.y - button.center.y) < 3f)
                val progressDetails = if (next.artHeight >= 112f) {
                    compose.onNodeWithText("Watched", substring = true).assertIsDisplayed()
                    compose.onNodeWithText("1 min remaining", substring = true)
                        .assertIsDisplayed().fetchSemanticsNode().boundsInRoot.also {
                            assertTrue("Progress details overlapped actions", it.bottom < icon.top)
                        }
                } else {
                    compose.onNodeWithText("Watched", substring = true).assertDoesNotExist()
                    null
                }
                if (next.artHeight in 180f..<210f || (next.artHeight < 210f &&
                        compose.onAllNodesWithText("Season 100", substring = true).fetchSemanticsNodes().isNotEmpty())) {
                    val summary = compose.onNodeWithText("Season 100", substring = true).assertIsDisplayed()
                        .fetchSemanticsNode().boundsInRoot
                    assertTrue("Compact metadata was clipped", summary.height >= 16f)
                    assertTrue("Compact metadata overlapped actions", summary.bottom < icon.top)
                    if (progressDetails != null) {
                        assertTrue("Progress details must stay below metadata", summary.bottom < progressDetails.top)
                    }
                }
                assertTrue("Play crossed indicator in $columns columns, oneRow=$oneRow", button.bottom < next.indicatorTop)
            }
        compose.runOnIdle {
            layout.value = WatchNextHomeLayout.calculate(960f, 385f, true)
            clockSide.value = null
            program.value = program.value.copy(title = "Short title")
        }
        val richTop = compose.onNodeWithText(play).fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { program.value = WatchNextProgram(program.value.key, title = "Short title") }
        compose.onNodeWithText("Short title").assertIsDisplayed()
        val basicTop = compose.onNodeWithText(play).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Absent metadata left reserved rows", basicTop < richTop)
        compose.runOnIdle { layout.value = WatchNextHomeLayout.calculate(960f, 275f, false) }
        val compactBasicTop = compose.onNodeWithText(play).fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { program.value = program.value.copy(description = "A useful description") }
        compose.onNodeWithText("A useful description").assertIsDisplayed()
        val descriptionTop = compose.onNodeWithText(play).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Additional metadata should expand the centered group", descriptionTop > compactBasicTop)
        compose.runOnIdle { program.value = program.value.copy(season = "2", episode = "5", durationMs = 120_000, positionMs = 60_000) }
        compose.onNodeWithText("Season 2", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1 min remaining", substring = true).assertIsDisplayed()
        // The short column prioritizes season/episode and progress after moving down.
        // A roomier compact column also retains the optional description.
        compose.onNodeWithText("A useful description").assertIsNotDisplayed()
        compose.runOnIdle { layout.value = WatchNextHomeLayout.calculate(960f, 305f, false) }
        compose.onNodeWithText("Season 2", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1 min remaining", substring = true).assertIsDisplayed()
        compose.onNodeWithText("A useful description").assertIsDisplayed()
        compose.runOnIdle { program.value = program.value.copy(episodeTitle = "The episode title") }
        compose.onNodeWithText("The episode title").assertIsDisplayed()
        compose.onNodeWithText("A useful description").assertDoesNotExist()
        compose.runOnIdle {
            layout.value = WatchNextHomeLayout.calculate(960f, 275f, false)
            program.value = WatchNextProgram(program.value.key, title = "Short title")
        }
        assertEquals(compactBasicTop, compose.onNodeWithText(play).fetchSemanticsNode().boundsInRoot.top, 0.5f)
        compose.runOnIdle { program.value = program.value.copy(engagementMs = 1_000, watchNextType = 0) }
        compose.onNodeWithText("Watched", substring = true).assertIsDisplayed()
        compose.onNodeWithText("remaining", substring = true).assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun transitioningPlayKeepsFocusAndRejectsPressStartedOnAnotherProgram() {
        val programs = (1L..3L).map { WatchNextProgram(ProgramKey("source", it), title = "Title $it") }
        val selected = mutableStateOf(programs[0])
        val requester = FocusRequester()
        val played = mutableListOf<ProgramKey>()
        var play = ""
        compose.runOnUiThread {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            play = host!!.get().getString(R.string.watch_next_play)
            val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
            val source = AppEntry("source", "Source", ComponentName("source", "Main"), true, false,
                TileArt(image, null, Rect.Zero, Color.Black, image))
            host!!.get().setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalReducedMotion provides false,
                    LocalGlassSource provides GlassSource("test", null)) {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        WatchNextPreview(selected.value, source, programs,
                            WatchNextHomeLayout.calculate(960f, 385f, true), null, true, true,
                            requester, {}, {}, {}, {}, { played += it.key })
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { requester.requestFocus() }
        compose.mainClock.autoAdvance = false
        compose.onRoot().performKeyInput { keyDown(Key.Enter) }
        compose.runOnIdle { selected.value = programs[1] }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().performKeyInput { keyUp(Key.Enter) }
        compose.runOnIdle { assertTrue(played.isEmpty()) }
        compose.onAllNodes(isFocused()).assertCountEquals(1)
        compose.onAllNodesWithText(play).assertCountEquals(1)
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(listOf(programs[1].key), played) }
        compose.runOnIdle { selected.value = programs[2] }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(1, played.size) }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(listOf(programs[1].key, programs[2].key), played) }
    }
}
