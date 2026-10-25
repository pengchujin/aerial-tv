package app.orchard.tv.wallpaper

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AerialsTraditionalTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun asset(name: String) = JSONObject(
        context.assets.open(name).bufferedReader().use { it.readText() },
    )

    @Test
    fun overlayCoversEveryVideoAndCaptionAtTheOriginalTimestamps() {
        val original = asset("aerials.json").getJSONArray("videos")
        val overlayRoot = asset("aerials-zh-Hant.json")
        assertEquals(asset("aerials.json").getString("source"), overlayRoot.getString("source"))
        val overlay = overlayRoot.getJSONObject("videos")
        assertEquals(114, original.length())
        assertEquals(original.length(), overlay.length())
        var count = 0
        for (i in 0 until original.length()) {
            val video = original.getJSONObject(i)
            val source = video.getJSONArray("captions")
            val translated = overlay.getJSONArray(video.getString("id"))
            assertEquals(video.getString("id"), source.length(), translated.length())
            for (j in 0 until source.length()) {
                val originalEntry = source.getJSONArray(j)
                val entry = translated.getJSONArray(j)
                assertEquals(2, entry.length())
                assertEquals(originalEntry.getInt(0), entry.getInt(0))
                assertFalse(entry.getString(1).isBlank())
                count++
            }
        }
        assertEquals(352, count)
    }

    @Test
    fun catalogueCacheFollowsLocaleChanges() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            val english = Aerials.all(context).first().captionAt(0)
            Locale.setDefault(Locale.TRADITIONAL_CHINESE)
            val traditional = Aerials.all(context).first().captionAt(0)
            assertFalse(english == traditional)
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            val simplified = Aerials.all(context).first().captionAt(0)
            assertFalse(traditional == simplified)
        } finally {
            Locale.setDefault(previous)
            Aerials.all(context)
        }
    }

    @Test
    fun traditionalLocalesUseOverlayWithoutChangingOtherLanguages() {
        val original = asset("aerials.json").getJSONArray("videos")
        val first = original.getJSONObject(0)
        val firstId = first.getString("id")
        val source = first.getJSONArray("captions").getJSONArray(0)
        val traditional = asset("aerials-zh-Hant.json").getJSONObject("videos")
            .getJSONArray(firstId).getJSONArray(0).getString(1)
        for (locale in listOf(Locale.TRADITIONAL_CHINESE,
            Locale.Builder().setLanguage("zh").setRegion("HK").build(),
            Locale.Builder().setLanguage("zh").setRegion("MO").build(),
            Locale.Builder().setLanguage("zh").setScript("Hant").build())) {
            val video = Aerials.load(context, locale).first()
            assertEquals(firstId, video.id)
            assertEquals(traditional, video.captionAt(0))
        }
        assertEquals(source.getString(1), Aerials.load(context, Locale.SIMPLIFIED_CHINESE).first().captionAt(0))
        assertEquals(source.getString(2).replace(' ', ' '), Aerials.load(context, Locale.ENGLISH).first().captionAt(0))
        assertTrue(traditional != source.getString(1))
    }
}
