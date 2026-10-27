package app.orchard.tv.settings

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ScreensaverImmersiveTest {
    @After fun resetSettings() {
        AerialSettings.chooseScreensaverClock(true)
        AerialSettings.chooseScreensaverImmersive(false)
        RuntimeEnvironment.getApplication().getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        ReflectionHelpers.setStaticField(AerialSettings::class.java, "started", false)
    }

    @Test fun immersiveKeepsTheOwnersClockChoiceAndTimer() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("screensaver_clock", true).putInt("screensaver_minutes", 20).commit()
        ReflectionHelpers.setStaticField(AerialSettings::class.java, "started", false)
        AerialSettings.init(context)
        assertTrue(AerialSettings.screensaverShowsClock)
        AerialSettings.chooseScreensaverImmersive(true)
        assertFalse(AerialSettings.screensaverShowsClock)
        assertTrue(prefs.getBoolean("screensaver_immersive", false))
        assertTrue(prefs.getBoolean("screensaver_clock", false))
        assertEquals(20, AerialSettings.screensaverMinutes)

        // Recreating the settings reads the new choice without changing the previous clock choice.
        ReflectionHelpers.setStaticField(AerialSettings::class.java, "started", false)
        AerialSettings.init(context)
        assertTrue(AerialSettings.screensaverImmersive)
        assertFalse(AerialSettings.screensaverShowsClock)
        AerialSettings.chooseScreensaverImmersive(false)
        assertTrue(AerialSettings.screensaverShowsClock)
        AerialSettings.chooseScreensaverClock(false)
        AerialSettings.chooseScreensaverImmersive(true)
        AerialSettings.chooseScreensaverImmersive(false)
        assertFalse(AerialSettings.screensaverShowsClock)
        assertFalse(prefs.getBoolean("screensaver_clock", true))
        assertEquals(20, prefs.getInt("screensaver_minutes", 0))
    }
}
