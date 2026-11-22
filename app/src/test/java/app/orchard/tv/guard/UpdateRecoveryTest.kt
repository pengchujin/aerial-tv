package app.orchard.tv.guard

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateRecoveryTest {
    @Test fun onlyConfirmedInstallerOrUninvitedStockHomeCanReturn() {
        assertTrue(UpdateRecovery.shouldRestore(true, true, false, "installer", false, false, true))
        assertTrue(UpdateRecovery.shouldRestore(true, true, false, "home", true, false, false))
        assertFalse(UpdateRecovery.shouldRestore(true, true, false, "home", true, true, false))
        for (front in listOf(null, "video.app", "hdmi.player")) {
            assertFalse(UpdateRecovery.shouldRestore(true, true, false, front, false, false, false))
        }
        assertFalse(UpdateRecovery.shouldRestore(false, true, false, "installer", false, false, true))
        assertFalse(UpdateRecovery.shouldRestore(true, false, false, "installer", false, false, true))
        assertFalse(UpdateRecovery.shouldRestore(true, true, true, "installer", false, false, true))
    }

    @Test fun installerMustBeAnEnabledSystemApkHandler() {
        val app = RuntimeEnvironment.getApplication()
        val pm = shadowOf(app.packageManager)
        val component = ComponentName("vendor.installer", "vendor.installer.Install")
        val info = pm.addActivityIfNotPresent(component).apply {
            exported = true; enabled = true; applicationInfo.enabled = true
        }
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("content")
            addDataType("application/vnd.android.package-archive")
        })
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(
            android.net.Uri.parse("content://app.orchard.tv.update/aerial.apk"), "application/vnd.android.package-archive")
        pm.setResolveInfosForIntent(intent, listOf(android.content.pm.ResolveInfo().apply {
            activityInfo = info; isDefault = true
        }))
        assertFalse(UpdateRecovery.isSystemInstaller(app, component.packageName))
        info.applicationInfo.flags = info.applicationInfo.flags or ApplicationInfo.FLAG_SYSTEM
        pm.getInternalMutablePackageInfo(component.packageName).applicationInfo!!.apply { flags = flags or ApplicationInfo.FLAG_SYSTEM }
        assertTrue(UpdateRecovery.isSystemInstaller(app, component.packageName))
        info.enabled = false
        assertFalse(UpdateRecovery.isSystemInstaller(app, component.packageName))
    }
}
