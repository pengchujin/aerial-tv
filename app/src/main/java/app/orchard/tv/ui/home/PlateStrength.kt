package app.orchard.tv.ui.home

internal val plateStrengthChoices = listOf(50, 75, 100)
internal fun plateStrength(value: Int): Int = value.takeIf { it in plateStrengthChoices } ?: 100

/** The original tint is 0.8 / 30; at half strength it is 0.9 / 15. */
internal fun plateTintMatrix(strength: Int): FloatArray {
    val amount = plateStrength(strength) / 100f
    val shade = 1f - 0.2f * amount
    val white = 30f * amount
    return floatArrayOf(
        shade, 0f, 0f, 0f, white,
        0f, shade, 0f, 0f, white,
        0f, 0f, shade, 0f, white,
        0f, 0f, 0f, 1f, 0f,
    )
}

/** Where no frame can be frosted, scale the veil's alpha without changing its color. */
internal fun plateVeilColor(color: Int, strength: Int): Int {
    val alpha = (color ushr 24) * plateStrength(strength)
    return (color and 0x00ffffff) or (((alpha + 50) / 100) shl 24)
}
