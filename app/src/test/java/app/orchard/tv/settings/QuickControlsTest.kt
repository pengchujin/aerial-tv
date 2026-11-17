package app.orchard.tv.settings

import android.content.Context
import app.orchard.tv.settings.QuickTarget.BuiltIn
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class QuickControlsTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun persistsOrderVisibilityAndRenameAcrossRestart() {
        val store = QuickControlStore(context)
        val mute = QuickAction("Mute", BuiltIn.Mute)
        val player = QuickAction("Player", manual(".Play"))
        assertNull(store.change(QuickEdit.Add(mute)))
        assertNull(store.change(QuickEdit.Add(player)))
        assertNull(store.change(QuickEdit.Move(player.id, 0)))
        assertNull(store.change(QuickEdit.Rename(player.id, "Watch")))
        assertNull(store.change(QuickEdit.Show(mute.id, false)))
        assertNull(store.change(QuickEdit.EnableArea(false)))
        val reloaded = QuickControlStore(context)
        assertEquals(listOf(player.copy(title = "Watch"), mute.copy(enabled = false)), reloaded.items)
        assertEquals(store.revision, reloaded.revision)
        assertFalse(reloaded.enabled)
        assertNull(reloaded.enabledAction(player.id))
    }

    @Test fun staleEditCannotOverwriteNewerLayout() {
        val store = QuickControlStore(context)
        val item = QuickAction("Original", BuiltIn.Mute)
        assertNull(store.change(QuickEdit.Add(item)))
        val oldRevision = store.revision
        assertNull(store.change(QuickEdit.Rename(item.id, "TV name")))
        assertEquals("conflict", store.change(QuickEdit.Rename(item.id, "Old phone name"), oldRevision))
        assertEquals("TV name", QuickControlStore(context).items.single().title)
    }

    @Test fun invalidOrRetiredRecordsDoNotEraseValidNeighbors() {
        val control = QuickAction("Connect", manual(".Connect"))
        val stored = JSONArray().put(false)
            .put(JSONObject().put("key", "legacy:broadcast").put("title", "Retired prototype"))
            .put(JSONObject().put("key", "app:org.example.player").put("id", "old-app").put("title", "Open player"))
            .put(JSONObject().put("key", "shortcut:org.example.remote:host").put("id", "old-shortcut").put("title", "Old shortcut"))
            .put(control.json())
        context.getSharedPreferences("quick_controls", Context.MODE_PRIVATE).edit()
            .putString("items", stored.toString()).apply()
        assertEquals(listOf(control), QuickControlStore(context).items)
    }

    @Test fun rejectsDuplicateCapabilitiesAndOutOfRangeMoves() {
        val store = QuickControlStore(context)
        val item = QuickAction("Mute", BuiltIn.Mute)
        assertNull(store.change(QuickEdit.Add(item)))
        val revision = store.revision
        assertEquals("exists", store.change(QuickEdit.Add(item.copy(id = "different"))))
        assertEquals("invalid", store.change(QuickEdit.Move(item.id, 3)))
        assertEquals(revision, store.revision)
        assertEquals(listOf(item), store.items)
    }

    @Test fun manualEditPreservesIdentityVisibilityAndTypes() {
        val store = QuickControlStore(context)
        val control = QuickAction("Play", manual(".Play"), enabled = false)
        val next = manual(".Pause", """{"volume":1.0,"enabled":true,"label":"room","timestamp":2147483648}""")
        assertNull(store.change(QuickEdit.Add(control)))
        assertNull(store.change(QuickEdit.Configure(control.id, "Pause", next)))
        val saved = QuickControlStore(context).items.single()
        assertEquals(control.copy(title = "Pause", target = next), saved)
        assertEquals(1.0, (saved.target as ManualQuickTarget).extras["volume"])
        assertEquals(2147483648L, saved.target.extras["timestamp"])
        assertTrue(saved.displayJson().getBoolean("manual"))
        assertFalse(saved.displayJson().toString().contains("org.example.player"))
        assertEquals(next.key, manual("org.example.player.Pause",
            """{"label":"room","timestamp":2147483648,"enabled":true,"volume":1.0}""").key)
    }

    @Test fun rejectsInvalidManualFields() {
        val invalidExtras = listOf("[]", "{unquoted:true}", """{"nested":{}}""", """{"empty":null}""", """{"x":1} true""")
        for (extras in invalidExtras) {
            assertNull(ManualQuickTarget.create("activity", "org.example.player", ".Play", "", "", extras))
        }
        assertNull(ManualQuickTarget.create("broadcast", "org.example.player", ".Play", "", "", ""))
        assertNull(ManualQuickTarget.create("activity", "org.example.player", ".Play", "", "file:///tmp/data", ""))
        assertNull(ManualQuickTarget.create("activity", "app.orchard.tv.shortcuts", ".Main", "", "", ""))
    }

    private fun manual(component: String, extras: String = "") =
        requireNotNull(ManualQuickTarget.create("activity", "org.example.player", component, "", "", extras))
}
