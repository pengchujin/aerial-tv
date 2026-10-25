package app.orchard.tv.system

import android.content.ComponentName
import android.media.tv.TvInputInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class XiaomiNsop0SourcesTest {
    @Test
    fun nsop0UsesPhysicalPortLabelsAndMatchingPlayerTargets() {
        for (model in listOf("MiTV-NSOP0", "MiTV_NSOP0")) {
            ReflectionHelpers.setStaticField(Build::class.java, "MODEL", model)
            val profile = DeviceProfile.current()
            // Measured on firmware 22.7.26.1365: player input -> active TvInputManager HW id.
            for ((input, hardware) in listOf(23 to 6, 24 to 5, 25 to 4)) {
                val label = "HDMI ${input - 22}"
                assertEquals(label, profile.labels[hardware])
                val intent = SystemProbe.xiaomiRoute(info(hardware), SourceKind.Hdmi, label, profile)!!
                assertEquals("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY", intent.action)
                assertEquals(ComponentName("com.xiaomi.mitv.tvplayer",
                    "com.xiaomi.mitv.tvplayer.ExternalSourceActivity"), intent.component)
                assertEquals(input, intent.getIntExtra("input", -1))
            }
            assertTrue(profile.hiddenHardware.isEmpty())
        }
    }

    @Test
    fun unknownModelKeepsDefaultMapping() {
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "Other TV")
        val profile = DeviceProfile.current()
        assertTrue(profile.labels.isEmpty())
        val intent = SystemProbe.xiaomiRoute(info(6), SourceKind.Hdmi, "HDMI 3", profile)!!
        assertEquals(24, intent.getIntExtra("input", -1))
    }

    private fun info(hardware: Int): TvInputInfo =
        ReflectionHelpers.callConstructor(TvInputInfo::class.java).also {
            ReflectionHelpers.setField(it, "mId", "com.novatek.hdmi${hardware - 3}tvinput/.hdmi${hardware - 3}TvInputService/HW$hardware")
        }
}
