package app.orchard.tv.watchnext

import org.junit.Assert.*
import org.junit.Test

class WatchNextBackgroundTest {
    @Test fun smallLandscapeCanScaleUpLocallyButCannotCoverWallpaper() {
        val local = artworkSize(640, 360, 900, 600, 2f)!!
        assertTrue(local.width > 640 && local.height > 360)
        assertTrue(local.width <= 852 && local.height <= 552)
        assertEquals(16.0 / 9, local.width.toDouble() / local.height, 0.005)
        assertNull(backgroundSize(640, 360, 1920, 1080))
    }
    @Test fun highResolutionLandscapeUsesBoundedFullScreenCanvas() {
        assertEquals(ArtworkSize(1920, 1080), backgroundSize(3840, 2160, 3840, 2160))
        assertEquals(ArtworkSize(1280, 720), backgroundSize(1920, 1080, 1280, 720))
    }
    @Test fun portraitSquareAndVeryWideImagesRemainLocal() {
        assertNull(backgroundSize(2000, 3000, 1920, 1080))
        assertNull(backgroundSize(2000, 2000, 1920, 1080))
        assertNull(backgroundSize(6000, 2000, 1920, 1080))
    }
    @Test fun lowResolutionAndInvalidImagesCannotCoverWallpaper() {
        assertNull(backgroundSize(640, 360, 1920, 1080))
        assertNull(backgroundSize(0, 1080, 1920, 1080))
        assertNull(backgroundSize(1920, 0, 1920, 1080))
        assertNull(backgroundSize(1920, 1080, 0, 1080))
    }
}
