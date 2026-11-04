package app.orchard.tv.watchnext

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.TileArt
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WatchNextArtworkTransitionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ActivityController<ComponentActivity>? = null
    private val programs = (1L..3L).map { WatchNextProgram(ProgramKey("source", it), title = "Title $it") }
    private val first = Bitmap.createBitmap(20, 12, Bitmap.Config.ARGB_8888)
    private val second = Bitmap.createBitmap(12, 20, Bitmap.Config.ARGB_8888)
    private val target = mutableStateOf(programs[0])
    private val records = mutableStateOf(programs)
    private val image = mutableStateOf<Bitmap?>(first)
    private val ready = mutableStateOf(true)
    private val animate = mutableStateOf(true)
    private val reduced = mutableStateOf(false)
    private lateinit var content: WatchNextContentTransition
    private lateinit var artwork: WatchNextArtworkTransition

    private fun start() {
        compose.runOnUiThread {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val icon = first.asImageBitmap()
            val source = AppEntry("source", "Source", ComponentName("source", "Main"), true, false,
                TileArt(icon, null, Rect.Zero, Color.Black, icon))
            host!!.get().setContent {
                CompositionLocalProvider(LocalReducedMotion provides reduced.value) {
                    content = rememberWatchNextContent(WatchNextContent(target.value, source), records.value,
                        1, animate.value, ready.value)
                    artwork = rememberWatchNextArtworkTransition(content.shown.program.key, target.value.key,
                        image.value, animate.value)
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun step(ms: Long) { compose.waitForIdle(); compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }
    private fun select(index: Int, bitmap: Bitmap?) {
        compose.runOnIdle { target.value = programs[index]; image.value = bitmap }
        compose.waitForIdle()
    }
    private fun opacity() = artwork.visibility.value * content.visibility.value

    @After fun close() { compose.runOnUiThread { host?.close() } }

    @Test fun oldCardExitsWithTextThenNewCardEntersWithoutLosingItsProgramKey() {
        start()
        select(1, second)
        step(64)
        compose.runOnIdle {
            assertSame(first, artwork.image)
            assertEquals(programs[0].key, content.shown.program.key)
            assertTrue(opacity() > 0f && opacity() < 1f)
        }
        step(144)
        compose.runOnIdle {
            assertSame(second, artwork.image)
            assertEquals(programs[1].key, content.shown.program.key)
            assertTrue(opacity() > 0f && opacity() < 1f)
        }
        step(600)
        compose.runOnIdle { assertSame(second, artwork.image); assertEquals(1f, opacity()) }
    }

    @Test fun localToBackgroundOrMissingImageReleasesOldCardAtTheHiddenHandover() {
        start()
        compose.runOnIdle { ready.value = false }
        select(1, null)
        step(600)
        compose.runOnIdle { assertSame(first, artwork.image); assertEquals(1f, opacity()); ready.value = true }
        step(64)
        compose.runOnIdle { assertSame(first, artwork.image); assertTrue(opacity() < 1f) }
        step(600)
        compose.runOnIdle { assertNull(artwork.image); assertEquals(programs[1].key, content.shown.program.key) }
        compose.runOnIdle { image.value = second }
        step(64)
        compose.runOnIdle { assertSame(second, artwork.image); assertTrue(opacity() > 0f && opacity() < 1f) }
        step(600)
        compose.runOnIdle { assertEquals(1f, opacity()) }
    }

    @Test fun sameProgramImageRemovalFadesOutAndReplacementCancelsItsPendingRemoval() {
        start()
        compose.runOnIdle { image.value = null }
        step(64)
        compose.runOnIdle { assertSame(first, artwork.image); assertTrue(opacity() < 1f); image.value = first }
        step(600)
        compose.runOnIdle { assertSame(first, artwork.image); assertEquals(1f, opacity()); image.value = null }
        step(300)
        compose.runOnIdle { assertNull(artwork.image); assertEquals(0f, opacity()) }
    }

    @Test fun fastReversalAndMissingTargetNeverResurrectAnIntermediateCard() {
        start()
        select(1, second)
        step(48)
        select(0, first)
        step(600)
        compose.runOnIdle { assertSame(first, artwork.image); assertEquals(1f, opacity()) }
        select(1, second)
        step(48)
        select(2, null)
        step(800)
        compose.runOnIdle { assertNull(artwork.image); assertEquals(programs[2].key, content.shown.program.key) }
    }

    @Test fun removedSourcesReducedMotionAndBackgroundingDoNotRetainAnExitingCard() {
        start()
        compose.runOnIdle { ready.value = false }
        select(1, null)
        compose.runOnIdle { records.value = listOf(programs[1]) }
        step(32)
        compose.runOnIdle { assertNull(artwork.image); reduced.value = true; image.value = second }
        step(32)
        compose.runOnIdle { assertSame(second, artwork.image); assertEquals(1f, opacity()) }
        compose.runOnIdle { reduced.value = false; animate.value = false; image.value = null }
        step(32)
        compose.runOnIdle { assertNull(artwork.image); assertFalse(artwork.visibility.isRunning) }
    }
}
