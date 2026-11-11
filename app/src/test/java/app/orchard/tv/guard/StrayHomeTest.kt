package app.orchard.tv.guard

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrayHomeTest {
    @Test fun remoteHomeMappedToMoveHomeIsHome() {
        // MiTV4A, Xiaomi Bluetooth remote 2717:32b8 through Generic.kl (issue #1396).
        assertTrue(HomeGuardService.strayHome(KeyEvent.KEYCODE_MOVE_HOME, 102, lettered = false))
    }

    @Test fun keyboardMoveHomeStaysMoveHome() {
        assertFalse(HomeGuardService.strayHome(KeyEvent.KEYCODE_MOVE_HOME, 102, lettered = true))
        assertFalse(HomeGuardService.strayHome(KeyEvent.KEYCODE_MOVE_HOME, 0, lettered = false))
    }

    @Test fun unmappedHomeScanCodesAreHome() {
        // Haier HaierTV-RC sends KEY_HOMEPAGE (172), left out of its layout (issue #1388).
        assertTrue(HomeGuardService.strayHome(KeyEvent.KEYCODE_UNKNOWN, 172, lettered = false))
        assertTrue(HomeGuardService.strayHome(KeyEvent.KEYCODE_UNKNOWN, 102, lettered = true))
    }

    @Test fun otherKeysAreNot() {
        assertFalse(HomeGuardService.strayHome(KeyEvent.KEYCODE_UNKNOWN, 30, lettered = false))
        assertFalse(HomeGuardService.strayHome(KeyEvent.KEYCODE_DPAD_CENTER, 102, lettered = false))
    }
}
