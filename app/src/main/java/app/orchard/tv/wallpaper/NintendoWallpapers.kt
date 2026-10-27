package app.orchard.tv.wallpaper

import android.graphics.BitmapFactory
import app.orchard.tv.R
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Nintendo's desktop pictures. Nothing is fetched until the owner chooses a card. */
object NintendoWallpapers {
    data class Picture(val id: String, val title: Int, val url: String, val entry: String? = null, val credit: String = "© Nintendo")

    private const val HK = "https://www.nintendo.com/hk/topics/article/article_img/a_200513_01/"
    private const val PLAY = "https://play.nintendo.com/documents/"
    private const val MAX_ARCHIVE = 25L * 1024 * 1024
    internal const val MAX_PICTURE = 6L * 1024 * 1024
    val pictures = listOf(
        Picture("nintendo:mario_kart", R.string.wp_nintendo_mario_kart, HK + "Mario%20Kart%208%20DX_wallpaper_01.jpg"),
        Picture("nintendo:zelda", R.string.wp_nintendo_zelda, HK + "Zelda%20BOTW_wallpaper_01.jpg"),
        Picture("nintendo:animal_crossing_hk", R.string.wp_nintendo_animal_crossing_hk, HK + "Animal%20Crossing_wallpaper_01.jpg"),
        Picture("nintendo:kirby", R.string.wp_nintendo_kirby, HK + "Kirby%20Star%20Allies_wallpaper_01.jpg", credit = "© Nintendo / HAL Laboratory"),
        Picture("nintendo:peach", R.string.wp_nintendo_peach, PLAY + "PPS-Wallpapers.zip", "PPS-Wallpaper-1920x1080.jpg"),
        Picture("nintendo:animal_crossing", R.string.wp_nintendo_animal_crossing, PLAY + "SiblingsDay-Wallpapers.zip", "SiblingsDay-Wallpapers-1920x1080.jpg"),
        Picture("nintendo:pokemon", R.string.wp_nintendo_pokemon, PLAY + "PokemonSword_PokemonShield_wallpapers_xVWKeT8.zip", "PokemonSword_PokemonShield_wallpapers/PokemonSword_PokemonShield_camp_wallpaper_1920x1080.jpg", "© Pokémon / Nintendo / Creatures / GAME FREAK"),
    )

    fun file(dir: File, id: String): File? = pictures.firstOrNull { it.id == id }
        ?.let { File(dir, it.id.substringAfter(':') + ".jpg") }

    /** Saves just the selected desktop image, after checking it, and leaves any existing file alone on failure. */
    fun download(dir: File, picture: Picture): Boolean {
        val target = file(dir, picture.id) ?: return false
        var archive: File? = null
        var image: File? = null
        return try {
            dir.mkdirs()
            val jpg = File.createTempFile("nintendo-", ".jpg", dir).also { image = it }
            val zip = if (picture.entry != null) File.createTempFile("nintendo-", ".zip", dir).also { archive = it } else null
            val limit = if (zip != null) MAX_ARCHIVE else MAX_PICTURE
            val connection = URL(picture.url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                check(connection.responseCode == HttpURLConnection.HTTP_OK)
                check((connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L) <= limit)
                connection.inputStream.use { input -> (zip ?: jpg).outputStream().use { copyBounded(input, it, limit) } }
            } finally { connection.disconnect() }
            if (zip != null && picture.entry != null) zip.inputStream().use { input -> jpg.outputStream().use { extract(input, picture.entry, it) } }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(jpg.path, bounds)
            check(bounds.outWidth == 1920 && bounds.outHeight == 1080)
            check(jpg.renameTo(target))
            true
        } catch (_: Exception) {
            false
        } finally {
            archive?.delete()
            image?.delete()
        }
    }

    internal fun extract(input: InputStream, name: String, output: OutputStream) {
        ZipInputStream(input).use { zip ->
            var entries = 0
            val discard = object : OutputStream() {
                override fun write(b: Int) { }
                override fun write(b: ByteArray, off: Int, len: Int) { }
            }
            while (true) {
                val entry = zip.nextEntry ?: break
                check(++entries <= 100)
                if (!entry.isDirectory && entry.name == name) {
                    copyBounded(zip, output, MAX_PICTURE)
                    return
                }
                if (!entry.isDirectory) copyBounded(zip, discard, MAX_PICTURE)
                zip.closeEntry()
            }
        }
        error("picture missing from archive")
    }

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long) {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= limit)
            output.write(buffer, 0, count)
        }
    }
}
