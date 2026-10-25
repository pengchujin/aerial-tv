package app.orchard.tv.locale

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ChineseLocaleTest {
    @Test fun traditionalScriptsAndRegions() {
        for (tag in listOf("zh-Hant", "zh-Hant-HK", "zh-HK", "zh-TW", "zh-MO")) {
            assertTrue(tag, ChineseLocale.isTraditional(Locale.forLanguageTag(tag)))
        }
    }

    @Test fun otherLanguagesAndSimplifiedRemainUnchanged() {
        for (tag in listOf("zh", "zh-CN", "zh-Hans", "zh-Hans-CN", "zh-Hans-HK", "en-HK", "ja-TW")) {
            assertFalse(tag, ChineseLocale.isTraditional(Locale.forLanguageTag(tag)))
        }
    }
}
