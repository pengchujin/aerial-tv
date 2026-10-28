package app.orchard.tv.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalizeClockTest {
    @Test fun `the small clock is always there without the large one`() {
        assertEquals(1f, smallClockShown(largeClock = false, personalize = 0f), 0f)
        assertEquals(1f, smallClockShown(largeClock = false, personalize = 1f), 0f)
    }

    @Test fun `with the large clock the small one comes in over the second half of the rise`() {
        assertEquals(0f, smallClockShown(true, 0f), 0f)
        assertEquals(0f, smallClockShown(true, 0.35f), 0f)
        assertEquals(0.5f, smallClockShown(true, 0.675f), 0.001f)
        assertEquals(1f, smallClockShown(true, 1f), 0f)
    }

    @Test fun `the large clock goes in the first half of the rise`() {
        assertEquals(1f, largeClockLeft(0f), 0f)
        assertEquals(0.5f, largeClockLeft(0.25f), 0f)
        assertEquals(0f, largeClockLeft(0.5f), 0f)
        assertEquals(0f, largeClockLeft(1f), 0f)
    }
}
