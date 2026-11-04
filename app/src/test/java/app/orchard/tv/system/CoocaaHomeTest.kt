package app.orchard.tv.system

import android.content.ComponentName
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CoocaaHomeTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun noCoocaaFirmwareNamesNoLauncher() {
        assertNull(CoocaaHome.launcher(context))
    }

    @Test
    fun aerialAndMissingActivitiesAreNotLaunchers() {
        assertFalse(CoocaaHome.isLauncher(context, ComponentName(context.packageName, "${context.packageName}.MainActivity")))
        assertFalse(CoocaaHome.isLauncher(context, ComponentName("com.tianci.movieplatform", "com.coocaa.aigc.home.home.rootpage.HomeActivity")))
        assertFalse(CoocaaHome.isLauncher(context, ComponentName("", "")))
    }
}
