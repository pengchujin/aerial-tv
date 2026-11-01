package app.orchard.tv.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StandInTest {
    private val patchWall = listOf(
        "com.mitv.tvhome.MainActivity",
        "com.mitv.tvhome.ErrorActivity",
        "com.mitv.videoplayer.VideoPlayerActivity",
        "com.mitv.tvhome.SearchActivity",
    )

    @Test
    fun userModeFallsToMainActivity() {
        assertEquals("com.mitv.tvhome.MainActivity", StandIn.pick("com.mitv.tvhome.MainActivityUserMode", patchWall))
    }

    @Test
    fun noRelatedEntryMeansNone() {
        assertNull(StandIn.pick("com.mitv.tvhome.HomeActivity", patchWall))
    }

    @Test
    fun theBlockedEntryItselfIsNotTaken() {
        assertNull(StandIn.pick("com.mitv.tvhome.MainActivity", listOf("com.mitv.tvhome.MainActivity")))
    }

    @Test
    fun theClosestNameWins() {
        assertEquals(
            "a.MainActivityUser",
            StandIn.pick("a.MainActivityUserMode", listOf("a.Main", "a.MainActivityUser", "a.MainActivity")),
        )
    }
}
