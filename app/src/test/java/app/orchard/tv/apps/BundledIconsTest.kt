package app.orchard.tv.apps

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.graphics.Bitmap
import android.graphics.Color

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BundledIconsTest {
    @Test fun everyMappingDecodesWithinTheTileMemoryBudget() {
        val context = RuntimeEnvironment.getApplication()
        for (pkg in BundledIcons.assets.keys) {
            val bitmap = requireNotNull(BundledIcons.load(context, pkg)) { pkg }.bitmap
            assertTrue(pkg, bitmap.width >= TileArtFactory.W)
            assertTrue(pkg, bitmap.width <= TileArtFactory.W * 2)
            assertTrue(pkg, bitmap.height >= TileArtFactory.H)
            bitmap.recycle()
        }
        assertNull(BundledIcons.load(context, "example.unmapped"))
    }

    @Test fun separateAppsHaveSeparateArtwork() {
        assertEquals(BundledIcons.assets.size, BundledIcons.assets.values.toSet().size)
        assertNotEquals(BundledIcons.assets["com.mitv.gallery"], BundledIcons.assets["com.xiaomi.tv.gallery"])
    }

    @Test fun reductionAveragesFineDetailInsteadOfDroppingAlternatePixels() {
        val source = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        for (y in 0 until 16) for (x in 0 until 16) {
            source.setPixel(x, y, if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE)
        }
        val reduced = BundledIcons.filteredReduction(source, 4)
        assertTrue(source.isRecycled)
        for (y in 0 until 4) for (x in 0 until 4) {
            assertTrue(Color.red(reduced.getPixel(x, y)) in 120..136)
        }
        reduced.recycle()
    }
}
