package app.orchard.tv.guard

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class CecHomeDirectBootTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager

    // Enforce the storage restriction from the TV's crash even if Robolectric allows the read.
    private val credentialContext = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            check(userManager.isUserUnlocked) { "Credential storage is locked" }
            return super.getSharedPreferences(name, mode)
        }
    }

    @Test
    fun disabledFeatureDoesNotReadCredentialStorageBeforeUnlock() {
        shadowOf(userManager).setUserUnlocked(false)
        assertNull(CecHome.inputSettingKey(credentialContext))
        assertFalse(CecHome.enabled(credentialContext))
    }

    @Test
    fun configuredFeatureResumesWithItsSavedInputAfterUnlock() {
        context.getSharedPreferences("system", Context.MODE_PRIVATE).edit()
            .putString("cec_home_input", "*")
            .putString("cec_home_input_setting", "vendor_current_input")
            .commit()
        shadowOf(userManager).setUserUnlocked(false)
        assertNull(CecHome.inputSettingKey(credentialContext))
        assertFalse(CecHome.enabled(credentialContext))

        shadowOf(userManager).setUserUnlocked(true)
        assertEquals("vendor_current_input", CecHome.inputSettingKey(credentialContext))
        assertEquals("*", context.getSharedPreferences("system", Context.MODE_PRIVATE)
            .getString("cec_home_input", null))
    }

    @Test
    fun guardBindsWhileLockedAndRegistersInputObserversAfterUnlock() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
        context.getSharedPreferences("system", Context.MODE_PRIVATE).edit()
            .putString("cec_home_input", "*")
            .putString("cec_home_input_setting", "vendor_current_input")
            .commit()
        shadowOf(userManager).setUserUnlocked(false)
        val controller = Robolectric.buildService(HomeGuardService::class.java)
        val service = controller.get()
        ReflectionHelpers.setField(service, "mBase", credentialContext)
        val resolver = shadowOf(context.contentResolver)
        val uris = listOf(
            Settings.System.getUriFor("vendor_current_input"),
            Settings.Global.getUriFor("vendor_current_input"),
        )
        try {
            controller.create()
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            uris.forEach { assertTrue(resolver.getContentObservers(it).isEmpty()) }

            shadowOf(userManager).setUserUnlocked(true)
            context.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
            shadowOf(Looper.getMainLooper()).idle()
            uris.forEach { assertEquals(1, resolver.getContentObservers(it).size) }
        } finally {
            controller.destroy()
        }
        uris.forEach { assertTrue(resolver.getContentObservers(it).isEmpty()) }
    }

    @Test
    fun unlockedFeatureKeepsItsDefaultInputSetting() {
        shadowOf(userManager).setUserUnlocked(true)
        assertNull(CecHome.inputSettingKey(credentialContext))
        context.getSharedPreferences("system", Context.MODE_PRIVATE).edit()
            .putString("cec_home_input", "*").commit()
        assertEquals("tv_current_inputid", CecHome.inputSettingKey(credentialContext))
    }

    @Test
    @Config(sdk = [23])
    fun preDirectBootDevicesCanStillReadSettings() {
        context.getSharedPreferences("system", Context.MODE_PRIVATE).edit()
            .putString("cec_home_input", "*").commit()
        assertEquals("tv_current_inputid", CecHome.inputSettingKey(context))
    }
}
