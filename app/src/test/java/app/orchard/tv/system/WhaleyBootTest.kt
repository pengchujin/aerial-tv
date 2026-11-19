package app.orchard.tv.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleyBootTest {
    @Test fun powerLightIsHandledOncePerBoot() {
        assertTrue(WhaleyBoot.newBoot("b1", null))
        assertTrue(WhaleyBoot.newBoot("b2", "b1"))
        assertFalse(WhaleyBoot.newBoot("b1", "b1"))
        assertFalse(WhaleyBoot.newBoot(null, null))
    }
}
