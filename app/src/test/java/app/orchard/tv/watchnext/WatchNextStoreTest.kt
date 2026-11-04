package app.orchard.tv.watchnext

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WatchNextStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var store: WatchNextStore
    @Before fun reset() {
        context.getSharedPreferences("watch_next", 0).edit().clear().commit()
        store = WatchNextStore(context)
    }
    @Test fun firstDiscoverySelectsAllWithoutTurningMasterOn() {
        store.discover(setOf("a", "b"))
        assertFalse(store.enabled)
        assertEquals(setOf("a", "b"), store.sources)
        store.master(true, setOf("a", "b"))
        assertTrue(store.enabled)
    }
    @Test fun reopeningRestoresSelectionAndNewAppsAreAdded() {
        store.discover(setOf("a", "b"))
        store.master(true, setOf("a", "b"))
        store.source("a", false)
        store.master(false, setOf("a", "b"))
        store.discover(setOf("a", "b", "c"))
        store.master(true, setOf("a", "b", "c"))
        assertEquals(setOf("b", "c"), store.sources)
        assertEquals(store.sources, WatchNextStore(context).sources)
    }
    @Test fun emptySavedSelectionIsRepopulatedOnlyWhenReenabled() {
        store.discover(setOf("a", "b"))
        store.source("a", false); store.source("b", false)
        store.discover(setOf("a", "b"))
        assertTrue(store.sources.isEmpty())
        store.master(false, setOf("a", "b"))
        assertTrue(store.sources.isEmpty())
        store.master(true, setOf("a", "b"))
        assertEquals(setOf("a", "b"), store.sources)
    }
    @Test fun alreadyEnabledEmptySelectionIsNotAnImplicitReset() {
        store.discover(setOf("a", "b")); store.master(true, setOf("a", "b"))
        store.source("a", false); store.source("b", false)
        store.master(true, setOf("a", "b"))
        assertTrue(store.sources.isEmpty())
        store.source("a", true)
        assertEquals(setOf("a"), store.sources)
    }
    @Test fun temporaryCatalogAbsenceDoesNotLoseAClosedPreference() {
        store.discover(setOf("a", "b")); store.source("a", false)
        store.discover(setOf("b")); store.discover(setOf("a", "b"))
        assertEquals(setOf("b"), store.sources)
    }
}
