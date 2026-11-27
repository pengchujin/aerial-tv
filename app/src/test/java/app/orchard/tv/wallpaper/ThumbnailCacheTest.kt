package app.orchard.tv.wallpaper

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ThumbnailCacheTest {
    private fun bitmap(bytes: Int): Bitmap = Bitmap.createBitmap(bytes, 1, Bitmap.Config.ALPHA_8)

    @Test fun evictsLeastRecentlyUsedEntriesToStayWithinByteBudget() {
        val cache = ThumbnailCache(10)
        val first = bitmap(4)
        val second = bitmap(4)
        val third = bitmap(4)
        cache.put("first", first)
        cache.put("second", second)
        cache.put("third", third)
        assertNull(cache.get("first"))
        assertSame(second, cache.get("second"))
        assertSame(third, cache.get("third"))
        assertTrue(cache.size() <= 10)
    }

    @Test fun accessRefreshesRecencyAndEvictionDoesNotRecycle() {
        val cache = ThumbnailCache(10)
        val first = bitmap(4)
        val second = bitmap(4)
        cache.put("first", first)
        cache.put("second", second)
        cache.get("first")
        cache.put("third", bitmap(4))
        assertSame(first, cache.get("first"))
        assertNull(cache.get("second"))
        assertFalse(second.isRecycled)
    }

    @Test fun explicitRemovalAndClearEvictEntries() {
        val cache = ThumbnailCache(20)
        cache.put("remove", bitmap(4))
        cache.put("clear", bitmap(4))
        assertTrue(cache.remove("remove") != null)
        cache.evictAll()
        assertEquals(0, cache.size())
        assertNull(cache.get("clear"))
    }
}
