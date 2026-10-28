package app.orchard.tv.ui.wallpaper

import app.orchard.tv.wallpaper.ScreensaverMode
import app.orchard.tv.wallpaper.Wallpapers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AerialContextTest {
    @Test fun `the aerial screensaver uses the films`() {
        assertTrue(usesAerialFilms(ScreensaverMode.Aerial, "photo:1"))
    }

    @Test fun `the aerial wallpaper uses the films whatever the screensaver is`() {
        assertTrue(usesAerialFilms(ScreensaverMode.Clock, Wallpapers.LIVE))
    }

    @Test fun `neither one chosen, nothing uses the films`() {
        assertFalse(usesAerialFilms(ScreensaverMode.Photos, "photo:1"))
        assertFalse(usesAerialFilms(ScreensaverMode.System, Wallpapers.AUTO))
    }
}
