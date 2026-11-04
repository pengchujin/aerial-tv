package app.orchard.tv.watchnext

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import app.orchard.tv.ui.home.HomeBackground
import app.orchard.tv.ui.theme.LocalReducedMotion
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-xhdpi")
class WatchNextBackgroundTransitionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ActivityController<ComponentActivity>? = null
    private val first = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
    private val second = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
    private val third = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
    private val target = mutableStateOf(first)
    private val direction = mutableStateOf(1)

    private fun start(reduced: Boolean) {
        compose.runOnUiThread {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            host!!.get().setContent {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    WatchNextBackgroundTransition(target.value, direction.value) { image ->
                        Box(Modifier.fillMaxSize().background(when (image) {
                            first -> Color.Red
                            second -> Color.Blue
                            else -> Color.Green
                        }).testTag(when (image) {
                            first -> "first"
                            second -> "second"
                            third -> "third"
                            else -> "empty"
                        }))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @After fun close() { compose.runOnUiThread { host?.close() } }

    @Test fun interruptedTransitionSettlesOnLatestPicture() {
        start(false)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { target.value = second }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { target.value = third }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("third").assertExists()
        compose.onNodeWithTag("first").assertDoesNotExist()
        compose.onNodeWithTag("second").assertDoesNotExist()
    }

    @Test fun reducedMotionDoesNotRetainPreviousPicture() {
        start(true)
        compose.runOnIdle { target.value = second }
        compose.waitForIdle()
        compose.onNodeWithTag("second").assertExists()
        compose.onNodeWithTag("first").assertDoesNotExist()
    }

    @Test fun pendingBackgroundRetainsPreviousUntilResolvedAndDiscardsCancelledVisit() {
        val visit = mutableStateOf(0)
        val enabled = mutableStateOf(true)
        val navigation = mutableStateOf(1)
        val a = WatchNextBackground("a", first, first)
        val b = WatchNextBackground("b", second, second)
        val requests = List(4) { CompletableDeferred<WatchNextBackground?>() }
        requests[0].complete(a)
        var displayed: WatchNextBackground? = null
        var displayedDirection = 0
        var displayedKey: ProgramKey? = null
        compose.runOnUiThread {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            host!!.get().setContent {
                val index = visit.value
                val result = rememberWatchNextBackground(index, enabled.value, false, navigation.value,
                    ProgramKey("source", index.toLong())) { requests[index].await() }
                SideEffect { displayed = result.image; displayedDirection = result.direction; displayedKey = result.programKey }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertSame(a, displayed); visit.value = 1 }
        compose.runOnIdle { assertSame(a, displayed); assertEquals(ProgramKey("source", 0), displayedKey); visit.value = 2 }
        compose.runOnIdle { requests[1].complete(b) }
        compose.runOnIdle { assertSame(a, displayed); navigation.value = -1 }
        compose.runOnIdle { requests[2].complete(b) }
        compose.runOnIdle { assertSame(b, displayed); assertEquals(1, displayedDirection); assertEquals(ProgramKey("source", 2), displayedKey); visit.value = 3 }
        compose.runOnIdle { assertSame(b, displayed); requests[3].complete(null) }
        compose.runOnIdle { assertNull(displayed); assertEquals(-1, displayedDirection); assertEquals(ProgramKey("source", 3), displayedKey); visit.value = 0 }
        compose.runOnIdle { assertSame(a, displayed); enabled.value = false }
        compose.runOnIdle { assertNull(displayed); assertNull(displayedKey) }
    }

    @Test fun pausedVisitKeepsItsFrameWithoutReloadingOrReplayingTheTransition() {
        val paused = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        val key = ProgramKey("source", 1)
        val allowed = mutableStateOf(setOf(key))
        val original = WatchNextBackground("original", first, first)
        var displayed = WatchNextBackgroundFrame()
        var loads = 0
        compose.runOnUiThread {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            host!!.get().setContent {
                val frame = rememberWatchNextBackground(key, enabled.value, false,
                    programKey = key, allowedKeys = allowed.value, paused = paused.value) {
                    loads++
                    original
                }
                SideEffect { displayed = frame }
                WatchNextPictureTransition(frame.image?.backdrop ?: second,
                    frame.image != null, animate = !paused.value, filmCover = { 0f }) { image, _ ->
                    Box(Modifier.fillMaxSize().testTag(if (image === first) "programme" else "wallpaper"))
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val retained = compose.runOnIdle {
            assertEquals(1, loads)
            assertSame(original, displayed.image)
            displayed
        }
        repeat(2) {
            compose.runOnIdle { paused.value = true }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnIdle { assertSame(retained, displayed); assertEquals(1, loads) }
            compose.runOnIdle { paused.value = false }
            compose.mainClock.advanceTimeBy(96)
            compose.runOnIdle { assertSame(retained, displayed); assertEquals(1, loads) }
            compose.onAllNodesWithTag("programme").assertCountEquals(1)
            compose.onNodeWithTag("programme").assertLeftPositionInRootIsEqualTo(0.dp)
            compose.onNodeWithTag("wallpaper").assertDoesNotExist()
        }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { paused.value = true; allowed.value = emptySet() }
        compose.runOnIdle { assertNull(displayed.image) }
        compose.runOnIdle { enabled.value = false; allowed.value = setOf(key) }
        compose.runOnIdle { assertNull(displayed.image) }
        compose.runOnIdle { enabled.value = true; paused.value = false }
        compose.runOnIdle { assertSame(original, displayed.image); assertEquals(2, loads) }
    }

    @Test fun pausedFallbackDoesNotBecomeANewBackgroundOnWake() {
        val paused = mutableStateOf(false)
        val key = ProgramKey("source", 1)
        var available: WatchNextBackground? = null
        var displayed = WatchNextBackgroundFrame()
        var loads = 0
        compose.runOnUiThread {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            host!!.get().setContent {
                val frame = rememberWatchNextBackground(key, true, false,
                    programKey = key, paused = paused.value) {
                    loads++
                    available
                }
                SideEffect { displayed = frame }
            }
        }
        compose.waitForIdle()
        val retained = compose.runOnIdle {
            assertEquals(key, displayed.programKey)
            assertNull(displayed.image)
            displayed
        }
        compose.runOnIdle {
            paused.value = true
            available = WatchNextBackground("downloaded", second, second)
        }
        compose.runOnIdle { paused.value = false }
        compose.runOnIdle {
            assertSame(retained, displayed)
            assertNull(displayed.image)
            assertEquals(1, loads)
        }
    }

    @Test fun pausingCancelsAnUnresolvedVisitAndResumesTheCurrentRequest() {
        val key = ProgramKey("source", 1)
        val paused = mutableStateOf(false)
        val pending = CompletableDeferred<WatchNextBackground?>()
        val original = WatchNextBackground("original", first, first)
        var displayed = WatchNextBackgroundFrame()
        var loads = 0
        var cancellations = 0
        compose.runOnUiThread {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            host!!.get().setContent {
                val frame = rememberWatchNextBackground(key, true, false,
                    programKey = key, paused = paused.value) {
                    loads++
                    try { pending.await() } finally { cancellations++ }
                }
                SideEffect { displayed = frame }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, loads); paused.value = true }
        compose.runOnIdle {
            assertEquals(1, cancellations)
            pending.complete(original)
        }
        compose.runOnIdle { assertNull(displayed.image); paused.value = false }
        compose.runOnIdle {
            assertEquals(2, loads)
            assertSame(original, displayed.image)
        }
    }

    @Test fun leftAndRightNavigationEnterFromOppositeEdges() {
        start(false)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { direction.value = 1; target.value = second }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(160)
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("second").getUnclippedBoundsInRoot().left.value > 0f)
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { direction.value = -1; target.value = third }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(160)
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("third").getUnclippedBoundsInRoot().left.value < 0f)
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("third").assertExists()
        compose.onNodeWithTag("second").assertDoesNotExist()
    }

    @Test fun removedSourceCannotRetainItsBackgroundWhileNextVisitLoads() {
        val firstKey = ProgramKey("first-source", 1)
        val secondKey = ProgramKey("second-source", 2)
        val visit = mutableStateOf(firstKey)
        val allowed = mutableStateOf(setOf(firstKey, secondKey))
        val pending = CompletableDeferred<WatchNextBackground?>()
        val original = WatchNextBackground("first", first, first)
        var displayed: WatchNextBackground? = null
        compose.runOnUiThread {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            host!!.get().setContent {
                val key = visit.value
                val frame = rememberWatchNextBackground(key, true, false, programKey = key,
                    allowedKeys = allowed.value) {
                    if (key == firstKey) original else pending.await()
                }
                SideEffect { displayed = frame.image }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertSame(original, displayed); visit.value = secondKey }
        compose.runOnIdle { assertSame(original, displayed); allowed.value = setOf(secondKey) }
        compose.runOnIdle { assertNull(displayed) }
        val next = WatchNextBackground("second", second, second)
        compose.runOnIdle { pending.complete(next) }
        compose.runOnIdle { assertSame(next, displayed) }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowSeamBlendsWithoutDimmingOrExposingTheUnderlay() {
        start(false)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { target.value = second }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(96)
        compose.waitForIdle()
        // Robolectric has no real Window/PixelCopy callback; draw the actual Compose view tree.
        val bitmap = compose.runOnIdle {
            val view = host!!.get().window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(android.graphics.Canvas(it))
            }
        }
        val pixels = bitmap.asImageBitmap().toPixelMap()
        var mixed = 0
        for (x in 0 until pixels.width) {
            val color = pixels[x, pixels.height / 2]
            assertTrue("Gap or dim seam at $x: $color", color.red + color.blue > 0.98f)
            assertTrue(color.green < 0.02f)
            if (color.red > 0.02f && color.blue > 0.02f) mixed++
        }
        assertTrue("No soft seam was drawn", mixed > 8)
        assertTrue("Blending escaped the narrow band: $mixed", mixed < pixels.width / 8)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun retainedProgrammeYieldsToCarriedPhotosAndFilmThenReturnsOnWake() {
        fun solid(color: Int) = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(color) }.asImageBitmap()
        val programme = solid(android.graphics.Color.RED)
        val photo = solid(android.graphics.Color.BLUE)
        val nextPhoto = solid(android.graphics.Color.YELLOW)
        // 0: Home, 1/2: carried photos, 3: carried film.
        val stage = mutableStateOf(0)
        compose.runOnUiThread {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            host!!.get().setContent {
                CompositionLocalProvider(LocalReducedMotion provides false, LocalDensity provides Density(1f)) {
                    val saving = stage.value != 0
                    val film = stage.value == 3
                    Box(Modifier.fillMaxSize().background(Color.Green)) {
                        HomeBackground(
                            backdrop = programme, frosted = programme,
                            plate = androidx.compose.ui.geometry.Rect(20f, 180f, 300f, 260f),
                            scroll = { 0f }, collapse = 100f,
                            cover = { if (film) 1f else 0f },
                            toSaver = { if (saving) 1f else 0f },
                            saverImage = when (stage.value) { 1 -> photo; 2 -> nextPhoto; else -> null },
                            saverReveal = { if (saving) 1f else 0f },
                            pictureTransition = { image, content ->
                                WatchNextPictureTransition(image, true, revealFilm = film,
                                    filmCover = { 1f }, content = content)
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        for ((step, expected) in listOf(1 to Color.Blue, 2 to Color.Yellow, 0 to Color.Red,
            3 to Color.Green, 0 to Color.Red)) {
            compose.runOnIdle { stage.value = step }
            compose.waitForIdle()
            val pixels = compose.runOnIdle {
                val view = host!!.get().window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                    view.draw(android.graphics.Canvas(it))
                }.asImageBitmap().toPixelMap()
            }
            // The lower point is inside the shelf's glass; it must disappear with Home.
            for ((x, y) in listOf(100 to 100, 100 to 220).take(if (step == 0) 1 else 2)) {
                val actual = pixels[x, y]
                assertEquals("Stage $step red at $x,$y", expected.red, actual.red, 0.02f)
                assertEquals("Stage $step green at $x,$y", expected.green, actual.green, 0.02f)
                assertEquals("Stage $step blue at $x,$y", expected.blue, actual.blue, 0.02f)
            }
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun programmeAndFilmKeepTheirOwnCoverageThroughBothDirectionsOfTheTransition() {
        val programme = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.RED) }.asImageBitmap()
        val wallpaper = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.BLUE) }.asImageBitmap()
        val showingProgramme = mutableStateOf(true)
        compose.runOnUiThread {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            host!!.get().setContent {
                CompositionLocalProvider(LocalReducedMotion provides false, LocalDensity provides Density(1f)) {
                    // The film plane is green; its older static wallpaper must never flash blue.
                    Box(Modifier.fillMaxSize().background(Color.Green)) {
                        HomeBackground(
                            backdrop = if (showingProgramme.value) programme else wallpaper,
                            frosted = null, plate = null, scroll = { 0f }, collapse = 100f,
                            cover = { if (showingProgramme.value) 0f else 1f },
                            pictureTransition = { image, content ->
                                WatchNextPictureTransition(image, showingProgramme.value,
                                    filmCover = { 1f }, content = content)
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        repeat(2) { visit ->
            compose.runOnIdle { showingProgramme.value = visit != 0 }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(96)
            compose.waitForIdle()
            val bitmap = compose.runOnIdle {
                val view = host!!.get().window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                    view.draw(android.graphics.Canvas(it))
                }
            }
            val pixels = bitmap.asImageBitmap().toPixelMap()
            var red = 0
            var green = 0
            for (x in 0 until pixels.width) {
                val color = pixels[x, pixels.height / 2]
                assertTrue("Static wallpaper flashed through the film: $color", color.blue < 0.02f)
                assertTrue("Uncovered transition pixel at $x: $color", color.red + color.green > 0.98f)
                if (color.red > 0.98f) red++
                if (color.green > 0.98f) green++
            }
            assertTrue("The outgoing programme/film disappeared before the transition", red > 0 && green > 0)
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
    }
}
