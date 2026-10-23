package app.orchard.tv.ui.theme

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.orchard.tv.R

/**
 * Pixels per dp of Aerial's 960×540 design canvas on this screen: 2 for a 1080p UI, 4 for a TV
 * that draws its UI at 4K. Anything rasterized ahead of time (tile art, wallpapers) is sized from
 * this too, so it stays as sharp as the text drawn live.
 */
fun canvasDensity(metrics: android.util.DisplayMetrics): Float =
    minOf(metrics.widthPixels, metrics.heightPixels * 16 / 9) / 960f

/** tvOS dark appearance. Values follow Apple's system colors for the dark, elevated context. */
object Palette {
    val Background = Color(0xFF000000)
    val Label = Color(0xFFFFFFFF)
    val SecondaryLabel = Color(0x99EBEBF5)
    val TertiaryLabel = Color(0x4DEBEBF5)
    val Glass = Color(0xE6202022)
    val Fill = Color(0x2E787880)
    val FillStrong = Color(0x52787880)
    val Hairline = Color(0x1AFFFFFF)
    val Green = Color(0xFF30D158)
    val Orange = Color(0xFFFF9F0A)
    val Blue = Color(0xFF0A84FF)
    val Red = Color(0xFFFF453A)

    /** tvOS draws a focused control as a near-white plate with dark content. */
    val Focus = Color(0xFFF5F5F7)
    val OnFocus = Color(0xFF1D1D1F)
}

object Motion {
    /** Strong ease-out: entrances, anything that answers the user. */
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    /** On-screen movement between two resting places. */
    val EaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

    /** iOS sheet curve, for panels sliding in. */
    val Drawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
}

/** True when the TV's animator scale is 0 ("remove animations"). Motion collapses to fades. */
val LocalReducedMotion = staticCompositionLocalOf { false }

// Inter has an optical size axis. Text sizes get the 14pt master, display sizes the 32pt master,
// the way SF Pro switches between Text and Display. Font variations need Android 8.0; before that
// every weight would come out as Inter Regular, so those TVs use the system's Roboto instead.
@OptIn(ExperimentalTextApi::class)
private fun inter(opticalSize: Float) = if (Build.VERSION.SDK_INT < 26) FontFamily.SansSerif else FontFamily(
    (1..9).map { i ->
        val w = i * 100
        Font(
            R.font.inter,
            FontWeight(w),
            variationSettings = FontVariation.Settings(
                FontVariation.weight(w),
                FontVariation.Setting("opsz", opticalSize),
            ),
        )
    },
)

val InterText = inter(14f)
val InterDisplay = inter(32f)

object Type {
    val Clock = TextStyle(
        fontFamily = InterDisplay, fontWeight = FontWeight.W500,
        fontSize = 104.sp, lineHeight = 104.sp, letterSpacing = (-0.02).em, color = Palette.Label,
    )
    val LargeTitle = TextStyle(
        fontFamily = InterDisplay, fontWeight = FontWeight.W600,
        fontSize = 26.sp, letterSpacing = (-0.015).em, color = Palette.Label,
    )
    val Title = TextStyle(
        fontFamily = InterDisplay, fontWeight = FontWeight.W600,
        fontSize = 19.sp, letterSpacing = (-0.01).em, color = Palette.Label,
    )
    val Headline = TextStyle(
        fontFamily = InterText, fontWeight = FontWeight.W600,
        fontSize = 15.sp, letterSpacing = (-0.005).em, color = Palette.Label,
    )
    val Body = TextStyle(
        fontFamily = InterText, fontWeight = FontWeight.W500,
        fontSize = 14.sp, color = Palette.Label,
    )
    val Callout = TextStyle(
        fontFamily = InterText, fontWeight = FontWeight.W500,
        fontSize = 13.sp, color = Palette.SecondaryLabel,
    )
    val Caption = TextStyle(
        fontFamily = InterText, fontWeight = FontWeight.W500,
        fontSize = 11.sp, letterSpacing = 0.01.em, color = Palette.SecondaryLabel,
    )
}
