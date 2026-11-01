package app.orchard.tv.ui.wallpaper

import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.composed
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import app.orchard.tv.ui.components.Pressable
import org.junit.Assert.*
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** The TV pivot must not retain a previous nested row-to-note request after focus reverses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-xhdpi")
class LatestBringIntoViewTest {
    // Own the host in the JVM test instead of adding ui-test-manifest to an app variant.
    // Both debug and release tests use this same test-owned host.
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ActivityController<ComponentActivity>? = null

    @After fun destroyHost() {
        compose.runOnUiThread {
            host?.close()
            host = null
        }
    }
    private lateinit var scroll: ScrollState
    private lateinit var view: View
    private lateinit var scope: CoroutineScope
    private val first = FocusRequester()
    private var firstY = 0f
    private var firstFocused = false
    private val removable = BringIntoViewRequester()
    private var showLast by mutableStateOf(true)

    // Same row-through-note geometry as WallpaperSheet.Group; only the production interceptor
    // is under test here. Full-sheet diagnostics separately exercise Group itself.
    @OptIn(ExperimentalFoundationApi::class)
    private fun Modifier.noteBounds(): Modifier = composed {
        var height by remember { mutableFloatStateOf(0f) }
        val responder = remember {
            object : BringIntoViewResponder {
                override fun calculateRectForParent(localRect: Rect) =
                    Rect(localRect.left, localRect.top, localRect.right, maxOf(localRect.bottom, height))
                override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
            }
        }
        onSizeChanged { height = it.height.toFloat() }.bringIntoViewResponder(responder)
    }
    private fun mount(twoGroups: Boolean = false) {
        Shadows.shadowOf(RuntimeEnvironment.getApplication().packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, true)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java)
            host = controller
            controller.setup()
            controller.get().setContent {
                view = LocalView.current
                scope = rememberCoroutineScope()
                scroll = rememberScrollState()
                Box(Modifier.size(960.dp, 540.dp), contentAlignment = Alignment.BottomStart) {
                    Column(Modifier.width(640.dp).height(336.dp).verticalScroll(scroll).latestBringIntoView(scroll)) {
                        Spacer(Modifier.height(242.dp))
                        Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
                            Pressable({}) { Box(Modifier.size(100.dp, 40.dp)) }
                        }
                        Column(Modifier.fillMaxWidth().noteBounds()) {
                            repeat(3) { row ->
                                Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Pressable({}, focusRequester = if(row == 0) first else null,
                                        modifier = if(row == 0) Modifier.onGloballyPositioned { firstY = it.positionInRoot().y }
                                            .onFocusChanged { firstFocused = it.isFocused } else Modifier) {
                                        Box(Modifier.size(100.dp, 40.dp))
                                    }
                                }
                            }
                            Spacer(Modifier.height(38.dp))
                        }
                        if (twoGroups) Column(Modifier.fillMaxWidth().noteBounds()) {
                            repeat(2) {
                                Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Pressable({}) { Box(Modifier.size(100.dp, 40.dp)) }
                                }
                            }
                            Spacer(Modifier.height(38.dp))
                        }
                        if (showLast) Box(Modifier.size(100.dp, 58.dp).bringIntoViewRequester(removable))
                        Spacer(Modifier.height(500.dp))
                    }
                }
                LaunchedEffect(Unit) { withFrameNanos {}; first.requestFocus() }
            }
            // setup() resumes and shows the Activity but does not give its window focus.
            // Match ActivityScenario's focused host before the initial focus request runs.
            controller.windowFocusChanged(true)
        }
        // Match ComposeContentTestRule.setContent's initial layout synchronization before
        // advancing the frame that triggers the initial focus request.
        compose.waitForIdle()
        frames(90)
        compose.runOnUiThread {
            assertTrue("test host window must have focus", view.hasWindowFocus())
            assertTrue("first row must own initial focus", firstFocused)
            assertEquals("initial focus must reach its TV pivot", 416, scroll.value)
            assertFalse("initial focus scroll must settle", scroll.isScrollInProgress)
        }
    }
    private fun frames(count: Int): List<Pair<Int,Float>> = List(count) {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnUiThread { scroll.value to firstY }
    }
    private fun tap(code: Int) = compose.runOnUiThread {
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
    @Test fun reversingBeforeRowScrollCompletesStopsAtLatestRow() {
        mount()
        val firstRest = compose.runOnUiThread { scroll.value }
        for(delay in listOf(0, 1, 2, 3, 4, 6, 8, 12, 20, 40)) {
            tap(KeyEvent.KEYCODE_DPAD_DOWN)
            frames(delay)
            tap(KeyEvent.KEYCODE_DPAD_UP)
            val tail = frames(180).takeLast(60)
            assertEquals("scroll keeps moving after delay=$delay", 1, tail.map { it.first }.distinct().size)
            assertEquals("content still moves after delay=$delay", 1, tail.map { it.second }.distinct().size)
            compose.runOnUiThread {
                assertFalse(scroll.isScrollInProgress)
                assertEquals("latest row must win, not a retained previous row", firstRest, scroll.value)
            }
        }
    }
    @Test fun settledNavigationStillBringsEachRowAndNoteIntoView() {
        mount()
        val positions = mutableListOf<Int>()
        repeat(3) {
            frames(90)
            positions += compose.runOnUiThread { scroll.value }
            tap(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        assertTrue(positions.zipWithNext().all { (a,b) -> b > a })
        repeat(2) { tap(KeyEvent.KEYCODE_DPAD_UP); frames(90) }
        compose.runOnUiThread { assertEquals(positions.first(), scroll.value); assertFalse(scroll.isScrollInProgress) }
    }
    @Test fun rapidReversalAcrossGroupsStillTargetsLatestRow() {
        mount(twoGroups = true)
        repeat(2) { tap(KeyEvent.KEYCODE_DPAD_DOWN); frames(90) }
        val expected = scroll.value
        for (delay in listOf(0, 1, 2, 4, 8)) {
            tap(KeyEvent.KEYCODE_DPAD_DOWN); frames(delay)
            tap(KeyEvent.KEYCODE_DPAD_UP); frames(180)
            compose.runOnUiThread {
                assertEquals("cross-group reversal delay=$delay", expected, scroll.value)
                assertFalse(scroll.isScrollInProgress)
            }
        }
    }
    @Test fun focusRequestDoesNotCancelUserInputScroll() {
        mount()
        tap(KeyEvent.KEYCODE_DPAD_DOWN); frames(2)
        val input = compose.runOnUiThread {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                scroll.scroll(MutatePriority.UserInput) { awaitCancellation() }
            }
        }
        tap(KeyEvent.KEYCODE_DPAD_UP); frames(4)
        compose.runOnUiThread {
            assertTrue("focus scroll must not cancel higher priority user input", input.isActive)
            input.cancel()
        }
        frames(4)
    }

    @Test fun reversingFromUngroupedWallpaperControlTargetsLatestRow() {
        mount()
        val expected = scroll.value
        for (delay in listOf(0, 1, 2, 4, 8)) {
            tap(KeyEvent.KEYCODE_DPAD_UP); frames(delay)
            tap(KeyEvent.KEYCODE_DPAD_DOWN); frames(180)
            compose.runOnUiThread {
                assertEquals("ungrouped reversal delay=$delay", expected, scroll.value)
                assertFalse(scroll.isScrollInProgress)
            }
        }
    }

    @Test fun removedRequesterDoesNotRetargetWholeSheet() {
        mount()
        // This job belongs to the surviving sheet, not the removed focus node. Its bounds
        // must become invalid without silently falling back to the whole scrolling content.
        val request = compose.runOnUiThread {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { removable.bringIntoView() }
        }
        frames(2)
        compose.runOnUiThread { showLast = false }
        val tail = frames(180).takeLast(60)
        compose.runOnUiThread {
            assertFalse(request.isActive)
            assertFalse(scroll.isScrollInProgress)
            assertTrue("removed child must not scroll the sheet back to its top", scroll.value > 300)
        }
        assertEquals(1, tail.map { it.first }.distinct().size)
    }

    @Test fun interruptedNestedRequestsDoNotOscillateAfterRelease() {
        mount()
        tap(KeyEvent.KEYCODE_DPAD_DOWN); frames(3)
        tap(KeyEvent.KEYCODE_DPAD_UP)
        val tail = frames(300).takeLast(120)
        assertEquals("scroll must settle after key release", 1, tail.map { it.first }.distinct().size)
        assertEquals("focused content must settle after key release", 1, tail.map { it.second }.distinct().size)
        compose.runOnUiThread { assertFalse(scroll.isScrollInProgress) }
    }

}
