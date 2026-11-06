package app.orchard.tv.watchnext

import android.content.ComponentName
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModelStore
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.TileArt
import app.orchard.tv.ui.home.gridGlassAmount
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WatchNextStateTest {
    private val app = RuntimeEnvironment.getApplication()
    private val owner = ViewModelStore()
    private lateinit var repo: FakeRepository
    private lateinit var state: WatchNextViewModel
    private lateinit var catalog: Map<String, AppEntry>
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun record(id: Long, source: String = "a") = WatchNextProgram(ProgramKey(source, id))
    @Before fun setup() {
        app.getSharedPreferences("watch_next", 0).edit().clear().commit()
        repo = FakeRepository()
        state = WatchNextViewModel(app, repo)
        owner.put("watch", state)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
        val art = TileArt(bitmap, null, Rect.Zero, Color.Black, bitmap)
        catalog = listOf("a", "b").associateWith { AppEntry(it, it, ComponentName(it, "Main"), true, false, art) }
        state.bind(catalog, catalog.keys, true)
    }
    @After fun finish() { owner.clear(); idle() }
    private fun enable() { state.setMaster(true); idle() }
    private fun publish(vararg values: WatchNextProgram) {
        assertTrue(repo.sessions.last().trySend(Result.success(values.toList())).isSuccess)
        idle()
    }
    @Test fun emptySuccessKeepsObserverAndLaterRecordsAppear() {
        enable()
        assertEquals(WatchNextStatus.Loading, state.status)
        publish()
        assertEquals(WatchNextStatus.Empty, state.status)
        assertEquals(1, repo.openReaders)
        publish(record(1))
        assertEquals(WatchNextStatus.Ready, state.status)
        assertEquals(record(1).key, state.currentKey)
        assertEquals(1, repo.sessions.size)
    }
    @Test fun closingMasterCancelsObserverAndRejectsItsLateResult() {
        enable(); publish(record(1))
        val old = repo.sessions.last()
        state.setMaster(false); idle()
        assertEquals(0, repo.openReaders)
        assertTrue(old.trySend(Result.success(listOf(record(2)))).isFailure)
        assertTrue(state.records.isEmpty())
        assertEquals(WatchNextStatus.Disabled, state.status)
    }
    @Test fun cancelledPermissionCommandCannotReenableFromLateGrant() {
        repo.cap = WatchNextCapability(true, false, true)
        state.refreshPermission()
        assertTrue(state.setMaster(true))
        state.setMaster(false)
        repo.cap = WatchNextCapability(true, true, true)
        state.permissionResult(true); idle()
        assertFalse(state.master)
        assertEquals(0, repo.openReaders)
    }
    @Test fun sourceCommandEnablesMasterAndClosingMasterPreservesPreference() {
        state.toggleSource("a"); idle()
        assertTrue(state.master)
        publish(record(1), record(2, "b"))
        state.toggleSource("a")
        assertEquals(listOf(record(2, "b")), state.records)
        state.setMaster(false)
        assertFalse(state.sourceOn("b"))
        state.setMaster(true)
        assertFalse(state.sourceOn("a"))
        assertTrue(state.sourceOn("b"))
    }
    @Test fun hostRestrictionAppliesWithoutWaitingForAnotherQuery() {
        enable(); publish(record(1), record(2, "b"))
        state.advance(1)
        assertEquals(record(2, "b").key, state.currentKey)
        state.bind(catalog, emptySet(), true)
        assertTrue(state.records.isEmpty())
        state.bind(catalog, setOf("b"), true)
        assertEquals(listOf(record(2, "b")), state.records)
        assertEquals(record(2, "b").key, state.currentKey)
    }
    @Test fun backgroundStopsObserverAndForegroundCreatesExactlyOne() {
        enable(); publish(record(1))
        state.bind(catalog, catalog.keys, false); idle()
        assertEquals(0, repo.openReaders)
        state.bind(catalog, catalog.keys, true); idle()
        state.bind(catalog, catalog.keys, true); idle()
        assertEquals(1, repo.openReaders)
        assertEquals(2, repo.sessions.size)
    }
    @Test fun failureIsNotEmptyAndPermissionRevocationDropsSnapshot() {
        enable(); publish(record(1))
        repo.sessions.last().trySend(Result.failure(IllegalStateException("temporarily unavailable"))); idle()
        assertEquals(WatchNextStatus.Failed, state.status)
        assertEquals(1, state.records.size)
        repo.cap = WatchNextCapability(true, false, true)
        state.refreshPermission(); idle()
        assertEquals(WatchNextStatus.Unauthorized, state.status)
        assertTrue(state.records.isEmpty())
        assertEquals(0, repo.openReaders)
    }
    @Test fun observerFailureCanBeRetriedWithoutLeavingHome() {
        enable(); publish(record(1))
        repo.sessions.last().close(IllegalStateException("observer temporarily unavailable")); idle()
        assertEquals(WatchNextStatus.Failed, state.status)
        assertEquals(listOf(record(1)), state.records)
        assertEquals(0, repo.openReaders)

        state.setMaster(true); idle()
        assertEquals(1, repo.openReaders)
        assertEquals(2, repo.sessions.size)
        publish(record(2))
        assertEquals(WatchNextStatus.Ready, state.status)
        assertEquals(listOf(record(2)), state.records)
    }
    @Test fun automaticCyclePausesAndRestartsAFullDwellAfterNavigation() {
        enable(); publish(record(1), record(2))
        state.carousel(true, 1L)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(8_320))
        assertEquals(record(2).key, state.currentKey)
        assertEquals(1, state.navigationDirection)
        state.pauseCarousel()
        state.carousel(false, 2L)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(20))
        assertEquals(record(2).key, state.currentKey)
        state.advance(-1)
        assertEquals(-1, state.navigationDirection)
        state.carousel(true, 3L)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(4_000))
        state.carousel(true, 4L)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(4_320))
        assertEquals(record(1).key, state.currentKey)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(4_000))
        assertEquals(record(2).key, state.currentKey)
        assertEquals(1, state.navigationDirection)
    }
    @Test fun gridAndScreensaverPauseCarouselUntilAFreshDwellAfterReturning() {
        enable(); publish(record(1), record(2), record(3))
        fun bindVisibility(screensaver: Boolean, scroll: Float) {
            state.bind(catalog, catalog.keys, !screensaver)
            state.carousel(!screensaver && gridGlassAmount(scroll, 100f) < 0.99f, 0L)
            idle()
        }
        bindVisibility(false, 0f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
        bindVisibility(false, 100f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10))
        assertEquals(record(1).key, state.currentKey)
        assertEquals(1, repo.openReaders)

        bindVisibility(false, 0f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
        assertEquals(record(1).key, state.currentKey)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
        assertEquals(record(2).key, state.currentKey)

        bindVisibility(true, 0f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10))
        assertEquals(record(2).key, state.currentKey)
        assertEquals(0, repo.openReaders)
        bindVisibility(false, 0f)
        assertEquals(1, repo.openReaders)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
        assertEquals(record(2).key, state.currentKey)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
        assertEquals(record(3).key, state.currentKey)
    }
    @Test fun wrappingUsesGestureDirectionRatherThanIndexDifference() {
        enable(); publish(record(1), record(2), record(3))
        state.advance(-1)
        assertEquals(record(3).key, state.currentKey)
        assertEquals(-1, state.navigationDirection)
        state.advance(1)
        assertEquals(record(1).key, state.currentKey)
        assertEquals(1, state.navigationDirection)
        state.advance(0)
        assertEquals(record(1).key, state.currentKey)
        assertEquals(1, state.navigationDirection)
    }
    private inner class FakeRepository : WatchNextRepository(app) {
        var cap = WatchNextCapability(true, true, true)
        val sessions = mutableListOf<ProducerScope<Result<List<WatchNextProgram>>>>()
        var openReaders = 0
        override fun capability() = cap
        override fun observe() = callbackFlow {
            sessions += this; openReaders++
            awaitClose { openReaders-- }
        }
    }
}
