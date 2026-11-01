package app.orchard.tv.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** The seven modes of 设置 → 图像 → 亮度模式, in the TV's own order. */
    @Test fun everyModeTheTvOffersHasARow() {
        assertEquals(7, HisenseLaserPanel.MODE_COUNT)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), listOf(16384, 8192, 2, 4096, 131072, 65536, 0).map { HisenseLaserPanel.modeIndexOf(it) })
    }

    /** The custom mode is the last row, and the one the brightness row switches to before writing. */
    @Test fun customIsTheRowTheSliderLandsOn() {
        assertEquals(6, HisenseLaserPanel.CUSTOM)
        assertEquals(HisenseLaserPanel.CUSTOM, HisenseLaserPanel.modeIndexOf(0))
    }

    /** A value the row does not offer ticks nothing, rather than ticking the nearest row. */
    @Test fun anUnknownModeHasNoRow() {
        assertNull(HisenseLaserPanel.modeIndexOf(-1))
        assertNull(HisenseLaserPanel.modeIndexOf(12345))
    }

    /**
     * 超新 and 超频 are the two modes whose brightness row the TV disables, so they are the only two
     * that may refuse a step; 明亮, 标准, 柔和, 动态 and 自定义 all take one and hand the picture over
     * to 自定义.
     */
    @Test fun onlyTheTwoOverlapModesLockTheLevel() {
        assertEquals(listOf(4, 5), (0 until HisenseLaserPanel.MODE_COUNT).filter { HisenseLaserPanel.levelLocked(it) })
        assertFalse(HisenseLaserPanel.levelLocked(null))
        assertFalse(HisenseLaserPanel.levelLocked(HisenseLaserPanel.CUSTOM))
        assertFalse(HisenseLaserPanel.levelLocked(HisenseLaserPanel.DYNAMIC))
    }

    /**
     * 动态 is the third row, and the one mode whose level belongs to neither the owner nor a number
     * we could name: entering it from 柔和 (4) measured 8, from 标准 (8) it stayed 8, and from 明亮
     * (10) it read 9.
     */
    @Test fun dynamicIsTheThirdRow() {
        assertEquals(3, HisenseLaserPanel.DYNAMIC)
        assertEquals(HisenseLaserPanel.DYNAMIC, HisenseLaserPanel.modeIndexOf(4096))
    }
}
