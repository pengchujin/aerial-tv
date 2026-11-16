package app.orchard.tv.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A volume step that leaves the level where it was went to a sound system (issue #1562). */
@RunWith(RobolectricTestRunner::class)
class ExternalVolumeTest {
    @Test fun aStepThatMovedNothingInTheMiddleIsStuck() {
        assertTrue(SystemProbe.stuckStep(before = 7, after = 7, max = 100, muted = false, direction = 1))
        assertTrue(SystemProbe.stuckStep(before = 7, after = 7, max = 100, muted = false, direction = -1))
    }

    @Test fun aStepThatMovedIsNot() {
        assertFalse(SystemProbe.stuckStep(before = 7, after = 8, max = 100, muted = false, direction = 1))
    }

    @Test fun theEndsAreNot() {
        assertFalse(SystemProbe.stuckStep(before = 100, after = 100, max = 100, muted = false, direction = 1))
        assertFalse(SystemProbe.stuckStep(before = 0, after = 0, max = 100, muted = false, direction = -1))
    }

    @Test fun mutedIsNot() {
        assertFalse(SystemProbe.stuckStep(before = 7, after = 7, max = 100, muted = true, direction = 1))
    }
}
