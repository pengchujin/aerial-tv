package app.orchard.tv

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import app.orchard.tv.guard.GuardSetup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A side-by-side benchmark must not silently become a second launcher/guardian. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PreviewIsolationTest {
    private val app: android.app.Application = RuntimeEnvironment.getApplication()

    @Test fun previewHasAnIndependentIdentityAndProvider() {
        assertTrue(BuildConfig.PREVIEW)
        assertEquals("app.orchard.tv.preview", app.packageName)
        assertNotNull(app.packageManager.resolveContentProvider("${app.packageName}.files", 0))
        assertNull(app.packageManager.resolveContentProvider("app.orchard.tv.files", 0))
    }

    @Test fun previewDoesNotRegisterHomeOrBackgroundGuardians() {
        val pm = app.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(app.packageName)
        assertTrue(pm.queryIntentActivities(home, 0).isEmpty())
        for (service in listOf("HomeGuardService", "RevivalListener", "RevivalSyncService", "RevivalAccountService")) {
            val component = ComponentName(app.packageName, "app.orchard.tv.guard.$service")
            assertTrue(runCatching { pm.getServiceInfo(component, 0) }.isFailure)
        }
    }

    @Test fun accidentalSecureSettingsGrantCannotEnablePreviewGuardian() {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        val original = "example.service/example.service.Reader"
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, original)
        assertFalse(GuardSetup.ensureEnabled(app))
        GuardSetup.rebindIfStalled(app)
        assertEquals(original, Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }
}
