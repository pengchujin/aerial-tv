package app.orchard.tv.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CoocaaHomeTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun theLauncherThePropertiesNameIsKeptForRestore() {
        // Skyworth 8R721_A5FP: named by the firmware, and resolve-activity found no Home at all.
        assertTrue(CoocaaHome.worthKeeping(context, "com.coocaa.aigc.home/com.coocaa.aigc.home.home.rootpage.HomeActivity"))
        assertTrue(CoocaaHome.worthKeeping(context, "com.skyworth.officehomepage/com.skyworth.officehomepage.view.HomepageActivity"))
    }

    @Test
    fun aerialItselfAndTheBootPlaceholderAreNotKept() {
        assertFalse(CoocaaHome.worthKeeping(context, "${context.packageName}/${context.packageName}.MainActivity"))
        assertFalse(CoocaaHome.worthKeeping(context, "home[0]/home[1]"))
        assertFalse(CoocaaHome.worthKeeping(context, "/"))
        assertFalse(CoocaaHome.worthKeeping(context, ""))
    }
}
