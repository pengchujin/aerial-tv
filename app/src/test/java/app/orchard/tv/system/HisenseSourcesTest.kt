package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HisenseSourcesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun absentOrPrivateNativeServicesDoNotInventPorts() {
        assertTrue(HisenseSources.load(context).isEmpty())
        val pm = shadowOf(context.packageManager)
        pm.addServiceIfNotPresent(ComponentName("com.jamdeo.tv.service", "com.jamdeo.tv.service.SourceService"))
            .exported = false
        pm.addServiceIfNotPresent(ComponentName("com.hisense.tv.tiletransition", "com.hisense.tv.tiletransition.TileTransitionService"))
            .exported = true
        assertTrue(HisenseSources.load(context).isEmpty())
    }

    @Test
    fun controlCenterAndBootRouteSendTheNativeBroadcast() {
        val caller = RecordingContext(context)
        SystemProbe.startSettingsIntent(caller, hdmi())
        assertEquals(1, caller.broadcasts.size)
        assertEquals(1283, caller.broadcasts.single().getIntExtra("silo_input_src_id", 0))
        assertEquals("com.hisense.tv.tiletransition", caller.broadcasts.single().`package`)
        assertTrue(caller.activities.isEmpty())
    }

    @Test
    fun tileRouteDoesNotConsumeOtherIntentsOrMissingPort() {
        val caller = RecordingContext(context)
        assertFalse(HisenseSources.open(caller, Intent(Intent.ACTION_MAIN)))
        assertFalse(HisenseSources.open(caller, hdmi().setPackage("another.package")))
        assertFalse(HisenseSources.open(caller, hdmi().apply { removeExtra("silo_input_src_id") }))
        assertTrue(caller.broadcasts.isEmpty())
        assertTrue(HisenseSources.open(caller, hdmi()))
        assertEquals(1, caller.broadcasts.size)
    }

    private fun hdmi() = Intent("com.jamdeo.tv.policy.SILO_SWITCH_EVENT")
        .setPackage("com.hisense.tv.tiletransition")
        .putExtra("silo_input_src_id", 1283)

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val broadcasts = mutableListOf<Intent>()
        val activities = mutableListOf<Intent>()
        override fun sendBroadcast(intent: Intent) { broadcasts += Intent(intent) }
        override fun startActivity(intent: Intent) { activities += Intent(intent) }
    }
}
