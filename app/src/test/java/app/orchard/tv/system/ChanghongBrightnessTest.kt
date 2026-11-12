package app.orchard.tv.system

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.provider.Settings
import com.changhong.tvos.common.TVManager
import com.xgimi.gmpf.api.DisplayManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChanghongBrightnessTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun prepare() {
        ShadowBuild.setModel("长虹智能电视")
        ShadowBuild.setProduct("mt5520_ll")
        DeviceProfile.setOwn(context, null)
        DisplayManager.getInstance().unavailable = true
        TVManager.instance = TVManager()
        ChanghongPicture.javaClass.getDeclaredField("failed").apply { isAccessible = true }.set(null, false)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 76)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
    }

    @After
    fun release() {
        DisplayManager.getInstance().unavailable = false
    }

    @Test
    fun readsAndAdjustsPanelRatherThanAndroidBrightness() {
        assertEquals(0.5f, Brightness.level(context)!!, 0.001f)
        assertEquals(0.55f, Brightness.adjust(context, 1)!!, 0.001f)
        assertEquals(55, TVManager.instance.picture.level)
        assertEquals(0.5f, Brightness.adjust(context, -1)!!, 0.001f)
        assertAndroidBrightnessUnchanged()
    }

    @Test
    fun panelApiIsIndependentOfServicesModelProductAndOwnProfile() {
        assertNull(ChanghongSettings.intent(context))
        for ((model, product) in listOf("Other TV" to "mt5520_ll", "长虹智能电视" to "other_product", "Other TV" to "other_product")) {
            ShadowBuild.setModel(model)
            ShadowBuild.setProduct(product)
            for (profile in listOf(null, "{}", """{"hidden":[8]}""")) {
                DeviceProfile.setOwn(context, profile)
                assertEquals(0.5f, Brightness.level(context)!!, 0.001f)
                assertEquals(0.55f, Brightness.adjust(context, 1)!!, 0.001f)
                assertEquals(0.5f, Brightness.adjust(context, -1)!!, 0.001f)
            }
        }
        assertAndroidBrightnessUnchanged()
    }

    @Test
    fun missingTvosClassKeepsAndroidBrightnessEvenWithBothServices() {
        val packages = shadowOf(context.packageManager)
        packages.addServiceIfNotPresent(ChanghongSettings.component).exported = true
        packages.addServiceIfNotPresent(ComponentName("com.changhong.SourceMenu", "com.changhong.source.SourceMenuService")).exported = true
        allowWriteSettings()
        val caller = withoutTvos()
        assertFalse(ChanghongPicture.available(caller))
        assertNull(ChanghongPicture.backlight(caller))
        assertEquals(76 / 255f, Brightness.level(caller)!!, 0.001f)
        assertEquals(89 / 255f, Brightness.adjust(caller, 1)!!, 0.001f)
        assertEquals(89, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
        assertEquals(76 / 255f, Brightness.adjust(caller, -1)!!, 0.001f)
        assertEquals(50, TVManager.instance.picture.level)
    }

    @Test
    fun failedFlagNeverReachesTvsWithoutTheTvosClass() {
        allowWriteSettings()
        ChanghongPicture.javaClass.getDeclaredField("failed").apply { isAccessible = true }.set(null, true)
        val caller = withoutTvos()
        assertEquals(76 / 255f, Brightness.level(caller)!!, 0.001f)
        assertEquals(89 / 255f, Brightness.adjust(caller, 1)!!, 0.001f)
        assertEquals(89, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
    }

    @Test
    fun clampsToSafePanelRange() {
        TVManager.instance.picture.level = 1
        assertEquals(0.01f, Brightness.adjust(context, -1)!!, 0.001f)
        TVManager.instance.picture.level = 100
        assertEquals(1f, Brightness.adjust(context, 1)!!, 0.001f)
        ChanghongPicture.setBacklight(context, -20)
        assertEquals(1, TVManager.instance.picture.level)
        ChanghongPicture.setBacklight(context, 120)
        assertEquals(100, TVManager.instance.picture.level)
    }

    @Test
    fun unreadablePanelKeepsTheExistingAndroidPath() {
        allowWriteSettings()
        val cases: List<() -> Unit> = listOf(
            { TVManager.instance.picture.level = -1 },
            { TVManager.instance.picture.level = 101 },
            { TVManager.instance.picture.throws = true },
            { TVManager.instance.providesPicture = false },
        )
        for (case in cases) {
            TVManager.instance = TVManager()
            case()
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 76)
            assertTrue(ChanghongPicture.available(context))
            assertEquals(76 / 255f, Brightness.level(context)!!, 0.001f)
            assertEquals(89 / 255f, Brightness.adjust(context, 1)!!, 0.001f)
            assertEquals(89, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
            assertFalse(ChanghongPicture.failed)
        }
    }

    @Test
    fun panelAtZeroIsReadAndSteppedUp() {
        TVManager.instance.picture.level = 0
        assertEquals(0f, Brightness.level(context)!!, 0.001f)
        assertEquals(0.05f, Brightness.adjust(context, 1)!!, 0.001f)
        assertEquals(5, TVManager.instance.picture.level)
        assertAndroidBrightnessUnchanged()
    }

    @Test
    fun rejectedWriteDisablesTheSlider() {
        TVManager.instance.picture.acceptsWrites = false
        assertNull(Brightness.adjust(context, 1))
        TVManager.instance.picture.acceptsWrites = true
        assertSliderStaysDisabled()
    }

    @Test
    fun silentNoOpDisablesTheSlider() {
        TVManager.instance.picture.appliesWrites = false
        assertNull(Brightness.adjust(context, 1))
        TVManager.instance.picture.appliesWrites = true
        assertSliderStaysDisabled()
    }

    @Test
    fun writeExceptionDisablesTheSlider() {
        TVManager.instance.picture.throwsOnWrite = true
        assertNull(Brightness.adjust(context, 1))
        TVManager.instance.picture.throwsOnWrite = false
        assertSliderStaysDisabled()
    }

    private fun assertSliderStaysDisabled() {
        assertTrue(ChanghongPicture.available(context))
        assertTrue(ChanghongPicture.failed)
        allowWriteSettings()
        assertNull(Brightness.level(context))
        assertNull(Brightness.adjust(context, -1))
        ChanghongPicture.setBacklight(context, 80)
        assertEquals(50, TVManager.instance.picture.level)
        assertAndroidBrightnessUnchanged()
    }

    private fun assertAndroidBrightnessUnchanged() {
        assertEquals(76, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
        assertEquals(Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE))
    }

    private fun allowWriteSettings() {
        shadowOf(context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
            .setMode(AppOpsManager.OPSTR_WRITE_SETTINGS, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
    }

    private fun withoutTvos(): Context = object : ContextWrapper(context) {
        private val loader = object : ClassLoader(context.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "com.changhong.tvos.common.TVManager") throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        override fun getClassLoader(): ClassLoader = loader
    }
}
