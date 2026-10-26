package app.orchard.tv.wallpaper

import android.content.Context
import app.orchard.tv.locale.ChineseLocale
import app.orchard.tv.settings.AerialSettings
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.core.content.getSystemService
import org.json.JSONObject
import java.util.Locale

/**
 * Apple TV's aerial screensaver films. Aerial ships only the catalogue (assets/aerials.json, built
 * from Apple's public resources-16.tar): addresses and place names. The films themselves stream from
 * Apple's servers while the screensaver runs, the way the Apple TV plays them.
 */
class Aerial(
    val id: String,
    val label: String,
    val category: String,
    /** 1080p H.264, about 10 Mbps: plays everywhere. */
    val url: String,
    /** 4K SDR HEVC, about 12 Mbps. */
    val uhd: String,
    /** 4K Dolby Vision (profile 5, HEVC 10-bit), about 24 Mbps. */
    val dolbyVision: String,
    /** (second, caption) pairs: tvOS changes the place name as the camera moves on. */
    val captions: List<Pair<Int, String>>,
) {
    fun captionAt(seconds: Int): String = captions.lastOrNull { it.first <= seconds }?.second ?: label

    fun url(quality: AerialQuality): String = when (quality) {
        AerialQuality.DolbyVision -> dolbyVision
        AerialQuality.Uhd -> uhd
        else -> url
    }
}

/** Which of Apple's files the screensaver streams. Auto takes the best this TV can play. */
enum class AerialQuality(val key: String) {
    Auto("auto"), Hd("1080p"), Uhd("4k"), DolbyVision("dolby_vision");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Auto
    }
}

/**
 * What this TV's decoders and display can play, asked once. A TV's UI is usually 1080p whatever
 * its panel (Xiaomi MiTV-MFTR0 reports a single 1920×1080 mode), so the panel cannot be asked;
 * the decoders can. 4K SDR costs about what the 1080p H.264 files do (12 against 10 Mbps), so a
 * TV that decodes it gets it. Dolby Vision needs a decoder for profile 5 at 4K and a display that
 * lists Dolby Vision: Apple's HDR files are profile 5, which has no HDR10 base layer to fall back
 * on, and decoded by anything else they come out green and purple.
 */
object AerialFormats {
    @Volatile
    private var probed: Set<AerialQuality>? = null

    fun supported(context: Context): Set<AerialQuality> = probed ?: probe(context).also { probed = it }

    /** [chosen], or for Auto the best supported; a choice the TV cannot play falls back too. */
    fun resolve(context: Context, chosen: AerialQuality): AerialQuality {
        val ok = supported(context)
        return if (chosen != AerialQuality.Auto && chosen in ok) chosen
        else listOf(AerialQuality.DolbyVision, AerialQuality.Uhd).firstOrNull { it in ok && (chosen == AerialQuality.Auto || it.ordinal < chosen.ordinal) }
            ?: AerialQuality.Hd
    }

    /**
     * What the aerial wallpaper plays: as chosen, but never Dolby Vision. A TV in Dolby Vision mode
     * shows the tiles over the film with Dolby's picture settings. A screensaver that carries the
     * wallpaper's film on plays this too (Screensaver), so the two are one, with no switch.
     */
    fun forWallpaper(context: Context): AerialQuality =
        resolve(context, AerialSettings.aerialQuality).let { if (it == AerialQuality.DolbyVision) AerialQuality.Uhd else it }

    /** One step down, after a decoder failed on [quality]. */
    fun lower(quality: AerialQuality): AerialQuality = when (quality) {
        AerialQuality.DolbyVision -> AerialQuality.Uhd
        else -> AerialQuality.Hd
    }

    private fun probe(context: Context): Set<AerialQuality> {
        val found = mutableSetOf(AerialQuality.Hd)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return found
        val codecs = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }.getOrDefault(emptyList())
        fun caps(mime: String) = codecs.mapNotNull { info ->
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) null
            else runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
        }
        val uhd = caps(MediaFormat.MIMETYPE_VIDEO_HEVC).any { c ->
            runCatching { c.videoCapabilities.areSizeAndRateSupported(3840, 2160, 30.0) }.getOrDefault(false)
        }
        if (uhd) found += AerialQuality.Uhd
        val dvDecoder = caps(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION).any { c ->
            c.profileLevels.any {
                it.profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn &&
                    it.level >= MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd30
            }
        }
        val dvDisplay = runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService<WindowManager>()?.defaultDisplay?.hdrCapabilities?.supportedHdrTypes
                ?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) == true
        }.getOrDefault(false)
        if (uhd && dvDecoder && dvDisplay) found += AerialQuality.DolbyVision
        return found
    }
}

object Aerials {
    @Volatile
    private var cache: List<Aerial>? = null
    @Volatile
    private var cachedLocale: Locale? = null

    fun all(context: Context): List<Aerial> {
        val locale = Locale.getDefault()
        if (cachedLocale == locale) cache?.let { return it }
        return synchronized(this) {
            if (cachedLocale == locale && cache != null) cache!!
            else load(context, locale).also {
                cache = it
                cachedLocale = locale
            }
        }
    }

    internal fun load(context: Context, locale: Locale): List<Aerial> {
        val zh = locale.language == "zh"
        val traditional = ChineseLocale.isTraditional(locale)
        // The overlay is optional: missing or corrupt data falls back to the original Chinese.
        val traditionalVideos = if (traditional) runCatching {
            JSONObject(context.assets.open("aerials-zh-Hant.json").bufferedReader().use { it.readText() })
                .optJSONObject("videos")
        }.getOrNull() else null
        val root = JSONObject(context.assets.open("aerials.json").bufferedReader().use { it.readText() })
        val arr = root.getJSONArray("videos")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            val caps = o.getJSONArray("captions")
            val translated = traditionalVideos?.optJSONArray(id)?.takeIf { it.length() == caps.length() }
            Aerial(
                id = id,
                label = o.getString("label"),
                category = o.getString("category"),
                url = o.getString("url"),
                uhd = o.optString("uhd").ifEmpty { o.getString("url") },
                dolbyVision = o.optString("dv").ifEmpty { o.getString("url") },
                captions = (0 until caps.length()).map { j ->
                    val c = caps.getJSONArray(j)
                    val candidate = translated?.optJSONArray(j)?.takeIf {
                        it.length() == 2 && it.optInt(0, -1) == c.getInt(0)
                    }?.optString(1)?.takeIf { it.isNotBlank() }
                    c.getInt(0) to (candidate ?: if (zh) c.getString(1) else c.getString(2))
                        .replace(' ', ' ')
                },
            )
        }
    }
}
