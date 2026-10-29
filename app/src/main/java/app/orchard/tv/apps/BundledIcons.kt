package app.orchard.tv.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable

/** Offline HD defaults. Owner-uploaded artwork takes precedence in AppCatalog. */
object BundledIcons {
    internal val assets = mapOf(
        "com.ktcp.video" to "tencent.png",
        "com.netease.cloudmusic.tv" to "netease.png",
        "com.tencent.karaoketv" to "karaoke.png",
        "com.xiaomi.tweather" to "weather.png",
        "com.mitv.gallery" to "gallery.png",
        "com.xiaomi.tv.gallery" to "fashion.png",
        "com.xiaomi.mitv.mediaexplorer" to "player.png",
    )

    /** Prefilter once while loading: PNG decoder subsampling aliases fine curved edges. */
    fun load(context: Context, packageName: String): BitmapDrawable? {
        val asset = assets[packageName] ?: return null
        return runCatching {
            val path = "icons-hd/$asset"
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
            require(options.outWidth in 1..4096 && options.outHeight in 1..4096)
            options.inJustDecodeBounds = false
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
                ?.let { filteredReduction(it, TileArtFactory.W * 2) }
                ?.let { BitmapDrawable(context.resources, it).apply { isFilterBitmap = true } }
        }.getOrNull()
    }

    /** Takes ownership of [source]; each filtered step is at most a 2:1 reduction. */
    internal fun filteredReduction(source: Bitmap, width: Int): Bitmap {
        var current = source
        while (current.width > width) {
            val nextWidth = maxOf(width, current.width / 2)
            val nextHeight = maxOf(1, (current.height.toLong() * nextWidth / current.width).toInt())
            val next = Bitmap.createScaledBitmap(current, nextWidth, nextHeight, true)
            current.recycle()
            current = next
        }
        return current
    }
}
