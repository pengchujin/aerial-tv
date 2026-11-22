package app.orchard.tv.apps

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import app.orchard.tv.BuildConfig
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.SourceKind
import app.orchard.tv.system.TvSource
import app.orchard.tv.wallpaper.PhoneServer
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceIconLibraryTest {
    private val app = RuntimeEnvironment.getApplication()
    private val source = TvSource("test/HW1", "HDMI 1", SourceKind.Hdmi, true, null, Intent())
    private val key = SOURCE_KEY_PREFIX + source.id
    private val dir get() = File(app.filesDir, "library")

    @Before fun setup() {
        // Kotlin singletons survive between tests using the same Robolectric SDK sandbox.
        org.robolectric.util.ReflectionHelpers.setStaticField(IconLibrary::class.java, "dir", null)
        org.robolectric.util.ReflectionHelpers.setStaticField(IconLibrary::class.java, "prefs", null)
        org.robolectric.util.ReflectionHelpers.setStaticField(CustomIcons::class.java, "dir", null)
        app.getSharedPreferences("icon_library", 0).edit().clear().commit()
        AerialSettings.init(app); CustomIcons.init(app); SourceLooks.init(app)
        dir.mkdirs()
        File(dir, "index.json").writeText("""{"v":2,"icons":{},"choices":{"test.app":[
            {"id":"red","p":"p/red.png","by":"test"},{"id":"blue","p":"p/blue.png","by":"test"}]}}""")
        picture(File(dir, "red.png"), Color.RED)
        picture(File(dir, "blue.png"), Color.BLUE)
        app.getSharedPreferences("icon_library", 0).edit()
            .putLong("checked_at", System.currentTimeMillis()).putInt("checked_by", BuildConfig.VERSION_CODE).commit()
        IconLibrary.init(app)
        AppCatalog.sources = listOf(source)
        AppCatalog.pinnedSources = listOf(sourceEntry(app, source))
    }

    private fun picture(file: File, color: Int) {
        val bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun pick(id: String) = IconLibrary.applyPick(key, IconLibrary.Pick.Library, id, "test.app")
    private fun center(bitmap: Bitmap): Int = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    private fun tileColor(): Int = TileArtFactory.flatten(sourceEntry(app, source).art).let { b ->
        center(b).also { b.recycle() }
    }

    @Test fun sourceRendersNewPicksWithoutStaleCacheAndCanRestoreOwnArt() {
        val own = tileColor()
        assertTrue(pick("red")); assertEquals(Color.RED, tileColor())
        assertTrue(pick("blue")); assertEquals(Color.BLUE, tileColor())
        // Upload wins; a later library selection replaces it only on success.
        val custom = File(app.cacheDir, "custom.png").also { picture(it, Color.GREEN) }
        assertTrue(custom.inputStream().use { CustomIcons.save(key, it, app.cacheDir) })
        assertEquals(Color.GREEN, tileColor())
        assertFalse(IconLibrary.applyPick(key, IconLibrary.Pick.Library, "red", "test.app") { false })
        assertTrue(CustomIcons.has(key)); assertEquals(Color.GREEN, tileColor())
        assertEquals("blue", IconLibrary.borrowed(key)?.second?.id)
        assertTrue(pick("red")); assertFalse(CustomIcons.has(key)); assertEquals(Color.RED, tileColor())
        assertTrue(IconLibrary.applyPick(key, IconLibrary.Pick.Own, null, "test.app"))
        assertNull(IconLibrary.borrowed(key)); assertEquals(own, tileColor())
    }

    @Test fun pinnedBorrowSurvivesRefreshAndUnpinCollectsOnlyLibraryFiles() = runBlocking {
        assertTrue(pick("red"))
        val custom = File(app.cacheDir, "custom.png").also { picture(it, Color.GREEN) }
        assertTrue(custom.inputStream().use { CustomIcons.save(key, it, app.cacheDir) })
        IconLibrary.refresh(setOf(key))
        assertTrue(File(dir, "red.png").isFile)
        assertEquals("red", IconLibrary.borrowed(key)?.second?.id)
        assertFalse(File(dir, "blue.png").exists())
        IconLibrary.refresh(emptySet())
        assertFalse(File(dir, "red.png").exists())
        assertTrue(CustomIcons.has(key))
    }

    @Test fun phoneStateAndPreviewMatchSourceAndOwnPreviewIgnoresOverrides() {
        val server = PhoneServer::class.java.getDeclaredConstructor(android.content.Context::class.java, String::class.java)
            .apply { isAccessible = true }.newInstance(app, "test-token")
        assertTrue(pick("red"))
        val state = PhoneServer::class.java.getDeclaredMethod("state").apply { isAccessible = true }.invoke(server) as JSONObject
        assertTrue(state.getBoolean("sourceIconLibrary"))
        val src = state.getJSONArray("apps").getJSONObject(0)
        assertEquals("library", src.getString("icon"))
        assertEquals("test.app", src.getJSONArray("choices").getJSONObject(0).getString("from"))
        fun preview(own: Boolean): Int {
            val response = PhoneServer::class.java.getDeclaredMethod("tile", String::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(server, key, own) as NanoHTTPD.Response
            return response.data.use { stream -> BitmapFactory.decodeStream(stream).let { b -> center(b).also { b.recycle() } } }
        }
        val red = preview(false)
        assertTrue(Color.red(red) > 230 && Color.blue(red) < 20)
        assertNotEquals(red, preview(true))
        // Requests cannot mutate an input that is not pinned.
        AppCatalog.pinnedSources = emptyList()
        val installed = PhoneServer::class.java.getDeclaredMethod("installed", String::class.java).apply { isAccessible = true }
        assertEquals(false, installed.invoke(server, key))
    }
}
