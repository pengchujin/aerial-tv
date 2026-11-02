package app.orchard.tv.update

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Looper
import androidx.core.content.FileProvider
import app.orchard.tv.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowPackageInstaller
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [UpdateSessionTest.UnansweredSession::class, UpdateSessionTest.HostFileProvider::class],
    instrumentedPackages = ["androidx.core.content"],
)
@LooperMode(LooperMode.Mode.PAUSED)
class UpdateSessionTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val installer get() = app.packageManager.packageInstaller
    private val apk = File(app.cacheDir, "updates/aerial.apk")

    @Before
    fun prepare() {
        // Robolectric reuses the app singleton but resets PackageInstaller's session IDs.
        val pending = ReflectionHelpers.getField<Any?>(Updates, "awaiting")
        if (pending != null) ReflectionHelpers.callInstanceMethod<Unit>(Updates, "stopAwaiting",
            ClassParameter.from(Int::class.javaPrimitiveType!!, ReflectionHelpers.getField<Int>(pending, "id")),
        )
        ReflectionHelpers.getField<MutableSet<Int>>(Updates, "abandoned").clear()
        Updates.resetInstall()
        apk.parentFile!!.mkdirs()
        apk.writeBytes(byteArrayOf(1, 2, 3))
        // Aerial installed itself last, so from Android 12 the update may go through silently.
        if (Build.VERSION.SDK_INT >= 31) shadowOf(app.packageManager).setInstallSourceInfo(app.packageName, app.packageName, app.packageName)
        Updates.onHomeResumed()
    }

    @Test
    fun installedByAdbModernSessionFallsBackAfterEightSeconds() {
        // #1162's Hisense: installed by adb, so Android asks, and an answer is due at once.
        shadowOf(app.packageManager).setInstallSourceInfo(app.packageName, null, null)
        val id = commit()
        advance(7_999)
        assertNotNull(installer.getSessionInfo(id))
        advance(1)
        assertNull(installer.getSessionInfo(id))
        assertInstallerOpened()
    }

    @After
    fun clearTestDownload() {
        Updates.testApk = null
    }

    @Test
    fun downloadAndCommitDoNotClaimTheInstallerIsAlreadyVisible() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        shadowOf(app.packageManager).setPackageArchiveInfo(apk.path, PackageInfo().apply {
            packageName = app.packageName
            setLongVersionCode(BuildConfig.VERSION_CODE + 1L)
        })
        Updates.testApk = File(app.cacheDir, "update-test.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        Updates.install(app)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (Updates.install is Updates.Install.Downloading && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }

        assertEquals(Updates.Install.Waiting, Updates.install)
        assertEquals(1, installer.mySessions.size)
        assertNull(shadowOf(app).nextStartedActivity)
        Updates.resetInstall()
        assertEquals(Updates.Install.Waiting, Updates.install)
    }

    @Test
    @Config(sdk = [31, 34])
    fun unansweredModernSessionEventuallyFallsBackToApkView() {
        val id = commit()
        advance(119_999)
        assertNotNull(installer.getSessionInfo(id))
        assertNull(shadowOf(app).nextStartedActivity)

        advance(1)

        assertNull(installer.getSessionInfo(id))
        assertInstallerOpened()
    }

    @Test
    @Config(sdk = [28, 30])
    fun olderAndroidStillFallsBackAfterEightSeconds() {
        val id = commit()
        advance(7_999)
        assertNotNull(installer.getSessionInfo(id))
        assertNull(shadowOf(app).nextStartedActivity)
        advance(1)
        assertNull(installer.getSessionInfo(id))
        assertInstallerOpened()
    }

    @Test
    fun silentInstallTakingLongerThanEightSecondsCanSucceedWithoutFallback() {
        val id = commit()
        advance(30_000)
        assertNotNull(installer.getSessionInfo(id))
        assertNull(shadowOf(app).nextStartedActivity)
        answer(id, PackageInstaller.STATUS_SUCCESS)
        assertEquals(Updates.Install.Idle, Updates.install)
        advance(120_000)
        assertNotNull(installer.getSessionInfo(id))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun repeatedPressDoesNotOpenAnotherInstallOrPermissionRequest() {
        commit()
        // Without in-flight protection this opens the missing-install-permission settings.
        Updates.install(app)
        assertEquals(1, installer.mySessions.size)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun leavingAndReturningBeforeTimeoutDoesNotForgetThePendingSession() {
        val id = commit()
        Updates.onHomePaused()
        advance(30_000)
        Updates.onHomeResumed()
        Updates.resetInstall()
        assertEquals(Updates.Install.Waiting, Updates.install)
        Updates.install(app)
        assertNull(shadowOf(app).nextStartedActivity)
        advance(90_000)
        assertNull(installer.getSessionInfo(id))
        assertInstallerOpened()
    }

    @Test
    fun timeoutWhileAwayOnlyOpensInstallerWhenHomeResumes() {
        val id = commit()
        Updates.onHomePaused()
        advance(120_000)
        assertNotNull(installer.getSessionInfo(id))
        assertNull(shadowOf(app).nextStartedActivity)
        Updates.onHomeResumed()
        assertNull(installer.getSessionInfo(id))
        assertInstallerOpened()
    }

    @Test
    fun successfulAnswerWhileAwayCancelsDeferredFallback() {
        val id = commit()
        Updates.onHomePaused()
        advance(120_000)
        answer(id, PackageInstaller.STATUS_SUCCESS)
        Updates.onHomeResumed()
        assertNotNull(installer.getSessionInfo(id))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun confirmationThatNeverPausesHomeFallsBackAfterFourSeconds() {
        val id = commit()
        advance(30_000)
        requestConfirmation(id)
        advance(3_999)
        assertNotNull(installer.getSessionInfo(id))
        assertNull(shadowOf(app).nextStartedActivity)
        advance(1)
        assertNull(installer.getSessionInfo(id))
        assertInstallerOpened()
        advance(120_000)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun visibleConfirmationIsNotAbandonedWhileOwnerDecides() {
        val id = commit()
        requestConfirmation(id)
        assertEquals(Updates.Install.Waiting, Updates.install)
        Updates.onHomePaused()
        assertEquals(Updates.Install.Opened, Updates.install)
        advance(180_000)
        assertNotNull(installer.getSessionInfo(id))
        assertNull(shadowOf(app).nextStartedActivity)
        Updates.onHomeResumed()
        assertEquals(Updates.Install.Idle, Updates.install)
        advance(180_000)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun confirmationRequestedWhileAwayWaitsForHomeBeforeOpening() {
        val id = commit()
        Updates.onHomePaused()
        answer(id, PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("test.confirm"))
        advance(120_000)
        assertNull(shadowOf(app).nextStartedActivity)
        assertNotNull(installer.getSessionInfo(id))

        Updates.onHomeResumed()
        assertEquals("test.confirm", shadowOf(app).nextStartedActivity?.action)
        Updates.onHomePaused()
        advance(120_000)
        Updates.onHomeResumed()
        assertNotNull(installer.getSessionInfo(id))
        assertEquals(Updates.Install.Idle, Updates.install)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun terminalAnswerCancelsConfirmationDeferredUntilHomeReturns() {
        val id = commit()
        Updates.onHomePaused()
        answer(id, PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("test.confirm"))
        answer(id, PackageInstaller.STATUS_FAILURE_ABORTED)
        Updates.onHomeResumed()
        advance(120_000)
        assertEquals(Updates.Install.Idle, Updates.install)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun missingConfirmationActivityFallsBackAndIgnoresLateFailure() {
        val id = commit()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startActivity(intent: Intent) {
                if (intent.action == "test.confirm") throw ActivityNotFoundException()
                super.startActivity(intent)
            }
        }
        UpdateResult().onReceive(context, Intent().apply {
            putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
            putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
            putExtra(Intent.EXTRA_INTENT, Intent("test.confirm"))
        })
        assertNull(installer.getSessionInfo(id))
        assertInstallerOpened()
        answer(id, PackageInstaller.STATUS_FAILURE)
        advance(120_000)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun lateAnswerFromAbandonedSessionCannotOpenAnotherInstaller() {
        val id = commit()
        advance(120_000)
        assertInstallerOpened()
        answer(id, PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("test.confirm"))
        answer(id, PackageInstaller.STATUS_FAILURE)
        advance(120_000)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun missingDownloadedApkReportsFailureInsteadOfWaitingForever() {
        val id = commit()
        assertTrue(apk.delete())
        advance(120_000)
        assertNull(installer.getSessionInfo(id))
        assertEquals(Updates.Install.Failed(Updates.Failure.Blocked), Updates.install)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    private fun commit(): Int {
        assertTrue(ReflectionHelpers.callInstanceMethod(Updates, "installBySession",
            ClassParameter.from(Context::class.java, app),
            ClassParameter.from(File::class.java, apk),
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, false),
        ))
        assertEquals(Updates.Install.Waiting, Updates.install)
        assertNull(shadowOf(app).nextStartedActivity)
        return installer.mySessions.single().sessionId
    }

    private fun requestConfirmation(id: Int) {
        answer(id, PackageInstaller.STATUS_PENDING_USER_ACTION, Intent("test.confirm"))
        assertEquals("test.confirm", shadowOf(app).nextStartedActivity?.action)
    }

    private fun answer(id: Int, status: Int, confirm: Intent? = null) {
        UpdateResult().onReceive(app, Intent().apply {
            putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
            putExtra(PackageInstaller.EXTRA_STATUS, status)
            if (confirm != null) putExtra(Intent.EXTRA_INTENT, confirm)
        })
    }

    private fun assertInstallerOpened() {
        val intent = shadowOf(app).nextStartedActivity
        assertNotNull("The APK must be handed to the system installer", intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data?.scheme)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(Updates.Install.Opened, Updates.install)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    // Robolectric normally reports success at commit; model the firmware's missing response.
    @Implements(PackageInstaller.Session::class)
    class UnansweredSession : ShadowPackageInstaller.ShadowSession() {
        @Implementation
        override fun commit(statusReceiver: IntentSender) = Unit
    }

    // FileProvider's root check assumes '/' separators, unlike the Windows host filesystem.
    // Only URI construction is replaced; the APK, session lifecycle and launch Intent stay real.
    @Implements(value = FileProvider::class, isInAndroidSdk = false)
    class HostFileProvider {
        companion object {
            @JvmStatic
            @Implementation
            fun getUriForFile(context: Context, authority: String, file: File): Uri {
                assertEquals(File(context.cacheDir, "updates/aerial.apk"), file)
                assertEquals("${context.packageName}.files", authority)
                return Uri.parse("content://$authority/updates/aerial.apk")
            }
        }
    }
}
