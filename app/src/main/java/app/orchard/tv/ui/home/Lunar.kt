package app.orchard.tv.ui.home

import java.time.LocalDate

/**
 * The Chinese calendar's date, for the large clock when the TV speaks Chinese: "九月初五", as the
 * iPhone's lock screen shows it beside the date. A table rather than android.icu's ChineseCalendar,
 * which only came in Android 7 (the Xiaomi MiTV4 sets run Android 6).
 */
object Lunar {
    private const val FIRST_YEAR = 1901

    /**
     * One lunar year per Gregorian year it begins in, from 1901 to 2100, generated from macOS's
     * Chinese calendar and checked against it day by day (LunarTest). Bits 0-3: after which month
     * the leap month comes, 0 for none; bits 4-16: whether each month in turn (the leap one in its
     * place) has 30 days; bits 17-21 and 22-23: the day and month (0 = January) of the new year.
     */
    private val YEARS = intArrayOf(
        0x667520, 0x50ea50, 0x3b64a5, 0x6064b0, 0x48a9b0, 0x335564, 0x5a56a0, 0x44b590, 0x2d7522, 0x547520,
        0x3db256, 0x64b250, 0x4ca4b0, 0x352ab5, 0x5caad0, 0x4856a0, 0x2eb692, 0x56da90, 0x43d927, 0x68d920,
        0x50d250, 0x39a4d5, 0x60a560, 0x4a2b60, 0x315b54, 0x5a6d40, 0x44ea90, 0x2fe922, 0x54e920, 0x3cd266,
        0x6252b0, 0x4ca570, 0x352b65, 0x5cb5a0, 0x486d40, 0x30ec93, 0x567490, 0x3f6937, 0x66a930, 0x5052b0,
        0x36a5b6, 0x5eaad0, 0x4a56a0, 0x33b554, 0x5aba40, 0x44b490, 0x2da932, 0x54a950, 0x3b52d7, 0x625360,
        0x4caad0, 0x375aa5, 0x5c5b20, 0x46da50, 0x31d4a3, 0x58d4a0, 0x3ea958, 0x64a970, 0x505560, 0x38ab56,
        0x5ead50, 0x4a6d20, 0x32ea54, 0x5aea50, 0x4464a0, 0x2ac973, 0x52a9b0, 0x3d55a7, 0x6256a0, 0x4cb690,
        0x377525, 0x5eb520, 0x46b250, 0x2f64b4, 0x56a4b0, 0x3f4ab8, 0x642ad0, 0x4e56d0, 0x38b696, 0x60da90,
        0x4ad920, 0x33d254, 0x5ad250, 0x45a4da, 0x68a560, 0x522b60, 0x3a5b56, 0x626d50, 0x4cea90, 0x37e925,
        0x5ee920, 0x48d260, 0x2ea563, 0x54a570, 0x3f4d68, 0x6635a0, 0x4e6d50, 0x396c95, 0x607490, 0x4a6930,
        0x3152b4, 0x5852b0, 0x42a5b0, 0x2d55a2, 0x5256a0, 0x3bb557, 0x64ba40, 0x4eb490, 0x35a935, 0x5ca950,
        0x4652d0, 0x2eaad4, 0x54ab50, 0x3f5aa9, 0x665d20, 0x50da50, 0x39d4a6, 0x60d4a0, 0x4ac950, 0x3352e4,
        0x585560, 0x42ab50, 0x2d5b22, 0x546d20, 0x3aea56, 0x627250, 0x4c64b0, 0x34c975, 0x5acab0, 0x4655a0,
        0x2ead63, 0x56b690, 0x3f752b, 0x66b520, 0x50b250, 0x39a4b6, 0x5ea4b0, 0x484ab0, 0x3055b5, 0x585ad0,
        0x42b6a0, 0x2db522, 0x54d920, 0x3dd257, 0x62d250, 0x4ca550, 0x354ad5, 0x5c4b60, 0x445b50, 0x2edaa3,
        0x56ec90, 0x43e928, 0x66e920, 0x50d260, 0x38a566, 0x5ea570, 0x484d60, 0x306d54, 0x587550, 0x447490,
        0x2ae933, 0x526930, 0x3b52b7, 0x6252b0, 0x4aa5b0, 0x3555a5, 0x5c56a0, 0x46b650, 0x2f74a4, 0x56b4a0,
        0x3fa958, 0x66a950, 0x4e52d0, 0x36aad6, 0x5eab50, 0x4a5aa0, 0x30ba54, 0x58da50, 0x44d4a0, 0x2dc953,
        0x52c960, 0x3b94e7, 0x625560, 0x4cab50, 0x355b25, 0x5c6d20, 0x46ea50, 0x30e4a4, 0x5468b0, 0x3cc978,
        0x644ab0, 0x4e55b0, 0x36ad66, 0x5eb6a0, 0x4a7520, 0x337254, 0x58b250, 0x42a8b0, 0x2b49b2, 0x524ab0,
    )

    private val MONTHS = arrayOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")
    private val TENS = arrayOf("初", "十", "廿", "三")
    private val UNITS = arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")

    /** [date] in the Chinese calendar, as "九月初五" or "闰六月十二"; null outside 1901-2100. */
    fun text(date: LocalDate): String? {
        val (month, day, leap) = of(date) ?: return null
        val days = when (day) {
            10 -> "初十"
            20 -> "二十"
            30 -> "三十"
            else -> TENS[day / 10] + UNITS[day % 10 - 1]
        }
        return (if (leap) "闰" else "") + MONTHS[month - 1] + "月" + days
    }

    /** The month (1-12), day and whether the month is a leap one; null outside the table. */
    fun of(date: LocalDate): Triple<Int, Int, Boolean>? {
        var i = date.year - FIRST_YEAR
        if (i >= YEARS.size) return null
        // Before this year's new year, the date is still in the year that began last January or February.
        if (i >= 0 && date.isBefore(newYear(i))) i--
        if (i < 0) return null
        val info = YEARS[i]
        var left = (date.toEpochDay() - newYear(i).toEpochDay()).toInt()
        val leapAfter = info and 0xf
        val count = if (leapAfter == 0) 12 else 13
        for (k in 0 until count) {
            val len = if (info shr (4 + k) and 1 == 1) 30 else 29
            if (left < len) {
                val month = if (leapAfter == 0 || k < leapAfter) k + 1 else k
                return Triple(month, left + 1, leapAfter != 0 && k == leapAfter)
            }
            left -= len
        }
        return null
    }

    private fun newYear(i: Int): LocalDate {
        val info = YEARS[i]
        return LocalDate.of(FIRST_YEAR + i, (info shr 22 and 0x3) + 1, info shr 17 and 0x1f)
    }
}
