package app.orchard.tv.settings

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomePlateSettingTest {
    @Test fun tintIsSavedRestoredAndInvalidValuesAreIgnored() {
        val context: Context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val started = AerialSettings::class.java.getDeclaredField("started").apply { isAccessible = true }
        started.setBoolean(null, false)
        AerialSettings.init(context)
        assertEquals(100, AerialSettings.homePlateStrength)
        AerialSettings.chooseHomePlateStrength(50)
        assertEquals(50, prefs.getInt("home_plate_strength", -1))
        AerialSettings.chooseHomePlateStrength(12)
        assertEquals(50, AerialSettings.homePlateStrength)
        started.setBoolean(null, false)
        AerialSettings.init(context)
        assertEquals(50, AerialSettings.homePlateStrength)
        prefs.edit().putInt("home_plate_strength", 12).commit()
        started.setBoolean(null, false)
        AerialSettings.init(context)
        assertEquals(100, AerialSettings.homePlateStrength)
    }
}
