package app.orchard.tv.ui.home

import android.content.ComponentName
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.CustomIcons
import app.orchard.tv.apps.Folder
import app.orchard.tv.apps.TileArt
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-xhdpi")
class AppMenuTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ActivityController<ComponentActivity>? = null
    private lateinit var view: View
    private var anchor by mutableStateOf(Rect(800f, 130f, 940f, 220f))
    private var movedTo: String? = null

    @After fun close() {
        compose.runOnUiThread { host?.close() }
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun mount(folders: List<Folder> = emptyList()) {
        val context = RuntimeEnvironment.getApplication()
        CustomIcons.init(context)
        Shadows.shadowOf(context.packageManager).setSystemFeature("android.software.leanback", true)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()
            val app = AppEntry("test.app", "Test app", ComponentName("test.app", "Main"), true, true,
                TileArt(bitmap, null, Rect.Zero, Color.Black, bitmap))
            val controller = Robolectric.buildActivity(ComponentActivity::class.java)
            host = controller
            controller.setup()
            controller.get().setContent {
                view = LocalView.current
                val density = LocalDensity.current.density
                val bounds = Rect(anchor.left * density, anchor.top * density, anchor.right * density, anchor.bottom * density)
                Box(Modifier.size(960.dp, 540.dp)) {
                    AppMenu(app, bounds, false, folders,
                        onMove = {}, onHide = {}, onInfo = {}, onUninstall = {}, onUnpin = {},
                        onResetIcon = {}, onBootTarget = {}, onSourceDevice = { _, _ -> },
                        onToFolder = { _, id -> movedTo = id ?: "new" }, onOutOfFolder = {},
                        onRenameFolder = { _, _ -> }, onUngroup = {}, onDismiss = {})
                }
            }
            controller.windowFocusChanged(true)
        }
        compose.waitForIdle()
        settle()
    }

    private fun settle() {
        repeat(45) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    private fun tap(code: Int) {
        compose.runOnUiThread {
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        settle()
    }

    @Test fun wholeMenuFitsAtDockBottomRowAndNearTheTop() {
        mount()
        for (bounds in listOf(anchor, Rect(800f, 420f, 940f, 510f), Rect(56f, 330f, 210f, 420f), Rect(56f, -60f, 210f, 30f))) {
            compose.runOnUiThread { anchor = bounds }
            settle()
            for (label in listOf("Test app", text(R.string.menu_move), text(R.string.menu_uninstall))) {
                val node = compose.onNodeWithText(label).assertIsDisplayed()
                val rect = node.getUnclippedBoundsInRoot()
                assertTrue("$label above canvas: $rect", rect.top >= 24.dp)
                assertTrue("$label below canvas: $rect", rect.bottom <= 516.dp)
                assertTrue("$label outside canvas: $rect", rect.left >= 24.dp && rect.right <= 936.dp)
            }
        }
    }

    @Test fun remoteReachesLastFolderAndReturningResetsScroll() {
        val folders = List(16) { Folder("folder-$it", "Folder $it", emptyList()) }
        mount(folders)
        tap(KeyEvent.KEYCODE_DPAD_DOWN)
        tap(KeyEvent.KEYCODE_DPAD_CENTER)
        val menu = compose.onNode(hasScrollAction()).getUnclippedBoundsInRoot()
        assertTrue("long menu must fit: $menu", menu.top >= 24.dp && menu.bottom <= 516.dp)
        repeat(folders.size) { tap(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNodeWithText(folders.last().name).assertIsDisplayed()
        tap(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(folders.last().id, movedTo)

        compose.runOnUiThread { host!!.get().onBackPressedDispatcher.onBackPressed() }
        settle()
        compose.onNodeWithText(text(R.string.menu_move)).assertIsDisplayed()
        tap(KeyEvent.KEYCODE_DPAD_DOWN)
        tap(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(text(R.string.menu_new_folder)).assertIsDisplayed()
        tap(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("new", movedTo)
    }
}
