package app.orchard.tv

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The 原桌面 fallback (startStockDirectly) and the HOME way of bringing Aerial back are both
 * gated on Coocaa firmware from Android 13. Other TVs with Aerial as their Home must keep the
 * start they had, so a Robolectric context (which has no com.tianci.system) has to say no.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityCoocaaTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    @Config(sdk = [33])
    fun aTVWithoutTheCoocaaSystemPackageDoesNotGetTheFallback() {
        assertFalse(MainActivity.hidesStandardTasks(context))
    }

    @Test
    @Config(sdk = [28])
    fun aTVBeforeAndroid13DoesNotGetTheFallback() {
        assertFalse(MainActivity.hidesStandardTasks(context))
    }
}
