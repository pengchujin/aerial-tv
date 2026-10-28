package app.orchard.tv.guard

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import app.orchard.tv.system.DiagLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class RevivalListenerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val user = context.getSystemService(Context.USER_SERVICE) as UserManager
    private val boot = ComponentName(context, BootReceiver::class.java)
    private val guard = ComponentName(context, HomeGuardService::class.java).flattenToString()
    private val peer = "example.other/example.other.AccessibilityService"
    private val enabled get() = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    )

    @Before
    fun enabledButUnboundGuard() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        shadowOf(user).setUserUnlocked(true)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "$peer:$guard")
        context.packageManager.setComponentEnabledSetting(
            boot, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
        )
    }

    private fun advance(milliseconds: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))

    @Test
    fun creationRepairsGuardEvenWithoutListenerConnectedCallback() {
        val controller = Robolectric.buildService(RevivalListener::class.java).create()
        try {
            advance(5_000)
            assertEquals(peer, enabled)
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, context.packageManager.getComponentEnabledSetting(boot))
            advance(1_500)
            assertEquals("$peer:$guard", enabled)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun missingUnlockBroadcastDoesNotLeaveGuardStalled() {
        shadowOf(user).setUserUnlocked(false)
        val controller = Robolectric.buildService(RevivalListener::class.java).create()
        try {
            advance(5_000)
            assertEquals("$peer:$guard", enabled)
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, context.packageManager.getComponentEnabledSetting(boot))
            shadowOf(user).setUserUnlocked(true) // Intentionally do not send USER_UNLOCKED.
            advance(5_000)
            assertEquals(peer, enabled)
            advance(1_500)
            assertEquals("$peer:$guard", enabled)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun retriesWhenAndroidDoesNotBindAfterFirstRepair() {
        val controller = Robolectric.buildService(RevivalListener::class.java).create()
        try {
            advance(6_500)
            assertEquals("$peer:$guard", enabled)
            advance(3_500)
            assertEquals(peer, enabled)
            advance(1_500)
            assertEquals("$peer:$guard", enabled)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun retainsAnotherServiceAddedDuringDelayedRebind() {
        val controller = Robolectric.buildService(RevivalListener::class.java).create()
        try {
            advance(5_000)
            val added = "example.new/example.new.AccessibilityService"
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "$peer:$added")
            advance(1_500)
            assertEquals("$peer:$added:$guard", enabled)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun destroyCancelsRecoveryScheduledByConnectedCallback() {
        val controller = Robolectric.buildService(RevivalListener::class.java).create()
        controller.get().onListenerConnected()
        controller.destroy()
        advance(5_000)
        assertEquals("$peer:$guard", enabled)
    }

    @Test
    fun lockedServiceDoesNotReadCredentialPreferences() {
        shadowOf(user).setUserUnlocked(false)
        val credentialContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(user.isUserUnlocked) { "Credential storage is locked" }
                return super.getSharedPreferences(name, mode)
            }
        }
        val controller = Robolectric.buildService(RevivalListener::class.java)
        ReflectionHelpers.setField(controller.get(), "mBase", credentialContext)
        try {
            controller.create()
            advance(5_000)
            assertEquals("$peer:$guard", enabled)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun unlockBroadcastCanRestartRecoveryAfterRetryWindowExpires() {
        shadowOf(user).setUserUnlocked(false)
        val controller = Robolectric.buildService(RevivalListener::class.java).create()
        try {
            advance(150_000)
            assertEquals("$peer:$guard", enabled)
            shadowOf(user).setUserUnlocked(true)
            context.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
            advance(5_000)
            assertEquals(peer, enabled)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun startingGuardSetupRestoresOwnBootReceiver() {
        GuardSetup.ensureEnabled(context)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, context.packageManager.getComponentEnabledSetting(boot))
    }

    @Test
    fun missingAdbGrantDoesNotRestoreReceiverOrAlterServiceList() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        val controller = Robolectric.buildService(RevivalListener::class.java).create()
        try {
            advance(20_000)
            assertEquals("$peer:$guard", enabled)
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, context.packageManager.getComponentEnabledSetting(boot))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun lockedServiceStopsCheckingAfterBoundedWindow() {
        shadowOf(user).setUserUnlocked(false)
        fun attempts() = DiagLog.recent(600).count { it.contains("revival: attempt ") }
        val before = attempts()
        val controller = Robolectric.buildService(RevivalListener::class.java).create()
        try {
            advance(150_000)
            val afterWindow = attempts()
            assertTrue(afterWindow - before in 1..10)
            advance(3_600_000)
            assertEquals(afterWindow, attempts())
            assertEquals("$peer:$guard", enabled)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun lateConnectedCallbackAfterDestroyDoesNotRestartRecovery() {
        val controller = Robolectric.buildService(RevivalListener::class.java).create()
        controller.destroy()
        controller.get().onListenerConnected()
        advance(5_000)
        assertEquals("$peer:$guard", enabled)
    }

    @Test
    @Config(sdk = [23])
    fun preDirectBootDeviceStillRepairsAnUnboundGuard() {
        val controller = Robolectric.buildService(RevivalListener::class.java).create()
        try {
            advance(5_000)
            assertEquals(peer, enabled)
            advance(1_500)
            assertEquals("$peer:$guard", enabled)
        } finally {
            controller.destroy()
        }
    }
}
