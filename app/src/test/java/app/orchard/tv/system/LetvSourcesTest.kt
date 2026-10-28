package app.orchard.tv.system

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21])
class LetvSourcesTest {
    @Test fun preservesNativePortNamesWithoutInventingPorts() {
        val sources = LetvSources.fromPorts(listOf("HDMI 1", "HDMI 3", "AV", "Analog TV", "Digital TV", "HDMI 1"))
        assertEquals(listOf("HDMI 1", "HDMI 3", "AV", "Analog TV", "Digital TV"), sources.map { it.label })
        assertEquals(listOf(SourceKind.Hdmi, SourceKind.Hdmi, SourceKind.Av, SourceKind.Tuner, SourceKind.Tuner), sources.map { it.kind })
        sources.forEach {
            assertEquals(it.label, it.intent.getStringExtra("port"))
            assertEquals("com.stv.signalsourcemanager", it.intent.`package`)
            assertFalse(it.connected)
        }
    }

    @Test fun excludesVirtualAndUnknownPorts() {
        assertTrue(LetvSources.fromPorts(listOf("", "Super TV", "HDMI", "HDMI 0", "Storage", "unknown")).isEmpty())
        assertTrue(LetvSources.fromPorts(emptyList()).isEmpty())
    }
}
