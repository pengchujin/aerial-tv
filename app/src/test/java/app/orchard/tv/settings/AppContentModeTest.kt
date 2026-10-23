package app.orchard.tv.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppContentModeTest {
    @Test fun `a stored mode is used`() {
        assertEquals(AppContentMode.Always, AppContentMode.of("always", null))
        assertEquals(AppContentMode.Off, AppContentMode.of("off", true))
        assertEquals(AppContentMode.OnUp, AppContentMode.of("up", false))
    }

    @Test fun `the on and off setting this replaced is kept`() {
        assertEquals(AppContentMode.Off, AppContentMode.of(null, false))
        assertEquals(AppContentMode.OnUp, AppContentMode.of(null, true))
    }

    @Test fun `nothing stored is on Up`() {
        assertEquals(AppContentMode.OnUp, AppContentMode.of(null, null))
    }

    @Test fun `an unknown mode falls back to the old setting`() {
        assertEquals(AppContentMode.Off, AppContentMode.of("junk", false))
        assertEquals(AppContentMode.OnUp, AppContentMode.of("junk", null))
    }
}
