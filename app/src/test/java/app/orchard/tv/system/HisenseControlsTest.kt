package app.orchard.tv.system

import android.content.Context
import android.provider.Settings
import com.jamdeo.tv.common.ConfigOptionManager as Native
import com.jamdeo.tv.common.EnumConstants
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HisenseControlsTest {
    @Test fun nativeControlsAreOptInBoundedAndDoNotWriteAndroidMirrors() {
        val context: Context = RuntimeEnvironment.getApplication()
        val volume = EnumConstants.AudioOptions.TIL_VOLUME
        val backlight = EnumConstants.PictureOptions.TIL_BACK_LIGHT_CUSTOM
        Native.values.clear()
        Native.values[volume] = 15
        Native.values[backlight] = 30
        Native.values[EnumConstants.PictureOptions.TIL_BACK_LIGHT_MODE] = 1
        Native.muted = false
        Native.acceptWrites = true
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255)
        assertTrue(DeviceProfile.setOwn(context, null))
        assertNull(HisenseControls.volume(context))
        assertNull(HisenseControls.backlight(context))

        assertTrue(DeviceProfile.setOwn(context, """{"hisenseNativeVolume":true,"hisenseBacklightMax":30}"""))
        assertEquals(15, SystemProbe.volume(context).level)
        assertEquals(1f, Brightness.level(context)!!, 0.001f)
        SystemProbe.adjustVolume(context, -1)
        assertEquals(14, SystemProbe.volume(context).level)
        SystemProbe.toggleMute(context)
        assertTrue(SystemProbe.volume(context).muted)
        SystemProbe.toggleMute(context)
        assertFalse(SystemProbe.volume(context).muted)
        assertEquals(29f / 30f, Brightness.adjust(context, -1)!!, 0.001f)
        assertEquals(0, Native.values[EnumConstants.PictureOptions.TIL_BACK_LIGHT_MODE])
        assertEquals(255, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))

        repeat(110) { SystemProbe.adjustVolume(context, -1); Brightness.adjust(context, -1) }
        assertEquals(0, Native.values[volume])
        assertEquals(1, Native.values[backlight])
        repeat(110) { SystemProbe.adjustVolume(context, 1); Brightness.adjust(context, 1) }
        assertEquals(100, Native.values[volume])
        assertEquals(30, Native.values[backlight])
        Native.acceptWrites = false
        SystemProbe.adjustVolume(context, -1)
        assertEquals(100, SystemProbe.volume(context).level)
        assertEquals(1f, Brightness.adjust(context, -1)!!, 0.001f)
        assertFalse(DeviceProfile.setOwn(context, """{"hisenseBacklightMax":101}"""))
        assertEquals(30, DeviceProfile.current(context).hisenseBacklightMax)
        assertEquals(255, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
    }
}
