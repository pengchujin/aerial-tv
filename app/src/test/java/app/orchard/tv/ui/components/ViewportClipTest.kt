package app.orchard.tv.ui.components

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-xhdpi")
class ViewportClipTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ActivityController<ComponentActivity>? = null
    @After fun close() { compose.runOnUiThread { host?.close() } }

    @Test fun clippedTilesCannotBeTappedButTheVisibleRowRemainsInteractive() {
        var hiddenClicks = 0
        var visibleClicks = 0
        compose.runOnUiThread {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            host!!.get().setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalReducedMotion provides true) {
                    Box(Modifier.size(960.dp, 540.dp)) {
                        // The viewport stays fixed while the grid scrolls underneath it.
                        Box(Modifier.fillMaxSize().clipBelow(375.2f.dp).offset { IntOffset(0, -133) }) {
                            Pressable({ hiddenClicks++ }, Modifier.offset(56.dp, 395.dp)
                                .size(147.dp, 83.dp).testTag("hidden")) {}
                            Pressable({ visibleClicks++ }, Modifier.offset(56.dp, 528.dp)
                                .size(147.dp, 83.dp)) {}
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("hidden").getUnclippedBoundsInRoot().bottom.value < 375.2f)
        compose.onRoot().performTouchInput { click(Offset(120f, 300f)) }
        compose.runOnIdle { assertEquals(0, hiddenClicks) }
        compose.onRoot().performTouchInput { click(Offset(120f, 440f)) }
        compose.runOnIdle { assertEquals(1, visibleClicks) }
    }
}
