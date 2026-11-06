package app.orchard.tv.watchnext

import android.content.ActivityNotFoundException
import androidx.lifecycle.ViewModelStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WatchNextHostTest {
    private val app = RuntimeEnvironment.getApplication()
    private val owner = ViewModelStore()
    private lateinit var repository: FakeRepository
    private lateinit var state: WatchNextViewModel

    @Before fun setup() {
        app.getSharedPreferences("watch_next", 0).edit().clear().commit()
        repository = FakeRepository()
        state = WatchNextViewModel(app, repository)
        owner.put("watch", state)
        assertTrue(state.setMaster(true))
    }

    @After fun finish() { owner.clear() }

    @Test fun missingPermissionActivityAllowsAnotherRequestAndCancelsPendingEnable() {
        authorizeWatchNext(state,
            requestPermission = { throw ActivityNotFoundException("No permission activity") },
            openSettings = { fail("First attempt must request permission") },
        )
        assertFalse(state.store.asked)
        repository.cap = WatchNextCapability(true, true, true)
        state.refreshPermission()
        assertFalse(state.master)
    }

    @Test fun missingSettingsActivityAlsoCancelsPendingEnable() {
        state.store.asked = true
        authorizeWatchNext(state,
            requestPermission = { fail("Previously requested permission must use settings") },
            openSettings = { throw ActivityNotFoundException("No settings activity") },
        )
        assertTrue(state.store.asked)
        repository.cap = WatchNextCapability(true, true, true)
        state.refreshPermission()
        assertFalse(state.master)
    }

    @Test fun synchronousGrantCompletesEnableAndRecordsSuccessfulLaunch() {
        authorizeWatchNext(state,
            requestPermission = {
                repository.cap = WatchNextCapability(true, true, true)
                state.permissionResult(true)
            },
            openSettings = { fail("First attempt must request permission") },
        )
        assertTrue(state.master)
        assertTrue(state.store.asked)
    }

    private inner class FakeRepository : WatchNextRepository(app) {
        var cap = WatchNextCapability(true, false, true)
        override fun capability() = cap
    }
}
