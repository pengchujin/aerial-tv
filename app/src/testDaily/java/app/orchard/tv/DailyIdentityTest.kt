package app.orchard.tv

import android.content.ComponentName
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DailyIdentityTest {
    @Test fun dailyPromotesPreviewWithHomeAndItsOwnGuardAccount() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals("app.orchard.tv.preview", app.packageName)
        assertFalse(BuildConfig.PREVIEW)
        assertTrue(BuildConfig.MANAGED_FORK)
        assertEquals(app.packageName, app.getString(R.string.revival_account_type))
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(app.packageName)
        assertEquals(1, app.packageManager.queryIntentActivities(home, 0).size)
        assertNotNull(app.packageManager.getServiceInfo(ComponentName(app.packageName, "app.orchard.tv.guard.HomeGuardService"), 0))
        assertNotNull(app.packageManager.getReceiverInfo(ComponentName(app.packageName, "app.orchard.tv.guard.BootReceiver"), 0))
    }
}
