package app.orchard.tv.system

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Process
import android.provider.Settings
import com.changhong.tvos.common.TVManager
import com.xgimi.gmpf.api.DisplayManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(76, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
    }

    @Test
    fun otherModelsOrProductsIgnoreTheSamePackageAndTvosApi() {
        for ((model, product) in listOf("Other TV" to "mt5520_ll", "长虹智能电视" to "other_product")) {
            ShadowBuild.setModel(model)
            ShadowBuild.setProduct(product)
            DeviceProfile.setOwn(context, null)
            val legacyLevel = Brightness.level(context)
            shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = ChanghongSettings.PACKAGE })
            assertEquals(legacyLevel, Brightness.level(context))
            assertNull(ChanghongPicture.backlight(context))
            ChanghongPicture.setBacklight(context, 80)
            assertEquals(50, TVManager.instance.picture.level)
        }
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
    fun ownProfileOnOtherTvKeepsAndroidBrightnessReadsAndWrites() {
        shadowOf(context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
            .setMode(AppOpsManager.OPSTR_WRITE_SETTINGS, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
        for ((model, product) in listOf("Other TV" to "mt5520_ll", "长虹智能电视" to "other_product")) {
            ShadowBuild.setModel(model)
            ShadowBuild.setProduct(product)
            DeviceProfile.setOwn(context, """{"changhongTvos":true}""")
            assertEquals(76 / 255f, Brightness.level(context)!!, 0.001f)
            assertEquals(89 / 255f, Brightness.adjust(context, 1)!!, 0.001f)
            assertEquals(89, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
            assertEquals(76 / 255f, Brightness.adjust(context, -1)!!, 0.001f)
            assertNull(ChanghongPicture.backlight(context))
            ChanghongPicture.setBacklight(context, 80)
            assertEquals(50, TVManager.instance.picture.level)
        }
    }

    @Test
    fun invalidOrInaccessiblePanelNeverFallsThroughToAndroidBrightness() {
        for (level in listOf(-1, 0, 101)) {
            TVManager.instance.picture.level = level
            assertNull(Brightness.level(context))
            assertNull(Brightness.adjust(context, 1))
        }
        TVManager.instance.picture.throws = true
        assertNull(Brightness.level(context))
        assertEquals(76, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
    }

    @Test
    fun rejectedWriteDisablesTheSlider() {
        TVManager.instance.picture.acceptsWrites = false
        assertNull(Brightness.adjust(context, 1))
        assertEquals(50, TVManager.instance.picture.level)
    }

    @Test
    fun silentNoOpDisablesTheSlider() {
        TVManager.instance.picture.appliesWrites = false
        assertNull(Brightness.adjust(context, 1))
        assertEquals(50, TVManager.instance.picture.level)
    }
}
