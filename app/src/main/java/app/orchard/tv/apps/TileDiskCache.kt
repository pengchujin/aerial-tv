package app.orchard.tv.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import app.orchard.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Locale

/**
 * Tiles as drawn at an earlier start, one file per app, so a cold start shows them at once. Drawing
 * them took a cold start 1.3 s for 24 apps on Xiaomi MiTV-MFTR0, and Home stood without tiles
 * meanwhile, longer on slower TVs (issue #294); reading one back is a few small PNG decodes.
 *
 * A file is used only while its key matches: the app's update time and custom picture, the tile
 * size, the icon style, the language (the label is in it) and Aerial's version (the drawing may
 * change between versions).
 */
object TileDiskCache {
    private const val TAG = "AerialTiles"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dir: File

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.cacheDir, "tiles").apply { mkdirs() }
    }

    /** Bumped when tiles are drawn differently, so a build with the same version code redraws them. */
    private const val ART = 3

    fun key(stamp: Long): String =
        "$stamp|${TileArtFactory.W}|${TileArtFactory.unified}|${Locale.getDefault().toLanguageTag()}|${BuildConfig.VERSION_CODE}|$ART"

    private fun file(pkg: String) = File(dir, "$pkg.tile")

    /** The label and tile kept for [pkg] under [key], or null. Off the main thread. */
    fun read(pkg: String, key: String): Pair<String, TileArt>? {
        if (!::dir.isInitialized) return null
        val f = file(pkg).takeIf { it.exists() } ?: return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { input ->
                if (input.readUTF() != key) return null
                val label = input.readUTF()
                val tint = Color(input.readInt())
                val foreAt = Rect(input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat())
                fun image(): Bitmap? {
                    val size = input.readInt()
                    if (size == 0) return null
                    val bytes = ByteArray(size).also(input::readFully)
                    return BitmapFactory.decodeByteArray(bytes, 0, size) ?: error("undecodable picture")
                }
                val back = image() ?: return null
                val fore = image()
                val backdrop = image() ?: return null
                label to TileArt(back.asImageBitmap(), fore?.asImageBitmap(), foreAt, tint, backdrop.asImageBitmap())
            }
        }.onFailure { Log.w(TAG, "could not read the kept tile of $pkg", it) }.getOrNull()
    }

    /** Only the label and color kept for [pkg] under [key], or null: the few bytes at the file's start. */
    fun readHeader(pkg: String, key: String): Pair<String, Color>? {
        if (!::dir.isInitialized) return null
        val f = file(pkg).takeIf { it.exists() } ?: return null
        return runCatching {
            DataInputStream(f.inputStream().buffered(512)).use { input ->
                if (input.readUTF() != key) return null
                input.readUTF() to Color(input.readInt())
            }
        }.getOrNull()
    }

    /** Keeps [art] for the next start, in the background. */
    fun write(pkg: String, key: String, label: String, art: TileArt) {
        if (!::dir.isInitialized) return
        scope.launch {
            runCatching {
                val tmp = File(dir, "$pkg.tmp")
                DataOutputStream(tmp.outputStream().buffered()).use { out ->
                    out.writeUTF(key)
                    out.writeUTF(label)
                    out.writeInt(art.tint.toArgb())
                    art.foreAt.run { out.writeFloat(left); out.writeFloat(top); out.writeFloat(right); out.writeFloat(bottom) }
                    for (image in listOf(art.back, art.fore, art.backdrop)) {
                        if (image == null) {
                            out.writeInt(0)
                            continue
                        }
                        val png = ByteArrayOutputStream().also { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
                        out.writeInt(png.size())
                        png.writeTo(out)
                    }
                }
                if (!tmp.renameTo(file(pkg))) tmp.delete()
            }.onFailure { Log.w(TAG, "could not keep the tile of $pkg", it) }
        }
    }

    /** Drops the files of apps no longer listed. */
    fun keepOnly(packages: Set<String>) {
        if (!::dir.isInitialized) return
        scope.launch {
            dir.listFiles()?.forEach { f ->
                if (f.name.removeSuffix(".tile").removeSuffix(".tmp") !in packages) f.delete()
            }
        }
    }
}
