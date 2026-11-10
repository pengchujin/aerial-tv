package app.orchard.tv.apps

import android.content.Context
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The icon library's index (v1 and v2, #706) and the phone page's pick among an app's pictures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IconLibraryTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private val v2 = """
        {"v":2,
         "icons":{"a.b":"p/a.b-1.png"},
         "choices":{"a.b":[{"id":"le5inioo","p":"p/a.b-1.png","by":"le5inioo"},
                           {"id":"le5inioo-2","p":"p/a.b-2.png","by":"le5inioo"},
                           {"id":"aerial","p":"p/a.b-3.jpg","by":"aerial"},
                           {"id":"evil","p":"p/../../shared_prefs/x.xml","by":"aerial"},
                           {"id":"","p":"p/a.b-4.png","by":"aerial"}],
                    "bad key":[{"id":"x","p":"p/x.png","by":"aerial"}]},
         "credits":{"le5inioo":{"name":"le5inioo","url":"https://github.com/le5inioo"},"aerial":{"name":"Aerial"}}}
    """.trimIndent()

    @Test
    fun readsVersionTwoAndOne() {
        val (index, credits) = IconLibrary.read(v2)!!
        assertEquals(setOf("a.b"), index.keys)
        // A path out of p/ and a choice without an id are dropped.
        assertEquals(listOf("le5inioo", "le5inioo-2", "aerial"), index.getValue("a.b").map { it.id })
        assertEquals("a.b-3.jpg", index.getValue("a.b")[2].fileName)
        assertEquals("le5inioo", credits.getJSONObject("le5inioo").getString("name"))

        // Index v1, as on the site before #706: one picture an app, from no one in particular.
        val (old, none) = IconLibrary.read("""{"v":1,"icons":{"c.d":"p/c.d-9.jpg"}}""")!!
        assertEquals(listOf("default"), old.getValue("c.d").map { it.id })
        assertEquals("p/c.d-9.jpg", old.getValue("c.d").single().path)
        assertEquals(0, none.length())

        assertNull(IconLibrary.read("not json"))
        assertNull(IconLibrary.read("""{"v":2}"""))
    }

    @Test
    fun picksAmongPicturesOnThisTv() {
        val dir = File(context.filesDir, "library").apply { mkdirs() }
        File(dir, "index.json").writeText(v2)
        fun picture(name: String) = File(dir, name).outputStream().use {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // The second picture has not arrived yet.
        picture("a.b-1.png")
        picture("a.b-3.jpg")
        IconLibrary.init(context)

        assertEquals(listOf("le5inioo", "aerial"), IconLibrary.choices("a.b").map { it.id })
        assertEquals("le5inioo", IconLibrary.chosen("a.b")?.id)
        assertTrue(IconLibrary.has("a.b"))
        assertFalse(IconLibrary.has("e.f"))
        val first = IconLibrary.stamp("a.b")

        IconLibrary.choose("a.b", IconLibrary.Pick.Library, "aerial")
        assertEquals("aerial", IconLibrary.chosen("a.b")?.id)
        assertEquals("a.b-3.jpg", IconLibrary.file("a.b")?.name)
        assertEquals("a.b-1.png", IconLibrary.file("a.b", "le5inioo")?.name)
        assertNotEquals(first, IconLibrary.stamp("a.b"))

        // A pick of a picture not here (yet) falls back to the default.
        IconLibrary.choose("a.b", IconLibrary.Pick.Library, "le5inioo-2")
        assertEquals("le5inioo", IconLibrary.chosen("a.b")?.id)

        // The default picked outright: kept as a pick of the library, so a firmware app takes it.
        IconLibrary.choose("a.b", IconLibrary.Pick.Library, "le5inioo")
        assertEquals(IconLibrary.Pick.Library, IconLibrary.pick("a.b"))
        val picked = IconLibrary.stamp("a.b")
        assertNotEquals(first, picked)

        IconLibrary.choose("a.b", IconLibrary.Pick.Own)
        assertFalse(IconLibrary.uses("a.b"))
        assertEquals(0L + 1L, IconLibrary.stamp("a.b"))
        assertNull(IconLibrary.load("a.b"))
    }
}
