package app.orchard.tv.guard

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUsageStatsManager.EventBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeGuardWakeTest {
    private lateinit var service: HomeGuardService
    private val stock = "com.mitv.tvhome"

    @Before fun setup() {
        SystemClock.sleep(120_000)
        service = Robolectric.buildService(HomeGuardService::class.java).get()
        val now = System.currentTimeMillis()
        service.getSharedPreferences("system", Context.MODE_PRIVATE).edit()
            .putString("revivals", listOf(now - 3000, now - 2000, now - 1000).joinToString(",")).commit()
        assertTrue(GuardSetup.revivedOften(service))
        val field = HomeGuardService::class.java.getDeclaredField("stockHomes")
        field.isAccessible = true
        field.set(service, mapOf(stock to setOf("Home")))
        shadowOf(service.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), service.packageName, AppOpsManager.MODE_ALLOWED)
    }

    private fun event(pkg: String, type: Int, age: Long) {
        shadowOf(service.getSystemService(UsageStatsManager::class.java)).addEvent(
            EventBuilder.buildEvent().setPackage(pkg).setClass("Home")
                .setTimeStamp(System.currentTimeMillis() - age).setEventType(type).build())
    }

    private fun takeover() {
        HomeGuardService::class.java.getDeclaredMethod("takeOverIfStockHomeInFront", String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, "wake regression", true, false)
    }

    @Test fun repeatedRevivalsStillRecoverKnownStockHomeWithoutWindowEvent() {
        event(stock, UsageEvents.Event.MOVE_TO_FOREGROUND, 2000)
        takeover()
        assertEquals("app.orchard.tv.MainActivity", shadowOf(service).nextStartedActivity?.component?.className)
    }

    @Test fun doesNotInterruptAnAppOrHdmiAfterRevival() {
        event(stock, UsageEvents.Event.MOVE_TO_FOREGROUND, 3000)
        event("com.xiaomi.mitv.tvplayer", UsageEvents.Event.MOVE_TO_FOREGROUND, 1000)
        takeover()
        assertNull(shadowOf(service).nextStartedActivity)
    }

    @Test fun pausedHomeIsNotEvidenceThatItIsStillInFront() {
        event(stock, UsageEvents.Event.MOVE_TO_FOREGROUND, 3000)
        event(stock, UsageEvents.Event.MOVE_TO_BACKGROUND, 1000)
        takeover()
        assertNull(shadowOf(service).nextStartedActivity)
    }

    @Test fun missingPermissionKeepsRepeatedRevivalProtection() {
        event(stock, UsageEvents.Event.MOVE_TO_FOREGROUND, 1000)
        shadowOf(service.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), service.packageName, AppOpsManager.MODE_DEFAULT)
        takeover()
        assertNull(shadowOf(service).nextStartedActivity)
    }

    @Test fun staleHistoryCannotPullViewerOutOfAnUnknownApp() {
        event(stock, UsageEvents.Event.MOVE_TO_FOREGROUND, 6 * 60 * 60 * 1000L + 1000)
        takeover()
        assertNull(shadowOf(service).nextStartedActivity)
    }

    @Test fun missingHistoryKeepsRepeatedRevivalProtection() {
        takeover()
        assertNull(shadowOf(service).nextStartedActivity)
    }
}
