package app.orchard.tv.system

import android.provider.Settings
import app.orchard.tv.settings.QuickAction
import app.orchard.tv.settings.QuickControls
import app.orchard.tv.settings.QuickEdit
import app.orchard.tv.settings.QuickTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class QuickActionRunnerTest {
    @Test fun routesAVisibleControlThroughTheForegroundHomeAndRejectsItAfterHiding() {
        val context = RuntimeEnvironment.getApplication()
        QuickControls.init(context)
        val item = QuickAction("Bluetooth", QuickTarget.BuiltIn.Bluetooth)
        assertNull(QuickControls.store.change(QuickEdit.Add(item)))
        assertEquals(QuickActionRunner.Result.Background, QuickActionRunner.run(context, item.id))
        val opened = mutableListOf<String>()
        QuickActionRunner.foreground = QuickActionRunner.Foreground({ opened.add(it.action.orEmpty()) }, {}, {})
        try {
            assertEquals(QuickActionRunner.Result.Sent, QuickActionRunner.run(context, item.id))
            assertNull(QuickControls.store.change(QuickEdit.Show(item.id, false)))
            assertEquals(QuickActionRunner.Result.Unavailable, QuickActionRunner.run(context, item.id))
            assertEquals(listOf(Settings.ACTION_BLUETOOTH_SETTINGS), opened)
        } finally {
            QuickActionRunner.foreground = null
        }
    }
}
