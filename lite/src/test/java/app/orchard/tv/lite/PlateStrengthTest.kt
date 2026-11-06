package app.orchard.tv.lite

import org.junit.Assert.*
import org.junit.Test

class PlateStrengthTest {
    @Test fun tintRetainsTheDefaultAndAcceptsAWeakerSetting() {
        assertEquals(100, plateStrength(-1))
        assertEquals(30f, plateTintMatrix(100)[4], .0001f)
        assertEquals(.9f, plateTintMatrix(50)[0], .0001f)
        assertEquals(15f, plateTintMatrix(50)[4], .0001f)
    }

    @Test fun sharedVeilScalingPreservesTheDefault() {
        assertEquals(0x26ffffff, plateVeilColor(0x26ffffff, 100))
        assertEquals(0x13ffffff, plateVeilColor(0x26ffffff, 50))
    }
}
