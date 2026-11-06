package app.orchard.tv.watchnext

import android.content.*
import android.content.pm.PermissionInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WatchNextProviderTest {
    private val app = RuntimeEnvironment.getApplication()
    private val provider = TestProvider()
    private lateinit var repository: WatchNextRepository
    @Before fun setup() {
        val pm = shadowOf(app.packageManager)
        pm.addPermissionInfo(PermissionInfo().apply {
            name = WatchNextRepository.Permission
            protectionLevel = PermissionInfo.PROTECTION_DANGEROUS
        })
        val info = pm.addProviderIfNotPresent(ComponentName("tv.provider", TestProvider::class.java.name))
        info.authority = "android.media.tv"; info.exported = true
        pm.addOrUpdateProvider(info)
        provider.attachInfo(app, info)
        ShadowContentResolver.registerProviderInternal("android.media.tv", provider)
        shadowOf(app).grantPermissions(WatchNextRepository.Permission)
        repository = WatchNextRepository(app)
    }
    @After fun close() { provider.release.countDown(); repository.close() }
    @Test fun standardQueryUsesSharedTableAndKeepsMissingValuesUnknown() = runBlocking {
        val result = withTimeout(5_000) { repository.query() }.getOrThrow()
        assertEquals(1, provider.queries)
        assertEquals(WatchNextRepository.Uri, provider.uri)
        assertNull(provider.selection)
        assertNull(provider.selectionArgs)
        assertEquals(1, result.size)
        assertNull(result[0].durationMs)
        assertNull(result[0].progress)
        assertNull(result[0].description)
        assertNull(result[0].reviewRating)
        assertEquals(ProgramKey("source", 7), result[0].key)
    }
    @Test fun optionalMetadataUsesOnlyPublishedValuesAndFallsBackFromBlankSummary() = runBlocking {
        provider.metadata = true
        val item = withTimeout(5_000) { repository.query() }.getOrThrow().single()
        assertEquals(1, provider.queries)
        assertEquals("Published description", item.description)
        assertNull(item.genre)
        assertTrue(item.genreIndices().isEmpty())
        assertEquals(listOf(3, 2), item.copy(genre = "DRAMA,COMEDY,DRAMA,UNKNOWN").genreIndices())
        assertNull(item.copy(reviewRatingStyle = 1).ratingText())
        assertEquals("2026", item.releaseYear())
        assertEquals("4.5/5", item.ratingText())
        assertNull(item.copy(reviewRating = "7.5").ratingText())
        assertNull(item.copy(reviewRating = "NaN").ratingText())
        assertNull(item.copy(reviewRatingStyle = null).ratingText())
        assertNull(item.copy(releaseDate = "not a date").releaseYear())
        assertEquals("75%", item.copy(reviewRatingStyle = 2, reviewRating = "75").ratingText())
    }
    @Test fun incompatibleProjectionFallsBackOnlyOnce() = runBlocking {
        provider.rejectProjection = true
        assertEquals(1, withTimeout(5_000) { repository.query() }.getOrThrow().size)
        assertEquals(2, provider.queries)
    }
    @Test fun cancellationSignalsProviderAndDropsLateRows() = runBlocking {
        provider.block = true
        var published = false
        val job = launch(Dispatchers.Default) { repository.query(); published = true }
        assertTrue(provider.started.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(provider.cancelled.await(5, TimeUnit.SECONDS))
        provider.release.countDown()
        assertFalse(published)
    }
    @Test fun revokedPermissionNeverQueriesProvider() = runBlocking {
        shadowOf(app).denyPermissions(WatchNextRepository.Permission)
        assertTrue(withTimeout(5_000) { repository.query() }.isFailure)
        assertEquals(0, provider.queries)
    }
    @Test @Config(sdk = [25]) fun olderSystemDoesNotExposeWatchNext() {
        assertFalse(repository.capability().supported)
    }
    @Test fun missingProviderHidesCapabilityWithoutQuerying() {
        shadowOf(app.packageManager).removeProvider(ComponentName("tv.provider", TestProvider::class.java.name))
        assertFalse(repository.capability().supported)
    }
    class TestProvider : ContentProvider() {
        var queries = 0
        var uri: Uri? = null
        var selection: String? = "unset"
        var selectionArgs: Array<out String>? = arrayOf("unset")
        var rejectProjection = false
        var block = false
        var metadata = false
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            query(uri, projection, selection, selectionArgs, sortOrder, null)
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?, cancellationSignal: CancellationSignal?): Cursor {
            queries++; this.uri = uri; this.selection = selection; this.selectionArgs = selectionArgs
            if (rejectProjection && projection != null) throw IllegalArgumentException("optional column missing")
            if (block) {
                cancellationSignal?.setOnCancelListener { cancelled.countDown() }
                started.countDown()
                try { release.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
            }
            return if (metadata) MatrixCursor(arrayOf("_id", "package_name", "title", "short_description", "long_description",
                "canonical_genre", "release_date", "review_rating", "review_rating_style")).apply {
                addRow(arrayOf<Any?>(7, "source", null, "  ", " Published description ", " ", "2026-11-03", "4.5", 0))
            } else MatrixCursor(arrayOf("_id", "package_name", "title")).apply { addRow(arrayOf<Any?>(7, "source", null)) }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
