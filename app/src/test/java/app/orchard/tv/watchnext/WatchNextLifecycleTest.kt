package app.orchard.tv.watchnext

import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.TileArt
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WatchNextLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = RuntimeEnvironment.getApplication()
    private val models = ViewModelStore()
    private var host: ActivityController<ComponentActivity>? = null
    private lateinit var registry: LifecycleRegistry
    private lateinit var repository: FakeRepository
    private lateinit var state: WatchNextViewModel
    private var compositions = 0
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @After fun close() { compose.runOnUiThread { host?.close(); models.clear(); idle() } }

    private fun start() {
        compose.runOnUiThread {
            app.getSharedPreferences("watch_next", 0).edit().clear().commit()
            repository = FakeRepository()
            state = WatchNextViewModel(app, repository)
            models.put("watch", state)
            val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
            val source = AppEntry("source", "Source", ComponentName("source", "Main"), true, false,
                TileArt(image, null, Rect.Zero, Color.Black, image))
            val catalog = mapOf("source" to source)
            state.bind(catalog, catalog.keys, false)
            state.setMaster(true)
            val owner = object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this)
            }
            registry = owner.lifecycle
            registry.currentState = Lifecycle.State.RESUMED
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            host!!.get().setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    BindWatchNextLifecycle(state, catalog, catalog.keys, true, true, 0L)
                    SideEffect { compositions++ }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    @Test fun pauseAndResumeStopAndRestartTheReaderWithoutAnyComposeFrame() {
        start()
        compose.runOnUiThread {
            idle()
            assertEquals(1, repository.readers)
            val records = (1L..2L).map { WatchNextProgram(ProgramKey("source", it)) }
            repository.sessions.last().trySend(Result.success(records))
            idle()
            assertEquals(records, state.records)
            state.carousel(true, 0L)
            val key = state.currentKey
            val frames = compositions
            registry.currentState = Lifecycle.State.CREATED
            idle()
            assertEquals(0, repository.readers)
            assertTrue(repository.sessions.last().trySend(Result.success(records)).isFailure)
            // Cross one dwell, avoiding a full cycle that could hide a still-running carousel.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            assertEquals(key, state.currentKey)
            val permissionChecks = repository.permissionChecks
            registry.currentState = Lifecycle.State.RESUMED
            idle()
            assertEquals(1, repository.readers)
            assertEquals(2, repository.sessions.size)
            assertTrue(repository.permissionChecks > permissionChecks)
            assertEquals("Lifecycle cleanup must not require recomposition", frames, compositions)
        }
    }

    @Test fun resumingRechecksPermissionBeforeRestoringTheReader() {
        start()
        compose.runOnUiThread {
            idle()
            assertEquals(1, repository.readers)
            registry.currentState = Lifecycle.State.CREATED
            idle()
            repository.capability = WatchNextCapability(true, false, true)
            registry.currentState = Lifecycle.State.RESUMED
            idle()
            assertEquals(0, repository.readers)
            assertEquals(WatchNextStatus.Unauthorized, state.status)
            assertTrue(state.records.isEmpty())
        }
    }

    private inner class FakeRepository : WatchNextRepository(app) {
        var capability = WatchNextCapability(true, true, true)
        var permissionChecks = 0
        var readers = 0
        val sessions = mutableListOf<ProducerScope<Result<List<WatchNextProgram>>>>()
        override fun capability(): WatchNextCapability { permissionChecks++; return capability }
        override fun observe() = callbackFlow {
            readers++; sessions += this
            awaitClose { readers-- }
        }
    }
}
