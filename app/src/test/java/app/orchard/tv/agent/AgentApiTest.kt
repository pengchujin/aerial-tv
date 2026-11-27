package app.orchard.tv.agent

import androidx.compose.ui.geometry.Rect
import app.orchard.tv.Overlay
import app.orchard.tv.ui.home.PinMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentApiTest {
    private var flag = false
    private var columns = 5
    private var style = "high"

    private fun settings() = listOf(
        AgentSetting.bool("large_clock", { flag }, { flag = it }),
        AgentSetting.int("home_columns", 4..6, { columns }, { columns = it }),
        AgentSetting.choice("focus_style", listOf("high", "standard"), { style }, { style = it }),
    )

    @Test fun `a bool is set by true or false and by on or off`() {
        val s = settings().first()
        assertEquals("ok", s.set("true")); assertTrue(flag)
        assertEquals("ok", s.set("off")); assertFalse(flag)
    }

    @Test fun `a value that is not a bool is refused and leaves the setting alone`() {
        val s = settings().first()
        assertEquals("error: large_clock takes true or false, not \"maybe\"", s.set("maybe"))
        assertFalse(flag)
    }

    @Test fun `an int outside its range is refused`() {
        val s = settings()[1]
        assertEquals("error: home_columns takes 4 to 6, not \"9\"", s.set("9")); assertEquals(5, columns)
        assertEquals("ok", s.set("6")); assertEquals(6, columns)
        assertEquals("error: home_columns takes 4 to 6, not \"x\"", s.set("x"))
    }

    @Test fun `a choice takes one of its options and names them when refused`() {
        val s = settings()[2]
        assertEquals("ok", s.set("standard")); assertEquals("standard", style)
        assertEquals("error: focus_style takes high, standard, not \"soft\"", s.set("soft")); assertEquals("standard", style)
    }

    @Test fun `the settings are described with their kind, options and value`() {
        val json = AgentApi.describe(settings())
        assertEquals(3, json.length())
        val col = json.getJSONObject(1)
        assertEquals("home_columns", col.getString("key")); assertEquals("int", col.getString("kind"))
        assertEquals("5", col.getString("value")); assertEquals("4-6", col.getString("range"))
        assertEquals("high", json.getJSONObject(2).getString("value"))
        assertEquals(2, json.getJSONObject(2).getJSONArray("options").length())
    }

    @Test fun `a command line is read from the extras`() {
        val c = AgentApi.parse(mapOf("agent" to "set", "key" to "large_clock", "value" to "on"))
        assertEquals(AgentCommand.Set("large_clock", "on"), c)
        assertEquals(AgentCommand.State, AgentApi.parse(mapOf("agent" to "state")))
        assertEquals(AgentCommand.Open("control_center"), AgentApi.parse(mapOf("agent" to "open", "target" to "control_center")))
        assertEquals(AgentCommand.Focus("com.x"), AgentApi.parse(mapOf("agent" to "focus", "target" to "com.x")))
        assertEquals(AgentCommand.Help, AgentApi.parse(mapOf("agent" to "help")))
    }

    @Test fun `a command that lacks what it needs, or is unknown, is an error that says so`() {
        assertEquals(AgentCommand.Invalid("set needs key and value"), AgentApi.parse(mapOf("agent" to "set", "key" to "x")))
        assertEquals(AgentCommand.Invalid("open needs target"), AgentApi.parse(mapOf("agent" to "open")))
        assertEquals(AgentCommand.Invalid("focus needs target"), AgentApi.parse(mapOf("agent" to "focus")))
        assertEquals(AgentCommand.Invalid("no command \"dance\"; try help"), AgentApi.parse(mapOf("agent" to "dance")))
        assertNull(AgentApi.parse(emptyMap()))
    }

    @Test fun `setting an unknown key is an error that lists none and does not throw`() {
        assertEquals("error: no setting \"zzz\"; try settings", AgentApi.set(settings(), "zzz", "1"))
        assertEquals("ok", AgentApi.set(settings(), "large_clock", "true"))
    }

    @Test fun `an overlay is named by a stable word`() {
        assertEquals("none", AgentApi.overlayName(Overlay.None))
        assertEquals("control_center", AgentApi.overlayName(Overlay.ControlCenter()))
        assertEquals("personalize", AgentApi.overlayName(Overlay.Wallpaper))
        assertEquals("hidden_apps", AgentApi.overlayName(Overlay.HiddenApps))
        assertEquals("folder", AgentApi.overlayName(Overlay.Folder("f")))
        assertEquals("app_menu", AgentApi.overlayName(Overlay.AppMenu("k", Rect.Zero)))
        assertEquals("pin", AgentApi.overlayName(Overlay.Pin(PinMode.Unlock) {}))
    }

    @Test fun `the state is one JSON object`() {
        val j = JSONObject(AgentApi.stateJson("control_center", "com.a", listOf("com.a" to "A", "com.b" to "B"), settings()))
        assertEquals("control_center", j.getString("overlay"))
        assertEquals("com.a", j.getString("focus"))
        assertEquals(2, j.getJSONArray("tiles").length())
        assertEquals("com.b", j.getJSONArray("tiles").getJSONObject(1).getString("key"))
        assertEquals(3, j.getJSONObject("settings").length())
        assertEquals("5", j.getJSONObject("settings").getString("home_columns"))
    }
}
