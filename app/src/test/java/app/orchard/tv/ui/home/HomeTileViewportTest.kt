package app.orchard.tv.ui.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeTileViewportTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>

    @Before fun createHost() {
        // A caller-owned Robolectric host works in debug and release unit tests without
        // adding ui-test-manifest (and its test Activity) to any packaged application.
        compose.runOnUiThread {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        }
    }

    @After fun destroyHost() {
        if (::host.isInitialized) compose.runOnUiThread { host.pause().stop().destroy() }
    }

    private fun setContent(content: @Composable () -> Unit) {
        compose.runOnUiThread { host.get().setContent(content = content) }
    }

    @Test fun clippedTilesCannotBeTappedInHeader() {
        var clicks = 0
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.size(960.dp, 540.dp).testTag("screen")) {
                    HomeTileViewport(scroll = { 0f }) {
                        Box(Modifier.size(960.dp, 400.dp).clickable { clicks++ })
                    }
                }
            }
        }
        compose.onNodeWithTag("screen").performTouchInput { click(Offset(100f, 40f)) }
        compose.runOnIdle { assertEquals(0, clicks) }
        compose.onNodeWithTag("screen").performTouchInput { click(Offset(100f, 100f)) }
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    @Test fun topEdgeFadesIntoWallpaperDuringInterruptedScrolling() {
        lateinit var contentView: View
        val target = mutableStateOf(0f)
        val scroll = Animatable(0f)
        val wallpaper = Color(0xff204060)
        compose.mainClock.autoAdvance = false
        setContent {
            contentView = LocalView.current
            LaunchedEffect(target.value) { scroll.animateTo(target.value, spring()) }
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                Box(Modifier.size(960.dp, 540.dp).background(wallpaper)) {
                    HomeTileViewport(scroll = { scroll.value }) {
                        // Covers the edge through the tested scroll range, so every sampled
                        // pixel measures the real mask rather than a gap between tiles.
                        Box(Modifier.size(960.dp, 540.dp).background(Color.White))
                    }
                    Box(Modifier.offset(20.dp, 28.dp).size(10.dp).background(Color.Red))
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        // Retarget before each spring settles, then return home. This includes both directions.
        for (position in listOf(0f, 240f, 30f, 260f, 0f, 240f, 0f)) {
            compose.runOnUiThread { target.value = position }
            repeat(8) {
                compose.mainClock.advanceTimeByFrame()
                val pixels = compose.runOnIdle {
                    val bitmap = Bitmap.createBitmap(contentView.width, contentView.height, Bitmap.Config.ARGB_8888)
                    contentView.draw(Canvas(bitmap))
                    bitmap.asImageBitmap().toPixelMap().also { bitmap.recycle() }
                }
                assertEquals(wallpaper, pixels[200, 143])
                assertEquals(Color.Red, pixels[50, 66])
                var previous = wallpaper.red
                for (y in 144 until 192) {
                    val color = pixels[200, y]
                    val alpha = (y - 144 + 0.5f) / 48f
                    assertEquals(wallpaper.red + (1f - wallpaper.red) * alpha, color.red, 0.025f)
                    assertEquals(wallpaper.green + (1f - wallpaper.green) * alpha, color.green, 0.025f)
                    assertEquals(wallpaper.blue + (1f - wallpaper.blue) * alpha, color.blue, 0.025f)
                    assertTrue("Fade must not reverse or jump to a hard edge", color.red >= previous)
                    previous = color.red
                }
                assertEquals(Color.White, pixels[200, 192])
            }
        }
    }

    @Test fun headerStaysClearDuringScrollingAndFocusZoom() {
        lateinit var contentView: View
        val scroll = mutableStateOf(0f)
        val columns = mutableStateOf(5)
        val scale = mutableStateOf(1f)
        setContent {
            contentView = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.size(960.dp, 540.dp).background(Color.Black).testTag("screen")) {
                    HomeTileViewport(scroll = { scroll.value }) {
                        val width = restTileWidth(960.dp, columns.value)
                        val height = width * 9f / 16f
                        repeat(12) { row ->
                            repeat(columns.value) { column ->
                                Box(
                                    Modifier.offset {
                                        IntOffset(
                                            (56.dp + (width + gridGap(columns.value)) * column).roundToPx(),
                                            (72.dp + (height + 50.dp) * row).roundToPx(),
                                        )
                                    }.size(width, height).graphicsLayer {
                                        scaleX = scale.value
                                        scaleY = scale.value
                                    }.background(Color.White),
                                )
                            }
                        }
                    }
                    // A sibling clock above the tile viewport must not be clipped with the tiles.
                    Box(Modifier.offset(20.dp, 28.dp).size(10.dp).background(Color.Red))
                }
            }
        }
        for (count in 4..6) for (zoom in listOf(1f, 1.15f, 1.25f)) {
            // Both directions, fractional pixels and large jumps (rapid D-pad / touch fling).
            for (position in listOf(0f, 68.4f, 149.6f, 307.5f, 850.2f, 307.5f, 0f)) {
                compose.runOnIdle { columns.value = count; scale.value = zoom; scroll.value = position }
                // Draw the real composed View through Robolectric's native Canvas. Window
                // PixelCopy/captureToImage waits for a device surface that Robolectric lacks.
                val pixels = compose.runOnIdle {
                    val bitmap = Bitmap.createBitmap(contentView.width, contentView.height, Bitmap.Config.ARGB_8888)
                    contentView.draw(Canvas(bitmap))
                    if (zoom == 1.15f && position == 149.6f) {
                        val file = File("build/reports/viewport/columns-$count.png")
                        file.parentFile!!.mkdirs()
                        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    bitmap.asImageBitmap().toPixelMap().also { bitmap.recycle() }
                }
                assertEquals(960, pixels.width)
                assertEquals(540, pixels.height)
                for (y in 0 until 72) for (x in 50 until pixels.width) {
                    assertEquals("columns=$count scale=$zoom scroll=$position at $x,$y", Color.Black, pixels[x, y])
                }
                assertEquals(Color.Red, pixels[25, 33])
                // The bottom of the fixed viewport must stay visible even at large scroll.
                assertTrue((450 until 530).any { y -> pixels[100, y] == Color.White })
            }
        }
    }
}
