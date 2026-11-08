package app.orchard.tv.system

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The laser set's level is 11 notches, not percent: a step either side moves one notch and stops
 * at the ends, so a held key cannot walk the panel past 0 or 10. Only the arithmetic is tested —
 * reading and writing it needs the TV's own picture service, which no JVM test has.
 */
class HisenseLaserPanelTest {
    private val delta = 0.0001f

    @Test fun oneStepIsOneNotch() {
        assertEquals(0.5f, HisenseLaserPanel.step(0.4f, 1), delta)
        assertEquals(0.3f, HisenseLaserPanel.step(0.4f, -1), delta)
    }

    @Test fun stepsStopAtBothEnds() {
        assertEquals(0f, HisenseLaserPanel.step(0f, -1), delta)
        assertEquals(1f, HisenseLaserPanel.step(1f, 1), delta)
    }

    /** A level read between a write and a poll lands anywhere, and the next step rounds it in. */
    @Test fun levelBetweenNotchesRoundsToTheNearest() {
        assertEquals(1f, HisenseLaserPanel.step(0.95f, 1), delta)
        assertEquals(0f, HisenseLaserPanel.step(0.05f, -1), delta)
        assertEquals(0.6f, HisenseLaserPanel.step(0.71f, -1), delta)
    }

    /** 11 notches over the row: the Control Center steps the drag pitch by this. */
    @Test fun theRowStepsElevenNotches() {
        assertEquals(10, HisenseLaserPanel.LEVEL_MAX)
        assertEquals(1f, HisenseLaserPanel.step(0.9f, 1), delta)
    }

}
