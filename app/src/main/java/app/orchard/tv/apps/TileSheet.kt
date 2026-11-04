package app.orchard.tv.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import android.util.Log
import androidx.core.graphics.createBitmap
import java.io.File

/**
 * Every tile on one picture, for comparing how apps are drawn before and after a change to
 * [TileArtFactory]. Started from a computer (see ConfigReceiver):
 *
 *   adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --es export_tiles before
 *   adb pull /sdcard/Android/data/app.orchard.tv/files/tiles/before.png
 *
 * Installed apps come first, as Home draws them. APKs pushed to files/tiles/apks/ (from another
 * TV, say) follow, drawn as if installed; they only need their manifest and resources.
 */
object TileSheet {
    private const val TAG = "AerialTiles"
    private const val COLUMNS = 6
    private const val GAP = 24
    private const val LABEL = 44

    suspend fun export(context: Context, name: String): File? {
        CustomIcons.init(context)
        val dir = File(context.getExternalFilesDir(null) ?: return null, "tiles").apply { mkdirs() }
        val pm = context.packageManager
        val tiles = ArrayList<Pair<String, TileArt>>()
        AppCatalog.load(context).sortedBy { it.packageName }.forEach { tiles += it.label to it.art }
        File(dir, "apks").listFiles { f -> f.name.endsWith(".apk") }?.sortedBy { it.name }?.forEach { apk ->
            AppCatalog.archiveArt(pm, apk.path)?.let { (label, art) -> tiles += "$label ·" to art }
                ?: Log.w(TAG, "Could not read ${apk.name}")
        }
        val w = TileArtFactory.W
        val h = TileArtFactory.H
        val rows = (tiles.size + COLUMNS - 1) / COLUMNS
        val sheet = createBitmap(GAP + COLUMNS * (w + GAP), GAP + rows * (h + LABEL + GAP))
        val canvas = Canvas(sheet)
        canvas.drawColor(0xFF1C1C1E.toInt())
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEBEBF0.toInt(); textSize = 22f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        tiles.forEachIndexed { i, (label, art) ->
            val x = GAP + (i % COLUMNS) * (w + GAP).toFloat()
            val y = GAP + (i / COLUMNS) * (h + LABEL + GAP).toFloat()
            val flat = TileArtFactory.flatten(art)
            val r = h * 0.07f
            canvas.drawRoundRect(RectF(x, y, x + w, y + h), r, r, paint.apply {
                shader = android.graphics.BitmapShader(flat, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(android.graphics.Matrix().apply {
                        setScale(w.toFloat() / flat.width, h.toFloat() / flat.height)
                        postTranslate(x, y)
                    })
                }
            })
            paint.shader = null
            flat.recycle()
            val shown = TextUtils.ellipsize(label, text, w.toFloat(), TextUtils.TruncateAt.END).toString()
            canvas.drawText(shown, x + w / 2f, y + h + 32f, text)
        }
        val out = File(dir, "$name.png")
        out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        sheet.recycle()
        // Written as the app's user; ADB's shell may only read it if it is world-readable.
        out.setReadable(true, false)
        Log.i(TAG, "Wrote ${tiles.size} tiles to $out")
        return out
    }
}
