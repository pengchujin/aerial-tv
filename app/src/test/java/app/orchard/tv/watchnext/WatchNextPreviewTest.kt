package app.orchard.tv.watchnext

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.TileArt
import app.orchard.tv.ui.components.GlassSource
import app.orchard.tv.ui.components.LocalGlassSource
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.home.GridMetrics
import app.orchard.tv.ui.home.ShelfClock
import app.orchard.tv.ui.home.shelfContentAlpha
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
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-xhdpi")
class WatchNextPreviewTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ActivityController<ComponentActivity>? = null
    @After fun close() { compose.runOnUiThread { host?.close() } }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun localArtworkFadesAsOneCardAndClearsBeforeTheMetadata() {
        val opacity = mutableStateOf(1f)
        val layout = WatchNextHomeLayout.calculate(960f, 385f, true)
        val program = WatchNextProgram(ProgramKey("source", 1), title = "Current programme")
        compose.runOnUiThread {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(android.graphics.Color.rgb(32, 80, 128)) }
            val image = bitmap.asImageBitmap()
            val source = AppEntry("source", "Source", ComponentName("source", "Main"), true, false,
                TileArt(image, null, Rect.Zero, Color.Black, image))
            host!!.get().setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalReducedMotion provides true,
                    LocalGlassSource provides GlassSource("test", null)) {
                    Box(Modifier.fillMaxSize().drawBehind {
                        drawRect(Color(0xFFF0C080))
                        for (stripe in 0..(size.width / 8).toInt()) {
                            drawRect(Color(0xFF408060),
                                topLeft = Offset(stripe * 8f, 0f),
                                size = Size(4f, size.height))
                        }
                    }) {
                        WatchNextPreview(program, source, listOf(program), layout, bitmap, true, true,
                            remember { FocusRequester() }, {}, {}, {}, {}, {},
                            visibility = { opacity.value })
                    }
                }
            }
        }
        fun capture() = compose.runOnIdle {
            val view = host!!.get().findViewById<android.view.View>(android.R.id.content)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(android.graphics.Canvas(it))
            }.asImageBitmap().toPixelMap()
        }
        compose.waitForIdle()
        val full = capture()
        compose.runOnIdle { opacity.value = 0f }
        val wallpaper = capture()
        for (level in listOf(0.85f, 0.65f, 0.5f, 0.25f, 0f, 0.5f, 0.85f, 1f)) {
            compose.runOnIdle { opacity.value = level }
            val frame = capture()
            val artworkAlpha = watchNextArtworkShelfAlpha(level)
            assertTrue("Artwork must not linger behind the metadata", artworkAlpha <= level)
            if (level == 0.5f) assertTrue("The thumbnail should be almost gone halfway through", artworkAlpha < 0.1f)
            var artworkPixels = 0
            for (row in layout.contentTop.toInt() until (layout.contentTop + layout.contentHeight).toInt())
                for (column in layout.artLeft.toInt() until (layout.artLeft + layout.artWidth).toInt()) {
                    val original = full[column, row]
                    val behind = wallpaper[column, row]
                    val expected = Color(
                        behind.red + (original.red - behind.red) * artworkAlpha,
                        behind.green + (original.green - behind.green) * artworkAlpha,
                        behind.blue + (original.blue - behind.blue) * artworkAlpha,
                    )
                    val actual = frame[column, row]
                    if (kotlin.math.abs(original.red - behind.red) > 0.1f) artworkPixels++
                    assertEquals("Red at $column,$row, opacity=$level", expected.red, actual.red, 0.02f)
                    assertEquals("Green at $column,$row, opacity=$level", expected.green, actual.green, 0.02f)
                    assertEquals("Blue at $column,$row, opacity=$level", expected.blue, actual.blue, 0.02f)
                }
            assertTrue("The thumbnail was not drawn", artworkPixels > 10_000)
            if (level > 0f) compose.onNodeWithText(program.title!!).assertExists()
            else compose.onNodeWithText(program.title!!).assertDoesNotExist()
        }
    }

    @Test fun artworkShelfFadeIsContinuousAndReversible() {
        assertEquals(0f, watchNextArtworkShelfAlpha(-1f), 0f)
        assertEquals(1f, watchNextArtworkShelfAlpha(2f), 0f)
        val entering = (0..100).map { watchNextArtworkShelfAlpha(it / 100f) }
        val leaving = (100 downTo 0).map { watchNextArtworkShelfAlpha(it / 100f) }
        assertEquals(entering, leaving.reversed())
        assertEquals(0f, entering.first(), 0f)
        assertEquals(1f, entering.last(), 0f)
        entering.zipWithNext().forEach { (previous, next) ->
            assertTrue(next >= previous)
            assertTrue("The fade must not jump between adjacent scroll positions", next - previous < 0.05f)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun scrollingAwayHidesTheWholePreviewAndRestoresItWithoutTakingAppFocus() {
        val scroll = mutableStateOf(0f)
        val program = WatchNextProgram(ProgramKey("source", 1), title = "Current programme")
        val appRequester = FocusRequester()
        val programRequester = FocusRequester()
        val played = mutableListOf<ProgramKey>()
        var play = ""
        compose.runOnUiThread {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            play = host!!.get().getString(R.string.watch_next_play)
            val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
            val image = bitmap.asImageBitmap()
            val source = AppEntry("source", "Source", ComponentName("source", "Main"), true, false,
                TileArt(image, null, Rect.Zero, Color.Black, image))
            host!!.get().setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalReducedMotion provides true,
                    LocalGlassSource provides GlassSource("test", null)) {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        Pressable({}, Modifier.offset(56.dp, 400.dp).size(147.dp, 83.dp).testTag("app"),
                            focusRequester = appRequester) {}
                        WatchNextPreview(program, source, listOf(program),
                            WatchNextHomeLayout.calculate(960f, 385f, true), bitmap, true, true,
                            programRequester, {}, {}, {}, {}, { played += it.key },
                            visibility = { shelfContentAlpha(scroll.value, 200f) })
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { appRequester.requestFocus() }
        val indicators = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
        compose.onNodeWithText(program.title!!).assertExists()
        compose.onNodeWithText(play).assertExists()
        compose.onAllNodes(indicators).assertCountEquals(1)
        compose.runOnIdle { scroll.value = 200f }
        compose.onNodeWithText(program.title!!).assertDoesNotExist()
        compose.onNodeWithText(play).assertDoesNotExist()
        compose.onAllNodes(indicators).assertCountEquals(0)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.Enter) }
        compose.onNodeWithTag("app").assertIsFocused()
        compose.runOnIdle { assertTrue(played.isEmpty()); scroll.value = 0f }
        compose.onNodeWithText(program.title!!).assertExists()
        compose.onNodeWithText(play).assertExists()
        compose.onAllNodes(indicators).assertCountEquals(1)
        compose.onNodeWithTag("app").assertIsFocused()
        compose.runOnIdle { programRequester.requestFocus() }
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(listOf(program.key), played) }
    }

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

    @Test fun pointerDownTakesFocusAndCannotPlayAReplacementProgramOnRelease() {
        val programs = (1L..2L).map { WatchNextProgram(ProgramKey("source", it), title = "Title $it") }
        val selected = mutableStateOf(programs[0])
        val played = mutableListOf<ProgramKey>()
        val tileRequester = FocusRequester()
        var focused = false
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
                        Pressable({}, Modifier.offset(56.dp, 400.dp).size(147.dp, 83.dp),
                            focusRequester = tileRequester) {}
                        WatchNextPreview(selected.value, source, programs,
                            WatchNextHomeLayout.calculate(960f, 385f, true), null, true, true,
                            remember { FocusRequester() }, { focused = true }, {}, {}, {}, { played += it.key })
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { tileRequester.requestFocus(); focused = false }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(play).performTouchInput { down(center) }
        compose.runOnIdle {
            assertTrue("Pointer down must pause browsing by focusing the programme", focused)
            selected.value = programs[1]
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onNodeWithText(play).performTouchInput { up() }
        compose.runOnIdle { assertTrue("Release must not play a different programme", played.isEmpty()) }
        compose.onNodeWithText(play).performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(programs[1].key), played) }
    }
}
