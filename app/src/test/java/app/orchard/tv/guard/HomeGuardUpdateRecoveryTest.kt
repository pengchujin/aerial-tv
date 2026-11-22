package app.orchard.tv.guard

import android.content.Context
import android.os.PowerManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeGuardUpdateRecoveryTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Test
    fun updateRecoveryUsesFreshFrontAndRechecksPower() {
        val controller = Robolectric.buildService(HomeGuardService::class.java).create()
        val guard = controller.get()
        val app = shadowOf(RuntimeEnvironment.getApplication())
        ReflectionHelpers.setField(guard, "stockHomes", mapOf("vendor.home" to setOf("vendor.home.Home")))
        fun front(pkg: String, sequence: Long) {
            ReflectionHelpers.setField(guard, "lastWindow", pkg to "$pkg.Home")
            ReflectionHelpers.setField(guard, "lastActivityWindowSequence", sequence)
        }
        // windowsAtSchedule is 1: only a window event with a later sequence counts as current.
        fun finish() = ReflectionHelpers.callInstanceMethod<Unit>(guard, "recoverAfterUpdate",
            ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType, 1L))
        try {
            shadowOf(power).setIsInteractive(true)
            front("vendor.home", 1) // An old window is not current evidence; usage stats say nothing.
            finish(); assertNull(app.nextStartedActivity)
            front("video.app", 2) // User switched during the delay.
            finish(); assertNull(app.nextStartedActivity)
            front("vendor.home", 3)
            shadowOf(power).setIsInteractive(false) // Screen off by the time the delay ends.
            finish(); assertNull(app.nextStartedActivity)
            shadowOf(power).setIsInteractive(true)
            finish(); assertNotNull(app.nextStartedActivity)
        } finally { controller.destroy() }
    }
}
