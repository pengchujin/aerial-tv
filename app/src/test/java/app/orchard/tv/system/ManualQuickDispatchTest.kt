package app.orchard.tv.system

import android.content.ComponentName
import android.content.Intent
import app.orchard.tv.settings.ManualQuickTarget
import app.orchard.tv.system.QuickActionRunner.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ManualQuickDispatchTest {
    private val context = RuntimeEnvironment.getApplication()
    private val packageManager = shadowOf(context.packageManager)
    private val app = shadowOf(context)
    private val component = ComponentName("org.example.player", "org.example.player.Control")

    @Test fun sendsExplicitActivityWithTypedExtras() {
        packageManager.addActivityIfNotPresent(component).apply {
            exported = true
            enabled = true
            applicationInfo.enabled = true
        }
        assertEquals(Result.Sent, ManualQuickDispatch.send(context, target("activity")))
        val intent = app.nextStartedActivity
        assertEquals(component, intent.component)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
        assertEquals("org.example.player.RUN", intent.action)
        assertEquals("player://control", intent.dataString)
        assertEquals(7, intent.getIntExtra("count", 0))
        assertEquals(true, intent.getBooleanExtra("enabled", false))
    }

    @Test fun sendsExplicitBroadcastWithoutLaunchingAnActivity() {
        packageManager.addReceiverIfNotPresent(component).apply {
            exported = true
            enabled = true
            applicationInfo.enabled = true
        }
        assertEquals(Result.Sent, ManualQuickDispatch.send(context, target("broadcast")))
        val intent = app.broadcastIntents.last()
        assertEquals(component, intent.component)
        assertEquals("org.example.player.RUN", intent.action)
        assertNull(app.nextStartedActivity)
    }

    @Test fun rejectsMissingPrivateAndProtectedComponents() {
        val target = target("activity")
        assertEquals(Result.Unavailable, ManualQuickDispatch.send(context, target))
        val info = packageManager.addActivityIfNotPresent(component).apply {
            exported = false
            enabled = true
            applicationInfo.enabled = true
        }
        assertEquals(Result.Unavailable, ManualQuickDispatch.send(context, target))
        info.exported = true
        info.permission = "org.example.player.PRIVATE"
        assertEquals(Result.Unavailable, ManualQuickDispatch.send(context, target))
        assertNull(app.nextStartedActivity)
    }

    private fun target(type: String) = requireNotNull(ManualQuickTarget.create(type, component.packageName,
        component.className, "org.example.player.RUN", "player://control", """{"count":7,"enabled":true}"""))
}
