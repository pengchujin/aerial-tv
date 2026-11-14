package app.orchard.tv.system

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class ClashShortcutTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val component = ComponentName("com.github.metacubex.clash.meta", "com.github.kr328.clash.ExternalControlActivity")

    private fun installClient(target: ComponentName = component) = shadowOf(context.packageManager).addActivityIfNotPresent(target).apply {
        exported = true
        enabled = true
        applicationInfo.enabled = true
        shadowOf(context.packageManager).addIntentFilterForActivity(
            target, IntentFilter("com.github.metacubex.clash.meta.action.TOGGLE_CLASH"),
        )
    }

    @Test
    fun absentClientDoesNotLaunchAnything() {
        assertEquals(ClashShortcut.Result.Unavailable, ClashShortcut.toggle(context))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test
    @Config(sdk = [30])
    fun alphaClientUsesRuntimePackageDespiteItsStaleStableManifestFilter() {
        val alpha = ComponentName("com.github.metacubex.clash.alpha", component.className)
        installClient(alpha)
        assertEquals(ClashShortcut.Result.Sent, ClashShortcut.toggle(context))
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(alpha, started.component)
        assertEquals("com.github.metacubex.clash.alpha.action.TOGGLE_CLASH", started.action)
    }

    @Test
    @Config(sdk = [34])
    fun modernAlphaWithStaleFilterIsNotOffered() {
        val alpha = ComponentName("com.github.metacubex.clash.alpha", component.className)
        installClient(alpha).applicationInfo.targetSdkVersion = 35
        assertEquals(ClashShortcut.Result.Unavailable, ClashShortcut.toggle(context))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test
    fun stableClientTakesPriorityWhenBothAreInstalled() {
        installClient(ComponentName("com.github.metacubex.clash.alpha", component.className))
        installClient()
        assertEquals(ClashShortcut.Result.Sent, ClashShortcut.toggle(context))
        assertEquals(component, shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity.component)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test
    fun sendsToggleToTheClientWithoutClaimingConnectionOrRequestingOurOwnVpn() {
        installClient()
        assertEquals(ClashShortcut.Result.Sent, ClashShortcut.toggle(context))
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull(started)
        assertEquals(component, started.component)
        assertEquals("com.github.metacubex.clash.meta.action.TOGGLE_CLASH", started.action)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test
    fun privateOrDisabledActivityIsNotLaunched() {
        for (state in listOf("private", "disabled", "disabledApp")) {
            installClient().apply {
                if (state == "private") exported = false
                if (state == "disabled") enabled = false
                if (state == "disabledApp") applicationInfo.enabled = false
            }
            assertEquals(ClashShortcut.Result.Unavailable, ClashShortcut.toggle(context))
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        }
    }

    @Test
    fun clientRemovedBetweenLookupAndLaunchIsHandled() {
        installClient()
        val caller = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        assertEquals(ClashShortcut.Result.Unavailable, ClashShortcut.toggle(caller))
    }

    @Test
    fun launchDeniedByFirmwareIsHandled() {
        installClient()
        val caller = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw SecurityException() }
        }
        assertEquals(ClashShortcut.Result.Failed, ClashShortcut.toggle(caller))
    }
}
