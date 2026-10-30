package app.orchard.tv.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DvdBounceTest {
    @Test fun `it moves straight on while there is room`() {
        val b = DvdBounce(x = 10f, y = 20f, vx = 100f, vy = 50f)
        b.step(dt = 0.5f, maxX = 1000f, maxY = 1000f)
        assertEquals(60f, b.x, 0.001f)
        assertEquals(45f, b.y, 0.001f)
        assertEquals(100f, b.vx, 0f)
        assertEquals(50f, b.vy, 0f)
    }

    @Test fun `at the right edge it turns back with the way it overshot`() {
        val b = DvdBounce(x = 90f, y = 0f, vx = 100f, vy = 0f)
        b.step(dt = 0.3f, maxX = 100f, maxY = 100f) // wants 120, is 20 past the edge
        assertEquals(80f, b.x, 0.001f)
        assertEquals(-100f, b.vx, 0f)
    }

    @Test fun `at the left and top edges it turns back too`() {
        val b = DvdBounce(x = 10f, y = 5f, vx = -100f, vy = -50f)
        b.step(dt = 0.3f, maxX = 100f, maxY = 100f) // wants -20 and -10
        assertEquals(20f, b.x, 0.001f)
        assertEquals(10f, b.y, 0.001f)
        assertEquals(100f, b.vx, 0f)
        assertEquals(50f, b.vy, 0f)
    }

    @Test fun `a long pause crosses the box more than once and stays inside it`() {
        val b = DvdBounce(x = 0f, y = 0f, vx = 100f, vy = 70f)
        b.step(dt = 5f, maxX = 120f, maxY = 80f)
        assertTrue(b.x in 0f..120f)
        assertTrue(b.y in 0f..80f)
        // 500 along a 120 box: 4 whole lengths and 20 more, an even number of turns, so heading on
        assertEquals(20f, b.x, 0.001f)
        assertEquals(100f, b.vx, 0f)
    }

    @Test fun `a box with no room keeps it at the corner`() {
        val b = DvdBounce(x = 5f, y = 5f, vx = 100f, vy = 100f)
        b.step(dt = 1f, maxX = 0f, maxY = -3f)
        assertEquals(0f, b.x, 0f)
        assertEquals(0f, b.y, 0f)
    }

    @Test fun `the hue goes round the wheel and back to where it began`() {
        assertEquals(0f, DvdBounce.hue(0f, secondsPerTurn = 12f), 0.001f)
        assertEquals(180f, DvdBounce.hue(6f, secondsPerTurn = 12f), 0.001f)
        assertEquals(0f, DvdBounce.hue(12f, secondsPerTurn = 12f), 0.001f)
        assertEquals(90f, DvdBounce.hue(15f, secondsPerTurn = 12f), 0.001f)
    }
}
