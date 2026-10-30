package app.orchard.tv.apps

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LauncherEntriesTest {
    private fun activity(pkg: String, name: String) = ActivityInfo().apply {
        packageName = pkg
        this.name = name
        exported = true
        enabled = true
        applicationInfo = ApplicationInfo().apply {
            packageName = pkg
            enabled = true
        }
    }

    @Test fun keepsEveryLauncherComponentAndUsesThePackageKeyForTheFirst() {
        val entries = LauncherEntries()
        val home = activity("com.example.player", "com.example.player.HomeActivity")
        val search = activity("com.example.player", "com.example.player.SearchActivity")
        val settings = activity("com.example.player", "com.example.player.SettingsActivity")

        assertTrue(entries.add(home, leanback = true))
        assertFalse(entries.add(home, leanback = false))
        assertTrue(entries.add(search, leanback = false))
        assertTrue(entries.add(settings, leanback = false))

        assertEquals(
            listOf(
                "com.example.player",
                "activity:com.example.player/com.example.player.SearchActivity",
                "activity:com.example.player/com.example.player.SettingsActivity",
            ),
            entries.all().map { it.key },
        )
        assertTrue(entries.all().first().leanback)
    }

    @Test fun fallbackEntryDoesNotReplaceAnApksLaunchers() {
        val entries = LauncherEntries()
        val launcher = activity("com.example.home", "com.example.home.MainActivity")
        val fallback = activity("com.example.home", "com.example.home.HomeActivity")

        assertTrue(entries.add(launcher, leanback = false))
        assertFalse(entries.add(fallback, leanback = false, onlyWhenPackageMissing = true))
        assertEquals(listOf("com.example.home"), entries.all().map { it.key })
    }
}
