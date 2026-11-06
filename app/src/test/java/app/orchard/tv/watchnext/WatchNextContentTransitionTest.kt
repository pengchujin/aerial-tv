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
class WatchNextContentTransitionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ActivityController<ComponentActivity>? = null
    private val programs = (1L..3L).map { WatchNextProgram(ProgramKey("source", it), title = "Title $it") }
    private val target = mutableStateOf(programs[0])
    private val records = mutableStateOf(programs)
    private val direction = mutableIntStateOf(1)
    private val animate = mutableStateOf(true)
    private val reduced = mutableStateOf(false)
    private val ready = mutableStateOf(true)
    private lateinit var transition: WatchNextContentTransition

    private fun start() {
        compose.runOnUiThread {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
            val source = AppEntry("source", "Source", ComponentName("source", "Main"), true, false,
                TileArt(image, null, Rect.Zero, Color.Black, image))
            host!!.get().setContent {
                CompositionLocalProvider(LocalReducedMotion provides reduced.value) {
                    transition = rememberWatchNextContent(WatchNextContent(target.value, source), records.value,
                        direction.intValue, animate.value, ready.value)
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun step(ms: Long) { compose.waitForIdle(); compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }
    private fun select(index: Int, delta: Int = 1) {
        compose.runOnIdle { direction.intValue = delta; target.value = programs[index] }
        compose.waitForIdle()
    }

    @After fun close() { compose.runOnUiThread { host?.close() } }

    @Test fun oldContentExitsBeforeNewContentEntersAndPlayWaitsForSettling() {
        start()
        select(1)
        step(64)
        compose.runOnIdle {
            assertEquals(programs[0], transition.shown.program)
            assertTrue(transition.visibility.value < 1f)
            assertTrue(transition.offsetDp < 0f)
            assertFalse(transition.canPlay(programs[1].key))
        }
        step(144)
        compose.runOnIdle {
            assertEquals(programs[1], transition.shown.program)
            assertTrue(transition.offsetDp > 0f)
            assertFalse(transition.canPlay(programs[1].key))
        }
        step(600)
        compose.runOnIdle { assertTrue(transition.canPlay(programs[1].key)); assertEquals(0f, transition.offsetDp) }
        select(2, -1)
        step(208)
        compose.runOnIdle { assertTrue(transition.offsetDp < 0f) }
    }

    @Test fun rapidChangesAndReversalCannotPublishCancelledContent() {
        start()
        select(1)
        step(48)
        select(2, -1)
        step(48)
        select(0)
        step(800)
        compose.runOnIdle {
            assertEquals(programs[0], transition.shown.program)
            assertTrue(transition.canPlay(programs[0].key))
            assertEquals(1f, transition.visibility.value)
        }
    }

    @Test fun filteredSourceAndBackgroundingSnapWithoutRetainingRemovedContent() {
        start()
        select(1)
        step(48)
        compose.runOnIdle { records.value = listOf(programs[1]) }
        step(32)
        compose.runOnIdle { assertEquals(programs[1], transition.shown.program); assertFalse(transition.moving) }
        compose.runOnIdle { records.value = programs }
        select(2)
        step(48)
        compose.runOnIdle { animate.value = false }
        step(32)
        compose.runOnIdle { assertEquals(programs[2], transition.shown.program); assertEquals(1f, transition.visibility.value) }
    }

    @Test fun sameRecordRefreshAndReducedMotionDoNotAnimate() {
        start()
        compose.runOnIdle { target.value = programs[0].copy(title = "Updated", positionMs = 20) }
        step(32)
        compose.runOnIdle { assertEquals("Updated", transition.shown.program.title); assertFalse(transition.moving) }
        compose.runOnIdle { reduced.value = true }
        select(2, -1)
        step(32)
        compose.runOnIdle { assertEquals(programs[2], transition.shown.program); assertFalse(transition.moving) }
    }

    @Test fun pendingBackgroundKeepsOldContentAndStartsOnlyForLatestReadyVisit() {
        start()
        compose.runOnIdle { ready.value = false }
        select(1)
        step(600)
        compose.runOnIdle {
            assertEquals(programs[0], transition.shown.program)
            assertEquals(1f, transition.visibility.value)
            assertFalse(transition.canPlay(programs[1].key))
        }
        select(2, -1)
        step(600)
        compose.runOnIdle { assertEquals(programs[0], transition.shown.program); ready.value = true }
        step(64)
        compose.runOnIdle {
            assertEquals(programs[0], transition.shown.program)
            assertTrue(transition.offsetDp > 0f)
        }
        step(800)
        compose.runOnIdle {
            assertEquals(programs[2], transition.shown.program)
            assertTrue(transition.canPlay(programs[2].key))
        }
    }
}
