package app.orchard.tv.system

import android.content.ComponentName
import app.orchard.tv.settings.QuickTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class QuickCatalogTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun exposesClashOnlyWhenAnEnabledSupportedAppIsInstalled() {
        assertFalse(QuickCatalog.available(context).any { it.target.key == "clash" })
        val target = ComponentName("com.github.metacubex.clash.alpha", "com.github.kr328.clash.ExternalControlActivity")
        shadowOf(context.packageManager).addActivityIfNotPresent(target).apply {
            exported = true
            enabled = true
            applicationInfo.enabled = true
        }
        val entry = QuickCatalog.available(context).single { it.target.key == "clash" }
        assertEquals(QuickTarget.Vpn.Clash, entry.target)
        assertTrue(entry.valid())
        assertFalse(entry.displayJson().has("component"))
        assertFalse(entry.displayJson().has("extras"))
    }

    @Test fun availableControlsHaveUniqueValidKeys() {
        val entries = QuickCatalog.available(context)
        assertEquals(entries.size, entries.map { it.target.key }.distinct().size)
        assertTrue(entries.all { it.valid() })
        assertTrue(entries.any { it.target.key == "system:mute" })
    }
}
