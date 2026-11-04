package app.orchard.tv.ui.home

import org.junit.Assert.*
import org.junit.Test

class PlateStrengthTest {
    @Test fun tintDefaultsAndHalfStrengthMatchTheOriginalMatrix() {
        assertEquals(100, plateStrength(-1))
        assertEquals(75, plateStrength(75))
        assertArrayEquals(floatArrayOf(
            .8f, 0f, 0f, 0f, 30f, 0f, .8f, 0f, 0f, 30f,
            0f, 0f, .8f, 0f, 30f, 0f, 0f, 0f, 1f, 0f
        ), plateTintMatrix(100), .0001f)
        assertEquals(.9f, plateTintMatrix(50)[0], .0001f)
        assertEquals(15f, plateTintMatrix(50)[4], .0001f)
    }

    @Test fun framelessVeilKeepsItsColorAndScalesOpacity() {
        val white = 0x26ffffff
        val black = 0x48000000
        assertEquals(white, plateVeilColor(white, 100))
        assertEquals(0x1dffffff, plateVeilColor(white, 75))
        assertEquals(0x13ffffff, plateVeilColor(white, 50))
        assertEquals(black, plateVeilColor(black, 100))
        assertEquals(0x36000000, plateVeilColor(black, 75))
        assertEquals(0x24000000, plateVeilColor(black, 50))
        assertEquals(white, plateVeilColor(white, -1))
    }
}
