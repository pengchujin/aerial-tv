package app.orchard.tv.watchnext

import android.content.ComponentName
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WatchNextLaunchTest {
    private val app = RuntimeEnvironment.getApplication()
    private fun program(intent: Intent) = WatchNextProgram(ProgramKey("source", 1), intentUri = intent.toUri(Intent.URI_INTENT_SCHEME))
    @Test fun exportedSourceActivityKeepsDeepLinkButNotUriGrants() {
        val component = ComponentName("source", "source.Player")
        shadowOf(app.packageManager).addActivityIfNotPresent(component).apply { exported = true; enabled = true }
        val intent = Intent(Intent.ACTION_VIEW).setComponent(component).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val result = watchNextIntent(app, program(intent))
        assertNotNull(result)
        assertEquals("source", result!!.`package`)
        assertEquals(0, result.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    @Test fun foreignComponentCannotBypassHostSourceRestrictions() {
        assertNull(watchNextIntent(app, program(Intent().setComponent(ComponentName("other", "other.Player")))))
    }
    @Test fun missingOrPrivateDeepLinkFallsBackToOrdinaryLaunch() {
        assertNull(watchNextIntent(app, WatchNextProgram(ProgramKey("source", 1))))
        val component = ComponentName("source", "source.Private")
        shadowOf(app.packageManager).addActivityIfNotPresent(component).apply { exported = false; enabled = true }
        assertNull(watchNextIntent(app, program(Intent().setComponent(component))))
    }
}
